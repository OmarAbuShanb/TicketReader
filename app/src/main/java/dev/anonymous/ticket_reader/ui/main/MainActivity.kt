package dev.anonymous.ticket_reader.ui.main

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import dev.anonymous.ticket_reader.R
import dev.anonymous.ticket_reader.data.analyzers.CredentialsAnalyzer
import dev.anonymous.ticket_reader.data.detector.IdFieldDetector
import dev.anonymous.ticket_reader.data.ocr.MlKitOcrReader
import dev.anonymous.ticket_reader.ui.login.LoginBottomSheetFragment
import dev.anonymous.ticket_reader.ui.views.FocusBoxView
import dev.anonymous.ticket_reader.ui.views.OverlayView
import dev.anonymous.ticket_reader.utils.BitmapUtils
import dev.anonymous.ticket_reader.utils.ImageProxyUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var cbSaveCredentials: CheckBox
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var focusBoxView: FocusBoxView
    private lateinit var btnFlash: ImageButton
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var isFlashOn = false

    private val detector by lazy { IdFieldDetector(this) }

    private val labels = listOf(
        "username",
        "password"
    )

    @Volatile
    private var isAnalyzerPaused = false

    private val credentialsAnalyzer by lazy {
        CredentialsAnalyzer { user, pass ->
            runOnUiThread {
                Toast.makeText(this, "User: $user\nPass: $pass", Toast.LENGTH_LONG).show()
                pauseAnalyzer()
                val loginFragment = LoginBottomSheetFragment.newInstance(user, pass)
                loginFragment.setOnDismissListener(object :
                    LoginBottomSheetFragment.OnDismissListener {
                    override fun onDialogDismissed() {
                        resumeAnalyzer()
                    }
                })
                loginFragment.show(supportFragmentManager, "LoginBottomSheetFragment")
                if (cbSaveCredentials.isChecked) {
                    saveCredentialsToClipboard(user, pass)
                }
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
        btnFlash = findViewById(R.id.btnFlash)
        cbSaveCredentials = findViewById(R.id.cbSaveCredentials)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupFlashButton()
        requestPermissionAndStartCamera()
    }

    private fun setupFlashButton() {
        btnFlash.setOnClickListener {
            toggleFlash()
        }
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

            imageAnalysis = ImageAnalysis.Builder()
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
            resumeAnalyzer()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                observeFlashState()
            } catch (e: Exception) {
                Log.e("CameraX", "Binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleFlash() {
        camera?.let {
            if (it.cameraInfo.hasFlashUnit()) {
                isFlashOn = it.cameraInfo.torchState.value != TorchState.ON
                it.cameraControl.enableTorch(isFlashOn)
            }
        }
    }

    private fun observeFlashState() {
        camera?.cameraInfo?.torchState?.observe(this) { state ->
            if (state == TorchState.ON) {
                btnFlash.setImageResource(R.drawable.ic_flash_on)
            } else {
                btnFlash.setImageResource(R.drawable.ic_flash_off)
            }
        }
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
                cropTop + cropHeight > rotatedBitmap.height
            ) {
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
            if (isAnalyzerPaused) {
                imageProxy.close()
                return
            }
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
                        if (isAnalyzerPaused) {
                            imageProxy.close()
                            return@readText
                        }
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

    private fun pauseAnalyzer() {
        isAnalyzerPaused = true
        overlayView.clear()
        imageAnalysis?.clearAnalyzer()
        camera?.let {
            if (it.cameraInfo.hasFlashUnit() && isFlashOn) {
                it.cameraControl.enableTorch(false)
            }
        }
    }

    private fun resumeAnalyzer() {
        isAnalyzerPaused = false
        imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
            if (isAnalyzerPaused) {
                imageProxy.close()
                return@setAnalyzer
            }
            calculateFps()
            analyzeFrame(imageProxy)
        }
        camera?.let {
            if (it.cameraInfo.hasFlashUnit() && isFlashOn) {
                it.cameraControl.enableTorch(true)
            }
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

    private fun saveCredentialsToClipboard(username: String, password: String) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        // 1) نسخ اليوزر
        val clipUser = ClipData.newPlainText("Username", username)
        clipboardManager.setPrimaryClip(clipUser)

        // 2) نسخ الباس بعد 300ms ليظهر بشكل منفصل في Gboard
        android.os.Handler(mainLooper).postDelayed({
            val clipPass = ClipData.newPlainText("Password", password)
            clipboardManager.setPrimaryClip(clipPass)
        }, 300)
    }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        imageAnalysis?.clearAnalyzer()
        camera?.cameraControl?.enableTorch(false)
    }
}
