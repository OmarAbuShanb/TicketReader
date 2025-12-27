package dev.anonymous.ticket_reader.data.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class YoloDetector(context: Context) {

    companion object {
        private const val TAG = "YOLO-TFLITE"

        private const val MODEL_FILENAME = "best_int8_nms.tflite"
        private const val INPUT_SIZE = 640
        private const val CHANNELS = 3

        // output: [1, 6, 8400] إذا NMS غير مفعّل
        private const val OUTPUT_CH = 6
        private const val NUM_ANCHORS = 8400

        private const val CONF_THRES = 0.25f
        private const val IOU_THRES = 0.7f

        private const val PAD = 114

        // ⭐⭐⭐
        private const val MODEL_HAS_NMS = true
    }

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    private val inputBuffer = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * CHANNELS * 4)
        .order(ByteOrder.nativeOrder())

    private val outputRaw =
        Array(1) { Array(OUTPUT_CH) { FloatArray(NUM_ANCHORS) } }

    private val outputWithNms =
        Array(1) { Array(300) { FloatArray(OUTPUT_CH) } }

    init {
        interpreter = Interpreter(loadModel(context), createOptions())
    }

    fun close() {
        try {
            interpreter.close()
            gpuDelegate?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TensorFlow Lite interpreter", e)
        }
    }

    // -------------------- Inference --------------------

    fun detect(bitmap: Bitmap): List<DetectedBox> {
        val lb = letterbox(bitmap)
        bitmapToBuffer(lb.bmp)

        if (MODEL_HAS_NMS) {
            // output جاهز
            interpreter.run(inputBuffer, outputWithNms)
            return decodeFinal(outputWithNms, bitmap, lb)
        } else {
            // raw output
            interpreter.run(inputBuffer, outputRaw)
            val decoded = decodeRaw(outputRaw, bitmap, lb)
            return nmsPerClass(decoded)
        }
    }

    // -------------------- Decode --------------------

    private fun decodeRaw(
        out: Array<Array<FloatArray>>,
        bitmap: Bitmap,
        lb: Letterbox
    ): List<DetectedBox> {

        val boxes = ArrayList<DetectedBox>()

        for (i in 0 until NUM_ANCHORS) {
            val classConfidences = floatArrayOf(
                out[0][4][i],
                out[0][5][i]
            )
            val cls = classConfidences.indices.maxBy { classConfidences[it] }
            val score = classConfidences[cls]
            if (score < CONF_THRES) continue

            val cx = out[0][0][i] * INPUT_SIZE
            val cy = out[0][1][i] * INPUT_SIZE
            val w = out[0][2][i] * INPUT_SIZE
            val h = out[0][3][i] * INPUT_SIZE

            var left = cx - w / 2f
            var top = cy - h / 2f
            var right = cx + w / 2f
            var bottom = cy + h / 2f

            // undo letterbox
            left = (left - lb.padX) / lb.scale
            right = (right - lb.padX) / lb.scale
            top = (top - lb.padY) / lb.scale
            bottom = (bottom - lb.padY) / lb.scale

            left = left.coerceIn(0f, bitmap.width.toFloat())
            right = right.coerceIn(0f, bitmap.width.toFloat())
            top = top.coerceIn(0f, bitmap.height.toFloat())
            bottom = bottom.coerceIn(0f, bitmap.height.toFloat())

            if (right > left && bottom > top) {
                boxes.add(
                    DetectedBox(
                        cls,
                        score,
                        RectF(left, top, right, bottom)
                    )
                )
            }
        }
        return boxes
    }

    // إذا الموديل فيه NMS
    private fun decodeFinal(
        out: Array<Array<FloatArray>>,
        bitmap: Bitmap,
        lb: Letterbox
    ): List<DetectedBox> {

        val res = ArrayList<DetectedBox>()

        for (i in out[0].indices) {
            val row = out[0][i]

            val score = row[4]
            if (score < CONF_THRES) continue

            // 1) من normalized → 640
            var left = row[0] * INPUT_SIZE
            var top = row[1] * INPUT_SIZE
            var right = row[2] * INPUT_SIZE
            var bottom = row[3] * INPUT_SIZE
            val cls = row[5].toInt()

            // 2) فك الـ letterbox
            left = (left - lb.padX) / lb.scale
            right = (right - lb.padX) / lb.scale
            top = (top - lb.padY) / lb.scale
            bottom = (bottom - lb.padY) / lb.scale

            // 3) قص على حدود الصورة
            left = left.coerceIn(0f, bitmap.width.toFloat())
            right = right.coerceIn(0f, bitmap.width.toFloat())
            top = top.coerceIn(0f, bitmap.height.toFloat())
            bottom = bottom.coerceIn(0f, bitmap.height.toFloat())

            if (right > left && bottom > top) {
                res.add(
                    DetectedBox(
                        cls,
                        score,
                        RectF(left, top, right, bottom)
                    )
                )
            }
        }

        return res
    }


    // -------------------- NMS --------------------

    private fun nmsPerClass(boxes: List<DetectedBox>): List<DetectedBox> {
        val out = ArrayList<DetectedBox>()
        boxes.groupBy { it.classId }.forEach { (_, list) ->
            val sorted = list.sortedByDescending { it.score }
            val used = BooleanArray(sorted.size)

            for (i in sorted.indices) {
                if (used[i]) continue
                val a = sorted[i]
                out.add(a)
                for (j in i + 1 until sorted.size) {
                    if (iou(a.box, sorted[j].box) > IOU_THRES) {
                        used[j] = true
                    }
                }
            }
        }
        return out
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interW = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val interH = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = interW * interH
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    // -------------------- Utils --------------------

    private data class Letterbox(
        val bmp: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private fun letterbox(src: Bitmap): Letterbox {
        val r = min(INPUT_SIZE / src.width.toFloat(), INPUT_SIZE / src.height.toFloat())
        val nw = (src.width * r).toInt()
        val nh = (src.height * r).toInt()
        val resized = src.scale(nw, nh)
        val out = createBitmap(INPUT_SIZE, INPUT_SIZE)

        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(PAD, PAD, PAD))
        val px = (INPUT_SIZE - nw) / 2f
        val py = (INPUT_SIZE - nh) / 2f
        canvas.drawBitmap(resized, px, py, null)

        return Letterbox(out, r, px, py)
    }

    private fun bitmapToBuffer(bmp: Bitmap) {
        inputBuffer.rewind()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((p and 0xFF) / 255f)
        }
        inputBuffer.rewind()
    }

    private fun loadModel(ctx: Context): ByteBuffer {
        val fd = ctx.assets.openFd(MODEL_FILENAME)
        return FileInputStream(fd.fileDescriptor)
            .channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun createOptions(): Interpreter.Options {
        val opts = Interpreter.Options().setNumThreads(4)
        val compat = CompatibilityList()
        if (compat.isDelegateSupportedOnThisDevice) {
            gpuDelegate = GpuDelegate(compat.bestOptionsForThisDevice)
            opts.addDelegate(gpuDelegate)
            Log.d(TAG, "GPU Delegate Enabled")
        } else {
            opts.setUseXNNPACK(true)
            Log.d(TAG, "XNNPACK Enabled")
        }
        return opts
    }
}
