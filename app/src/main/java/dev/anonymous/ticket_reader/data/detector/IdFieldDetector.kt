package dev.anonymous.ticket_reader.data.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteBuffer.allocateDirect
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import androidx.core.graphics.scale
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate

class IdFieldDetector(context: Context) {

    companion object {
        private const val INPUT_IMAGE_SIZE = 512
        private const val NUM_DETECTIONS = 40
    }

    private val interpreter: Interpreter

    init {
        val model = loadModelFile(context)
        interpreter = createInterpreter(model)
    }

    private fun createInterpreter(modelBuffer: ByteBuffer): Interpreter {
        val options = Interpreter.Options()

        // --- Default: XNNPACK (Always Safe & Fast) ---
        options.setUseXNNPACK(true)

        // --- Try GPU Delegate (fallback if fails) ---
        try {
            val gpuDelegate = GpuDelegate()
            options.addDelegate(gpuDelegate)
            Log.d("TFLITE", "GPU Delegate Enabled")
        } catch (_: Exception) {
            Log.e("TFLITE", "GPU not supported, fallback to CPU/XNNPACK")
        }

        // --- OPTIONAL: NNAPI (only if device supports it) ---
        try {
            val nnapi = NnApiDelegate()
            options.addDelegate(nnapi)
            Log.d("TFLITE", "NNAPI Delegate Enabled")
        } catch (_: Exception) {
            Log.e("TFLITE", "NNAPI not supported")
        }

        return Interpreter(modelBuffer, options)
    }

    private fun loadModelFile(context: Context): ByteBuffer {
//        val fileDescriptor = context.assets.openFd("model.tflite")
        val fileDescriptor = context.assets.openFd("micro_ticket_model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength

        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun resizeBitmap(bitmap: Bitmap): Bitmap {
        return bitmap.scale(INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE)
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = allocateDirect(1 * INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE * 3)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val intValues = IntArray(INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in intValues) {
            inputBuffer.put(((pixel shr 16) and 0xFF).toByte()) // R
            inputBuffer.put(((pixel shr 8) and 0xFF).toByte())  // G
            inputBuffer.put((pixel and 0xFF).toByte())          // B
        }

        return inputBuffer
    }

    fun detect(bitmap: Bitmap): List<DetectedBox> {
        val resized = resizeBitmap(bitmap)
        val resizedRgb = resized.copy(Bitmap.Config.ARGB_8888, false)
        val input = bitmapToInputBuffer(resizedRgb)

        val locations = Array(1) { Array(NUM_DETECTIONS) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(NUM_DETECTIONS) }
        val scores = Array(1) { FloatArray(NUM_DETECTIONS) }
        val numDetections = FloatArray(1)

        val outputs = mapOf(
            0 to locations,
            1 to classes,
            2 to scores,
            3 to numDetections
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val results = mutableListOf<DetectedBox>()

        for (i in 0 until NUM_DETECTIONS) {

            val score = scores[0].getOrNull(i) ?: continue
            if (score < 0.50f) continue

            val loc = locations[0].getOrNull(i) ?: continue
            if (loc.size < 4) continue

            if (loc[0] == 0f && loc[1] == 0f && loc[2] == 0f && loc[3] == 0f) continue

            val clazz = classes[0].getOrNull(i)?.toInt() ?: continue

            val box = RectF(
                loc[1],
                loc[0],
                loc[3],
                loc[2]
            )

            Log.d("DETECT-BOX", "class=$clazz score=$score box=$loc")
            results.add(DetectedBox(clazz, score, box))
        }

        return results
    }
}