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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import dev.anonymous.ticket_reader.data.analyzers.CredentialsAnalyzer
import dev.anonymous.ticket_reader.data.detector.YoloDetector
import dev.anonymous.ticket_reader.data.ocr.MlKitOcrReader
import dev.anonymous.ticket_reader.databinding.ActivityMainBinding
import dev.anonymous.ticket_reader.ui.login.LoginBottomSheetFragment
import dev.anonymous.ticket_reader.utils.BitmapUtils
import dev.anonymous.ticket_reader.utils.FlashMode
import dev.anonymous.ticket_reader.utils.ImageProxyUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val detector by lazy { YoloDetector(this) }

    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val labels = listOf(
        "password_field",
        "username_field"
    )

    @Volatile
    private var isAnalyzerPaused = false

    private val credentialsAnalyzer by lazy {
        CredentialsAnalyzer { user, pass ->
            runOnUiThread {
                Toast.makeText(this, "$user\n$pass", Toast.LENGTH_LONG).show()
                pauseAnalyzer()
                binding.overlayView.clear()
                val loginFragment = LoginBottomSheetFragment.newInstance(user, pass)
                loginFragment.setOnDismissListener(object :
                    LoginBottomSheetFragment.OnDismissListener {
                    override fun onDialogDismissed() {
                        resumeAnalyzer()
                    }
                })
                loginFragment.show(supportFragmentManager, "LoginBottomSheetFragment")
                if (binding.cbSaveCredentials.isChecked) {
                    saveCredentialsToClipboard(user, pass)
                }
            }
        }
    }

    @Volatile
    private var isPickerOpen = false

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            isPickerOpen = false
            if (uri != null) {
                val bitmapFromUri = BitmapUtils.loadBitmapCorrectly(this, uri)
                binding.imageView2.setImageBitmap(bitmapFromUri)
                analyzeImageFromGallery(bitmapFromUri)
            } else {
                Log.d("PhotoPicker", "No media selected")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupListeners()
        observeFlashMode()
        requestPermissionAndStartCamera()
    }

    private fun setupListeners() {
        binding.layoutToggleFlashMode.setOnClickListener {
            viewModel.toggleFlashMode()
        }

        binding.btnPickImage.setOnClickListener {
            if (isPickerOpen) return@setOnClickListener
            isPickerOpen = true
            pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    private fun observeFlashMode() {
        viewModel.flashMode.observe(this) { mode ->
            updateFlashUI(mode)

            // Control the camera torch state based on the ViewModel
            val enableTorch = viewModel.isTorchNeeded(mode)
            camera?.let {
                if (it.cameraInfo.hasFlashUnit()) {
                    it.cameraControl.enableTorch(enableTorch)
                }
            }
        }
    }

    private fun updateFlashUI(mode: FlashMode) {
        binding.btnFlash.setImageResource(mode.iconRes)
        binding.tvFlashMode.text = mode.text
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
                .also { it.surfaceProvider = binding.previewView.surfaceProvider }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 640),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                            )
                        )
                        .build()
                )
                .setTargetRotation(binding.previewView.display.rotation)
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

                // Set initial torch state based on ViewModel's persisted mode
                if (viewModel.isTorchNeeded()) {
                    camera?.cameraControl?.enableTorch(true)
                }

            } catch (e: Exception) {
                Log.e("CameraX", "Binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val originalBitmap = ImageProxyUtils.toBitmap(imageProxy)

            // 1. تدوير الصورة لتكون بوضعها الصحيح
            val matrix = Matrix()
            matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

            val rotatedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
            )

            runOnUiThread {
                binding.imageView2.setImageBitmap(originalBitmap)
            }

            // 2. تحديد منطقة التركيز (Focus Box)
            val focusRect = binding.focusBoxView.getBoxRect() ?: return

            val scaleX = rotatedBitmap.width / binding.focusBoxView.width.toFloat()
            val scaleY = rotatedBitmap.height / binding.focusBoxView.height.toFloat()

            val cropLeft = (focusRect.left * scaleX).toInt()
            val cropTop = (focusRect.top * scaleY).toInt()
            val cropWidth = (focusRect.width() * scaleX).toInt()
            val cropHeight = (focusRect.height() * scaleY).toInt()

            // التحقق من الحدود لتجنب الاخطاء
            if (cropLeft < 0 || cropTop < 0 ||
                cropLeft + cropWidth > rotatedBitmap.width ||
                cropTop + cropHeight > rotatedBitmap.height
            ) {
                return // أو يمكنك عمل clamp للقيم
            }

            val croppedBitmap = Bitmap.createBitmap(
                rotatedBitmap, cropLeft, cropTop, cropWidth, cropHeight
            )

            // 3. الاكتشاف (Detection)
            val detections = detector.detect(croppedBitmap)

            if (isAnalyzerPaused) return

            if (detections.isEmpty()) {
                runOnUiThread { binding.overlayView.clear() }
                return
            }

            // حساب نسبة التمدد التي قام بها الموديل (لأن الموديل ضغط الصورة إلى 640x640)
            // ملاحظة: افترضنا أن كلاس Detector يقوم بعمل Resize داخلي لـ 640x640
            val finalDetectionsForUI = mutableListOf<RectF>()
            val finalDetectionsForOCR = mutableListOf<Pair<Int, RectF>>()

            detections.forEach { detection ->
                val b = detection.box  // هذا الآن pixel coords على croppedBitmap

                // رجّع للصورة rotatedBitmap عبر إضافة إزاحة القص فقط
                val absoluteRect = RectF(
                    b.left + cropLeft,
                    b.top + cropTop,
                    b.right + cropLeft,
                    b.bottom + cropTop
                )

                finalDetectionsForOCR.add(Pair(detection.classId, absoluteRect))

                val normalizedRect = RectF(
                    absoluteRect.left / rotatedBitmap.width,
                    absoluteRect.top / rotatedBitmap.height,
                    absoluteRect.right / rotatedBitmap.width,
                    absoluteRect.bottom / rotatedBitmap.height
                )
                finalDetectionsForUI.add(normalizedRect)
            }

            runOnUiThread {
                binding.overlayView.setResults(
                    finalDetectionsForUI,
                    rotatedBitmap.width,
                    rotatedBitmap.height
                )
            }

            // 5. تنفيذ الـ OCR على المناطق المكتشفة
            finalDetectionsForOCR.forEach { (classId, pixelRect) ->
                val label = labels.getOrNull(classId) ?: "unknown"

                // استخدام الإحداثيات البكسلية (pixelRect) للقص
                // تأكد من أن الدالة cropBox تتعامل مع overflow (إذا خرجت الإحداثيات قليلاً عن الصورة)
                val croppedForOcr = BitmapUtils.cropBoxPx(rotatedBitmap, pixelRect)

                // تكبير الصورة لتحسين قراءة النصوص الصغيرة
                val big =
                    croppedForOcr.scale(croppedForOcr.width * 4, croppedForOcr.height * 4, true)
                runOnUiThread {
                    binding.imageView2.setImageBitmap(big)
                }
                MlKitOcrReader.readText(big) { text ->
                    Log.d("ID-FIELD", "$label → $text")
                    if (!isAnalyzerPaused) {
                        credentialsAnalyzer.onDetect(label, text)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("ID-DETECT", "Error: ${e.message}", e)
        } finally {
            // إغلاق الصورة دائماً لتجنب تسريب الذاكرة
            imageProxy.close()
        }
    }

    // New: Function to analyze an image picked from the gallery
    private fun analyzeImageFromGallery(bitmap: Bitmap) {
        Toast.makeText(this, "جار تحليل الصورة", Toast.LENGTH_SHORT).show()
        cameraExecutor.execute {
            try {
                val startTime = System.currentTimeMillis()
                val detections = detector.detect(bitmap)
                if (isAnalyzerPaused) {
                    return@execute
                }
                val inferenceTime = System.currentTimeMillis() - startTime
                Log.d("PERFORMANCE", "Model inference time (gallery): $inferenceTime ms")
                println("detections size = ${detections.size}")
                val uniqueDetections = detections
                    .groupBy { it.classId }
                    .map { (_, group) -> group.maxByOrNull { it.score }!! }

                if (uniqueDetections.isNotEmpty()) {
                    uniqueDetections.forEach { box ->
                        val label = labels.getOrNull(box.classId) ?: "unknown"
                        val croppedForOcr = BitmapUtils.cropBoxPx(bitmap, box.box)
                        val big =
                            croppedForOcr.scale(croppedForOcr.width * 4, croppedForOcr.height * 4)

                        MlKitOcrReader.readText(big) {
                            Log.d("ID-FIELD", "$label (from gallery) → $it")
                            if (isAnalyzerPaused) {
                                return@readText
                            }
                            // The original code uses processSingleImageCredentials which is missing here, 
                            // I will assume it's part of CredentialsAnalyzer and keep it as is.
                            credentialsAnalyzer.processSingleImageCredentials(label, it)
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "لم يستطيع البرنامج التعرف على الصورة",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

            } catch (e: Exception) {
                Log.e("ID-DETECT", "Error analyzing gallery image: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "فشل في تحليل الصورة", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun pauseAnalyzer() {
        isAnalyzerPaused = true
        imageAnalysis?.clearAnalyzer()

        // Temporarily disable torch if the current mode requires it
        if (viewModel.isTorchNeeded()) {
            camera?.let {
                if (it.cameraInfo.hasFlashUnit()) {
                    it.cameraControl.enableTorch(false)
                }
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
            analyzeFrame(imageProxy)
        }

        // Re-enable torch if the current mode requires it
        if (viewModel.isTorchNeeded()) {
            camera?.let {
                if (it.cameraInfo.hasFlashUnit()) {
                    it.cameraControl.enableTorch(true)
                }
            }
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

    override fun onResume() {
        super.onResume()
        if (viewModel.isTorchNeeded()) {
            camera?.let {
                if (it.cameraInfo.hasFlashUnit()) {
                    it.cameraControl.enableTorch(true)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageAnalysis?.clearAnalyzer()
        cameraExecutor.shutdown()
        imageAnalysis?.clearAnalyzer()
        try {
            if (!cameraExecutor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                Log.w("YOLO-SHUTDOWN", "Executor did not terminate in time. Forcing shutdown.")
                cameraExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Log.e("YOLO-SHUTDOWN", "Executor termination interrupted", e)
            Thread.currentThread().interrupt()
        }
        camera?.cameraControl?.enableTorch(false)
        detector.close()
    }
}