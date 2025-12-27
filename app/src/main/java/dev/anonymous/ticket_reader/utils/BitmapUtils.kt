package dev.anonymous.ticket_reader.utils

import android.graphics.Bitmap
import android.graphics.RectF

object BitmapUtils {

    fun cropBoxPx(original: Bitmap, boxPx: RectF): Bitmap {
        val left = boxPx.left.toInt().coerceIn(0, original.width - 1)
        val top = boxPx.top.toInt().coerceIn(0, original.height - 1)
        val right = boxPx.right.toInt().coerceIn(left + 1, original.width)
        val bottom = boxPx.bottom.toInt().coerceIn(top + 1, original.height)

        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)

        return Bitmap.createBitmap(original, left, top, width, height)
    }
}
