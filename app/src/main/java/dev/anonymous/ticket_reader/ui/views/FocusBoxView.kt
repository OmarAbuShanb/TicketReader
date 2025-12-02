package dev.anonymous.ticket_reader.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class FocusBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint().apply {
        color = Color.BLACK
        alpha = 180 // Semi-transparent
    }

    private val boxPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val boxCornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND

        strokeWidth = 12f // Thicker corners
    }

    private var boxRect: RectF? = null
    
    fun getBoxRect(): RectF? = boxRect

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Adjust the box size and position as needed.
        val boxWidth = w * 0.75f // Reduced width
        val boxHeight = boxWidth / 1.8f // Shorter height relative to width
        val left = (w - boxWidth) / 2
        val top = (h - boxHeight) / 2
        val right = left + boxWidth
        val bottom = top + boxHeight
        boxRect = RectF(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = boxRect ?: return

        // Draw the semi-transparent overlay
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

        // "Cut out" the focus box area
        val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawRect(rect, clearPaint)

        // Draw the border of the focus box
        canvas.drawRect(rect, boxPaint)

        // Draw the corners
        val cornerLength = rect.width() * 0.1f // Length of the corner lines
        canvas.apply {
            // Top-left
            drawLine(rect.left , rect.top, rect.left + cornerLength, rect.top, boxCornerPaint)
            drawLine(rect.left, rect.top, rect.left, rect.top + cornerLength, boxCornerPaint)
            // Top-right
            drawLine(rect.right , rect.top, rect.right - cornerLength, rect.top, boxCornerPaint)
            drawLine(rect.right, rect.top, rect.right, rect.top + cornerLength, boxCornerPaint)
            // Bottom-left
            drawLine(rect.left , rect.bottom, rect.left + cornerLength, rect.bottom, boxCornerPaint)
            drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLength, boxCornerPaint)
            // Bottom-right
            drawLine(rect.right , rect.bottom, rect.right - cornerLength, rect.bottom, boxCornerPaint)
            drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLength, boxCornerPaint)
        }
    }
}