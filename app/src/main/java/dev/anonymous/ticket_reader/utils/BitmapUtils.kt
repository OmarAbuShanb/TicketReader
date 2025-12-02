package dev.anonymous.ticket_reader.utils

import android.graphics.Bitmap
import android.graphics.RectF

object BitmapUtils {

    fun cropBox(original: Bitmap, box: RectF): Bitmap {

        val left = (box.left * original.width)
            .toInt()
            .coerceIn(0, original.width - 1)

        val top = (box.top * original.height)
            .toInt()
            .coerceIn(0, original.height - 1)

        val right = (box.right * original.width)
            .toInt()
            .coerceIn(1, original.width)

        val bottom = (box.bottom * original.height)
            .toInt()
            .coerceIn(1, original.height)

        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)

        return Bitmap.createBitmap(original, left, top, width, height)
    }
}
