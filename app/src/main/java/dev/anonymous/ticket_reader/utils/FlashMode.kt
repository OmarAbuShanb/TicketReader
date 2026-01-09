package dev.anonymous.ticket_reader.utils

import dev.anonymous.ticket_reader.R

enum class FlashMode(private val nextName: String, val text: String, val iconRes: Int) {
    OFF(
        nextName = "ON",
        text = "الفلاش متوقف",
        iconRes = R.drawable.ic_flash_off
    ),
    ON(
        nextName = "ON_DEFAULT",
        text = "الفلاش قيد تشغيل",
        iconRes = R.drawable.ic_flash_on
    ),
    ON_DEFAULT(
        nextName = "OFF",
        text = "قيد التشغيل افتراضياً",
        iconRes = R.drawable.ic_flash_on
    );

    val next: FlashMode
        get() = entries.first { it.name == nextName }

    companion object {
        fun fromPreferenceValue(value: String?): FlashMode = when (value) {
            ON.name -> ON
            ON_DEFAULT.name -> ON_DEFAULT
            else -> OFF
        }
    }
}