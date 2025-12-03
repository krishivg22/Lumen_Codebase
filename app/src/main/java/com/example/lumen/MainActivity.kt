package com.example.lumen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var detectorHelper: ObjectDetectorHelper

    // TTS
    private var tts: TextToSpeech? = null

    // For announcing only new objects
    private val currentlyVisibleLabels = mutableSetOf<String>()     // labels in the latest frame
    private val lastSpokenAt = ConcurrentHashMap<String, Long>()    // label -> last spoken timestamp (ms)

    // Controls
    private val speakCooldownMs = 5_000L    // if a label was spoken within this, don't speak again on re-appear
    private val maxLabelsPerAnnouncement = 3 // how many labels to join in one sentence
    private var lastProcessTime = 0L
    private val minIntervalMs = 350L   // ~3 frames per second

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.getDefault())
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("Main", "TTS language not supported")
                }
            } else {
                Log.e("Main", "TTS init failed")
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewView = findViewById(R.id.previewView)

        // Add overlay programmatically (same as before)
        overlay = OverlayView(this)
        val parent = previewView.parent as ViewGroup
        parent.addView(overlay)

        // Ensure overlay stays on top of preview
        overlay.bringToFront()

        // Wire back button to finish activity
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        detectorHelper = ObjectDetectorHelper(
            context = this,
            listener = this
        )

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
                    val bitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    detectorHelper.detect(bitmap, rotation)
                } catch (e: Exception) {
                    Log.e("Main", "Analyzer error: ", e)
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

    // Listener result from ObjectDetectorHelper
    override fun onResults(
        results: MutableList<org.tensorflow.lite.task.vision.detector.Detection>?,
        imageHeight: Int,
        imageWidth: Int
    ) {
        if (results == null) return

        // Build boxes for overlay (keeps same UI behavior)
        val boxes = results.map { det ->
            val box = det.boundingBox
            val cat = det.categories[0]

            val scaleX = previewView.width / imageWidth.toFloat()
            val scaleY = previewView.height / imageHeight.toFloat()

            Box(
                left = box.left * scaleX,
                top = box.top * scaleY,
                right = box.right * scaleX,
                bottom = box.bottom * scaleY,
                score = cat.score,
                label = cat.label
            )
        }

        // Update overlay on UI thread
        overlay.post { overlay.setBoxes(boxes) }

        // New algorithm: announce only newly appeared labels (no percentages)
        announceNewObjects(results)
    }

    /**
     * Announces only labels that newly appeared on screen (i.e., in current frame but
     * not in previous frame). Uses a cooldown so the same label isn't re-announced while
     * it remains present or reappears too quickly.
     */
    private fun announceNewObjects(detections: MutableList<org.tensorflow.lite.task.vision.detector.Detection>?) {
        if (detections.isNullOrEmpty()) {
            // If nothing detected, clear current view set (so later re-appearance will count as new)
            currentlyVisibleLabels.clear()
            return
        }

        // Collect labels present in this frame (use only top category per detection)
        val labelsThisFrame = mutableSetOf<String>()
        for (d in detections) {
            val cat = d.categories.firstOrNull() ?: continue
            val label = cat.label.trim()
            if (label.isNotEmpty()) labelsThisFrame.add(label)
        }

        // Determine newly appeared labels = labelsThisFrame - currentlyVisibleLabels
        val newlyAppeared = labelsThisFrame.filter { it !in currentlyVisibleLabels }

        // Prepare list of labels to speak after applying cooldown checks
        val now = System.currentTimeMillis()
        val toSpeak = mutableListOf<String>()
        for (label in newlyAppeared) {
            val last = lastSpokenAt[label] ?: 0L
            if (now - last >= speakCooldownMs) {
                toSpeak.add(label)
                lastSpokenAt[label] = now
            }
        }

        // Update the visible set for next frame
        currentlyVisibleLabels.clear()
        currentlyVisibleLabels.addAll(labelsThisFrame)

        if (toSpeak.isEmpty()) return

        // Limit how many labels to include in one utterance to keep it short
        val utterLabels = toSpeak.take(maxLabelsPerAnnouncement)

        // Build human-friendly phrase:
        // - single: "Person"
        // - two: "Person and cup"
        // - three+: "Person, cup and bottle"
        val announcement = when (utterLabels.size) {
            1 -> utterLabels[0]
            2 -> "${utterLabels[0]} and ${utterLabels[1]}"
            else -> {
                val firstPart = utterLabels.dropLast(1).joinToString(", ")
                "$firstPart and ${utterLabels.last()}"
            }
        }

        speakText(announcement)
    }

    private fun speakText(text: String) {
        runOnUiThread {
            try {
                tts?.let {
                    // Short, direct announcements; use QUEUE_ADD so brief successive labels are spoken in order
                    it.speak(text, TextToSpeech.QUEUE_ADD, null, "LUMEN_LABEL")
                }
            } catch (e: Exception) {
                Log.e("Main", "TTS speak error: ${e.message}")
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
            Log.e("Main", "TTS shutdown error: ${e.message}")
        }
        executor.shutdownNow()
    }

    override fun onError(msg: String) {
        Log.e("Main", "Detector Error: $msg")
    }
}
