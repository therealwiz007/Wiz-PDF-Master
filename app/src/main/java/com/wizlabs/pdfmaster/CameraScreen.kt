package com.wizlabs.pdfmaster

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

@Composable
fun CameraScreen(nav: Nav) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().background(WizBar).statusBarsPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Camera access needed", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Wiz PDF Master uses the camera only to scan documents. Photos never leave your phone.",
                color = WizMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { ask.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk)
            ) { Text("Allow camera") }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = { nav.pop() }) { Text("Not now", color = WizMuted) }
        }
        return
    }
    CameraContent(nav)
}

@Composable
private fun CameraContent(nav: Nav) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val tick = rememberTick()

    var torch by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(Prefs.autoCapture(ctx)) }
    var batch by remember { mutableStateOf(ScanSession.pages.isNotEmpty()) }
    var liveQuad by remember { mutableStateOf<Quad?>(null) }
    var steady by remember { mutableStateOf(0f) }        // 0..1 progress towards auto capture
    var capturing by remember { mutableStateOf(false) }
    var blurHeld by remember { mutableStateOf(false) }     // countdown done but the frame is still soft
    var glare by remember { mutableStateOf(false) }
    var blurryPage by remember { mutableStateOf<ScanPage?>(null) }   // batch mode: offer a retake
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val flash = remember { Animatable(0f) }

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    // About 8 MP (or the next size up): pages are kept at most ScanEngine.MAX_SIDE px
                    // long, so anything bigger only costs memory and storage on big sensors.
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Tracks detection stability across frames (only touched on the analysis thread).
    val tracker = remember { StabilityTracker() }
    val frames = remember { FrameAnalyzer() }

    fun goReview() {
        if (nav.stack.size >= 2 && nav.stack[nav.stack.size - 2] == Screen.Review) nav.pop() else nav.replace(Screen.Review)
    }

    fun capture() {
        if (capturing) return
        capturing = true
        tick()
        val hint = liveQuad
        val mainExecutor = ContextCompat.getMainExecutor(ctx)
        scope.launch {
            // Focus and expose on the page itself, wait (briefly) for both to settle, then shoot.
            val control = camera?.cameraControl
            if (control != null && previewView.width > 0) runCatching {
                val cx = hint?.points?.map { it.x }?.average()?.toFloat() ?: .5f
                val cy = hint?.points?.map { it.y }?.average()?.toFloat() ?: .5f
                val point = previewView.meteringPointFactory.createPoint(cx * previewView.width, cy * previewView.height)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .disableAutoCancel().build()
                withTimeoutOrNull(ScanTuning.FOCUS_TIMEOUT_MS) { control.startFocusAndMetering(action).await(mainExecutor) }
            }
            launch { flash.snapTo(.85f); flash.animateTo(0f, tween(320)) }
            // The camera's JPEG goes to disk untouched (one encode only); EXIF carries the rotation.
            val file = File(ScanSession.dir(ctx), "${System.nanoTime()}.jpg")
            val saved = imageCapture.saveTo(file, mainExecutor)
            runCatching { control?.cancelFocusAndMetering() }
            val page = if (saved.isFailure) null else withContext(Dispatchers.Default) {
                // Edges and focus are judged on a small decode; the full photo is only read at render time.
                val small = ScanEngine.decodeFile(file, 1000)
                if (small == null) { file.delete(); return@withContext null }
                val quad = ScanEngine.detect(small) ?: hint ?: Quad.FULL
                val blurry = !isSharp(ScanEngine.sharpness(small, quad), ScanTuning.BLUR_MIN_CAPTURE)
                small.recycle()
                ScanPage(original = file, quad = quad, filter = Prefs.defaultFilter(ctx), blurry = blurry)
            }
            capturing = false
            tracker.reset()
            if (page == null) {
                file.delete()
                toast(ctx, saved.exceptionOrNull()?.message?.let { "Capture failed: $it" } ?: "Capture failed, try again")
                return@launch
            }
            ScanSession.pages.add(page)
            ScanSession.selected.intValue = ScanSession.pages.lastIndex
            if (!batch) goReview() else blurryPage = page.takeIf { it.blurry }
        }
    }

    DisposableEffect(lifecycle) {
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = providerFuture.get()
            provider = p
            val selector43 = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector43).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()      // default YUV output: the Y plane is used directly, no RGBA conversion
            analysis.setAnalyzer(analysisExecutor) { image ->
                val info = try {
                    frames.analyze(image)
                } catch (_: Throwable) {
                    FrameInfo(null, Double.MAX_VALUE, false)
                } finally {
                    image.close()
                }
                if (info != null) {
                    val (shown, progress) = tracker.feed(info.quad, System.currentTimeMillis(), isSharp(info.sharpness))
                    val held = tracker.blurHeld
                    ContextCompat.getMainExecutor(ctx).execute {
                        liveQuad = shown
                        steady = progress
                        blurHeld = held
                        glare = info.glare
                    }
                }
            }
            runCatching {
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            }.onFailure { toast(ctx, "Camera unavailable: ${it.message}") }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { provider?.unbindAll() }
        }
    }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    LaunchedEffect(steady, auto) {
        if (auto && steady >= 1f && !capturing && liveQuad != null) capture()
    }

    Box(Modifier.fillMaxSize().background(WizBar)) {
        Column(Modifier.fillMaxSize()) {
            // ---- Top bar
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(Icons.Outlined.Close, "Close", onClick = {
                    if (ScanSession.pages.isEmpty()) nav.pop() else goReview()
                })
                Spacer(Modifier.weight(1f))
                WizChip("Auto", auto, { auto = !auto; Prefs.setAutoCapture(ctx, auto) })
                Spacer(Modifier.size(8.dp))
                WizChip(if (batch) "Batch" else "Single", batch, { batch = !batch })
                Spacer(Modifier.size(8.dp))
                GlassIconButton(
                    if (torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    "Torch", onClick = { torch = !torch }, active = torch
                )
            }

            // ---- Viewfinder (exactly 3:4 so overlay coordinates map 1:1)
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(3f / 4f, matchHeightConstraintsFirst = false)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    AndroidView({ previewView }, Modifier.fillMaxSize())
                    QuadOverlay(liveQuad, steady, auto, Modifier.fillMaxSize(), onTap = { pt, _ ->
                        focusPoint = pt
                        val factory = previewView.meteringPointFactory
                        val mp = factory.createPoint(pt.x, pt.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(mp).build())
                    })
                    focusPoint?.let { FocusRing(it) { focusPoint = null } }
                    Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
                    StatusPill(
                        when {
                            capturing -> "Capturing…"
                            liveQuad == null -> "Point at a document"
                            glare -> "Tilt to avoid glare"
                            auto && blurHeld -> "Focusing…"
                            auto && steady > 0f -> "Hold steady…"
                            else -> "Document found"
                        },
                        Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                    )
                    blurryPage?.let { soft ->
                        LaunchedEffect(soft.id) { kotlinx.coroutines.delay(5000); if (blurryPage?.id == soft.id) blurryPage = null }
                        Surface(
                            onClick = {
                                val i = ScanSession.pages.indexOfFirst { it.id == soft.id }
                                if (i >= 0) ScanSession.remove(ctx, i)
                                blurryPage = null
                            },
                            shape = RoundedCornerShape(50), color = WizGlass, contentColor = Color.White,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
                                Text("Looks blurry — ", style = MaterialTheme.typography.labelLarge)
                                Text("Retake?", style = MaterialTheme.typography.labelLarge, color = WizYellow)
                            }
                        }
                    }
                }
            }

            // ---- Bottom controls
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
                if (uris.isNotEmpty()) scope.launch {
                    importImages(ctx, uris) {}
                    goReview()
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 26.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    GlassIconButton(Icons.Outlined.PhotoLibrary, "Import from gallery", size = 52.dp, onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                }
                Shutter(capturing) { capture() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    LastPageButton(onClick = { goReview() })
                }
            }
        }
    }
}

@Composable
private fun QuadOverlay(
    quad: Quad?,
    steady: Float,
    auto: Boolean,
    modifier: Modifier,
    onTap: (Offset, androidx.compose.ui.geometry.Size) -> Unit
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(quad != null) { anim.animateTo(if (quad != null) 1f else 0f, tween(200)) }
    Canvas(
        modifier.then(
            Modifier.pointerInputTap { pt, size -> onTap(pt, size) }
        )
    ) {
        val q = quad ?: return@Canvas
        val pts = q.points.map { Offset(it.x * size.width, it.y * size.height) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        val a = anim.value
        drawPath(path, WizYellow.copy(alpha = 0.18f * a))
        drawPath(path, WizYellow.copy(alpha = a), style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
        if (auto && steady > 0f) {
            // Progress runs around the outline as the page holds still.
            val progressPath = Path()
            val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
            measure.getSegment(0f, measure.length * steady, progressPath, true)
            drawPath(progressPath, Color.White, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
        }
        pts.forEach { drawCircle(WizYellow.copy(alpha = a), radius = 7.dp.toPx(), center = it) }
        pts.forEach { drawCircle(WizInk.copy(alpha = a), radius = 3.dp.toPx(), center = it) }
    }
}

@Composable
private fun FocusRing(at: Offset, onDone: () -> Unit) {
    val s = remember(at) { Animatable(1.4f) }
    LaunchedEffect(at) { s.animateTo(1f, tween(220)); kotlinx.coroutines.delay(700); onDone() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val r = with(density) { 34.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Color.White, radius = r * s.value, center = at, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(50), color = WizGlass, contentColor = Color.White, modifier = modifier) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Shutter(busy: Boolean, onClick: () -> Unit) {
    val confirm = rememberConfirm()
    Surface(
        onClick = { confirm(); onClick() },
        enabled = !busy,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(4.dp, Color.White),
        modifier = Modifier.size(78.dp).pressScale(0.88f)
    ) {
        Box(Modifier.padding(7.dp).clip(CircleShape).background(WizYellow), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(color = WizInk, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun LastPageButton(onClick: () -> Unit) {
    val pages = ScanSession.pages
    val last = pages.lastOrNull()
    val thumb by produceState<Bitmap?>(null, last?.id, last?.version) {
        value = last?.let { ScanSession.preview(it, 240) }
    }
    if (last == null) { Spacer(Modifier.size(56.dp)); return }
    Box(Modifier.size(width = 58.dp, height = 64.dp)) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = WizPanelHi,
            modifier = Modifier.size(width = 50.dp, height = 62.dp).align(Alignment.BottomStart)
                .border(2.dp, Color.White, RoundedCornerShape(10.dp))
        ) {
            thumb?.let { Image(it.asImageBitmap(), "Review pages", contentScale = ContentScale.Crop) }
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(24.dp).clip(CircleShape).background(WizYellow),
            contentAlignment = Alignment.Center
        ) { Text("${pages.size}", color = WizInk, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
    }
}

/** Tap detector that reports the canvas size too. */
private fun Modifier.pointerInputTap(onTap: (Offset, androidx.compose.ui.geometry.Size) -> Unit): Modifier =
    this.then(Modifier.pointerInput(Unit) {
        detectTapGestures { o -> onTap(o, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())) }
    })

/** Suspend until a CameraX future finishes; failures (e.g. focus cancelled) come back as null. */
private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(executor: Executor): T? =
    suspendCancellableCoroutine { cont ->
        addListener({ if (cont.isActive) cont.resume(runCatching { get() }.getOrNull()) }, executor)
    }

/** Write the camera's own JPEG straight to [file]. */
private suspend fun ImageCapture.saveTo(file: File, executor: Executor): Result<Unit> =
    suspendCancellableCoroutine { cont ->
        takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(), executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resume(Result.failure(exception))
                }
            }
        )
    }
