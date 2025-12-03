package com.example.lumen

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class LiveTextActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayText: TextView

    private val executor = Executors.newSingleThreadExecutor()

    // ML Kit recognizer (on-device Latin model; change options if you need other scripts)
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // TTS
    private var tts: TextToSpeech? = null

    // Keep track of what we've already spoken recently to avoid repeats
    private val lastSpokenAt = ConcurrentHashMap<String, Long>()
    private val speakCooldownMs = 5000L   // don't speak same text again within this cooldown
    private val maxCharsPerUtterance = 200 // safety cap
    private var lastProcessTime = 0L
    private val minIntervalMs = 350L   // ~3 frames per second

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_live_text)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.live_text_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewView = findViewById(R.id.previewViewLive)
        overlayText = findViewById(R.id.liveTextOverlay)

        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.getDefault())
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("LiveText", "TTS language not supported")
                }
            } else {
                Log.e("LiveText", "TTS init failed")
            }
        }


        findViewById<ImageButton>(R.id.btnBackLive).setOnClickListener {
            finish()
        }

        if (hasCameraPermission()) startCamera()
        else requestCameraPermission()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analyzer.setAnalyzer(executor) { image ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastProcessTime < minIntervalMs) {
                        image.close()
                        return@setAnalyzer // skip this frame
                    }
                    lastProcessTime = now
                    // convert to bitmap using your ImageExtensions.toBitmap()
                    val bitmap: Bitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees

                    // Build InputImage for ML Kit
                    val inputImage = InputImage.fromBitmap(bitmap, rotation)

                    // Run text recognition (async)
                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            val blocks = visionText.textBlocks
                            if (blocks.isNullOrEmpty()) {
                                runOnUiThread { overlayText.text = "" }
                            } else {
                                // Collect short lines (trim and filter)
                                val lines = mutableListOf<String>()
                                for (b in blocks) {
                                    for (line in b.lines) {
                                        val t = line.text.trim()
                                        if (t.isNotEmpty()) lines.add(t)
                                    }
                                }

                                if (lines.isEmpty()) {
                                    runOnUiThread { overlayText.text = "" }
                                } else {
                                    // Show the most relevant text in overlay (joined, capped)
                                    val displayed = lines.joinToString(separator = "\n").take(maxCharsPerUtterance)
                                    runOnUiThread { overlayText.text = displayed }

                                    // Announce only newly-appearing / not-recently-spoken lines
                                    announceNewText(lines)
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("LiveText", "Text recognition error: ${e.message}")
                        }
                } catch (e: Exception) {
                    Log.e("LiveText", "Analyzer error: ${e.message}")
                } finally {
                    image.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analyzer
            )
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Announce only lines that are not recently spoken.
     * We prefer to group a few lines into one short utterance.
     */
    private fun announceNewText(lines: List<String>) {
        val now = System.currentTimeMillis()
        val toSpeak = mutableListOf<String>()

        for (line in lines) {
            val text = line.trim()

            // ------------------------------
            // 1. Reject single-letter garbage
            // ------------------------------
            if (text.length == 1) continue  // ignore I, l, t, a, etc.

            // --------------------------------------------------------------
            // 2. Reject repeated patterns like "lll", "III", "oooo", "___"
            // --------------------------------------------------------------
            if (text.all { it == text[0] }) continue

            // -------------------------------------------------------------
            // 3. Reject lines with almost no letters (symbols/noise)
            // -------------------------------------------------------------
            val letterCount = text.count { it.isLetter() }
            val digitCount = text.count { it.isDigit() }
            val total = text.length

            // ------------------------------------
            // 5. Reject ridiculously long garbage
            // ------------------------------------
            if (text.length > 60) continue

            // Normalize key for cooldown tracking
            val key = text.lowercase(Locale.getDefault())

            // Cooldown check
            val last = lastSpokenAt[key] ?: 0L
            if (now - last >= speakCooldownMs) {
                toSpeak.add(text)
                lastSpokenAt[key] = now
            }

            // Limit utterance length
            if (toSpeak.joinToString(" ").length > 120) break
            if (toSpeak.size >= 4) break
        }

        if (toSpeak.isEmpty()) return

        val utterance = when (toSpeak.size) {
            1 -> toSpeak[0]
            2 -> "${toSpeak[0]} and ${toSpeak[1]}"
            else -> {
                val firstPart = toSpeak.dropLast(1).joinToString(", ")
                "$firstPart and ${toSpeak.last()}"
            }
        }

        speakText(utterance)
    }


    private fun speakText(text: String) {
        runOnUiThread {
            try {
                tts?.let {
                    it.speak(text, TextToSpeech.QUEUE_ADD, null, "LUMEN_LIVETEXT")
                }
            } catch (e: Exception) {
                Log.e("LiveText", "TTS speak error: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e("LiveText", "TTS shutdown error: ${e.message}")
        }
        executor.shutdownNow()
    }
}
