package com.wizlabs.pdfmaster

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ReviewScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = ScanSession.pages
    var confirmDiscard by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<PdfBuilder.Progress?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }

    if (pages.isEmpty()) {
        // Only navigate if Review is still on top; after saving we've already moved on to the viewer.
        LaunchedEffect(Unit) { if (nav.current == Screen.Review) nav.popTo(Screen.Home) }
        return
    }

    val pager = rememberPagerState(initialPage = ScanSession.selected.intValue.coerceIn(0, pages.lastIndex)) { pages.size }
    val strip = rememberLazyListState()
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect {
            ScanSession.selected.intValue = it
            strip.animateScrollToItem(it.coerceAtMost(pages.size))
        }
    }
    val current = pager.currentPage.coerceIn(0, pages.lastIndex)

    BackHandler { confirmDiscard = true }

    Column(Modifier.fillMaxSize().background(WizBg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { confirmDiscard = true }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Review", style = MaterialTheme.typography.titleMedium)
                Text("Page ${current + 1} of ${pages.size}", style = MaterialTheme.typography.bodySmall, color = WizMuted)
            }
            TextButton(onClick = { nav.push(Screen.Camera) }) {
                Icon(Icons.Outlined.Add, null, tint = WizYellow); Spacer(Modifier.width(4.dp)); Text("Add page", color = WizYellow)
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 28.dp),
            pageSpacing = 14.dp,
            key = { pages.getOrNull(it)?.id ?: it }
        ) { i ->
            val page = pages.getOrNull(i) ?: return@HorizontalPager
            val bmp by produceState<Bitmap?>(null, page.id, page.version) { value = ScanSession.preview(page) }
            Box(Modifier.fillMaxSize().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b == null) CircularProgressIndicator(color = WizYellow)
                else Image(
                    b.asImageBitmap(), "Page ${i + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.shadow(12.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
                )
            }
        }

        // Filters for the current page
        val page = pages[current]
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScanFilter.entries.forEach { f ->
                WizChip(f.label, page.filter == f, { ScanSession.update(current) { it.copy(filter = f) } })
            }
            TextButton(onClick = {
                val f = page.filter
                pages.indices.forEach { idx -> if (pages[idx].filter != f) ScanSession.update(idx) { it.copy(filter = f) } }
                Prefs.setDefaultFilter(ctx, f)
                toast(ctx, "${f.label} applied to all pages")
            }) { Text("Apply to all", color = WizYellow) }
        }

        // Page tools
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Tool(Icons.Outlined.Crop, "Crop") { nav.push(Screen.Crop(current)) }
            Tool(Icons.Outlined.RotateRight, "Rotate") { ScanSession.update(current) { it.copy(rotation = (it.rotation + 90) % 360) } }
            Tool(Icons.Outlined.ChevronLeft, "Move left", enabled = current > 0) {
                ScanSession.move(current, current - 1); scope.launch { pager.scrollToPage(current - 1) }
            }
            Tool(Icons.Outlined.ChevronRight, "Move right", enabled = current < pages.lastIndex) {
                ScanSession.move(current, current + 1); scope.launch { pager.scrollToPage(current + 1) }
            }
            Tool(Icons.Outlined.Delete, "Delete", tint = WizBad) { ScanSession.remove(ctx, current) }
        }

        // Thumbnails
        LazyRow(
            state = strip,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                val t by produceState<Bitmap?>(null, p.id, p.version) { value = ScanSession.preview(p, 240) }
                Box(
                    Modifier.size(width = 52.dp, height = 68.dp).clip(RoundedCornerShape(8.dp))
                        .background(WizPanelHi)
                        .border(if (i == current) 2.dp else 1.dp, if (i == current) WizYellow else WizLine, RoundedCornerShape(8.dp))
                ) {
                    Surface(onClick = { scope.launch { pager.animateScrollToPage(i) } }, color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
                        t?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop) }
                    }
                    Text(
                        "${i + 1}", style = MaterialTheme.typography.labelSmall, color = WizInk,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(RoundedCornerShape(4.dp))
                            .background(WizYellow).padding(horizontal = 4.dp)
                    )
                }
            }
            item {
                Surface(
                    onClick = { nav.push(Screen.Camera) },
                    shape = RoundedCornerShape(8.dp), color = WizPanel,
                    border = BorderStroke(1.dp, WizLine),
                    modifier = Modifier.size(width = 52.dp, height = 68.dp)
                ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, "Add page", tint = WizYellow) } }
            }
        }

        Button(
            onClick = { showSave = true },
            colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Outlined.PictureAsPdf, null)
            Spacer(Modifier.width(8.dp))
            Text("Save PDF  ·  ${pages.size} page${if (pages.size == 1) "" else "s"}", fontWeight = FontWeight.Bold)
        }
    }

    if (showSave) SaveDialog(
        onDismiss = { showSave = false },
        onSave = { name, ocr, size ->
            showSave = false
            Prefs.setOcrDefault(ctx, ocr); Prefs.setPageSize(ctx, size)
            progress = PdfBuilder.Progress(0, pages.size, "Preparing")
            saveJob = scope.launch {
                val snapshot = pages.toList()
                val result = runCatching {
                    PdfBuilder.build(ctx, snapshot, name, ocr, size) { p -> scope.launch { progress = p } }
                }
                progress = null
                result.onSuccess { doc ->
                    ScanSession.clear(ctx)
                    nav.popTo(Screen.Home)
                    nav.push(Screen.Viewer(PdfRef.Local(doc.id, doc.name)))
                    toast(ctx, if (doc.searchable) "Saved — text is searchable" else "Saved")
                }.onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) toast(ctx, "Couldn't save: ${it.message}")
                }
            }
        }
    )

    progress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Creating PDF") },
            text = {
                Column {
                    Text("${p.stage} · page ${p.page.coerceAtLeast(1)} of ${p.total}", color = WizMuted)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (p.total == 0) 0f else (p.page - 0.5f).coerceAtLeast(0f) / p.total },
                        modifier = Modifier.fillMaxWidth(), color = WizYellow
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { saveJob?.cancel(); progress = null }) { Text("Cancel", color = WizMuted) } },
            containerColor = WizPanel
        )
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard this scan?") },
        text = { Text("${pages.size} page${if (pages.size == 1) "" else "s"} will be lost.", color = WizMuted) },
        confirmButton = {
            TextButton(onClick = { confirmDiscard = false; ScanSession.clear(ctx); nav.popTo(Screen.Home) }) { Text("Discard", color = WizBad) }
        },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing", color = WizYellow) } },
        containerColor = WizPanel
    )
}

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = WizText, onClick: () -> Unit) {
    val tick = rememberTick()
    Surface(
        onClick = { tick(); onClick() }, enabled = enabled,
        shape = RoundedCornerShape(14.dp), color = Color.Transparent,
        contentColor = if (enabled) tint else WizMuted.copy(alpha = .4f)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, Modifier.size(24.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onSave: (String, Boolean, PageSize) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(PdfBuilder.defaultName()) }
    var ocr by remember { mutableStateOf(Prefs.ocrDefault(ctx)) }
    var size by remember { mutableStateOf(Prefs.pageSize(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as PDF") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Make text searchable", style = MaterialTheme.typography.titleSmall)
                        Text("Reads the text on your phone (OCR) so you can search and copy it", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                    }
                    Switch(ocr, { ocr = it }, colors = SwitchDefaults.colors(checkedTrackColor = WizYellow, checkedThumbColor = WizInk))
                }
                Spacer(Modifier.height(14.dp))
                Text("Page size", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageSize.entries.forEach { p -> WizChip(p.label, size == p, { size = p }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim().ifBlank { PdfBuilder.defaultName() }, ocr, size) }) {
                Text("Save", color = WizYellow, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
}
