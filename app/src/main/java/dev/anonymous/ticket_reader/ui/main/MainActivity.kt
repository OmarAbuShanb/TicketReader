package dev.anonymous.ticket_reader.ui.main

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
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
import dev.anonymous.ticket_reader.data.detector.YoloDetector
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
    private lateinit var imageView2: ImageView
    private lateinit var btnPickImage: ImageButton
    private lateinit var tvPickImage: TextView
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var isFlashOn = false

    private val detector by lazy { YoloDetector(this) }

    private val labels = listOf(
        "password_field",
        "username_field"
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

    // New: ActivityResultLauncher for picking visual media
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, uri)
                imageView2.setImageBitmap(bitmap)
                analyzeImageFromGallery(bitmap)
            } catch (e: Exception) {
                Log.e("PhotoPicker", "Error loading image: ${e.message}")
                Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d("PhotoPicker", "No media selected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        focusBoxView = findViewById(R.id.focusBoxView)
        btnFlash = findViewById(R.id.btnFlash)
        cbSaveCredentials = findViewById(R.id.cbSaveCredentials)
        imageView2 = findViewById(R.id.imageView2)
        btnPickImage = findViewById(R.id.btnPickImage) // Initialize new button
        tvPickImage = findViewById(R.id.tvPickImage) // Initialize new text view

//        testWithStaticImage()
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupFlashButton()
        setupPickImageButton() // New setup function
        requestPermissionAndStartCamera()
    }

    private fun testWithStaticImage() {
        // استبدل R.drawable.test_id_card باسم صورتك في مجلد drawable
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.test_id_card)

      detector.detect(bitmap)
    }

    private fun setupFlashButton() {
        btnFlash.setOnClickListener {
            toggleFlash()
        }
    }

    // New: Setup function for the gallery button
    private fun setupPickImageButton() {
        btnPickImage.setOnClickListener {
            // Launch the photo picker to select a single image
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                                Size(640, 640),
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

            // 1. تدوير الصورة لتكون بوضعها الصحيح
            val matrix = Matrix()
            matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

            val rotatedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
            )

            // 2. تحديد منطقة التركيز (Focus Box)
            val focusRect = focusBoxView.getBoxRect() ?: return

            val scaleX = rotatedBitmap.width / focusBoxView.width.toFloat()
            val scaleY = rotatedBitmap.height / focusBoxView.height.toFloat()

            val cropLeft = (focusRect.left * scaleX).toInt()
            val cropTop = (focusRect.top * scaleY).toInt()
            val cropWidth = (focusRect.width() * scaleX).toInt()
            val cropHeight = (focusRect.height() * scaleY).toInt()

            // التحقق من الحدود لتجنب الاخطاء
            if (cropLeft < 0 || cropTop < 0 ||
                cropLeft + cropWidth > rotatedBitmap.width ||
                cropTop + cropHeight > rotatedBitmap.height) {
                return // أو يمكنك عمل clamp للقيم
            }

            val croppedBitmap = Bitmap.createBitmap(
                rotatedBitmap, cropLeft, cropTop, cropWidth, cropHeight
            )

            // 3. الاكتشاف (Detection)
            val detections = detector.detect(croppedBitmap)

            if (detections.isEmpty()) {
                runOnUiThread { overlayView.clear() }
                return
            }

            // --- تصحيح الحسابات هنا ---

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


            // 4. تحديث الواجهة (الرسم)
            runOnUiThread {
                overlayView.setResults(finalDetectionsForUI, rotatedBitmap.width, rotatedBitmap.height)
            }

            if (isAnalyzerPaused) return

            // 5. تنفيذ الـ OCR على المناطق المكتشفة
            finalDetectionsForOCR.forEach { (classId, pixelRect) ->
                val label = labels.getOrNull(classId) ?: "unknown"

                // استخدام الإحداثيات البكسلية (pixelRect) للقص
                // تأكد من أن الدالة cropBox تتعامل مع overflow (إذا خرجت الإحداثيات قليلاً عن الصورة)
                val croppedForOcr = BitmapUtils.cropBoxPx(rotatedBitmap, pixelRect)


                // تكبير الصورة لتحسين قراءة النصوص الصغيرة
                val big = croppedForOcr.scale(croppedForOcr.width * 4, croppedForOcr.height * 4, true)
                runOnUiThread {
                    imageView2.setImageBitmap(big)
                }
                MlKitOcrReader.readText(big) { text ->
                    Log.d("ID-FIELD", "$label → $text")
                    if (!isAnalyzerPaused) {
                        println("!isAnalyzerPaused")
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
                        val big = croppedForOcr.scale(croppedForOcr.width * 4, croppedForOcr.height * 4)

                        MlKitOcrReader.readText(big) {
                            Log.d("ID-FIELD", "$label (from gallery) → $it")
                            if (isAnalyzerPaused) {
                                return@readText
                            }
                            credentialsAnalyzer.onDetect(label, it)
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "No credentials found in selected image.", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e("ID-DETECT", "Error analyzing gallery image: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Error analyzing image", Toast.LENGTH_SHORT).show()
                }
            }
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
        detector.close()
    }
}