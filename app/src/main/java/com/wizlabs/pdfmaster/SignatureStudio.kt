@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.wizlabs.pdfmaster

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Presets = listOf(
    SignatureEngine.BLACK to "Black",
    SignatureEngine.BLUE to "Blue",
    SignatureEngine.RED to "Red",
    SignatureEngine.ORIGINAL to "Original"
)

private fun customInk(hue: Float): Int = Color.hsv(hue.coerceIn(0f, 359.9f), .85f, .55f).toArgb()

/** Ink colour picker: four presets plus a custom hue. */
@Composable
fun InkSwatches(ink: Int, onPick: (Int) -> Unit) {
    val tick = rememberTick()
    val isPreset = Presets.any { it.first == ink }
    var custom by remember { mutableStateOf(!isPreset) }
    var hue by remember { mutableFloatStateOf(if (isPreset) 140f else hueOf(ink)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("INK", style = MaterialTheme.typography.labelMedium, color = WizMuted, modifier = Modifier.width(34.dp))
            Presets.forEach { (c, name) ->
                Swatch(c, name, selected = ink == c && !custom) { tick(); custom = false; onPick(c) }
            }
            Swatch(customInk(hue), "Custom colour", selected = custom, custom = true) {
                tick(); custom = true; onPick(customInk(hue))
            }
        }
        AnimatedVisibility(custom) {
            Slider(
                value = hue, onValueChange = { hue = it; onPick(customInk(it)) }, valueRange = 0f..359f,
                colors = SliderDefaults.colors(thumbColor = Color(customInk(hue)), activeTrackColor = Color(customInk(hue)), inactiveTrackColor = WizPanelHi),
                modifier = Modifier.padding(start = 44.dp)
            )
        }
    }
}

private fun hueOf(argb: Int): Float {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(argb, hsv); return hsv[0]
}

@Composable
private fun Swatch(color: Int, label: String, selected: Boolean, custom: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).pressScale(.88f).clip(CircleShape)
            .background(if (selected) WizYellow else Color.Transparent)
            .padding(3.dp).clip(CircleShape).background(WizBg).padding(2.dp).clip(CircleShape)
            .background(
                when {
                    custom -> Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
                    color == SignatureEngine.ORIGINAL -> Brush.linearGradient(listOf(Color(0xFF1A3FBF), Color(0xFF111111)))
                    else -> Brush.linearGradient(listOf(Color(color), Color(color)))
                }
            )
            .combinedClickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            selected,
            enter = scaleIn(spring(dampingRatio = .4f, stiffness = 600f), initialScale = .2f) + fadeIn(),
            exit = scaleOut(tween(120)) + fadeOut(tween(120))
        ) {
            Icon(if (custom) Icons.Rounded.Palette else Icons.Rounded.Check, label, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/** Picks a saved signature, or starts a new one from a photo / image. */
@Composable
fun SignatureSheet(session: SignSession, onPick: (SavedSignature) -> Unit, onNew: (Uri) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SignatureStore.ensureLoaded(ctx) }
    val sigs by SignatureStore.items.collectAsState()
    var deleting by remember { mutableStateOf<SavedSignature?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onNew(uri) }
    val captureFile = remember { File(ctx.cacheDir, "share/sig_capture.jpg") }
    val captureUri = remember { lazy { captureFile.parentFile?.mkdirs(); FileProvider.getUriForFile(ctx, ctx.packageName + ".files", captureFile) } }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) onNew(captureUri.value) }
    fun launchCamera() = runCatching { takePicture.launch(captureUri.value) }.onFailure { toast(ctx, "No camera app found") }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast(ctx, "Camera permission is needed to take a photo. You can choose an image instead.")
    }
    fun camera() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }
    fun gallery() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WizPanel) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            Text(if (sigs.isEmpty()) "Add your signature" else "My signatures", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (sigs.isEmpty()) "Sign on plain white paper with a dark pen, then photograph it or pick the picture. The paper is removed on your phone."
                else "Tap to place · hold to delete",
                style = MaterialTheme.typography.bodySmall, color = WizMuted
            )
            Spacer(Modifier.height(14.dp))
            if (sigs.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 14.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(sigs, key = { it.id }) { sig ->
                        LaunchedEffect(sig.id) { session.ensureImage(ctx, sig.id) }
                        Box {
                            SignatureThumb(
                                sig, session.images[sig.id], selected = sig.id == sigs.firstOrNull()?.id,
                                modifier = Modifier.fillMaxWidth().height(84.dp).pressScale()
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(onClick = { onPick(sig) }, onLongClick = { menuFor = sig.id })
                            )
                            DropdownMenu(menuFor == sig.id, { menuFor = null }, containerColor = WizPanelHi) {
                                MenuItem("Delete", Icons.Outlined.Delete) { menuFor = null; deleting = sig }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(Icons.Outlined.PhotoCamera, "Take photo", "Of your signature", onClick = { camera() }, modifier = Modifier.weight(1f), primary = sigs.isEmpty())
                ActionTile(Icons.Outlined.PhotoLibrary, "Choose image", "JPG or PNG", onClick = { gallery() }, modifier = Modifier.weight(1f))
            }
        }
    }

    deleting?.let { sig ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete signature?") },
            text = { Text("It will be removed from this phone. Documents you already signed keep it.", color = WizMuted) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    session.removeSignature(sig.id)
                    SignatureStore.delete(ctx, sig.id)
                }) { Text("Delete", color = WizBad) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = WizMuted) } },
            containerColor = WizPanel
        )
    }
}

/**
 * Full-screen editor: shows the photo with the paper removed over a checkerboard, lets the user
 * choose the ink and fine-tune the clean-up, then saves the signature.
 */
@Composable
fun SignatureStudio(uri: Uri, onClose: () -> Unit, onSaved: (SavedSignature) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val motion = rememberMotionOn()
    val confirm = rememberConfirm()
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var cutter by remember { mutableStateOf<SignatureCutter?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var noInk by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableFloatStateOf(.5f) }
    var ink by remember { mutableIntStateOf(SignatureEngine.BLACK) }
    var comparing by remember { mutableStateOf(false) }
    val wipe = remember { Animatable(if (motion) 0f else 1f) }

    BackHandler { if (!saving) onClose() }

    LaunchedEffect(uri) {
        val c = withContext(Dispatchers.Default) {
            if (!ScanEngine.ensure()) return@withContext null
            val bmp = SignatureEngine.prepare(ctx, uri, 640) ?: return@withContext null
            photo = bmp.asImageBitmap()
            runCatching { SignatureCutter(bmp) }.getOrNull()
        }
        if (c == null) { failed = true; return@LaunchedEffect }
        cutter = c
        snapshotFlow { sensitivity }.conflate().collectLatest { s ->
            val out = withContext(Dispatchers.Default) { runCatching { c.cut(s, crop = false) }.getOrNull() }
            noInk = out == null
            preview = out
            if (out != null && wipe.value < 1f) wipe.animateTo(1f, tween(420, easing = Emphasized))
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            cutter?.close()
            File(ctx.cacheDir, "share/sig_capture.jpg").delete()
        }
    }

    fun save() {
        if (saving) return
        saving = true
        confirm()
        scope.launch {
            val sig = withContext(Dispatchers.Default) {
                val big = SignatureEngine.prepare(ctx, uri, 1600) ?: return@withContext null
                val out = runCatching { SignatureCutter(big).use { it.cut(sensitivity, crop = true) } }.getOrNull()
                big.recycle()
                out?.let { b -> SignatureStore.add(ctx, b, ink).also { b.recycle() } }
            }
            saving = false
            if (sig == null) toast(ctx, "Couldn't save the signature. Try another photo.") else onSaved(sig)
        }
    }

    Column(
        Modifier.fillMaxSize().background(WizBar).statusBarsPadding().navigationBarsPadding()
            .blockTouches()
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!saving) onClose() }) { Icon(Icons.Outlined.Close, "Cancel") }
            Text("New signature", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(
                onClick = { save() },
                enabled = preview != null && !noInk && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk, disabledContainerColor = WizPanelHi),
                modifier = Modifier.padding(end = 8.dp).pressScale()
            ) {
                if (saving) CircularProgressIndicator(color = WizInk, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                else Text("Save", fontWeight = FontWeight.SemiBold)
            }
        }

        // Preview: photo underneath, cut-out on a checkerboard wiped in over it.
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(WizPanel),
            contentAlignment = Alignment.Center
        ) {
            val ph = photo
            when {
                failed -> Text("Couldn't read that image.", color = WizMuted)
                ph == null -> CircularProgressIndicator(color = WizYellow)
                else -> Box(Modifier.padding(18.dp).aspectRatio(ph.width / ph.height.toFloat().coerceAtLeast(1f)).clipToBounds()) {
                    Image(ph, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    val pv = preview
                    Box(
                        Modifier.fillMaxSize().drawWithContent {
                            val p = if (comparing) 0f else wipe.value
                            clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
                            if (p in 0.01f..0.99f) drawLine(WizYellow, Offset(size.width * p, 0f), Offset(size.width * p, size.height), 2.dp.toPx())
                        }
                    ) {
                        Checkerboard(Modifier.fillMaxSize())
                        if (pv != null) Image(
                            pv.asImageBitmap(), "Signature preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                            colorFilter = if (ink == SignatureEngine.ORIGINAL) null else ColorFilter.tint(Color(ink), BlendMode.SrcIn)
                        )
                    }
                    if (noInk) Box(Modifier.fillMaxSize().background(Color(0xCC0B0B0D)), contentAlignment = Alignment.Center) {
                        Text(
                            "No signature found.\nTry a darker pen, more light, or slide towards “More ink”.",
                            color = WizText, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp)) {
            Surface(
                shape = RoundedCornerShape(50), color = if (comparing) WizYellow else WizPanelHi,
                contentColor = if (comparing) WizInk else WizText, border = BorderStroke(1.dp, WizLine),
                modifier = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(); comparing = true
                        waitForUpOrCancellation(); comparing = false
                    }
                }
            ) { Text("Hold to compare", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
            Spacer(Modifier.height(14.dp))
            InkSwatches(ink) { ink = it }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CLEAN", style = MaterialTheme.typography.labelMedium, color = WizMuted, modifier = Modifier.width(44.dp))
                Text("Less ink", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                Slider(
                    value = sensitivity, onValueChange = { sensitivity = it }, enabled = cutter != null,
                    colors = SliderDefaults.colors(thumbColor = WizYellow, activeTrackColor = WizYellow, inactiveTrackColor = WizPanelHi),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text("More ink", style = MaterialTheme.typography.bodySmall, color = WizMuted)
            }
            Text(
                "Faint pen? Slide right. Shadows or lines showing? Slide left.",
                style = MaterialTheme.typography.bodySmall, color = WizMuted, modifier = Modifier.padding(start = 44.dp)
            )
        }
    }
}

/** Light two-tone checkerboard (the usual "transparent" backdrop). Draw-only, no allocation per frame. */
@Composable
fun Checkerboard(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color.White)
        val t = 10.dp.toPx()
        val cols = (size.width / t).toInt() + 1
        val rows = (size.height / t).toInt() + 1
        val c = Color(0xFFE4E4E8)
        for (y in 0 until rows) for (x in 0 until cols) {
            if ((x + y) % 2 == 1) drawRect(c, Offset(x * t, y * t), Size(t, t))
        }
    }
}

/** Swallows every touch that reaches it (after the controls inside have had theirs), so nothing
 *  underneath scrolls or zooms. */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        do {
            val e = awaitPointerEvent()
            e.changes.forEach { it.consume() }
        } while (e.changes.any { it.pressed })
    }
}
