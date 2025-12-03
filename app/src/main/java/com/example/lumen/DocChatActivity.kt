package com.example.lumen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.text.qa.BertQuestionAnswerer
import org.tensorflow.lite.task.text.qa.BertQuestionAnswerer.BertQuestionAnswererOptions
import java.util.Locale
import java.util.concurrent.Executors


class DocChatActivity : AppCompatActivity() {

    companion object {
        private const val REQ_RECORD_AUDIO = 101
    }

    private lateinit var rvChat: RecyclerView
    private lateinit var etQuestion: EditText
    private lateinit var btnSend: Button
    private lateinit var btnMic: ImageButton
    private lateinit var chatAdapter: ChatAdapter

    private var docText: String = ""
    private var questionAnswerer: BertQuestionAnswerer? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()

    // Speech components
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_doc_chat)

        val root = findViewById<View>(R.id.doc_main)
        val inputArea = findViewById<View>(R.id.chatInputArea)
        val rv = findViewById<RecyclerView>(R.id.rvChat)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            val effectiveRootBottom = if (ime.bottom > 0) { 0 } else { systemBars.bottom }
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, effectiveRootBottom)

            inputArea.translationY = -ime.bottom.toFloat()
            val pad = (8 * resources.displayMetrics.density).toInt()
            rv.setPadding(pad, pad, pad, pad + ime.bottom)
            rv.clipToPadding = false
            insets
        }

        rvChat = findViewById(R.id.rvChat)
        etQuestion = findViewById(R.id.etQuestion)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)

        chatAdapter = ChatAdapter()
        val lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        rvChat.layoutManager = lm
        rvChat.adapter = chatAdapter

        val pad = (8 * resources.displayMetrics.density).toInt()
        rvChat.setPadding(pad, pad, pad, pad)
        rvChat.clipToPadding = false

        docText = intent.getStringExtra("doc_text") ?: ""
        if (docText.isBlank()) {
            Toast.makeText(this, "No document text passed", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // init TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.0f)
            }
        }

        // ensure audio permission for mic
        if (!hasAudioPermission()) requestAudioPermission()

        // init SpeechRecognizer lazily when user taps mic
        btnMic.setOnClickListener {
            if (!hasAudioPermission()) {
                requestAudioPermission()
            } else {
                toggleListening()
            }
        }

        // Load model in background (original behavior retained)
        bgExecutor.execute {
            try {
                val baseOptions = BaseOptions.builder()
                    .setNumThreads(2)
                    .build()

                val options = BertQuestionAnswererOptions.builder()
                    .setBaseOptions(baseOptions)
                    .build()

                questionAnswerer = BertQuestionAnswerer.createFromFileAndOptions(
                    this,
                    "mobilebert_qa.tflite",
                    options
                )

                runOnUiThread {
                    chatAdapter.addSystemMessage("Model loaded. Ask any question about the document.")
                    rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                    speak("Model loaded. You can ask questions about the document.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to load model: ${e.message}", Toast.LENGTH_LONG).show()
                    chatAdapter.addSystemMessage("Failed to load model.")
                    speak("Failed to load model: ${e.message ?: "unknown error"}")
                }
            }
        }

        btnSend.setOnClickListener { onSendQuestion() }
        etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onSendQuestion()
                true
            } else false
        }
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
                // optionally start listening immediately
            } else {
                Toast.makeText(this, "Microphone permission is required for voice input", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleListening() {
        if (isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (SpeechRecognizer.isRecognitionAvailable(this).not()) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        if (speechRecognizer == null) speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true; btnMic.isSelected = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false; btnMic.isSelected = false }
            override fun onError(error: Int) {
                isListening = false
                btnMic.isSelected = false
                runOnUiThread {
                    chatAdapter.addSystemMessage("Voice recognition error: $error")
                    speak("Voice recognition error.")
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                btnMic.isSelected = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    runOnUiThread {
                        etQuestion.setText(text)
                        onSendQuestion() // auto-send the recognized text
                    }
                } else {
                    runOnUiThread {
                        chatAdapter.addSystemMessage("Didn't catch that. Please try again.")
                        speak("Didn't catch that. Please try again.")
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
        chatAdapter.addSystemMessage("Listening...")
        rvChat.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        isListening = false
        btnMic.isSelected = false
        chatAdapter.addSystemMessage("Stopped listening.")
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "UTTERANCE_ID_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun onSendQuestion() {
        val q = etQuestion.text.toString().trim()
        if (q.isEmpty()) return

        chatAdapter.addUserMessage(q)
        etQuestion.setText("")
        rvChat.scrollToPosition(chatAdapter.itemCount - 1)

        bgExecutor.execute {
            val qa = questionAnswerer
            if (qa == null) {
                runOnUiThread {
                    chatAdapter.addSystemMessage("Model not ready yet.")
                    speak("Model not ready yet.")
                }
                return@execute
            }

            try {
                val answers = qa.answer(docText, q)
                if (answers.isEmpty()) {
                    runOnUiThread {
                        chatAdapter.addSystemMessage("No answer found.")
                        speak("No answer found.")
                    }
                } else {
                    val best = answers[0]
                    val text = best.text
                    runOnUiThread {
                        chatAdapter.addBotMessage(text)
                        rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                        speak(text) // speak the bot response
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    chatAdapter.addSystemMessage("Error during inference: ${e.message}")
                    speak("Error during inference.")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        questionAnswerer?.close()
        bgExecutor.shutdownNow()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}
