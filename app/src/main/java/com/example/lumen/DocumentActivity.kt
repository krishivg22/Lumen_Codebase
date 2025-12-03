package com.example.lumen

import android.Manifest
import android.content.ContentValues
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.speech.tts.TextToSpeech
import java.io.OutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent

class DocumentActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnUpload: ImageButton
    private lateinit var btnSelectPage: Button
    private lateinit var tvPageCount: TextView
    private lateinit var docStatus: TextView
    private lateinit var tvDocText: TextView
    private lateinit var featurePanel: View
    private lateinit var btnReadAloud: Button
    private lateinit var btnAskQueries: Button
    private lateinit var textPreviewScroll: ScrollView

    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // extracted text for last capture / upload (context for queries)
    private var lastDocumentText: String = ""

    // TTS
    private var tts: TextToSpeech? = null

    // Keep last picked PDF uri (so prev/next or re-render can use it)
    private var lastPickedPdfUri: Uri? = null
    private var lastPickedPdfPageCount: Int = 0

    // Activity result launcher for document picker
    private val pickDocumentLauncher = registerForActivityResult(
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
        setContentView(R.layout.activity_document)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.doc_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewView = findViewById(R.id.previewViewDoc)
        btnCapture = findViewById(R.id.btnCaptureDoc)
        btnUpload = findViewById(R.id.btnUploadDoc)
        btnSelectPage = findViewById(R.id.btnSelectPage)
        tvPageCount = findViewById(R.id.tvPageCount)
        docStatus = findViewById(R.id.docStatus)
        tvDocText = findViewById(R.id.tvDocText)
        featurePanel = findViewById(R.id.featurePanel)
        btnReadAloud = findViewById(R.id.btnReadAloud)
        btnAskQueries = findViewById(R.id.btnAskQueries)
        textPreviewScroll = findViewById(R.id.textPreviewScroll)

        // Initially no PDF selected
        tvPageCount.visibility = View.GONE
        btnSelectPage.visibility = View.GONE

        // TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.getDefault())
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("Doc", "TTS language not supported")
                }
            } else {
                Log.e("Doc", "TTS init failed")
            }
        }

        btnCapture.setOnClickListener { captureDocument() }
        btnUpload.setOnClickListener { pickDocument() }
        btnSelectPage.setOnClickListener { showPagePickerForLastPdf() }

        // Initially hide features until OCR completes
        featurePanel.visibility = View.GONE
        btnReadAloud.setOnClickListener { onReadAloudClicked() }
        btnAskQueries.setOnClickListener { onAskQueriesClicked() }

        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
        findViewById<ImageButton>(R.id.btnBackDoc).setOnClickListener {
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

    private fun captureDocument() {
        val ic = imageCapture
        if (ic == null) {
            runOnUiThread {
                docStatus.text = "Capture not ready. Try again."
                Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            }
            return
        }

        runOnUiThread { docStatus.text = "Capturing..." }

        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                try {
                    val bitmap = imageProxy.toBitmap()
                    imageProxy.close()
                    runOcrOnBitmap(bitmap)
                } catch (e: Exception) {
                    Log.e("Doc", "Capture to bitmap failed: ${e.message}", e)
                    try { imageProxy.close() } catch (_: Exception) {}
                    runOnUiThread {
                        docStatus.text = "Capture failed (conversion)"
                        Toast.makeText(this@DocumentActivity, "Capture conversion failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("Doc", "Image capture error: ${exception.message}", exception)
                runOnUiThread {
                    docStatus.text = "Capture failed: ${exception.message ?: "unknown"}"
                    Toast.makeText(this@DocumentActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun pickDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf", "text/*"))
        }
        pickDocumentLauncher.launch(intent)
    }

    private fun handlePickedUri(uri: Uri) {
        runOnUiThread { docStatus.text = "Processing selected file..." }
        try {
            val mime = contentResolver.getType(uri) ?: ""
            when {
                mime.startsWith("image/") -> {
                    val image = InputImage.fromFilePath(this, uri)
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            lastDocumentText = (visionText.text ?: "").trim()
                            onOcrResult(lastDocumentText)
                            clearPdfSelection()
                        }
                        .addOnFailureListener { e ->
                            Log.e("Doc", "OCR for image failed: ${e.message}", e)
                            runOnUiThread {
                                docStatus.text = "OCR failed for image"
                                featurePanel.visibility = View.GONE
                            }
                        }
                }
                mime.startsWith("text/") -> {
                    val text = readTextFromUri(uri)
                    lastDocumentText = text.trim()
                    onOcrResult(lastDocumentText)
                    clearPdfSelection()
                }
                mime == "application/pdf" || uri.path?.endsWith(".pdf") == true -> {
                    // Store the selected PDF uri and show page count + picker button
                    lastPickedPdfUri = uri
                    // Determine page count in background
                    executor.execute {
                        var pfd: ParcelFileDescriptor? = null
                        try {
                            pfd = contentResolver.openFileDescriptor(uri, "r")
                            if (pfd == null) {
                                runOnUiThread {
                                    docStatus.text = "Unable to open PDF"
                                    Toast.makeText(this, "Cannot open PDF", Toast.LENGTH_SHORT).show()
                                }
                                return@execute
                            }
                            PdfRenderer(pfd).use { renderer ->
                                val pageCount = renderer.pageCount
                                lastPickedPdfPageCount = pageCount
                                runOnUiThread {
                                    tvPageCount.text = "Pages: $pageCount"
                                    tvPageCount.visibility = View.VISIBLE
                                    btnSelectPage.visibility = View.VISIBLE
                                    docStatus.text = "PDF ready — choose page to read"
                                    // Do not TTS the selection step per user instruction
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Doc", "PDF inspect failed: ${e.message}", e)
                            runOnUiThread {
                                docStatus.text = "Failed to inspect PDF"
                                Toast.makeText(this, "Failed to read PDF", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            try { pfd?.close() } catch (_: Exception) {}
                        }
                    }
                }
                else -> {
                    val textAttempt = readTextFromUri(uri)
                    if (textAttempt.isNotBlank()) {
                        lastDocumentText = textAttempt.trim()
                        onOcrResult(lastDocumentText)
                        clearPdfSelection()
                    } else {
                        runOnUiThread {
                            docStatus.text = "Unsupported file type: $mime"
                            Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Doc", "handlePickedUri error: ${e.message}", e)
            runOnUiThread {
                docStatus.text = "Failed to process file"
                Toast.makeText(this, "File processing failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearPdfSelection() {
        lastPickedPdfUri = null
        lastPickedPdfPageCount = 0
        runOnUiThread {
            tvPageCount.visibility = View.GONE
            btnSelectPage.visibility = View.GONE
        }
    }

    private fun showPagePickerForLastPdf() {
        val uri = lastPickedPdfUri
        val pageCount = lastPickedPdfPageCount
        if (uri == null || pageCount <= 0) {
            Toast.makeText(this, "No PDF selected", Toast.LENGTH_SHORT).show()
            return
        }
        showPagePickerDialog(pageCount) { zeroBasedIndex ->
            // Render the chosen page and OCR it (background)
            runOnUiThread { docStatus.text = "Rendering page ${zeroBasedIndex + 1}..." }
            executor.execute {
                val bmp = renderPdfPageToBitmap(uri, zeroBasedIndex)
                if (bmp != null) {
                    runOnUiThread { docStatus.text = "Running OCR on page ${zeroBasedIndex + 1}..." }
                    runOcrOnBitmap(bmp)
                } else {
                    runOnUiThread {
                        docStatus.text = "Failed to render page"
                        Toast.makeText(this, "Failed to render selected page", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showPagePickerDialog(pageCount: Int, onPageSelected: (Int) -> Unit) {
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = pageCount
            value = 1
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }

        AlertDialog.Builder(this)
            .setTitle("Select page (1–$pageCount)")
            .setView(picker)
            .setPositiveButton("Read Page") { _, _ ->
                val chosenOneBased = picker.value
                val zeroBased = chosenOneBased - 1
                onPageSelected(zeroBased)
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun renderPdfPageToBitmap(uri: Uri, pageIndex: Int): Bitmap? {
        try {
            val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) {
                Log.e("Doc", "ParcelFileDescriptor is null for $uri")
                return null
            }
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                    Log.e("Doc", "Requested page out of range")
                    return null
                }
                renderer.openPage(pageIndex).use { page ->
                    val width = page.width
                    val height = page.height
                    // Optionally scale to limit memory, but using native size for better OCR accuracy
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        } catch (e: Exception) {
            Log.e("Doc", "renderPdfPageToBitmap error: ${e.message}", e)
            return null
        }
    }

    private fun onOcrResult(fullText: String) {
        runOnUiThread {
            if (fullText.isBlank()) {
                docStatus.text = "No text found in file"
                featurePanel.visibility = View.GONE
                tvDocText.text = ""
            } else {
                docStatus.text = "File processed."
                tvDocText.text = fullText
                featurePanel.visibility = View.VISIBLE
                textPreviewScroll.post { textPreviewScroll.fullScroll(ScrollView.FOCUS_UP) }
                tts?.speak("Document loaded. Use Read Aloud or Ask Queries.", TextToSpeech.QUEUE_FLUSH, null, "DOC_LOADED")
            }
        }
        saveDocumentTextToFile(fullText)
    }

    private fun runOcrOnBitmap(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text ?: ""
                lastDocumentText = fullText.trim()
                runOnUiThread {
                    if (lastDocumentText.isBlank()) {
                        docStatus.text = "OCR found no text. Try again."
                        featurePanel.visibility = View.GONE
                        tvDocText.text = ""
                    } else {
                        docStatus.text = "OCR complete."
                        tvDocText.text = lastDocumentText
                        featurePanel.visibility = View.VISIBLE
                        textPreviewScroll.post { textPreviewScroll.fullScroll(ScrollView.FOCUS_UP) }
                        tts?.speak("Page processed. You can read aloud or ask questions.", TextToSpeech.QUEUE_FLUSH, null, "PAGE_DONE")
                    }
                }
                saveDocumentTextToFile(lastDocumentText)
                // free bitmap memory if caller expects it (caller may recycle)
                try { bitmap.recycle() } catch (_: Exception) {}
            }
            .addOnFailureListener { e ->
                Log.e("Doc", "OCR failed: ${e.message}")
                runOnUiThread {
                    docStatus.text = "OCR failed"
                    featurePanel.visibility = View.GONE
                }
                try { bitmap.recycle() } catch (_: Exception) {}
            }
    }

    private fun saveDocumentTextToFile(text: String) {
        thread {
            try {
                val name = "doc_text_${System.currentTimeMillis()}.txt"
                openFileOutput(name, MODE_PRIVATE).use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
                Log.i("Doc", "Saved extracted text as $name")
            } catch (e: Exception) {
                Log.e("Doc", "Failed to save text: ${e.message}")
            }
        }
    }

    private fun onReadAloudClicked() {
        if (lastDocumentText.isBlank()) {
            Toast.makeText(this, "No document text found", Toast.LENGTH_SHORT).show()
            return
        }
        docStatus.text = "Reading aloud..."
        speakLongText(lastDocumentText)
    }

    private fun onAskQueriesClicked() {
        if (lastDocumentText.isBlank()) {
            Toast.makeText(this, "No document text found", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, DocChatActivity::class.java).apply {
            putExtra("doc_text", lastDocumentText)
        }
        startActivity(intent)
    }

    private fun speakLongText(text: String) {
        val maxChunk = 3000
        var start = 0
        val len = text.length
        while (start < len) {
            val end = (start + maxChunk).coerceAtMost(len)
            var chunkEnd = end
            if (end < len) {
                val nextPeriod = text.lastIndexOf('.', end - 1).takeIf { it >= start } ?: -1
                if (nextPeriod >= start) chunkEnd = nextPeriod + 1
            }
            val chunk = text.substring(start, chunkEnd.coerceAtLeast(start + 1)).trim()
            tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, "DOC_READ_${start}")
            start = chunkEnd
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e("Doc", "TTS shutdown error: ${e.message}")
        }
        executor.shutdownNow()
    }

    private fun readTextFromUri(uri: Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    val sb = StringBuilder()
                    var line = reader.readLine()
                    while (line != null) {
                        sb.append(line).append("\n")
                        line = reader.readLine()
                    }
                    sb.toString()
                }
            } ?: ""
        } catch (e: Exception) {
            Log.e("Doc", "readTextFromUri error: ${e.message}", e)
            ""
        }
    }
}
