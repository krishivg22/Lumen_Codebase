package com.example.lumen

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

class ObjectDetectorHelper(
    val context: Context,
    val listener: DetectorListener,
    private val threshold: Float = 0.4f,
    private val maxResults: Int = 10,
    private val numThreads: Int = 2
) {

    private var detector: ObjectDetector? = null

    init {
        setup()
    }

    private fun setup() {
        val base = BaseOptions.builder().setNumThreads(numThreads).useNnapi().build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setScoreThreshold(threshold)
            .setMaxResults(maxResults)
            .setBaseOptions(base)
            .build()

        try {
            detector = ObjectDetector.createFromFileAndOptions(
                context,
                "model.tflite",     // Your EfficientDet model in assets
                options
            )
        } catch (e: Exception) {
            listener.onError("Failed to initialize detector: ${e.message}")
            Log.e("ODH", "Error: ", e)
        }
    }

    fun detect(bitmap: Bitmap, rotation: Int) {
        if (detector == null) setup()

        val imageProcessor = ImageProcessor.Builder()
            .add(Rot90Op(-rotation / 90))
            .build()

        val tensorImg = imageProcessor.process(TensorImage.fromBitmap(bitmap))
        val results = detector?.detect(tensorImg)

        listener.onResults(
            results,
            tensorImg.height,
            tensorImg.width
        )
    }

    interface DetectorListener {
        fun onError(msg: String)
        fun onResults(
            results: MutableList<org.tensorflow.lite.task.vision.detector.Detection>?,
            imageHeight: Int,
            imageWidth: Int
        )
    }
}
