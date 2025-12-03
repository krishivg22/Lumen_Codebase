package com.example.lumen

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.TreeMap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln
class Captioner @Throws(IOException::class)
constructor(private val activity: Activity) {

    companion object {
        private const val TAG = "TfLiteCameraDemo"
    }

    private val tfliteOptions = Interpreter.Options()
    private val tfliteOptionsLstm = Interpreter.Options()

    private var tfliteModel: MappedByteBuffer
    private var tfliteModelLstm: MappedByteBuffer

    protected var tflite: Interpreter
    protected var tfliteLstm: Interpreter

    // buffers and feeds as in original Java (kept types stable)
    protected var imageFeed: Array<Array<FloatArray>> = Array(346) { Array(346) { FloatArray(3) } }
    protected var inputFeed: LongArray = LongArray(1)
    protected var stateFeed: Array<FloatArray> = arrayOf(FloatArray(1024))

    // softmax and states (same sizes as original model)
    private var softmax: Array<FloatArray> = Array(1) { FloatArray(12000) }
    private var lstmState: Array<FloatArray> = Array(1) { FloatArray(1024) }
    private var initialState: Array<FloatArray> = Array(1) { FloatArray(1024) }

    private var vocabulary: Vocabulary = Vocabulary(activity)

    // --- Tunables ---
    private val CONF_THRESH = 0.05f      // if top prob < this, return safe fallback (tune 0.08-0.25)
    private val TEMP = 1.2f              // temperature for softmax calibration (>=1.0 softens)
    private val TOP_K = 5                // for logging / diagnostics and safer selection

    init {
        val inceptionModelPath = getInceptionModelPath()
        val lstmModelPath = getLSTMModelPath()
        tfliteModel = loadModelFile(activity, inceptionModelPath)
        tflite = Interpreter(tfliteModel, tfliteOptions)

        tfliteModelLstm = loadModelFile(activity, lstmModelPath)
        tfliteLstm = Interpreter(tfliteModelLstm, tfliteOptionsLstm)

        Log.d(TAG, "Created a Tensorflow Lite Captioner.")
    }

    // Public API --------------------------------------------------------------

    /**
     * Predict from a Bitmap directly (no temp file).
     * Scales the bitmap to 346x346 and fills imageFeed like the original Java.
     */
    fun predictImage(bitmap: Bitmap): String {
        // Resize to 346 x 346
        val R = 346
        val C = 346
        val img = Bitmap.createScaledBitmap(bitmap, C, R, false)

        for (i in 0 until R) {
            for (j in 0 until C) {
                val pixelValue = img.getPixel(j, i)
                // NOTE: kept original divide-by-256 behavior so we don't change numeric behaviour unexpectedly.
                // If you know the model expects [-1,1] or [0,1], replace these lines accordingly (see earlier notes).
                imageFeed[i][j][0] = ((pixelValue shr 16) and 0xFF) / 256.0f
                imageFeed[i][j][1] = ((pixelValue shr 8) and 0xFF) / 256.0f
                imageFeed[i][j][2] = (pixelValue and 0xFF) / 256.0f
            }
        }

        return classifyFrame()
    }

    /**
     * Predict from an image file path (keeps original behavior)
     */
    fun predictImage(imgPath: String): String {
        Log.d(TAG, "predictImage: imgPath=$imgPath")
        val origImg = BitmapFactory.decodeFile(imgPath) ?: return ""
        return predictImage(origImg)
    }

    fun close() {
        try {
            tflite.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing tflite: ${e.message}")
        }
        try {
            tfliteLstm.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing tflite_lstm: ${e.message}")
        }
    }

    // Internal helpers -------------------------------------------------------

    private fun classifyFrame(): String {
        val startTime = SystemClock.uptimeMillis()
        val caption = runInference()
        val endTime = SystemClock.uptimeMillis()
        Log.d(TAG, "Timecost to run model inference: ${endTime - startTime}")
        return caption
    }

    private fun runInference(): String {
        val outputsCnn = TreeMap<Int, Any>()
        val outputsLstm = TreeMap<Int, Any>()
        val inputsCnn = arrayOf<Any>(imageFeed)
        val inputsLstm = arrayOf<Any>(inputFeed, stateFeed)

        // outputs mapping (keeps original names)
        outputsLstm[tfliteLstm.getOutputIndex("import/softmax")] = softmax
        outputsLstm[tfliteLstm.getOutputIndex("import/lstm/state")] = lstmState
        outputsCnn[tflite.getOutputIndex("import/lstm/initial_state")] = initialState

        // run CNN to get initial_state
        tflite.runForMultipleInputsOutputs(inputsCnn, outputsCnn)

        val maxCaptionLength = 10
        val words = ArrayList<Int>()
        words.add(vocabulary.getStartIndex())

        // copy initial_state into state_feed
        for (i in initialState[0].indices) {
            stateFeed[0][i] = initialState[0][i]
        }
        inputFeed[0] = words[words.size - 1].toLong()

        var maxIdx: Int
        for (i in 0 until maxCaptionLength) {
            tfliteLstm.runForMultipleInputsOutputs(inputsLstm, outputsLstm)

            // Apply temperature calibration to the raw softmax from the LSTM (softmax[0])
            val calibrated = applyTemperature(softmax[0], TEMP)

            // Compute top-K for diagnostics & safe decisions
            val topKList = topK(calibrated, TOP_K)
            val (bestIdx, bestProb) = if (topKList.isNotEmpty()) topKList[0] else Pair(-1, 0f)

            // Log top-k (word + prob)
            val topkStr = topKList.joinToString { (idx, p) ->
                val w = vocabulary.getWordAtIndex(idx)
                "${if (w.isBlank()) "<OOV:$idx>" else w}:${"%.3f".format(p)}"
            }
            Log.d(TAG, "step $i top${TOP_K}: $topkStr (bestProb=${"%.3f".format(bestProb)})")

            // --- NEW: skip low-confidence tokens but keep decoding ---
            if (bestProb < CONF_THRESH) {
                Log.w(TAG, "Low confidence at step $i (bestProb=${"%.3f".format(bestProb)}). Skipping this token but continuing decoding.")
                // update stateFeed with the produced lstmState so the decoder can evolve
                for (j in stateFeed[0].indices) {
                    stateFeed[0][j] = lstmState[0][j]
                }
                // do NOT add a token to `words`, do NOT change inputFeed (keep last token)
                // continue to next iteration
                continue
            }
            // --- END NEW ---

            // Existing logic: choose greedy-best, handle OOV/blank fallback
            var maxIdx = bestIdx
            var chosenWord = vocabulary.getWordAtIndex(maxIdx)
            if (chosenWord.isBlank()) {
                var found = false
                for (k in 1 until topKList.size) {
                    val candIdx = topKList[k].first
                    val candWord = vocabulary.getWordAtIndex(candIdx)
                    if (candWord.isNotBlank() && candIdx != vocabulary.getEndIndex()) {
                        maxIdx = candIdx
                        chosenWord = candWord
                        found = true
                        break
                    }
                }
                if (!found) {
                    Log.w(TAG, "Top tokens map to OOV / blank. Fallbacking.")
                    return "I can't identify the main object in this photo."
                }
            }

            words.add(maxIdx)
            if (maxIdx == vocabulary.getEndIndex()) {
                break
            }
            // update inputFeed to chosen token and update stateFeed with new lstm state
            inputFeed[0] = maxIdx.toLong()
            for (j in stateFeed[0].indices) {
                stateFeed[0][j] = lstmState[0][j]
            }
            Log.d(TAG, "current word index: $maxIdx (word='$chosenWord')")
        }

        val sb = StringBuilder()
        // append words from 1..words.size-2 (skip start and end)
        for (i in 1 until max(1, words.size - 1)) {
            val w = vocabulary.getWordAtIndex(words[i])
            if (w.isBlank()) continue
            sb.append(w)
            sb.append(" ")
        }

        val result = sb.toString().trim()

// Remove trailing non-grammatical words
        val badEndWords = setOf(
            "a","an","the","of","in","on","at","for","with","to","by","from","and","or"
        )

        var tokens = result.split(" ").toMutableList()

// Remove forbidden trailing tokens
        while (tokens.isNotEmpty() && tokens.last().lowercase() in badEndWords) {
            tokens.removeAt(tokens.size - 1)
        }

        val cleaned = tokens.joinToString(" ").trim()

// If everything got removed, fallback
        if (cleaned.isBlank()) {
            return "I can't identify the main object in this photo."
        }

        return cleaned

    }

    /**
     * Apply temperature scaling to a raw logits/softmax array.
     * If the passed array is already softmax probabilities, dividing by temp before re-softmaxing
     * will effectively soften or sharpen the distribution depending on temp.
     */
    private fun applyTemperature(rawProbs: FloatArray, temp: Float): FloatArray {
        // To be robust, treat input as scores: take log then divide by temp, then softmax.
        // But we have probabilities — we'll convert to logits via log(p+eps).
        val eps = 1e-9f
        val logits = FloatArray(rawProbs.size)
        for (i in rawProbs.indices) logits[i] = ln(rawProbs[i] + eps).toFloat()


        // divide logits by temperature
        val scaled = FloatArray(logits.size)
        var maxLogit = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            val v = logits[i] / temp
            scaled[i] = v
            if (v > maxLogit) maxLogit = v
        }
        // softmax
        val exps = FloatArray(scaled.size)
        var sum = 0.0f
        for (i in scaled.indices) {
            val e = exp(scaled[i] - maxLogit)
            exps[i] = e
            sum += e
        }
        val out = FloatArray(exps.size)
        if (sum <= 0f) {
            // fallback: return uniform distribution
            val u = 1f / out.size
            for (i in out.indices) out[i] = u
            return out
        }
        for (i in out.indices) out[i] = exps[i] / sum
        return out
    }

    /**
     * Return top-k pairs (index, prob) sorted by prob desc.
     */
    private fun topK(arr: FloatArray, k: Int): List<Pair<Int, Float>> {
        val list = arr.mapIndexed { idx, p -> idx to p }
            .sortedByDescending { it.second }
            .take(min(k, arr.size))
        return list
    }

    private fun findMaximumIdx(arr: FloatArray): Int {
        var maxVal = arr[0]
        var maxIdx = 0
        for (i in 1 until arr.size) {
            if (arr[i] > maxVal) {
                maxVal = arr[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    // Model loading ----------------------------------------------------------

    @Throws(IOException::class)
    private fun loadModelFile(activity: Activity, modelFileName: String): MappedByteBuffer {
        val fileDescriptor = activity.assets.openFd(modelFileName)
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }

    private fun getInceptionModelPath(): String = "inceptionv3_1.tflite"
    private fun getLSTMModelPath(): String = "lstm_2.tflite"

    // Vocabulary class (same behavior as your Java impl)
    inner class Vocabulary(private val activity: Activity) {

        private var id2word: List<String> = emptyList()

        init {
            id2word = try {
                loadWords()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load vocabulary: ${e.message}")
                emptyList()
            }
        }

        fun getLabelPath(): String = "word_counts.txt"

        @Throws(IOException::class)
        fun loadWords(): List<String> {
            val labelList = ArrayList<String>()
            BufferedReader(InputStreamReader(activity.assets.open(getLabelPath()))).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(" ")
                    if (parts.isNotEmpty()) labelList.add(parts[0])
                }
            }
            return labelList
        }

        fun getStartIndex(): Int = 1
        fun getEndIndex(): Int = 2

        fun getWordAtIndex(index: Int): String {
            if (index < 0 || index >= id2word.size) return ""
            return id2word[index]
        }
    }

    // safe max helper
    private fun max(a: Int, b: Int): Int = if (a > b) a else b
}
