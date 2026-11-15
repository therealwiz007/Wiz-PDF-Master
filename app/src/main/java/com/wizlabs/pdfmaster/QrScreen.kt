package com.wizlabs.pdfmaster

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A code that was read: what it says and what kind of code it was. */
private class QrHit(val raw: String, val content: QrContent, val qr: Boolean)

private fun hitOf(codes: List<Barcode>): QrHit? {
    val b = codes.firstOrNull { !it.rawValue.isNullOrEmpty() } ?: return null
    val raw = b.rawValue!!
    return QrHit(raw, QrParse.parse(raw), b.format == Barcode.FORMAT_QR_CODE)
}

/**
 * QR and barcode reader. The reader is a component of Google Play services; when the phone
 * doesn't have it (yet), the screen says so instead of failing. Nothing a code says is opened
 * until the user taps a button.
 */
@Composable
fun QrScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { runCatching { BarcodeScanning.getClient() }.getOrNull() }
    var hit by remember { mutableStateOf<QrHit?>(null) }
    var unavailable by remember { mutableStateOf(scanner == null) }
    var module by remember { mutableStateOf<ModuleState?>(null) }
    var torch by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) { onDispose { runCatching { scanner?.close() } } }

    // The reader can't be used: ask Play services for it once, and keep showing where that stands.
    LaunchedEffect(unavailable) {
        if (unavailable && scanner != null && module == null) {
            module = ModuleState.CHECKING
            PlayModules.install(ctx.applicationContext, scanner) { module = it; if (it == ModuleState.READY) unavailable = false }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && scanner != null) scope.launch {
            try {
                val found = hitOf(scanner.process(InputImage.fromFilePath(ctx, uri)).await())
                if (found != null) hit = found else toast(ctx, "No QR code or barcode found in that picture")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                unavailable = true
                toast(ctx, "The code reader isn't ready on this phone yet")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(WizBar)) {
        if (granted && scanner != null) QrCamera(
            scanner, torch, paused = hit != null,
            onHit = { if (hit == null) hit = it },
            onFailure = { unavailable = true },
            modifier = Modifier.fillMaxSize()
        )
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Outlined.Close, "Close", onClick = { nav.pop() })
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(50), color = WizGlass, contentColor = Color.White) {
                Text("Point at a QR code or barcode", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
            }
            Spacer(Modifier.weight(1f))
            GlassIconButton(if (torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff, "Torch", onClick = { torch = !torch }, active = torch)
        }

        // Aiming frame
        if (hit == null && !unavailable && granted) Box(
            Modifier.align(Alignment.Center).size(230.dp).clip(RoundedCornerShape(26.dp))
                .background(Color.Transparent)
        ) {
            Surface(color = Color.Transparent, border = BorderStroke(3.dp, WizYellow), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxSize()) {}
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val h = hit
            when {
                h != null -> ResultCard(h, onAgain = { hit = null })
                !granted -> NoticeCard(
                    "Camera access needed",
                    "The camera is used only to read the code. You can also pick a picture that shows one.",
                    "Allow camera"
                ) { ask.launch(Manifest.permission.CAMERA) }
                unavailable -> NoticeCard(
                    "Code reader not ready",
                    when (module) {
                        ModuleState.DOWNLOADING, ModuleState.CHECKING -> "Google Play services is adding the code reader to this phone. Leave this screen open or try again in a minute."
                        ModuleState.READY -> "The code reader is ready now."
                        else -> "Reading QR codes needs a component of Google Play services that isn't available on this phone."
                    },
                    null
                ) {}
            }
            if (h == null) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    shape = RoundedCornerShape(50), color = WizGlass, contentColor = Color.White, border = BorderStroke(1.dp, WizLine)
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Scan QR from image", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, action: String?, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = WizPanel, border = BorderStroke(1.dp, WizLine), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = WizMuted)
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk)) { Text(action) }
            }
        }
    }
}

@Composable
private fun Line(k: String, v: String) {
    if (v.isBlank()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, color = WizMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 10.dp).size(width = 74.dp, height = 18.dp))
        SelectionContainer { Text(v, style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun start(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(ctx, "No app on this phone can open that") }
}

@Composable
private fun ResultCard(h: QrHit, onAgain: () -> Unit) {
    val ctx = LocalContext.current
    val c = h.content
    val plain = remember(h) { QrParse.plain(c, h.raw) }
    Surface(shape = RoundedCornerShape(22.dp), color = WizPanel, border = BorderStroke(1.dp, WizYellow.copy(alpha = .55f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(if (h.qr) "QR CODE" else "BARCODE")
                Spacer(Modifier.size(8.dp))
                Text(c.title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
                when (c) {
                    is QrContent.Url -> SelectionContainer { Text(c.url, style = MaterialTheme.typography.bodyMedium, color = WizYellow) }
                    is QrContent.Wifi -> { Line("Network", c.ssid); Line("Password", c.password); Line("Security", c.security + if (c.hidden) " · hidden" else "") }
                    is QrContent.Contact -> {
                        Line("Name", c.name); Line("Company", c.org)
                        c.phones.forEach { Line("Phone", it) }; c.emails.forEach { Line("E-mail", it) }
                    }
                    is QrContent.Email -> { Line("To", c.to); Line("Subject", c.subject); Line("Message", c.body) }
                    is QrContent.Phone -> Line("Number", c.number)
                    is QrContent.Sms -> { Line("To", c.number); Line("Message", c.body) }
                    is QrContent.Geo -> Line("Position", "${c.lat}, ${c.lon}")
                    is QrContent.Text -> SelectionContainer { Text(c.text, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The main action for this kind of code. Links open in the browser, never inside this app.
                when (c) {
                    is QrContent.Url -> WizChip("Open link", true, { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) })
                    is QrContent.Wifi -> {
                        if (c.password.isNotEmpty()) WizChip("Copy password", true, { copyToClipboard(ctx, c.password) })
                        WizChip("Wi-Fi settings", false, { start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS)) })
                    }
                    is QrContent.Contact -> WizChip("Add contact", true, {
                        start(ctx, Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.NAME, c.name)
                            .putExtra(ContactsContract.Intents.Insert.PHONE, c.phones.firstOrNull())
                            .putExtra(ContactsContract.Intents.Insert.EMAIL, c.emails.firstOrNull())
                            .putExtra(ContactsContract.Intents.Insert.COMPANY, c.org))
                    })
                    is QrContent.Email -> WizChip("Write e-mail", true, {
                        start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(c.to)))
                            .putExtra(Intent.EXTRA_SUBJECT, c.subject).putExtra(Intent.EXTRA_TEXT, c.body))
                    })
                    is QrContent.Phone -> WizChip("Call", true, { start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.number)))) })
                    is QrContent.Sms -> WizChip("Write message", true, {
                        start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", c.body))
                    })
                    is QrContent.Geo -> WizChip("Open map", true, { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${c.lat},${c.lon}?q=${c.lat},${c.lon}"))) })
                    is QrContent.Text -> {}
                }
                WizChip("Copy", false, { copyToClipboard(ctx, plain) })
                WizChip("Share", false, { shareText(ctx, plain) })
            }
            TextButton(onClick = onAgain, modifier = Modifier.align(Alignment.End)) { Text("Scan another", color = WizYellow, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Live camera feeding frames to the reader, one at a time; stops reading while [paused]. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun QrCamera(
    scanner: BarcodeScanner, torch: Boolean, paused: Boolean,
    onHit: (QrHit) -> Unit, onFailure: () -> Unit, modifier: Modifier
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val hold = remember { AtomicBoolean(false) }
    hold.set(paused)
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val lastTry = remember { java.util.concurrent.atomic.AtomicLong(0) }

    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        val main = ContextCompat.getMainExecutor(ctx)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                val now = System.currentTimeMillis()
                // About six reads a second is plenty for a code held still, and keeps the phone cool.
                if (media == null || hold.get() || now - lastTry.get() < 160) { proxy.close(); return@setAnalyzer }
                lastTry.set(now)
                runCatching {
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener(main) { codes -> hitOf(codes)?.let(onHit) }
                        .addOnFailureListener(main) { lastTry.set(System.currentTimeMillis() + 1500); onFailure() }
                        .addOnCompleteListener { proxy.close() }
                }.onFailure { proxy.close() }
            }
            runCatching {
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { toast(ctx, "Camera unavailable: ${it.message}") }
        }, main)
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }
    LaunchedEffect(torch, camera) { runCatching { camera?.cameraControl?.enableTorch(torch) } }
    AndroidView({ previewView }, modifier)
}
