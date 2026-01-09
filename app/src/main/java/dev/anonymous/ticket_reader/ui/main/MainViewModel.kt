package dev.anonymous.ticket_reader.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dev.anonymous.ticket_reader.utils.FlashMode
import dev.anonymous.ticket_reader.utils.SharedPreferencesManager

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefsManager = SharedPreferencesManager(application)

    private val _flashMode = MutableLiveData<FlashMode>()
    val flashMode: LiveData<FlashMode> = _flashMode

    init {
        _flashMode.value = prefsManager.getSavedFlashMode()
    }

    fun toggleFlashMode() {
        val currentMode = _flashMode.value ?: FlashMode.OFF
        val nextMode = currentMode.next
        _flashMode.value = nextMode

        when (nextMode) {
            FlashMode.ON_DEFAULT -> {
                prefsManager.saveFlashMode(FlashMode.ON_DEFAULT)
            }
            FlashMode.OFF, FlashMode.ON -> {
                prefsManager.clearSavedFlashMode()
            }
        }
    }

    fun isTorchNeeded(mode: FlashMode? = _flashMode.value): Boolean {
        return mode == FlashMode.ON || mode == FlashMode.ON_DEFAULT
    }
}