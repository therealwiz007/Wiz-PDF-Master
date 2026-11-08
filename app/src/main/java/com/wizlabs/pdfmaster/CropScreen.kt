package com.wizlabs.pdfmaster

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
fun CropScreen(nav: Nav, index: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = ScanSession.pages.getOrNull(index)
    if (page == null) { androidx.compose.runtime.LaunchedEffect(Unit) { if (nav.current is Screen.Crop) nav.pop() }; return }
    val bmp by produceState<Bitmap?>(null, page.original) { value = ScanSession.originalPreview(page) }
    var quad by remember(page.id) { mutableStateOf(page.quad) }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    Column(Modifier.fillMaxSize().background(WizBar)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.Outlined.Close, "Cancel") }
            Text("Adjust corners", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(22.dp), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b == null) { CircularProgressIndicator(color = WizYellow); return@BoxWithConstraints }
            val density = LocalDensity.current
            val maxW = with(density) { maxWidth.toPx() }
            val maxH = with(density) { maxHeight.toPx() }
            val scale = min(maxW / b.width, maxH / b.height)
            val dw = b.width * scale
            val dh = b.height * scale
            val img = remember(b) { b.asImageBitmap() }
            val handleR = with(density) { 26.dp.toPx() }
            val loupeR = with(density) { 56.dp.toPx() }

            Canvas(
                Modifier.size(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                    .pointerInput(b) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val pts = quad.points.map { Offset(it.x * dw, it.y * dh) }
                                val nearest = pts.indices.minBy { (pts[it] - start).getDistance() }
                                dragging = if ((pts[nearest] - start).getDistance() < handleR * 2.2f) nearest else -1
                                dragPos = pts.getOrElse(nearest) { start }
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                            onDrag = { change, delta ->
                                if (dragging >= 0) {
                                    change.consume()
                                    dragPos = Offset((dragPos.x + delta.x).coerceIn(0f, dw), (dragPos.y + delta.y).coerceIn(0f, dh))
                                    val candidate = quad.with(dragging, P(dragPos.x / dw, dragPos.y / dh))
                                    if (candidate.isConvex()) quad = candidate
                                }
                            }
                        )
                    }
            ) {
                drawImage(img, dstSize = IntSize(dw.toInt(), dh.toInt()))
                val pts = quad.points.map { Offset(it.x * dw, it.y * dh) }
                val outline = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                // Dim everything outside the selection.
                val dim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addPath(outline)
                }
                drawPath(dim, Color.Black.copy(alpha = .45f))
                drawPath(outline, WizYellow, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
                pts.forEachIndexed { i, p ->
                    drawCircle(WizYellow.copy(alpha = if (i == dragging) .35f else .22f), radius = handleR, center = p)
                    drawCircle(WizYellow, radius = 8.dp.toPx(), center = p)
                    drawCircle(WizInk, radius = 3.dp.toPx(), center = p)
                }

                // Magnifier: shows the pixels under your finger so corners land exactly on the edge.
                if (dragging >= 0) {
                    val p = pts[dragging]
                    val center = Offset(
                        if (p.x < size.width / 2) size.width - loupeR - 8f else loupeR + 8f,
                        loupeR + 8f
                    )
                    val zoom = 3f
                    val circle = Path().apply {
                        addOval(androidx.compose.ui.geometry.Rect(center, loupeR))
                    }
                    clipPath(circle) {
                        drawRect(Color.Black, topLeft = center - Offset(loupeR, loupeR), size = Size(loupeR * 2, loupeR * 2))
                        drawImage(
                            img,
                            dstOffset = IntOffset((center.x - p.x * zoom).toInt(), (center.y - p.y * zoom).toInt()),
                            dstSize = IntSize((dw * zoom).toInt(), (dh * zoom).toInt())
                        )
                    }
                    drawCircle(WizYellow, radius = loupeR, center = center, style = Stroke(3.dp.toPx()))
                    drawLine(WizYellow, center - Offset(10f, 0f), center + Offset(10f, 0f), 2f)
                    drawLine(WizYellow, center - Offset(0f, 10f), center + Offset(0f, 10f), 2f)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            WizChip("Auto detect", false, {
                scope.launch {
                    val found = withContext(Dispatchers.Default) {
                        ScanEngine.decodeFile(page.original, 1400)?.let { src -> ScanEngine.detect(src).also { src.recycle() } }
                    }
                    if (found != null) quad = found else toast(ctx, "No edges found — drag the corners")
                }
            }, icon = Icons.Outlined.AutoFixHigh)
            WizChip("Full page", quad == Quad.FULL, { quad = Quad.FULL }, icon = Icons.Outlined.Fullscreen)
        }
        Button(
            onClick = {
                ScanSession.update(index) { it.copy(quad = quad) }
                nav.pop()
            },
            colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(vertical = 15.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
        ) { Text("Apply crop", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(1.dp))
    }
}

