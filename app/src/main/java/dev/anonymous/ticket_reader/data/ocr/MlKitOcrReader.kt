package dev.anonymous.ticket_reader.data.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object MlKitOcrReader {

    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    fun readText(bitmap: Bitmap, onResult: (String) -> Unit) {
        val img = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(img)
            .addOnSuccessListener { onResult(it.text) }
            .addOnFailureListener {
                Log.e("OCR", "Failed: ${it.message}")
                onResult("")
            }
    }
}