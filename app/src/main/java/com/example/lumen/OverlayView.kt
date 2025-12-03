package com.example.lumen

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val label: String
)

class OverlayView(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private var boxes: List<Box> = emptyList()

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.YELLOW
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun setBoxes(b: List<Box>) {
        boxes = b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (b in boxes) {
            canvas.drawRect(b.left, b.top, b.right, b.bottom, boxPaint)

            val label = "${b.label} (${String.format("%.2f", b.score)})"
            val tw = textPaint.measureText(label)
            val th = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top

            canvas.drawRect(
                b.left,
                b.top - th - 16,
                b.left + tw + 16,
                b.top,
                bgPaint
            )

            canvas.drawText(label, b.left + 8, b.top - 8, textPaint)
        }
    }
}
