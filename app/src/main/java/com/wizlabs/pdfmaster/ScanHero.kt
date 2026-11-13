package com.wizlabs.pdfmaster

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val CYCLE_MS = 3600L
private const val FRAME_MS = 33L          // ~30 fps cap
private const val STILL = 0.86f            // frame shown when motion is off: edges locked, page captured

private val PhotoPaper = Color(0xFFE6DFCC)
private val CleanPaper = Color(0xFFFFFFFF)
private val InkLight = Color(0xFFC9C2B0)
private val InkDark = Color(0xFF34343B)

/**
 * Home-screen hero: a page lying slightly askew, a scanning beam sweeps down it turning the faded
 * lines into crisp text, the detected edges snap onto its corners, a shutter flash fires and the
 * page comes out clean and white with a check. Everything is drawn in code from cached geometry;
 * only one float changes per frame, it is read in the draw phase only, and the loop runs at
 * 30 fps while the screen is resumed and focused (a still frame under Battery Saver).
 */
@Composable
fun ScanHero(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val motion = rememberMotionOn()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    val running = motion && focused && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val phase = remember { mutableFloatStateOf(if (motion) 0f else STILL) }

    val touches = MotionIdle.touches
    LaunchedEffect(running, motion, touches) {
        if (!motion) { phase.floatValue = STILL; return@LaunchedEffect }
        if (!running) return@LaunchedEffect          // freeze where it is
        val start = SystemClock.uptimeMillis() - (phase.floatValue * CYCLE_MS).toLong()
        val began = System.nanoTime()
        // Rests on a still frame 9 s after the last touch; the next touch wakes it.
        while (isActive && System.nanoTime() - began < MotionIdle.SETTLE_NS) {
            phase.floatValue = ((SystemClock.uptimeMillis() - start) % CYCLE_MS) / CYCLE_MS.toFloat()
            delay(FRAME_MS)
        }
    }
    // Recomposes only when the stage changes (3 times per loop), not per frame.
    val stage by remember { derivedStateOf { stageOf(phase.floatValue) } }
    val confirm = rememberConfirm()

    Surface(
        onClick = { confirm(); onClick() },
        shape = RoundedCornerShape(26.dp),
        color = WizPanel,
        contentColor = WizText,
        border = BorderStroke(1.dp, WizYellow.copy(alpha = .28f)),
        modifier = modifier.fillMaxWidth().pressScale(0.975f)
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(listOf(WizYellow.copy(alpha = .16f), Color.Transparent, WizYellow.copy(alpha = .05f)))
            )
        ) {
            Row(Modifier.padding(start = 20.dp, end = 10.dp, top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = stage,
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                                (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 })
                        },
                        label = "stage"
                    ) { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (s == 2) WizGood else WizYellow))
                            Spacer(Modifier.width(7.dp))
                            Text(
                                STAGES[s].uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (s == 2) WizGood else WizYellow
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Scan document", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Edges snap in, shadows vanish and the text becomes searchable.",
                        style = MaterialTheme.typography.bodySmall, color = WizMuted
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(WizYellow).padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Start scanning", color = WizInk, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = WizInk, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(width = 124.dp, height = 156.dp).scanArt { phase.floatValue })
            }
        }
    }
}

private val STAGES = arrayOf("Finding edges", "Edges locked", "Captured")

private fun stageOf(p: Float) = when {
    p < 0.56f -> 0
    p < 0.73f -> 1
    else -> 2
}

private val LINE_WIDTHS = floatArrayOf(.86f, .74f, .9f, .66f, .82f, .58f, .78f, .46f)

/** The scanning illustration. [phase] is read inside the draw lambda only. */
private fun Modifier.scanArt(phase: () -> Float): Modifier = drawWithCache {
    val d = density
    var pw = size.width * 0.74f
    var ph = pw * 1.32f
    if (ph > size.height * 0.84f) { ph = size.height * 0.84f; pw = ph / 1.32f }
    val page = Rect(Offset((size.width - pw) / 2f, (size.height - ph) / 2f), Size(pw, ph))
    val corner = CornerRadius(5f * d)
    val glow = Brush.radialGradient(listOf(WizYellow.copy(alpha = .22f), Color.Transparent), center = page.center, radius = size.minDimension * 0.5f)
    val trail = 30f * d
    val beamBrush = Brush.verticalGradient(listOf(Color.Transparent, WizYellow.copy(alpha = .38f)), startY = 0f, endY = trail)
    val padX = pw * 0.12f
    val lineTop = page.top + ph * 0.30f
    val lineGap = (ph * 0.60f) / (LINE_WIDTHS.size - 1)
    val lineStroke = 3.2f * d
    val headStroke = 6f * d
    val bracketLen = pw * 0.2f
    val bracketStroke = Stroke(3.2f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val outline = Stroke(1.6f * d)
    val check = Path().apply {
        moveTo(-4.5f * d, 0.2f * d); lineTo(-1.3f * d, 3.4f * d); lineTo(5f * d, -3.4f * d)
    }
    val checkStroke = Stroke(2.4f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val shadowOffset = Offset(3f * d, 5f * d)

    onDrawBehind {
        val p = phase()
        // Fade-out at the end of the loop so it restarts seamlessly.
        val k = if (p > 0.94f) 1f - (p - 0.94f) / 0.06f else 1f
        val beamT = ((p - 0.05f) / 0.50f).coerceIn(0f, 1f)
        val beamY = page.top + beamT * ph
        val swept = if (p >= 0.55f) 1f else 0f
        val snapT = ((p - 0.55f) / 0.14f).coerceIn(0f, 1f)
        val snap = backOut(snapT)
        val flash = when {
            p < 0.72f || p > 0.82f -> 0f
            p < 0.735f -> (p - 0.72f) / 0.015f
            else -> 1f - (p - 0.735f) / 0.085f
        }
        val clean = (((p - 0.73f) / 0.06f).coerceIn(0f, 1f)) * k

        drawRect(glow)
        rotate(-6f, page.center) {
            // Page with soft shadow; turns from photographed paper into a clean white page.
            drawRoundRect(Color.Black.copy(alpha = .35f), page.topLeft + shadowOffset, page.size, corner)
            drawRoundRect(lerp(PhotoPaper, CleanPaper, clean), page.topLeft, page.size, corner)

            // Text lines: faded below the beam, crisp ink above it (and everywhere once captured).
            val cut = if (swept > 0f) page.bottom else beamY
            clipRect(page.left, page.top, page.right, cut) { textLines(page, padX, lineTop, lineGap, headStroke, lineStroke, lerp(InkLight, InkDark, k)) }
            if (cut < page.bottom) clipRect(page.left, cut, page.right, page.bottom) { textLines(page, padX, lineTop, lineGap, headStroke, lineStroke, InkLight) }

            // Scanning beam.
            if (p in 0.04f..0.57f) {
                val a = when {
                    p < 0.08f -> (p - 0.04f) / 0.04f
                    p > 0.53f -> (0.57f - p) / 0.04f
                    else -> 1f
                }.coerceIn(0f, 1f)
                clipRect(page.left - 6f * d, page.top, page.right + 6f * d, page.bottom) {
                    translate(page.left - 6f * d, beamY - trail) {
                        drawRect(beamBrush, size = Size(pw + 12f * d, trail), alpha = a)
                    }
                    drawLine(WizYellow.copy(alpha = a), Offset(page.left - 6f * d, beamY), Offset(page.right + 6f * d, beamY), 2.4f * d, StrokeCap.Round)
                }
            }

            // Detected edges: outline + corner brackets snapping in from outside.
            if (snapT > 0f && k > 0f) {
                val a = (snapT * 3f).coerceAtMost(1f) * k
                drawRoundRect(WizYellow.copy(alpha = .10f * a), page.topLeft, page.size, corner)
                drawRoundRect(WizYellow.copy(alpha = .9f * a), page.topLeft, page.size, corner, style = outline)
                val off = (1f - snap) * 20f * d
                brackets(page, off, bracketLen, WizYellow.copy(alpha = a), bracketStroke)
            }

            // Check badge pops in after the capture.
            val popT = ((p - 0.76f) / 0.10f).coerceIn(0f, 1f)
            if (popT > 0f && k > 0f) {
                val r = 12f * d * backOut(popT)
                val c = Offset(page.right - 2f * d, page.top + 2f * d)
                drawCircle(WizBg.copy(alpha = k), r + 2.5f * d, c)
                drawCircle(WizGood.copy(alpha = k), r, c)
                translate(c.x, c.y) { drawPath(check, WizInk.copy(alpha = k), style = checkStroke) }
            }
        }
        if (flash > 0f) drawRect(Color.White.copy(alpha = .8f * flash))
    }
}

private fun DrawScope.textLines(page: Rect, padX: Float, top: Float, gap: Float, head: Float, line: Float, color: Color) {
    val y0 = page.top + page.height * 0.15f
    drawLine(color, Offset(page.left + padX, y0), Offset(page.left + padX + page.width * 0.48f, y0), head, StrokeCap.Round)
    for (i in LINE_WIDTHS.indices) {
        val y = top + i * gap
        drawLine(color, Offset(page.left + padX, y), Offset(page.left + padX + (page.width - 2 * padX) * LINE_WIDTHS[i], y), line, StrokeCap.Round)
    }
}

private fun DrawScope.brackets(r: Rect, off: Float, len: Float, color: Color, stroke: Stroke) {
    val w = stroke.width
    // top-left
    drawLine(color, Offset(r.left - off, r.top - off + len), Offset(r.left - off, r.top - off), w, StrokeCap.Round)
    drawLine(color, Offset(r.left - off, r.top - off), Offset(r.left - off + len, r.top - off), w, StrokeCap.Round)
    // top-right
    drawLine(color, Offset(r.right + off - len, r.top - off), Offset(r.right + off, r.top - off), w, StrokeCap.Round)
    drawLine(color, Offset(r.right + off, r.top - off), Offset(r.right + off, r.top - off + len), w, StrokeCap.Round)
    // bottom-right
    drawLine(color, Offset(r.right + off, r.bottom + off - len), Offset(r.right + off, r.bottom + off), w, StrokeCap.Round)
    drawLine(color, Offset(r.right + off, r.bottom + off), Offset(r.right + off - len, r.bottom + off), w, StrokeCap.Round)
    // bottom-left
    drawLine(color, Offset(r.left - off + len, r.bottom + off), Offset(r.left - off, r.bottom + off), w, StrokeCap.Round)
    drawLine(color, Offset(r.left - off, r.bottom + off), Offset(r.left - off, r.bottom + off - len), w, StrokeCap.Round)
}

/** Static empty-state art: a small stack of pages with locked yellow corners. Drawn once. */
fun Modifier.emptyPagesArt(): Modifier = drawWithCache {
    val d = density
    val pw = size.width * 0.52f
    val ph = pw * 1.3f
    val c = Offset(size.width / 2f, size.height / 2f)
    val page = Rect(Offset(c.x - pw / 2f, c.y - ph / 2f), Size(pw, ph))
    val corner = CornerRadius(4f * d)
    val stroke = Stroke(2.6f * d, cap = StrokeCap.Round)
    val glow = Brush.radialGradient(listOf(WizYellow.copy(alpha = .16f), Color.Transparent), center = c, radius = size.minDimension * 0.5f)
    onDrawBehind {
        drawRect(glow)
        rotate(10f, c) { drawRoundRect(WizPanelHi, page.topLeft, page.size, corner) }
        rotate(-5f, c) {
            drawRoundRect(Color(0xFF2C2C33), page.topLeft, page.size, corner)
            for (i in 0 until 4) {
                val y = page.top + ph * (0.25f + i * 0.17f)
                drawLine(WizMuted.copy(alpha = .5f), Offset(page.left + pw * .16f, y), Offset(page.left + pw * (if (i == 3) .55f else .84f), y), 2.6f * d, StrokeCap.Round)
            }
            brackets(page, 5f * d, pw * 0.22f, WizYellow, stroke)
        }
    }
}
