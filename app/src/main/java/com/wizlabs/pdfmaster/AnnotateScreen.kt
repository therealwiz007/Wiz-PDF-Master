package com.wizlabs.pdfmaster

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class AnnotTool(val label: String) {
    HIGHLIGHT("Highlight"), MARKER("Marker"), PEN("Pen"), NOTE("Note"), ERASER("Eraser")
}

/** One step that undo can take back: a mark that was added, or one that was erased from [index]. */
private class AnnotStep(val mark: Mark, val erased: Boolean, val index: Int)

private val PenColors = intArrayOf(0xFF111114.toInt(), 0xFFD92D20.toInt(), 0xFF1D4ED8.toInt())
private val PenWidths = floatArrayOf(0.004f, 0.009f)
private const val MARKER_WIDTH = 0.028f

@Composable
fun AnnotateScreen(nav: Nav, ref: PdfRef, password: String?, page: Int) {
    PdfGate(nav, ref, password) { src -> AnnotateContent(nav, src, page) }
}

@Composable
private fun AnnotateContent(nav: Nav, src: PdfSource, startPage: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(startPage.coerceIn(0, (src.pageCount - 1).coerceAtLeast(0))) }
    val marks = remember { mutableStateListOf<Mark>() }
    val undo = remember { mutableStateListOf<AnnotStep>() }
    val redo = remember { mutableStateListOf<AnnotStep>() }
    var tool by remember { mutableStateOf(AnnotTool.HIGHLIGHT) }
    var penColor by remember { mutableIntStateOf(PenColors[0]) }
    var penWidth by remember { mutableStateOf(PenWidths[0]) }
    var noteAt by remember { mutableStateOf<Offset?>(null) }       // page fractions, waiting for its text
    var askSave by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun add(m: Mark) { marks += m; undo += AnnotStep(m, false, marks.lastIndex); redo.clear() }
    fun erase(m: Mark) {
        val i = marks.indexOf(m)
        if (i < 0) return
        marks.removeAt(i); undo += AnnotStep(m, true, i); redo.clear()
    }
    fun back() {
        val s = undo.removeLastOrNull() ?: return
        if (s.erased) marks.add(s.index.coerceAtMost(marks.size), s.mark) else marks.remove(s.mark)
        redo += s
        page = s.mark.page
    }
    fun forward() {
        val s = redo.removeLastOrNull() ?: return
        if (s.erased) marks.remove(s.mark) else marks.add(s.index.coerceAtMost(marks.size), s.mark)
        undo += s
        page = s.mark.page
    }

    BackHandler(enabled = marks.isNotEmpty() && !busy) { askLeave = true }

    Column(Modifier.fillMaxSize().background(WizBg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (marks.isEmpty()) nav.pop() else askLeave = true }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Annotate", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (marks.isEmpty()) "Draw on the page" else "${marks.size} mark${if (marks.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
            }
            IconButton(onClick = { back() }, enabled = undo.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
            IconButton(onClick = { forward() }, enabled = redo.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
            TextButton(onClick = { askSave = true }, enabled = marks.isNotEmpty() && !busy) {
                Text("Save", color = if (marks.isNotEmpty()) WizYellow else WizMuted, fontWeight = FontWeight.Bold)
            }
        }

        // ---- The page, fitted to the space, with the drawing layer exactly on top of it
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            val (pw, ph) = src.sizes.getOrElse(page) { 595 to 842 }
            val aspect = if (ph <= 0) 0.707f else pw.toFloat() / ph
            val bmp by produceState<Bitmap?>(src.anyCached(page), page) { value = src.render(page, 1400) ?: src.render(page, 800) }
            Box(Modifier.aspectRatio(aspect).background(Color.White)) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), "Page ${page + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                else CircularProgressIndicator(color = WizYellow, modifier = Modifier.align(Alignment.Center))
                MarkLayer(
                    page, marks, tool, penColor, penWidth,
                    onAdd = { add(it) },
                    onErase = { erase(it) },
                    onNote = { noteAt = it }
                )
            }
        }

        // ---- Page stepper
        if (src.pageCount > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.Outlined.ChevronLeft, "Previous page") }
            Text("${page + 1} / ${src.pageCount}", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { page++ }, enabled = page < src.pageCount - 1) { Icon(Icons.Outlined.ChevronRight, "Next page") }
        }

        // ---- Tools
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnnotTool.entries.forEach { t -> WizChip(t.label, tool == t, { tool = t }) }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tool) {
                AnnotTool.PEN -> {
                    PenColors.forEach { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                .border(2.dp, if (penColor == c) WizYellow else WizLine, CircleShape)
                                .clickable { penColor = c }
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    WizChip("Fine", penWidth == PenWidths[0], { penWidth = PenWidths[0] })
                    WizChip("Bold", penWidth == PenWidths[1], { penWidth = PenWidths[1] })
                }
                AnnotTool.HIGHLIGHT -> Hint("Drag across the text to highlight it")
                AnnotTool.MARKER -> Hint("Draw freely with a see-through marker")
                AnnotTool.NOTE -> Hint("Tap where the note should go")
                AnnotTool.ERASER -> {
                    Hint("Tap a mark to remove it", Modifier.weight(1f))
                    TextButton(onClick = { marks.lastOrNull { it.page == page }?.let { erase(it) } }, enabled = marks.any { it.page == page }) {
                        Text("Remove last", color = WizYellow)
                    }
                }
            }
        }
    }

    noteAt?.let { at ->
        var text by remember(at) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteAt = null },
            title = { Text("Note") },
            text = { OutlinedTextField(text, { text = it.take(400) }, placeholder = { Text("Type your note") }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { if (text.isNotBlank()) add(Mark.Note(page, at.x, at.y, text.trim())); noteAt = null }) {
                    Text("Add", color = WizYellow, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { noteAt = null }) { Text("Cancel", color = WizMuted) } },
            containerColor = WizPanel
        )
    }

    if (askSave) SaveChoiceDialog(
        "Save annotations",
        "Your marks become part of the pages, so they show in every PDF app and in print.",
        canReplace = src.ref is PdfRef.Local,
        onDismiss = { askSave = false }
    ) { replace ->
        askSave = false
        busy = true
        val list = marks.toList()
        scope.launch {
            PdfDecor.save(ctx, nav, src, "annotated", replace, "Annotations saved") { doc, fonts -> PdfDecor.drawMarks(doc, list, fonts) }
            busy = false
        }
    }
    if (busy) BusyDialogPlain("Saving", "Adding your marks…")

    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false },
        title = { Text("Discard your marks?") },
        text = { Text("${marks.size} mark${if (marks.size == 1) "" else "s"} will be lost.", color = WizMuted) },
        confirmButton = { TextButton(onClick = { askLeave = false; nav.pop() }) { Text("Discard", color = WizBad) } },
        dismissButton = { TextButton(onClick = { askLeave = false }) { Text("Keep editing", color = WizYellow) } },
        containerColor = WizPanel
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = WizMuted, modifier = modifier.padding(vertical = 8.dp))
}

/**
 * The drawing layer. Finished strokes keep their Path (built once per size); the stroke under
 * the finger grows one segment at a time in a single reused Path, so a long line costs no more
 * per frame than a short one.
 */
@Composable
private fun MarkLayer(
    page: Int, marks: List<Mark>, tool: AnnotTool, penColor: Int, penWidth: Float,
    onAdd: (Mark) -> Unit, onErase: (Mark) -> Unit, onNote: (Offset) -> Unit
) {
    val live = remember { Path() }
    val livePts = remember { ArrayList<Float>() }
    var liveTick by remember { mutableIntStateOf(0) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }
    var drawing by remember { mutableStateOf(false) }
    val paths = remember { HashMap<Mark.Stroke, Path>() }
    var pathsFor by remember { mutableStateOf(Size.Zero) }
    val notePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111114.toInt() } }

    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(tool, page, penColor, penWidth) {
                when (tool) {
                    AnnotTool.NOTE -> detectTapGestures { o -> onNote(Offset(o.x / size.width, o.y / size.height)) }
                    AnnotTool.ERASER -> detectTapGestures { o ->
                        val x = o.x / size.width; val y = o.y / size.height
                        val tol = 14.dp.toPx() / size.width
                        val aspect = size.width.toFloat() / size.height            // y distances in x units
                        marks.lastOrNull { m ->
                            m.page == page && when (m) {
                                is Mark.Box -> x in (minOf(m.l, m.r) - tol)..(maxOf(m.l, m.r) + tol) && y in (minOf(m.t, m.b) - tol)..(maxOf(m.t, m.b) + tol)
                                is Mark.Stroke -> AnnotGeometry.nearStroke(
                                    FloatArray(m.pts.size) { i -> if (i % 2 == 0) m.pts[i] else m.pts[i] / aspect }, x, y / aspect, tol + m.width / 2
                                )
                                is Mark.Note -> x in m.x..(m.x + 0.5f) && y in (m.y - tol)..(m.y + 0.25f)
                            }
                        }?.let(onErase)
                    }
                    AnnotTool.HIGHLIGHT -> detectDragGestures(
                        onDragStart = { dragStart = it; dragNow = it },
                        onDragCancel = { dragStart = null; dragNow = null },
                        onDragEnd = {
                            val a = dragStart; val b = dragNow
                            dragStart = null; dragNow = null
                            if (a != null && b != null && kotlin.math.abs(b.x - a.x) > 6f) {
                                // A flat drag still makes a band about one text line high.
                                val minH = 9.dp.toPx()
                                var t = minOf(a.y, b.y); var bt = maxOf(a.y, b.y)
                                if (bt - t < minH) { val mid = (t + bt) / 2; t = mid - minH / 2; bt = mid + minH / 2 }
                                onAdd(
                                    Mark.Box(
                                        page, (minOf(a.x, b.x) / size.width).coerceIn(0f, 1f), (t / size.height).coerceIn(0f, 1f),
                                        (maxOf(a.x, b.x) / size.width).coerceIn(0f, 1f), (bt / size.height).coerceIn(0f, 1f)
                                    )
                                )
                            }
                        }
                    ) { change, _ -> change.consume(); dragNow = change.position }
                    AnnotTool.MARKER, AnnotTool.PEN -> detectDragGestures(
                        onDragStart = { o ->
                            live.rewind(); live.moveTo(o.x, o.y)
                            livePts.clear(); livePts += o.x / size.width; livePts += o.y / size.height
                            drawing = true; liveTick++
                        },
                        onDragCancel = { drawing = false; live.rewind(); liveTick++ },
                        onDragEnd = {
                            drawing = false
                            if (livePts.size >= 4) {
                                val marker = tool == AnnotTool.MARKER
                                onAdd(
                                    Mark.Stroke(
                                        page, AnnotGeometry.thin(livePts.toFloatArray(), 0.002f),
                                        if (marker) PdfDecor.HIGHLIGHT else penColor, if (marker) MARKER_WIDTH else penWidth, marker
                                    )
                                )
                            }
                            live.rewind(); liveTick++
                        }
                    ) { change, _ ->
                        change.consume()
                        val p = change.position
                        live.lineTo(p.x, p.y)
                        livePts += (p.x / size.width).coerceIn(0f, 1f); livePts += (p.y / size.height).coerceIn(0f, 1f)
                        liveTick++
                    }
                }
            }
    ) {
        if (pathsFor != size) { paths.clear(); pathsFor = size }
        for (m in marks) {
            if (m.page != page) continue
            when (m) {
                is Mark.Box -> drawRect(
                    Color(PdfDecor.HIGHLIGHT), Offset(m.l * size.width, m.t * size.height),
                    Size((m.r - m.l) * size.width, (m.b - m.t) * size.height), alpha = 0.45f, blendMode = BlendMode.Multiply
                )
                is Mark.Stroke -> {
                    val path = paths.getOrPut(m) {
                        Path().apply {
                            moveTo(m.pts[0] * size.width, m.pts[1] * size.height)
                            for (i in 1 until m.pts.size / 2) lineTo(m.pts[i * 2] * size.width, m.pts[i * 2 + 1] * size.height)
                        }
                    }
                    strokePath(path, m.color, m.width, m.marker)
                }
                is Mark.Note -> drawNote(m, notePaint)
            }
        }
        // Erased strokes must not keep their paths alive.
        if (paths.size > marks.size + 8) paths.keys.retainAll(marks.filterIsInstance<Mark.Stroke>().toSet())
        liveTick                                    // read: redraw as the live stroke grows
        if (drawing) {
            val marker = tool == AnnotTool.MARKER
            strokePath(live, if (marker) PdfDecor.HIGHLIGHT else penColor, if (marker) MARKER_WIDTH else penWidth, marker)
        }
        val a = dragStart; val b = dragNow
        if (a != null && b != null) drawRect(
            Color(PdfDecor.HIGHLIGHT), Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
            Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y).coerceAtLeast(9.dp.toPx())), alpha = 0.45f, blendMode = BlendMode.Multiply
        )
    }
}

private fun DrawScope.strokePath(path: Path, color: Int, width: Float, marker: Boolean) {
    drawPath(
        path, Color(color), alpha = if (marker) 0.45f else 1f,
        style = Stroke((width * size.width).coerceAtLeast(1f), cap = if (marker) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = if (marker) BlendMode.Multiply else BlendMode.SrcOver
    )
}

/** The sticky note as it will be written into the PDF: same wrapping, same size relative to the page. */
private fun DrawScope.drawNote(m: Mark.Note, paint: android.graphics.Paint) {
    val fs = size.width * PdfDecor.NOTE_FONT
    paint.textSize = fs
    val lines = PdfDecor.wrap(m.text)
    val pad = fs * 0.6f; val lead = fs * 1.25f
    val bw = (lines.maxOfOrNull { paint.measureText(it) } ?: 0f) + pad * 2
    val bh = lines.size * lead + pad * 2 - (lead - fs)
    val x = (m.x * size.width).coerceIn(0f, (size.width - bw).coerceAtLeast(0f))
    val y = (m.y * size.height).coerceIn(0f, (size.height - bh).coerceAtLeast(0f))
    drawRect(Color(PdfDecor.NOTE_PAPER), Offset(x, y), Size(bw, bh))
    drawRect(Color(0xFFC9A400), Offset(x, y), Size(bw, bh), style = Stroke(1f))
    drawIntoCanvas { c ->
        lines.forEachIndexed { i, line -> c.nativeCanvas.drawText(line, x + pad, y + pad + fs * 0.8f + i * lead, paint) }
    }
}
