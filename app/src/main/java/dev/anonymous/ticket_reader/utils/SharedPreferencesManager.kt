package dev.anonymous.ticket_reader.utils

import android.content.Context
import androidx.core.content.edit

class SharedPreferencesManager(context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FLASH_MODE = "flash_mode"
    }

    fun saveFlashMode(mode: FlashMode) {
        prefs.edit {
            putString(KEY_FLASH_MODE, mode.name)
        }
    }

    fun getSavedFlashMode(): FlashMode {
        val value = prefs.getString(KEY_FLASH_MODE, null)
        return FlashMode.fromPreferenceValue(value)
    }

    fun clearSavedFlashMode() {
        prefs.edit {
            remove(KEY_FLASH_MODE)
        }
    }
}