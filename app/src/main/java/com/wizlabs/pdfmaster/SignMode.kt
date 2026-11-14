@file:OptIn(ExperimentalMaterial3Api::class)

package com.wizlabs.pdfmaster

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One signature placed on a page. Fields are snapshot state read only in draw/gesture code. */
@Stable
class Placement(val id: Long, val sigId: String, val page: Int, val aspect: Float, ink: Int) {
    var cx by mutableFloatStateOf(.5f)
    var cy by mutableFloatStateOf(.5f)
    var w by mutableFloatStateOf(.38f)
    var rot by mutableFloatStateOf(0f)
    var ink by mutableIntStateOf(ink)
    val pop = Animatable(0f)

    fun data() = PlacementData(sigId, page, cx, cy, w, rot, ink, aspect)
}

/** Everything about "sign mode" in one viewer. */
@Stable
class SignSession(val src: PdfSource) {
    var active by mutableStateOf(false)
    val placements = mutableStateListOf<Placement>()
    var selected by mutableStateOf<Placement?>(null)
    /** Display bitmaps (long side ≤ 800), by signature id. */
    val images = mutableStateMapOf<String, ImageBitmap>()
    var sheet by mutableStateOf(false)
    var studio by mutableStateOf(false)
    var busy by mutableStateOf<Pair<Int, Int>?>(null)
    var result by mutableStateOf<PdfStamper.Result?>(null)
    var confirmDiscard by mutableStateOf(false)
    var warnCert by mutableStateOf(false)
    var blocked by mutableStateOf<String?>(null)
    var motion = true
    private var nextId = 1L

    fun enter() { active = true; if (placements.isEmpty()) sheet = true }

    fun exit() {
        active = false; placements.clear(); selected = null; sheet = false; studio = false
        images.clear()
    }

    suspend fun ensureImage(ctx: Context, id: String) {
        if (images.containsKey(id)) return
        val bmp = withContext(Dispatchers.IO) { SignatureStore.decode(ctx, id, 800) } ?: return
        images[id] = bmp.asImageBitmap()
    }

    /** Drop a signature at ([u], [v]) of [page], sized to fit nicely. */
    fun add(scope: CoroutineScope, sig: SavedSignature, page: Int, u: Float, v: Float, ink: Int = sig.ink, like: Placement? = null) {
        val (pw, ph) = src.sizes.getOrElse(page) { 1 to 1 }
        val p = Placement(nextId++, sig.id, page, sig.aspect, ink)
        var w = like?.w ?: .38f
        // Keep the height within 20 % of the page.
        val maxW = .2f * ph / pw.coerceAtLeast(1) * sig.aspect
        if (like == null) w = w.coerceAtMost(maxW).coerceAtLeast(.08f)
        p.w = w; p.cx = u.coerceIn(0f, 1f); p.cy = v.coerceIn(0f, 1f); p.rot = like?.rot ?: 0f
        placements += p
        selected = p
        scope.launch { if (motion) p.pop.animateTo(1f, tween(260)) else p.pop.snapTo(1f) }
    }

    fun remove(scope: CoroutineScope, p: Placement) {
        if (selected === p) selected = null
        scope.launch {
            if (motion) p.pop.animateTo(0f, tween(120))
            placements.remove(p)
        }
    }

    /**
     * Copy [p] onto every other page: same place and the same real size (in points), so pages of
     * a different size still get an identical-looking signature. Pages that already have this
     * signature in the same spot are skipped. Returns how many copies were added.
     */
    fun copyToAllPages(scope: CoroutineScope, p: Placement): Int {
        val srcW = src.sizes.getOrNull(p.page)?.first?.toFloat() ?: return 0
        var added = 0
        for (page in 0 until src.pageCount) {
            if (page == p.page) continue
            val dup = placements.any { q ->
                q.page == page && q.sigId == p.sigId && kotlin.math.abs(q.cx - p.cx) < .02f && kotlin.math.abs(q.cy - p.cy) < .02f
            }
            if (dup) continue
            val pw = src.sizes[page].first.toFloat().coerceAtLeast(1f)
            val c = Placement(nextId++, p.sigId, page, p.aspect, p.ink)
            c.cx = p.cx; c.cy = p.cy; c.rot = p.rot
            c.w = (p.w * srcW / pw).coerceIn(.05f, 1.5f)
            placements += c
            scope.launch { if (motion) c.pop.animateTo(1f, tween(260)) else c.pop.snapTo(1f) }
            added++
        }
        return added
    }

    fun removeSignature(id: String) {
        placements.removeAll { it.sigId == id }
        if (selected?.sigId == id) selected = null
        images.remove(id)
    }
}

private enum class Grab { BODY, HANDLE, DELETE }

/**
 * Draws and edits the placements of one page. Sits inside the page's box, so its coordinates are
 * page pixels whatever the zoom; [scale] is the current zoom, used to keep handles a constant size.
 */
@Composable
fun SignLayer(session: SignSession, page: Int, scale: () -> Float) {
    val tick = rememberTick()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // The resize handle often sits near the screen edge: keep the system back swipe off it.
    // An invisible box follows the handle; moving it (placement only) updates the exclusion.
    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
    val sel = session.selected?.takeIf { session.active && it.page == page }
    if (sel != null) Box(
        Modifier
            .offset {
                val W = boxSize.width.toFloat(); val H = boxSize.height.toFloat()
                val w = sel.w * W; val h = w / sel.aspect
                val r = SignGeometry.rotate(w / 2f, h / 2f, sel.rot)
                val half = 36.dp.toPx() / scale().coerceAtLeast(1f)
                IntOffset((sel.cx * W + r.x - half).roundToInt(), (sel.cy * H + r.y - half).roundToInt())
            }
            .size(72.dp / scale().coerceAtLeast(1f))
            .systemGestureExclusion()
    )
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(session, page) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!session.active) return@awaitEachGesture
                val s = scale().coerceAtLeast(1f)
                val handleR = 22.dp.toPx() / s
                val W = size.width.toFloat(); val H = size.height.toFloat()
                fun geom(p: Placement): FloatArray {
                    val w = p.w * W; val h = w / p.aspect
                    return floatArrayOf(p.cx * W, p.cy * H, w, h)
                }
                fun corner(p: Placement, sx: Float, sy: Float): Offset {
                    val g = geom(p)
                    val r = SignGeometry.rotate(sx * g[2] / 2f, sy * g[3] / 2f, p.rot)
                    return Offset(g[0] + r.x, g[1] + r.y)
                }
                val pos = down.position
                val sel = session.selected?.takeIf { it.page == page }
                var target: Placement? = null
                var grab = Grab.BODY
                if (sel != null) {
                    when {
                        (pos - corner(sel, 1f, 1f)).getDistance() <= handleR -> { target = sel; grab = Grab.HANDLE }
                        (pos - corner(sel, -1f, -1f)).getDistance() <= handleR -> { target = sel; grab = Grab.DELETE }
                    }
                }
                if (target == null) {
                    target = session.placements.lastOrNull { p ->
                        p.page == page && geom(p).let { g -> SignGeometry.hit(pos.x, pos.y, g[0], g[1], g[2], g[3], p.rot, 6.dp.toPx() / s) }
                    }
                }
                val t = target ?: return@awaitEachGesture   // not ours: the document scrolls / taps as usual
                down.consume()
                if (session.selected !== t) { session.selected = t; tick() }
                if (grab == Grab.DELETE) {
                    // Delete on release, if the finger stays on the button.
                    var inside = true
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach { if ((it.position - corner(t, -1f, -1f)).getDistance() > handleR * 1.5f) inside = false; it.consume() }
                    } while (e.changes.any { it.pressed })
                    if (inside) { tick(); session.remove(scope, t) }
                    return@awaitEachGesture
                }
                var startDist = 0f; var startAng = 0f; var startW = t.w; var startRot = t.rot
                if (grab == Grab.HANDLE) {
                    val c = Offset(t.cx * W, t.cy * H)
                    startDist = (pos - c).getDistance().coerceAtLeast(1f)
                    startAng = Math.toDegrees(atan2((pos - c).y, (pos - c).x).toDouble()).toFloat()
                    startW = t.w; startRot = t.rot
                }
                do {
                    val e = awaitPointerEvent()
                    val pressed = e.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        // Pinch on the signature: resize and turn it.
                        t.w = (t.w * e.calculateZoom()).coerceIn(.05f, 1.5f)
                        t.rot = ((t.rot + e.calculateRotation()) % 360f + 360f) % 360f
                    } else if (pressed.size == 1) {
                        val ch = pressed[0]
                        if (grab == Grab.HANDLE) {
                            val c = Offset(t.cx * W, t.cy * H)
                            val v = ch.position - c
                            t.w = (startW * hypot(v.x, v.y) / startDist).coerceIn(.05f, 1.5f)
                            val ang = Math.toDegrees(atan2(v.y, v.x).toDouble()).toFloat()
                            t.rot = ((startRot + ang - startAng) % 360f + 360f) % 360f
                        } else if (ch.previousPressed) {
                            val d = ch.positionChange()
                            t.cx = (t.cx + d.x / W).coerceIn(0f, 1f)
                            t.cy = (t.cy + d.y / H).coerceIn(0f, 1f)
                        }
                    }
                    e.changes.forEach { it.consume() }
                } while (e.changes.any { it.pressed })
                val snapped = SignGeometry.snapRotation(t.rot)
                if (snapped != t.rot && snapped % 90f == 0f) tick()
                t.rot = snapped
            }
        }
    ) {
        val s = scale().coerceAtLeast(1f)
        val list = session.placements
        for (p in list) {
            if (p.page != page) continue
            val img = session.images[p.sigId] ?: continue
            val w = p.w * size.width; val h = w / p.aspect
            val cx = p.cx * size.width; val cy = p.cy * size.height
            val k = p.pop.value
            val pop = .8f + .2f * backOut(k)
            withTransform({
                rotate(p.rot, Offset(cx, cy))
                scale(pop, pop, Offset(cx, cy))
            }) {
                drawImage(
                    img,
                    srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt()),
                    dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                    alpha = k.coerceIn(0f, 1f),
                    colorFilter = if (p.ink == SignatureEngine.ORIGINAL) null else ColorFilter.tint(Color(p.ink), androidx.compose.ui.graphics.BlendMode.SrcIn)
                )
                if (session.active && session.selected === p) {
                    val stroke = 1.5.dp.toPx() / s
                    val pad = 4.dp.toPx() / s
                    drawRoundRect(
                        WizYellow, Offset(cx - w / 2 - pad, cy - h / 2 - pad), Size(w + pad * 2, h + pad * 2),
                        CornerRadius(4.dp.toPx() / s),
                        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx() / s, 5.dp.toPx() / s)))
                    )
                    val r = 11.dp.toPx() / s
                    // Resize / rotate handle (bottom-right).
                    val hc = Offset(cx + w / 2, cy + h / 2)
                    drawCircle(WizYellow, r, hc)
                    drawCircle(WizInk, r * .62f, hc, style = Stroke(stroke * 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * .9f, r * .5f))))
                    // Delete (top-left).
                    val dc = Offset(cx - w / 2, cy - h / 2)
                    drawCircle(WizBad, r, dc)
                    val x = r * .38f
                    drawLine(Color.White, dc + Offset(-x, -x), dc + Offset(x, x), stroke * 1.4f)
                    drawLine(Color.White, dc + Offset(-x, x), dc + Offset(x, -x), stroke * 1.4f)
                }
            }
        }
    }
}
}

@Composable
fun SignTopBar(session: SignSession, visiblePage: Int, onClose: () -> Unit, onDone: () -> Unit) {
    val confirm = rememberConfirm()
    Surface(color = WizBar.copy(alpha = .96f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Stop signing") }
            Column(Modifier.weight(1f)) {
                Text("Place signature", style = MaterialTheme.typography.titleSmall)
                val n = session.placements.size
                Text(
                    "Page ${visiblePage + 1}/${session.src.pageCount} · ${if (n == 0) "none placed" else "$n placed"}",
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
            }
            Button(
                onClick = { confirm(); onDone() },
                enabled = session.placements.isNotEmpty() && session.busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk, disabledContainerColor = WizPanelHi),
                modifier = Modifier.padding(end = 8.dp).pressScale()
            ) { Text("Done", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun SignBottomBar(session: SignSession, onAdd: () -> Unit, onDuplicate: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sel = session.selected
    Surface(color = WizBar.copy(alpha = .96f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (sel != null) {
                InkSwatches(sel.ink, onPick = { ink ->
                    sel.ink = ink
                    SignatureStore.get(sel.sigId)?.let { SignatureStore.update(it.copy(ink = ink)) }
                })
                Spacer(Modifier.height(10.dp))
            } else {
                Text("Tap a signature to move, resize or recolour it", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton(Icons.Outlined.Add, "Add", WizYellow, enabled = true, onClick = onAdd, modifier = Modifier.weight(1f))
                ToolButton(Icons.Outlined.ContentCopy, "Copy", WizText, enabled = sel != null, onClick = onDuplicate, modifier = Modifier.weight(1f))
                ToolButton(
                    Icons.Outlined.LibraryAdd, "All pages", WizText,
                    enabled = sel != null && session.src.pageCount > 1,
                    onClick = {
                        sel?.let {
                            val n = session.copyToAllPages(scope, it)
                            toast(ctx, if (n == 0) "Already on every page" else "Added to ${n} more page${if (n == 1) "" else "s"}")
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                ToolButton(Icons.Outlined.Delete, "Delete", WizBad, enabled = sel != null, onClick = { sel?.let { session.remove(scope, it) } }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Compact icon-over-label button for the signing bar. */
@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tick = rememberTick()
    Surface(
        onClick = { tick(); onClick() }, enabled = enabled,
        shape = RoundedCornerShape(14.dp), color = WizPanel, border = BorderStroke(1.dp, WizLine),
        modifier = modifier.pressScale(.92f)
    ) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (enabled) tint else WizMuted.copy(alpha = .5f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) WizText else WizMuted.copy(alpha = .5f), maxLines = 1)
        }
    }
}

/** Blocking progress while the signed copy is written. */
@Composable
fun SignBusyOverlay(progress: Pair<Int, Int>) {
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)).blockTouches(),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = WizPanel) {
            Column(Modifier.padding(24.dp).width(240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Signing…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (progress.second == 0) "Preparing" else "Page ${progress.first} of ${progress.second}",
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { if (progress.second == 0) 0f else progress.first / progress.second.toFloat() },
                    color = WizYellow, trackColor = WizPanelHi, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SignResultSheet(result: PdfStamper.Result, onOpen: () -> Unit, onShare: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val motion = rememberMotionOn()
    val pop = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .45f, stiffness = 500f)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WizPanel) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.CheckCircle, null, tint = WizGood,
                modifier = Modifier.size(56.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            )
            Spacer(Modifier.height(10.dp))
            Text("Signed copy saved", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${result.doc.name} · ${result.doc.pages} page${if (result.doc.pages == 1) "" else "s"} · ${humanSize(result.doc.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall, color = WizMuted, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text("The original is unchanged.", style = MaterialTheme.typography.bodySmall, color = WizMuted)
            if (result.encrypted) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = WizYellow, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Protected with the same password.", style = MaterialTheme.typography.bodySmall, color = WizMuted)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
                modifier = Modifier.fillMaxWidth().height(50.dp).pressScale()
            ) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open signed copy", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onShare, border = BorderStroke(1.dp, WizLine), modifier = Modifier.weight(1f).pressScale()) {
                    Icon(Icons.Outlined.Share, null, tint = WizText); Spacer(Modifier.width(6.dp)); Text("Share", color = WizText)
                }
                OutlinedButton(onClick = onSave, border = BorderStroke(1.dp, WizLine), modifier = Modifier.weight(1f).pressScale()) {
                    Icon(Icons.Outlined.Save, null, tint = WizText); Spacer(Modifier.width(6.dp)); Text("Save to device", color = WizText, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SignDialogs(session: SignSession, onDiscard: () -> Unit, onSignAnyway: () -> Unit) {
    if (session.confirmDiscard) AlertDialog(
        onDismissRequest = { session.confirmDiscard = false },
        title = { Text("Discard signatures?") },
        text = { Text("The signatures you placed on this document will be removed. Your saved signatures stay.", color = WizMuted) },
        confirmButton = { TextButton(onClick = { session.confirmDiscard = false; onDiscard() }) { Text("Discard", color = WizBad) } },
        dismissButton = { TextButton(onClick = { session.confirmDiscard = false }) { Text("Keep editing", color = WizMuted) } },
        containerColor = WizPanel
    )
    if (session.warnCert) AlertDialog(
        onDismissRequest = { session.warnCert = false },
        title = { Text("Already digitally signed") },
        text = { Text("This PDF has a digital (certificate) signature. Adding your signature makes a new copy, and that digital signature will no longer verify.", color = WizMuted) },
        confirmButton = { TextButton(onClick = { session.warnCert = false; onSignAnyway() }) { Text("Sign anyway", color = WizYellow) } },
        dismissButton = { TextButton(onClick = { session.warnCert = false }) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
    session.blocked?.let { why ->
        AlertDialog(
            onDismissRequest = { session.blocked = null },
            title = { Text("Can't sign this PDF") },
            text = { Text(why, color = WizMuted) },
            confirmButton = { TextButton(onClick = { session.blocked = null }) { Text("OK", color = WizYellow) } },
            containerColor = WizPanel
        )
    }
}

/** Small preview of a saved signature on white. */
@Composable
fun SignatureThumb(sig: SavedSignature, image: ImageBitmap?, selected: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = Color.White,
        border = if (selected) BorderStroke(2.dp, WizYellow) else null,
        modifier = modifier
    ) {
        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
            if (image != null) Image(
                image, sig.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                colorFilter = if (sig.ink == SignatureEngine.ORIGINAL) null else ColorFilter.tint(Color(sig.ink), androidx.compose.ui.graphics.BlendMode.SrcIn)
            )
        }
    }
}
