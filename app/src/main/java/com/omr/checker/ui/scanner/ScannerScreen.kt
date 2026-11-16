package com.omr.checker.ui.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omr.checker.vision.OmrEngine
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToResult: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var camera: Camera? by remember { mutableStateOf(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val isCapturing = remember { AtomicBoolean(false) }
    val consecutiveFrames = remember { AtomicInteger(0) }
    val lastLockedCornersNorm = remember { java.util.concurrent.atomic.AtomicReference<List<org.opencv.core.Point>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.exam?.title ?: "Scan Answer Sheet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = uiState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isCornersLocked) Color(0xFF00E676) else Color.LightGray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isTorchOn = !isTorchOn
                            camera?.cameraControl?.enableTorch(isTorchOn)
                        }
                    ) {
                        Icon(
                            if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Flash",
                            tint = if (isTorchOn) Color.Yellow else Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                )
            )
        }
    ) { innerPadding ->
        if (!hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission is required to scan answer sheets.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.Black)
            ) {
                // 1. CameraX PreviewView with ImageAnalysis for real-time corner detection
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()
                            imageCapture = capture

                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                try {
                                    val currentState = viewModel.uiState.value
                                    if (currentState.isProcessing || isCapturing.get()) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }

                                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                    val rawBitmap = imageProxy.toBitmap()

                                    // Rotate if needed
                                    val rotated = if (rotationDegrees != 0) {
                                        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                                        val rot = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                                        if (rot != rawBitmap) rawBitmap.recycle()
                                        rot
                                    } else {
                                        rawBitmap
                                    }

                                    // Downscale to 480px width for fast live detection (<8ms)
                                    val targetWidth = 480
                                    val scale = targetWidth.toFloat() / rotated.width.coerceAtLeast(1)
                                    val targetHeight = (rotated.height * scale).toInt().coerceAtLeast(1)
                                    val scaled = Bitmap.createScaledBitmap(rotated, targetWidth, targetHeight, false)
                                    if (scaled != rotated) rotated.recycle()

                                    val rgbaMat = Mat()
                                    Utils.bitmapToMat(scaled, rgbaMat)
                                    scaled.recycle()

                                    val grayMat = Mat()
                                    Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
                                    rgbaMat.release()

                                    val detection = OmrEngine.detectLiveCorners(grayMat)
                                    grayMat.release()

                                    // Update per-corner guide status in real-time
                                    mainHandler.post {
                                        viewModel.updateCornerStatus(detection.status)
                                    }

                                    if (detection.isDetected && detection.corners != null && detection.corners.size == 4) {
                                        val normCorners = detection.corners.map {
                                            org.opencv.core.Point(it.x / targetWidth.toDouble(), it.y / targetHeight.toDouble())
                                        }
                                        lastLockedCornersNorm.set(normCorners)
                                        val count = consecutiveFrames.incrementAndGet()

                                        // Auto-capture after 3 consecutive frames of 4/4 corner lock (~250ms)
                                        if (count >= 3 && !isCapturing.get() && !currentState.isProcessing) {
                                            if (isCapturing.compareAndSet(false, true)) {
                                                val hintCorners = lastLockedCornersNorm.get()
                                                mainHandler.post {
                                                    captureAndGrade(
                                                        imageCapture = imageCapture,
                                                        context = context,
                                                        onCaptured = { capturedBmp ->
                                                            viewModel.processCapturedImage(
                                                                bitmap = capturedBmp,
                                                                context = context,
                                                                hintCorners = hintCorners,
                                                                onGraded = { scanId ->
                                                                    isCapturing.set(false)
                                                                    onNavigateToResult(scanId)
                                                                }
                                                            )
                                                        },
                                                        onError = {
                                                            isCapturing.set(false)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        consecutiveFrames.set(0)
                                    }
                                } catch (_: Exception) {
                                } finally {
                                    imageProxy.close()
                                }
                            }

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            try {
                                cameraProvider.unbindAll()
                                camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    capture,
                                    analysis
                                )
                            } catch (_: Exception) {}
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 2. Viewfinder Overlay with Cutout & 4 Individual Corner Guide Boxes
                ViewfinderOverlay(
                    cornerStatus = uiState.cornerStatus,
                    modifier = Modifier.fillMaxSize()
                )

                // 3. Real-Time Corner Status Badge below top bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .align(Alignment.TopCenter),
                    contentAlignment = Alignment.Center
                ) {
                    val count = uiState.cornerStatus.detectedCount
                    val isAll = uiState.cornerStatus.isAllDetected

                    val badgeBg = when {
                        isAll -> Color(0xFF00E676)
                        count >= 2 -> Color(0xFF0288D1)
                        count == 1 -> Color(0xFFF57C00)
                        else -> Color.Black.copy(alpha = 0.65f)
                    }

                    val textColor = if (isAll) Color.Black else Color.White

                    Row(
                        modifier = Modifier
                            .background(badgeBg, RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isAll) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "4/4 Corners Locked • Auto-Capturing...",
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                fontSize = 13.sp
                            )
                        } else if (count > 0) {
                            Text(
                                text = "$count/4 Corner Marks Detected",
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                fontSize = 13.sp
                            )
                        } else {
                            Text(
                                text = "Align sheet corner marks inside the 4 guide boxes",
                                color = textColor,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // 4. Manual Shutter Button & Controls at Bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.isProcessing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (isCapturing.compareAndSet(false, true)) {
                                    val hintCorners = lastLockedCornersNorm.get()
                                    captureAndGrade(
                                        imageCapture = imageCapture,
                                        context = context,
                                        onCaptured = { bitmap ->
                                            viewModel.processCapturedImage(
                                                bitmap = bitmap,
                                                context = context,
                                                hintCorners = hintCorners,
                                                onGraded = { scanId ->
                                                    isCapturing.set(false)
                                                    onNavigateToResult(scanId)
                                                }
                                            )
                                        },
                                        onError = {
                                            isCapturing.set(false)
                                        }
                                    )
                                }
                            },
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color.White, CircleShape)
                                .border(
                                    4.dp,
                                    if (uiState.isCornersLocked) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Capture Sheet",
                                tint = Color.Black,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ViewfinderOverlay(
    cornerStatus: com.omr.checker.vision.CornerStatus,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val isAllLocked = cornerStatus.isAllDetected
        val strokeColor = if (isAllLocked) Color(0xFF00E676) else Color.White

        // A4 Paper Aspect Ratio (1 : 1.414) centered cutout
        val cutoutWidth = size.width * 0.85f
        val cutoutHeight = (cutoutWidth * 1.414f).coerceAtMost(size.height * 0.72f)
        val left = (size.width - cutoutWidth) / 2f
        val top = (size.height - cutoutHeight) / 2f - 20f

        val cutoutRect = Rect(left, top, left + cutoutWidth, top + cutoutHeight)

        // Darken outside area
        val path = Path().apply {
            addRoundRect(RoundRect(cutoutRect, CornerRadius(8f, 8f)))
        }

        clipPath(path, clipOp = ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = if (isAllLocked) 0.45f else 0.65f))
        }

        // Draw Guide Frame
        drawRoundRect(
            color = strokeColor.copy(alpha = if (isAllLocked) 0.9f else 0.4f),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(cutoutWidth, cutoutHeight),
            cornerRadius = CornerRadius(8f, 8f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isAllLocked) 3.5f else 2f)
        )

        // 4 Larger Guide Target Boxes at the 4 Corners
        // The printed sheet fiducial square centers sit at 6% of sheet width from edges
        val guideSize = (cutoutWidth * 0.16f).coerceIn(46f, 76f)
        val markerOffset = cutoutWidth * 0.06f

        // Top-Left Guide Box
        drawCornerGuideBox(
            center = Offset(left + markerOffset, top + markerOffset),
            boxSize = guideSize,
            isDetected = cornerStatus.isTopLeftDetected
        )

        // Top-Right Guide Box
        drawCornerGuideBox(
            center = Offset(left + cutoutWidth - markerOffset, top + markerOffset),
            boxSize = guideSize,
            isDetected = cornerStatus.isTopRightDetected
        )

        // Bottom-Left Guide Box
        drawCornerGuideBox(
            center = Offset(left + markerOffset, top + cutoutHeight - markerOffset),
            boxSize = guideSize,
            isDetected = cornerStatus.isBottomLeftDetected
        )

        // Bottom-Right Guide Box
        drawCornerGuideBox(
            center = Offset(left + cutoutWidth - markerOffset, top + cutoutHeight - markerOffset),
            boxSize = guideSize,
            isDetected = cornerStatus.isBottomRightDetected
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornerGuideBox(
    center: Offset,
    boxSize: Float,
    isDetected: Boolean
) {
    val half = boxSize / 2f
    val boxTopLeft = Offset(center.x - half, center.y - half)
    val boxSizeObj = androidx.compose.ui.geometry.Size(boxSize, boxSize)
    val cornerRadius = CornerRadius(8f, 8f)

    if (isDetected) {
        // 1. Vibrant Green Glow Fill
        drawRoundRect(
            color = Color(0x4000E676),
            topLeft = boxTopLeft,
            size = boxSizeObj,
            cornerRadius = cornerRadius
        )

        // 2. Thick Vibrant Emerald Green Border
        drawRoundRect(
            color = Color(0xFF00E676),
            topLeft = boxTopLeft,
            size = boxSizeObj,
            cornerRadius = cornerRadius,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.5f)
        )

        // 3. Bold Green Checkmark in Center
        val arm = boxSize * 0.22f
        val checkColor = Color(0xFF00E676)
        val strokeW = 5f
        drawLine(
            color = checkColor,
            start = Offset(center.x - arm, center.y),
            end = Offset(center.x - arm * 0.2f, center.y + arm * 0.8f),
            strokeWidth = strokeW
        )
        drawLine(
            color = checkColor,
            start = Offset(center.x - arm * 0.2f, center.y + arm * 0.8f),
            end = Offset(center.x + arm * 1.1f, center.y - arm * 0.8f),
            strokeWidth = strokeW
        )
    } else {
        // 1. Neutral guide box (translucent white/cyan border)
        drawRoundRect(
            color = Color(0x70FFFFFF),
            topLeft = boxTopLeft,
            size = boxSizeObj,
            cornerRadius = cornerRadius,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.0f)
        )

        // 2. Inner target square indication (showing where the marker square should fit)
        val targetSize = boxSize * 0.42f
        val tHalf = targetSize / 2f
        drawRoundRect(
            color = Color(0x3064B5F6),
            topLeft = Offset(center.x - tHalf, center.y - tHalf),
            size = androidx.compose.ui.geometry.Size(targetSize, targetSize),
            cornerRadius = CornerRadius(4f, 4f)
        )
        drawRoundRect(
            color = Color(0x9064B5F6),
            topLeft = Offset(center.x - tHalf, center.y - tHalf),
            size = androidx.compose.ui.geometry.Size(targetSize, targetSize),
            cornerRadius = CornerRadius(4f, 4f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
        )

        // 3. Corner tick brackets
        val tick = 10f
        val bColor = Color(0xB0FFFFFF)
        val bW = 2.5f
        // TL
        drawLine(bColor, Offset(boxTopLeft.x, boxTopLeft.y), Offset(boxTopLeft.x + tick, boxTopLeft.y), bW)
        drawLine(bColor, Offset(boxTopLeft.x, boxTopLeft.y), Offset(boxTopLeft.x, boxTopLeft.y + tick), bW)
        // TR
        drawLine(bColor, Offset(boxTopLeft.x + boxSize, boxTopLeft.y), Offset(boxTopLeft.x + boxSize - tick, boxTopLeft.y), bW)
        drawLine(bColor, Offset(boxTopLeft.x + boxSize, boxTopLeft.y), Offset(boxTopLeft.x + boxSize, boxTopLeft.y + tick), bW)
        // BL
        drawLine(bColor, Offset(boxTopLeft.x, boxTopLeft.y + boxSize), Offset(boxTopLeft.x + tick, boxTopLeft.y + boxSize), bW)
        drawLine(bColor, Offset(boxTopLeft.x, boxTopLeft.y + boxSize), Offset(boxTopLeft.x, boxTopLeft.y + boxSize - tick), bW)
        // BR
        drawLine(bColor, Offset(boxTopLeft.x + boxSize, boxTopLeft.y + boxSize), Offset(boxTopLeft.x + boxSize - tick, boxTopLeft.y + boxSize), bW)
        drawLine(bColor, Offset(boxTopLeft.x + boxSize, boxTopLeft.y + boxSize), Offset(boxTopLeft.x + boxSize, boxTopLeft.y + boxSize - tick), bW)
    }
}

private fun captureAndGrade(
    imageCapture: ImageCapture?,
    context: Context,
    onCaptured: (Bitmap) -> Unit,
    onError: () -> Unit = {}
) {
    val capture = imageCapture ?: run {
        onError()
        return
    }

    capture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val originalBitmap = imageProxy.toBitmap()
                imageProxy.close()

                // Rotate bitmap if needed according to EXIF/Camera rotation
                val finalBitmap = if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    val rot = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
                    if (rot != originalBitmap) originalBitmap.recycle()
                    rot
                } else {
                    originalBitmap
                }

                onCaptured(finalBitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                onError()
            }
        }
    )
}
