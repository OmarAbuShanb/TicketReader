package dev.anonymous.ticket_reader.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxes = mutableListOf<RectF>()
    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // This holds the dimensions of the image frame that the model analyzed.
    // We need this to properly scale the bounding boxes.
    private var frameWidth: Int = 1
    private var frameHeight: Int = 1

    fun setResults(detectedBoxes: List<RectF>, frameWidth: Int, frameHeight: Int) {
        this.boxes.clear()
        this.boxes.addAll(detectedBoxes)
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        invalidate()
    }
    
    fun clear() {
        boxes.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Don't draw anything if there are no boxes or frame dimensions are not set.
        if (boxes.isEmpty() || frameWidth <= 1 || frameHeight <= 1) {
            return
        }

        // Calculate scale factors.
        // The PreviewView and the analyzed image might have different aspect ratios.
        // We need to fit the image dimensions within the view dimensions while preserving aspect ratio.
        val scaleFactorX = width.toFloat() / frameWidth
        val scaleFactorY = height.toFloat() / frameHeight
        val scale = max(scaleFactorX, scaleFactorY) // Use max to fill the view (covers like FILL_CENTER)

        // Calculate the offsets to center the scaled image within the view.
        val offsetX = (width - frameWidth * scale) / 2
        val offsetY = (height - frameHeight * scale) / 2
        
        for (box in boxes) {
            // The box coordinates are relative to the frame size.
            // First, scale them to the absolute pixel values of the frame.
            val scaledBox = RectF(
                box.left * frameWidth,
                box.top * frameHeight,
                box.right * frameWidth,
                box.bottom * frameHeight
            )

            // Now, scale the absolute box to the view's coordinate system and apply offsets.
            scaledBox.left = scaledBox.left * scale + offsetX
            scaledBox.top = scaledBox.top * scale + offsetY
            scaledBox.right = scaledBox.right * scale + offsetX
            scaledBox.bottom = scaledBox.bottom * scale + offsetY

            canvas.drawRect(scaledBox, paint)
        }
    }
}