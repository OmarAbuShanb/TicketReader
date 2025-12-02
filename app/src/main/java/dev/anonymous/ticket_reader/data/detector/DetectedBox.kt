package dev.anonymous.ticket_reader.data.detector

import android.graphics.RectF

data class DetectedBox(
    val classId: Int,
    val score: Float,
    val box: RectF
)
