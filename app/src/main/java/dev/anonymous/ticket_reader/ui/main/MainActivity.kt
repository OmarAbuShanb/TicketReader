package dev.anonymous.ticket_reader.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import dev.anonymous.ticket_reader.data.analyzers.CredentialsAnalyzer
import dev.anonymous.ticket_reader.data.detector.IdFieldDetector
import dev.anonymous.ticket_reader.ui.login.LoginBottomSheetFragment
import dev.anonymous.ticket_reader.data.ocr.MlKitOcrReader
import dev.anonymous.ticket_reader.R
import dev.anonymous.ticket_reader.ui.views.FocusBoxView
import dev.anonymous.ticket_reader.ui.views.OverlayView
import dev.anonymous.ticket_reader.utils.BitmapUtils
import dev.anonymous.ticket_reader.utils.ImageProxyUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var focusBoxView: FocusBoxView
    private lateinit var cameraExecutor: ExecutorService

    private val detector by lazy { IdFieldDetector(this) }

    private val labels = listOf(
        "username",
        "password"
    )

    private val credentialsAnalyzer by lazy {
        CredentialsAnalyzer { user, pass ->
            runOnUiThread {
                Toast.makeText(this, "User: $user\nPass: $pass", Toast.LENGTH_LONG).show()
                val loginFragment = LoginBottomSheetFragment.Companion.newInstance(user, pass)
                loginFragment.show(supportFragmentManager, "LoginBottomSheetFragment")
            }
        }
    }

    // --- Variables for performance measurement ---
    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    // -----------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        focusBoxView = findViewById(R.id.focusBoxView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        requestPermissionAndStartCamera()
    }

    private fun requestPermissionAndStartCamera() {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) startCamera()
                else Log.e("CameraX", "Camera permission denied")
            }

        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()

            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.Companion.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(360, 240),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                            )
                        )
                        .build()
                )
                .setTargetRotation(previewView.display.rotation)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        calculateFps()
                        analyzeFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
                camera.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                Log.e("CameraX", "Binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val originalBitmap = ImageProxyUtils.toBitmap(imageProxy)

            val matrix = Matrix()
            matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

            val rotatedBitmap = Bitmap.createBitmap(
                originalBitmap,
                0, 0,
                originalBitmap.width,
                originalBitmap.height,
                matrix,
                true
            )

            val focusRect = focusBoxView.getBoxRect() ?: return

            val scaleX = rotatedBitmap.width / focusBoxView.width.toFloat()
            val scaleY = rotatedBitmap.height / focusBoxView.height.toFloat()

            val cropLeft = (focusRect.left * scaleX).toInt()
            val cropTop = (focusRect.top * scaleY).toInt()
            val cropWidth = (focusRect.width() * scaleX).toInt()
            val cropHeight = (focusRect.height() * scaleY).toInt()

            if (cropLeft + cropWidth > rotatedBitmap.width ||
                cropTop + cropHeight > rotatedBitmap.height) {
                imageProxy.close()
                return
            }

            val croppedBitmap = Bitmap.createBitmap(
                rotatedBitmap,
                cropLeft,
                cropTop,
                cropWidth,
                cropHeight
            )

            // --- Inference Time Measurement ---
            val startTime = System.currentTimeMillis()
            val detections = detector.detect(croppedBitmap)
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d("PERFORMANCE", "Model inference time: $inferenceTime ms")
            // --------------------------------

            val translatedDetections = detections.map { detection ->
                val box = detection.box
                val translatedBox = RectF(
                    (box.left * cropWidth + cropLeft) / rotatedBitmap.width.toFloat(),
                    (box.top * cropHeight + cropTop) / rotatedBitmap.height.toFloat(),
                    (box.right * cropWidth + cropLeft) / rotatedBitmap.width.toFloat(),
                    (box.bottom * cropHeight + cropTop) / rotatedBitmap.height.toFloat()
                )
                detection.copy(box = translatedBox)
            }

            val uniqueDetections = translatedDetections
                .groupBy { it.classId }
                .map { (_, group) -> group.maxByOrNull { it.score }!! }

            runOnUiThread {
                if (uniqueDetections.isNotEmpty()) {
                    val boxes = uniqueDetections.map { it.box }
                    overlayView.setResults(boxes, rotatedBitmap.width, rotatedBitmap.height)
                } else {
                    overlayView.clear()
                }
            }

            if (translatedDetections.isNotEmpty()) {
                translatedDetections.forEach { box ->
                    val label = labels.getOrNull(box.classId) ?: "unknown"
                    val croppedForOcr = BitmapUtils.cropBox(rotatedBitmap, box.box)
                    val big = croppedForOcr.scale(croppedForOcr.width * 4, croppedForOcr.height * 4)

                    MlKitOcrReader.readText(big) {
                        Log.d("ID-FIELD", "$label → $it")
                        credentialsAnalyzer.onDetect(label, it)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("ID-DETECT", "Error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun calculateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (lastFpsTimestamp == 0L) lastFpsTimestamp = now
        if (now - lastFpsTimestamp >= 1000) {
            Log.d("PERFORMANCE", "FPS: $frameCount")
            frameCount = 0
            lastFpsTimestamp = now
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}