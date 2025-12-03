package com.example.lumen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.speech.tts.TextToSpeech
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class SceneActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnUpload: ImageButton
    private lateinit var sceneStatus: TextView
    private lateinit var tvSceneText: TextView
    private lateinit var featurePanel: View
    private lateinit var btnReadAloud: Button
    private lateinit var textPreviewScroll: ScrollView

    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()

    // extracted caption/text for last capture / upload (context)
    private var lastSceneText: String = ""

    // TTS
    private var tts: TextToSpeech? = null

    // Captioner (Kotlin)
    private var captioner: Captioner? = null

    // Activity result launcher for image picker
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val uri = data.data
            if (uri != null) {
                handlePickedUri(uri)
            } else {
                Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_scene)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scene_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewView = findViewById(R.id.previewViewScene)
        btnCapture = findViewById(R.id.btnCaptureScene)
        btnUpload = findViewById(R.id.btnUploadScene)
        sceneStatus = findViewById(R.id.sceneStatus)
        tvSceneText = findViewById(R.id.tvSceneText)
        featurePanel = findViewById(R.id.featurePanelScene)
        btnReadAloud = findViewById(R.id.btnReadAloudScene)
        textPreviewScroll = findViewById(R.id.textPreviewScrollScene)

        // TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.getDefault())
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("Scene", "TTS language not supported")
                }
            } else {
                Log.e("Scene", "TTS init failed")
            }
        }

        btnCapture.setOnClickListener { captureScene() }
        btnUpload.setOnClickListener { pickImage() }

        // Initially hide features until processing completes
        featurePanel.visibility = View.GONE
        btnReadAloud.setOnClickListener { onReadAloudClicked() }

        // initialize captioner
        try {
            captioner = Captioner(this)
        } catch (e: Exception) {
            Log.e("Scene", "Failed to init captioner: ${e.message}", e)
            sceneStatus.text = "Captioner init failed"
            captioner = null
        }

        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
        findViewById<ImageButton>(R.id.btnBackScene).setOnClickListener {
            finish()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureScene() {
        val ic = imageCapture
        if (ic == null) {
            runOnUiThread {
                sceneStatus.text = "Capture not ready. Try again."
                Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            }
            return
        }

        runOnUiThread { sceneStatus.text = "Capturing." }

        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                try {
                    val bitmap = imageProxy.toBitmap()
                    imageProxy.close()
                    if (bitmap != null) runCaptioningOnBitmap(bitmap)
                    else runOnUiThread {
                        sceneStatus.text = "Capture conversion failed"
                        Toast.makeText(this@SceneActivity, "Capture conversion failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("Scene", "Capture to bitmap failed: ${e.message}", e)
                    try { imageProxy.close() } catch (_: Exception) {}
                    runOnUiThread {
                        sceneStatus.text = "Capture failed (conversion)"
                        Toast.makeText(this@SceneActivity, "Capture conversion failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("Scene", "Image capture error: ${exception.message}", exception)
                runOnUiThread {
                    sceneStatus.text = "Capture failed: ${exception.message ?: "unknown"}"
                    Toast.makeText(this@SceneActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        pickImageLauncher.launch(intent)
    }

    private fun handlePickedUri(uri: Uri) {
        runOnUiThread { sceneStatus.text = "Processing selected image." }
        try {
            // Load bitmap from uri on background thread
            executor.execute {
                try {
                    val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                    if (bitmap != null) {
                        runOnUiThread { sceneStatus.text = "Running scene description." }
                        runCaptioningOnBitmap(bitmap)
                    } else {
                        runOnUiThread {
                            sceneStatus.text = "Failed to load image"
                            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Scene", "handlePickedUri error: ${e.message}", e)
                    runOnUiThread {
                        sceneStatus.text = "Failed to process image"
                        Toast.makeText(this, "File processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Scene", "handlePickedUri outer error: ${e.message}", e)
            runOnUiThread {
                sceneStatus.text = "Failed to process file"
                Toast.makeText(this, "File processing failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Run captioning on a bitmap using Captioner.predictImage(bitmap)
     */
    private fun runCaptioningOnBitmap(bitmap: Bitmap) {
        executor.execute {
            try {
                if (captioner == null) {
                    val fallback = "Captioner not initialized"
                    lastSceneText = fallback
                    runOnUiThread { onCaptionResult(fallback) }
                    return@execute
                }

                runOnUiThread { sceneStatus.text = "Running caption model." }

                val caption = try {
                    captioner?.predictImage(bitmap) ?: "No caption"
                } catch (e: Exception) {
                    Log.e("Scene", "Captioner predictImage failed: ${e.message}", e)
                    "Captioning failed"
                }

                lastSceneText = caption.trim()
                runOnUiThread { onCaptionResult(lastSceneText) }

            } catch (e: Exception) {
                Log.e("Scene", "runCaptioningOnBitmap error: ${e.message}", e)
                runOnUiThread {
                    sceneStatus.text = "Captioning failed"
                    featurePanel.visibility = View.GONE
                    Toast.makeText(this, "Captioning failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                try { bitmap.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun onCaptionResult(caption: String) {
        runOnUiThread {
            if (caption.isBlank()) {
                sceneStatus.text = "No description generated"
                featurePanel.visibility = View.GONE
                tvSceneText.text = ""
            } else {
                sceneStatus.text = "Scene described."
                tvSceneText.text = caption
                // If the caption is the fallback string, still show UI but hide actionable features
                if (caption.contains("I can't identify the main object")) {
                    featurePanel.visibility = View.GONE
                } else {
                    featurePanel.visibility = View.VISIBLE
                }
                textPreviewScroll.post { textPreviewScroll.fullScroll(ScrollView.FOCUS_UP) }
                tts?.speak("Scene described. Use Read Aloud to hear it.", TextToSpeech.QUEUE_FLUSH, null, "SCENE_LOADED")
            }
        }
        saveSceneTextToFile(caption)
    }

    private fun onReadAloudClicked() {
        if (lastSceneText.isBlank()) {
            Toast.makeText(this, "No scene text found", Toast.LENGTH_SHORT).show()
            return
        }
        sceneStatus.text = "Reading aloud."
        tts?.speak(lastSceneText, TextToSpeech.QUEUE_FLUSH, null, "SCENE_READ")
    }

    private fun saveSceneTextToFile(text: String) {
        thread {
            try {
                val name = "scene_text_${System.currentTimeMillis()}.txt"
                openFileOutput(name, MODE_PRIVATE).use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
                Log.i("Scene", "Saved scene text as $name")
            } catch (e: Exception) {
                Log.e("Scene", "Failed to save text: ${e.message}")
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
            Log.e("Scene", "TTS shutdown error: ${e.message}")
        }
        try {
            captioner?.close()
            captioner = null
        } catch (e: Exception) {
            Log.e("Scene", "Error closing captioner: ${e.message}")
        }
        executor.shutdownNow()
    }

    // utility to convert ImageProxy to Bitmap
    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val plane = this.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e("Scene", "ImageProxy toBitmap error: ${e.message}", e)
            null
        }
    }
}
