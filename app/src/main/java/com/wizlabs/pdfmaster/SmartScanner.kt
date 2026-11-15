package com.wizlabs.pdfmaster

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The "Smart" scanner: Google's document scanner, a component of Play services that brings its own
 * camera screen. Its pages come back as JPEG files and go into the app's own review screen, where
 * looks, text recognition, page size and saving work exactly as for the app's own camera.
 */
object SmartScanner {
    const val PAGE_LIMIT = 50

    fun client(cleanup: Boolean): GmsDocumentScanner = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            .setScannerMode(if (cleanup) GmsDocumentScannerOptions.SCANNER_MODE_FULL else GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setGalleryImportAllowed(true)
            .setPageLimit(PAGE_LIMIT)
            .build()
    )

    /** The scanner the next scan will open on this phone. */
    fun kind(ctx: Context, idCard: Boolean): ScannerKind =
        ScannerChoice.pick(Prefs.scanner(ctx), PlayModules.playServices(ctx), idCard)

    /** Start fetching the scanner component in the background so the first scan opens without a wait. */
    fun warmUp(ctx: Context) {
        if (kind(ctx, false) != ScannerKind.SMART) return
        val app = ctx.applicationContext
        App.appScope.launch {
            val api = client(Prefs.scannerCleanup(app))
            if (!PlayModules.available(app, api)) withContext(Dispatchers.Main) { PlayModules.install(app, api) }
        }
    }

    /**
     * Copy the scanner's pages into the scan session. They are already cropped and straightened,
     * so they come in whole ([Quad.FULL], crop by hand still works) and with their colours as the
     * scanner left them; "Auto" still knows which look would suit each page.
     */
    suspend fun import(ctx: Context, uris: List<Uri>): Int = withContext(Dispatchers.Default) {
        ScanEngine.ensure()
        var added = 0
        for (uri in uris) {
            val f = File(ScanSession.dir(ctx), "${System.nanoTime()}.jpg")
            val copied = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (!copied || f.length() == 0L) { f.delete(); continue }
            if (uri.scheme == "file") runCatching { uri.path?.let { File(it).delete() } }
            val pick = runCatching {
                ScanEngine.decodeFile(f, 1000)?.let { small -> try { ScanEngine.pickFilter(small) } finally { small.recycle() } }
            }.getOrNull()
            val page = ScanPage(original = f, quad = Quad.FULL, filter = ScanFilter.ORIGINAL, autoPick = pick)
            withContext(Dispatchers.Main) { ScanSession.add(ctx, page) }
            added++
        }
        added
    }
}

/** "3 pages added": shown once by the review screen after the Google scanner hands its pages over. */
object ScanNotice {
    val added = mutableStateOf<Int?>(null)
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Starts a scan from anywhere: asks about an unfinished scan first, then opens Google's scanner or
 * the app's own camera, and falls back to the camera whenever Google's can't be used.
 */
class ScanStarter internal constructor() {
    internal var pendingAsk by mutableStateOf<(() -> Unit)?>(null)
    internal var opening by mutableStateOf(false)
    internal lateinit var ctx: Context
    internal lateinit var scope: CoroutineScope
    internal lateinit var nav: Nav
    internal lateinit var launch: (IntentSenderRequest) -> Unit

    private val unfinished get() = ScanSession.pending.value != null || ScanSession.pages.isNotEmpty()

    /** Run [then] on an empty session. An unfinished scan is never dropped without asking. */
    fun fresh(then: () -> Unit) {
        if (unfinished) pendingAsk = then else { ScanSession.clear(ctx); then() }
    }

    internal fun continuePrevious() {
        pendingAsk = null
        val ok = ScanSession.pages.isNotEmpty() || ScanSession.resume(ctx)
        if (ok) nav.push(Screen.Review) else toast(ctx, "That scan is no longer available")
    }

    internal fun startNew() {
        val then = pendingAsk
        pendingAsk = null
        ScanSession.clear(ctx)
        then?.invoke()
    }

    /** A new scan ([append] = more pages for the scan under review). */
    fun scan(idCard: Boolean = false, append: Boolean = false) {
        if (append) open(ScanSession.idCard.value) else fresh { ScanSession.idCard.value = idCard; open(idCard) }
    }

    private fun classic(why: String? = null) {
        opening = false
        if (why != null) toast(ctx, why)
        if (nav.current != Screen.Camera) nav.push(Screen.Camera)
    }

    private fun open(idCard: Boolean) {
        if (SmartScanner.kind(ctx, idCard) == ScannerKind.CLASSIC) { classic(); return }
        val activity = ctx.findActivity()
        if (activity == null || opening) { if (activity == null) classic(); return }
        opening = true
        scope.launch {
            val started = System.currentTimeMillis()
            val sender = try {
                val client = SmartScanner.client(Prefs.scannerCleanup(ctx))
                if (!PlayModules.available(ctx, client, 2000)) {
                    // Not on the phone yet: let Play services fetch it quietly and scan with the app's
                    // own camera meanwhile, rather than parking the user on a download screen.
                    PlayModules.install(ctx.applicationContext, client)
                    classic("Smart scanner is still being set up on this phone. Using the Wiz scanner for now.")
                    return@launch
                }
                withTimeoutOrNull(12_000) { client.getStartScanIntent(activity).await() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // MlKitException (component unavailable, Play services too old …) or anything else.
                null
            }
            if (sender == null) { classic("Smart scanner isn't available here. Using the Wiz scanner."); return@launch }
            // Let the yellow "Opening scanner" card play before the other screen covers it.
            delay((300 - (System.currentTimeMillis() - started)).coerceIn(0, 300))
            runCatching { launch(IntentSenderRequest.Builder(sender).build()) }
                .onFailure { classic("Smart scanner couldn't start. Using the Wiz scanner.") }
        }
    }

    internal fun onResult(result: ActivityResult) {
        val ok = result.resultCode == Activity.RESULT_OK
        val uris = if (!ok) emptyList() else runCatching {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri }
        }.getOrNull().orEmpty()
        if (ScannerChoice.fallBack(ok, result.resultCode == Activity.RESULT_CANCELED, uris.size)) {
            classic("Smart scanner had a problem. Using the Wiz scanner."); return
        }
        if (uris.isEmpty()) { opening = false; return }
        scope.launch {
            val n = SmartScanner.import(ctx, uris)
            opening = false
            if (n == 0) { toast(ctx, "Couldn't read the scanned pages"); return@launch }
            ScanSession.selected.intValue = (ScanSession.pages.size - n).coerceAtLeast(0)
            ScanNotice.added.value = n
            if (nav.current != Screen.Review) nav.push(Screen.Review)
        }
    }
}

@Composable
fun rememberScanStarter(nav: Nav): ScanStarter {
    val starter = remember { ScanStarter() }
    starter.ctx = LocalContext.current
    starter.scope = rememberCoroutineScope()
    starter.nav = nav
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { starter.onResult(it) }
    starter.launch = { launcher.launch(it) }
    return starter
}

/** The dialogs and the opening card of [starter]; call once from the screen that owns it. */
@Composable
fun ScanStarterUi(starter: ScanStarter) {
    if (starter.pendingAsk != null) {
        val n = ScanSession.pending.value?.pages?.size ?: ScanSession.pages.size
        AlertDialog(
            onDismissRequest = { starter.pendingAsk = null },
            title = { Text("Continue previous scan or start new?") },
            text = {
                Text(
                    "You have an unfinished scan with $n page${if (n == 1) "" else "s"}. Starting a new one deletes it.",
                    color = WizMuted
                )
            },
            confirmButton = { TextButton(onClick = { starter.continuePrevious() }) { Text("Continue", color = WizYellow, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { starter.startNew() }) { Text("Start new", color = WizBad) } },
            containerColor = WizPanel
        )
    }
    if (starter.opening) Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        // Black with one yellow mark: the hand-over to Google's screen looks like part of the app.
        val motion = rememberMotionOn()
        val p = remember { Animatable(if (motion) 0f else 1f) }
        LaunchedEffect(Unit) { p.animateTo(1f, tween(300, easing = Emphasized)) }
        Box(Modifier.fillMaxSize().background(WizBar), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = p.value
                    val s = 0.86f + 0.14f * p.value
                    scaleX = s; scaleY = s
                    translationY = (1f - p.value) * 18.dp.toPx()
                }
            ) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(WizYellow), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.DocumentScanner, null, tint = WizInk, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Opening scanner…", style = MaterialTheme.typography.titleMedium, color = WizYellow)
            }
        }
    }
}
