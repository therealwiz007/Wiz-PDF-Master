package com.wizlabs.pdfmaster

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One page in the grid: which source page it is and how far the user has turned it. */
private data class PageItem(val index: Int, val rot: Int = 0)

private const val THUMB_PX = 220

/**
 * Long-press drag-to-reorder for a lazy grid. The dragged cell follows the finger with a layer
 * translation only (no relayout per frame); the others slide aside via animateItem().
 */
private class GridDrag(private val grid: LazyGridState, private val onMove: (Int, Int) -> Unit) {
    var index by mutableStateOf<Int?>(null)
        private set
    private var dragged by mutableStateOf(Offset.Zero)
    private var startOffset by mutableStateOf(Offset.Zero)
    val scroll = Channel<Float>(Channel.CONFLATED)

    private val info: LazyGridItemInfo? get() = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    /** Where to draw the dragged cell, relative to its slot. */
    val offset: Offset
        get() = info?.let { startOffset + dragged - Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) } ?: Offset.Zero

    private fun LazyGridItemInfo.contains(p: Offset) =
        p.x >= offset.x && p.x <= offset.x + size.width && p.y >= offset.y && p.y <= offset.y + size.height

    fun start(at: Offset) {
        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(at) }?.let {
            index = it.index
            startOffset = Offset(it.offset.x.toFloat(), it.offset.y.toFloat())
            dragged = Offset.Zero
        }
    }

    fun drag(by: Offset) {
        val item = info ?: return
        dragged += by
        val topLeft = startOffset + dragged
        val centre = topLeft + Offset(item.size.width / 2f, item.size.height / 2f)
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index != item.index && it.contains(centre) }
        if (target != null) {
            onMove(item.index, target.index)
            index = target.index
        } else {
            val over = when {
                by.y > 0 -> (topLeft.y + item.size.height - grid.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                by.y < 0 -> (topLeft.y - grid.layoutInfo.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (over != 0f) scroll.trySend(over * 0.35f)
        }
    }

    fun end() { index = null; dragged = Offset.Zero; startOffset = Offset.Zero }
}

@Composable
fun PagesScreen(nav: Nav, ref: PdfRef, password: String?, startSplit: Boolean) {
    PdfGate(nav, ref, password) { src -> PagesContent(nav, src, startSplit) }
}

@Composable
private fun PagesContent(nav: Nav, src: PdfSource, startSplit: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberTick()
    val confirm = rememberConfirm()
    val original = remember(src) { List(src.pageCount) { PageItem(it) } }
    val items = remember(src) { mutableStateListOf<PageItem>().apply { addAll(original) } }
    var selected by remember(src) { mutableStateOf(setOf<Int>()) }      // source page indices
    var showSave by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(startSplit) }
    var confirmLeave by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Busy?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val changed = items.size != original.size || items.indices.any { items[it] != original[it] }
    val baseName = remember(src) {
        when (val r = src.ref) {
            is PdfRef.Local -> Library.doc(r.docId)?.name ?: r.title
            is PdfRef.External -> r.title.removeSuffix(".pdf").removeSuffix(".PDF")
        }
    }
    fun taken() = Library.docs.value.map { it.name }.toSet()

    /** Run a PDF job with a progress dialog; errors become a toast. */
    fun runJob(title: String, block: suspend () -> Unit) {
        busy = Busy(title, null)
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfOps.OpError) {
                toast(ctx, e.message ?: "Something went wrong")
            } catch (e: Exception) {
                toast(ctx, "Couldn't finish: ${e.message ?: "unknown error"}")
            } finally {
                busy = null
            }
        }
    }

    fun ops(of: List<PageItem>) = of.map { PdfOps.PageOp(it.index, it.rot) }

    val gridState = rememberLazyGridState()
    val drag = remember(gridState) { GridDrag(gridState) { from, to -> items.move(from, to) } }
    LaunchedEffect(drag) { for (delta in drag.scroll) gridState.scrollBy(delta) }

    // Unsaved edits: ask before leaving (same pattern as the scan review screen).
    BackHandler(enabled = changed) { confirmLeave = true }

    Column(Modifier.fillMaxSize().background(WizBg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (changed) confirmLeave = true else nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Pages", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (selected.isEmpty()) "${items.size} page${if (items.size == 1) "" else "s"}" else "${selected.size} of ${items.size} selected",
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
            }
            TextButton(onClick = { selected = if (selected.size == items.size) emptySet() else items.map { it.index }.toSet() }) {
                Text(if (selected.size == items.size) "Clear" else "Select all", color = WizYellow)
            }
            IconButton(onClick = { showSplit = true }) { Icon(Icons.AutoMirrored.Outlined.CallSplit, "Split") }
            TextButton(onClick = { showSave = true }, enabled = changed) {
                Text("Save", color = if (changed) WizYellow else WizMuted.copy(alpha = .5f), fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "Tap to select · hold and drag to reorder",
            style = MaterialTheme.typography.bodySmall, color = WizMuted,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .pointerInput(drag) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { drag.start(it); if (drag.index != null) confirm() },
                        onDrag = { change, amount -> change.consume(); drag.drag(amount) },
                        onDragEnd = { drag.end() },
                        onDragCancel = { drag.end() }
                    )
                }
        ) {
            itemsIndexed(items, key = { _, it -> it.index }) { i, item ->
                val dragging = drag.index == i
                val thumb by produceState<Bitmap?>(src.cached(item.index, THUMB_PX), item.index) {
                    value = src.render(item.index, THUMB_PX)
                }
                val turn by animateFloatAsState(item.rot.toFloat(), label = "turn")
                val picked = item.index in selected
                Box(
                    (if (dragging) Modifier.zIndex(1f).graphicsLayer {
                        translationX = drag.offset.x; translationY = drag.offset.y
                        scaleX = 1.06f; scaleY = 1.06f; alpha = .92f
                    } else Modifier.animateItem())
                ) {
                    Surface(
                        onClick = {
                            // A long press that ended without moving is not a tap.
                            if (drag.index == null) { tick(); selected = if (picked) selected - item.index else selected + item.index }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = WizPanel,
                        border = BorderStroke(if (picked) 2.dp else 1.dp, if (picked) WizYellow else WizLine),
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            thumb?.let {
                                // Turning is a layer rotation of the cached thumbnail: nothing is re-rendered.
                                Image(
                                    it.asImageBitmap(), "Page ${i + 1}", contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(.8f).graphicsLayer { rotationZ = turn }
                                )
                            }
                            Text(
                                "${i + 1}", style = MaterialTheme.typography.labelSmall, color = WizInk,
                                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(4.dp))
                                    .background(WizYellow).padding(horizontal = 5.dp)
                            )
                            if (picked) Box(
                                Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(WizYellow),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.Check, null, tint = WizInk, modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(WizBar).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val any = selected.isNotEmpty()
            PageTool(Icons.AutoMirrored.Outlined.RotateRight, "Rotate", any) {
                for (i in items.indices) if (items[i].index in selected) items[i] = items[i].copy(rot = (items[i].rot + 90) % 360)
            }
            PageTool(Icons.Outlined.FileCopy, "Extract", any) {
                val picked = items.filter { it.index in selected }
                runJob("Extracting pages") {
                    val name = uniqueDocName(baseName, pagesLabel(picked.map { it.index }), taken())
                    val d = PdfOps.savePages(ctx, src.ref, src.password, ops(picked), name)
                    selected = emptySet()
                    toast(ctx, "Saved “${d.name}” to My scans")
                }
            }
            PageTool(Icons.Outlined.Delete, "Delete", any, WizBad) {
                if (selected.size >= items.size) toast(ctx, "A PDF needs at least one page")
                else { items.removeAll { it.index in selected }; selected = emptySet() }
            }
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf(uniqueDocName(baseName, "edited", taken())) }
        val local = src.ref as? PdfRef.Local
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save changes") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name of the new copy") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (local != null) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { showSave = false; confirmReplace = true }) { Text("Replace the original instead", color = WizMuted) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSave = false
                    val list = items.toList()
                    runJob("Saving PDF") {
                        val d = PdfOps.savePages(ctx, src.ref, src.password, ops(list), name.trim().ifBlank { uniqueDocName(baseName, "edited", taken()) })
                        nav.popTo(Screen.Home)
                        nav.push(Screen.Viewer(PdfRef.Local(d.id, d.name)))
                        toast(ctx, "Saved as a new copy")
                    }
                }) { Text("Save copy", color = WizYellow, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel", color = WizMuted) } },
            containerColor = WizPanel
        )
    }

    if (confirmReplace) (src.ref as? PdfRef.Local)?.let { local ->
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace “$baseName”?") },
            text = { Text("The original is overwritten. Deleted pages can't be brought back.", color = WizMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    val list = items.toList()
                    runJob("Saving PDF") {
                        val d = PdfOps.savePages(ctx, src.ref, src.password, ops(list), baseName, replaceDocId = local.docId)
                        nav.popTo(Screen.Home)
                        nav.push(Screen.Viewer(PdfRef.Local(d.id, d.name)))
                        toast(ctx, "Saved")
                    }
                }) { Text("Replace", color = WizBad) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel", color = WizMuted) } },
            containerColor = WizPanel
        )
    }

    if (showSplit) SplitDialog(
        pageCount = src.pageCount,
        unsaved = changed,
        onDismiss = { showSplit = false },
        onSplit = { groups ->
            showSplit = false
            runJob("Splitting PDF") {
                val made = PdfOps.split(ctx, src.ref, src.password, groups, baseName) { d, t -> busy = Busy("Splitting PDF", d to t) }
                nav.popTo(Screen.Home)
                toast(ctx, "Created ${made.size} PDF${if (made.size == 1) "" else "s"} in My scans")
            }
        }
    )

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Discard changes?") },
        text = { Text("Your page edits haven't been saved.", color = WizMuted) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; nav.pop() }) { Text("Discard", color = WizBad) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing", color = WizYellow) } },
        containerColor = WizPanel
    )

    busy?.let { BusyDialog(it) { job?.cancel(); busy = null } }
}

/** A long-running job: a title and, when known, (done, total). */
class Busy(val title: String, val progress: Pair<Int, Int>?, val detail: String? = null)

@Composable
fun BusyDialog(b: Busy, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(b.title) },
        text = {
            Column {
                val p = b.progress
                Text(b.detail ?: if (p != null) "${p.first} of ${p.second}" else "Working…", color = WizMuted)
                Spacer(Modifier.height(12.dp))
                if (p != null && p.second > 0) {
                    LinearProgressIndicator(progress = { p.first / p.second.toFloat() }, modifier = Modifier.fillMaxWidth(), color = WizYellow)
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WizYellow)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
}

@Composable
private fun PageTool(icon: ImageVector, label: String, enabled: Boolean, tint: Color = WizText, onClick: () -> Unit) {
    val tick = rememberTick()
    Surface(
        onClick = { tick(); onClick() }, enabled = enabled,
        shape = RoundedCornerShape(14.dp), color = Color.Transparent,
        contentColor = if (enabled) tint else WizMuted.copy(alpha = .4f),
        modifier = Modifier.pressScale(0.9f)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, Modifier.size(24.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SplitDialog(pageCount: Int, unsaved: Boolean, onDismiss: () -> Unit, onSplit: (List<List<Int>>) -> Unit) {
    var byRanges by remember { mutableStateOf(true) }
    var ranges by remember { mutableStateOf("") }
    var every by remember { mutableStateOf("1") }
    var combine by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split PDF") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizChip("Page ranges", byRanges, { byRanges = true; error = null })
                    WizChip("Every N pages", !byRanges, { byRanges = false; error = null })
                }
                Spacer(Modifier.height(12.dp))
                if (byRanges) {
                    OutlinedTextField(
                        ranges, { ranges = it; error = null }, singleLine = true, label = { Text("Pages, e.g. 1-3,5") },
                        isError = error != null, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Put them all in one PDF", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(combine, { combine = it }, colors = SwitchDefaults.colors(checkedTrackColor = WizYellow, checkedThumbColor = WizInk))
                    }
                    if (!combine) Text("Each range becomes its own PDF.", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                } else {
                    OutlinedTextField(
                        every, { every = it.filter(Char::isDigit).take(4); error = null }, singleLine = true,
                        label = { Text("Pages per PDF") }, isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    error ?: ("This PDF has $pageCount page${if (pageCount == 1) "" else "s"}." +
                        if (unsaved) " Unsaved page edits are not included." else ""),
                    style = MaterialTheme.typography.bodySmall, color = if (error != null) WizBad else WizMuted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val groups: List<List<Int>> = if (byRanges) {
                    when (val r = parsePageRanges(ranges, pageCount)) {
                        is RangeResult.Error -> { error = r.message; return@TextButton }
                        is RangeResult.Ok -> if (combine) listOf(r.groups.flatten().distinct()) else r.groups
                    }
                } else {
                    val n = every.toIntOrNull() ?: 0
                    if (n < 1) { error = "Type how many pages each PDF should have"; return@TextButton }
                    if (n >= pageCount) { error = "That would leave the PDF in one piece"; return@TextButton }
                    splitEvery(pageCount, n)
                }
                if (groups.size > MAX_SPLIT_PARTS) { error = "That makes ${groups.size} PDFs; the limit is $MAX_SPLIT_PARTS at a time"; return@TextButton }
                onSplit(groups)
            }) { Text("Split", color = WizYellow, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
}

private const val MAX_SPLIT_PARTS = 100
