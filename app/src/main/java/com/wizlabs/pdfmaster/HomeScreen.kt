package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by Library.docs.collectAsState()
    val recents by Library.recents.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var renameDoc by remember { mutableStateOf<Doc?>(null) }
    var deleteDoc by remember { mutableStateOf<Doc?>(null) }
    var exportDoc by remember { mutableStateOf<Doc?>(null) }
    val listState = rememberLazyListState()

    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openExternal(ctx, nav, uri)
    }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val d = exportDoc
        if (uri != null && d != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { copyDocTo(ctx, d, uri) }
                toast(ctx, if (ok) "Saved to device" else "Couldn't save the file")
            }
        }
        exportDoc = null
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            ScanSession.clear(ctx)
            importing = 0 to uris.size
            importImages(ctx, uris) { done -> importing = done to uris.size }
            importing = null
            if (ScanSession.pages.isNotEmpty()) nav.push(Screen.Review) else toast(ctx, "Couldn't read those images")
        }
    }

    val q = query.trim()
    val shownDocs = remember(docs, q) { if (q.isEmpty()) docs else docs.filter { it.name.contains(q, true) } }
    val shownRecents = remember(recents, q) { if (q.isEmpty()) recents else recents.filter { it.name.contains(q, true) } }
    val showFab by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }

    Box(Modifier.fillMaxSize().background(WizBg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 120.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(WizYellow),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.DocumentScanner, null, tint = WizInk, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Wiz PDF Master", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Scan · Read · Search  —  100% offline", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                    }
                    IconButton(onClick = { nav.push(Screen.Settings) }) {
                        Icon(Icons.Outlined.Settings, "Settings", tint = WizText)
                    }
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                HeroScan { ScanSession.clear(ctx); nav.push(Screen.Camera) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionTile(
                        Icons.Outlined.PhotoLibrary, "Import photos", "Gallery → PDF",
                        onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f)
                    )
                    ActionTile(
                        Icons.Outlined.FileOpen, "Open PDF", "From phone storage",
                        onClick = { openPdf.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search documents") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear") }
                    },
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = WizPanel, focusedContainerColor = WizPanel,
                        unfocusedBorderColor = WizLine, focusedBorderColor = WizYellow
                    )
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizChip("My scans  ${docs.size}", tab == 0, { tab = 0 })
                    WizChip("Recent PDFs  ${recents.size}", tab == 1, { tab = 1 })
                }
                Spacer(Modifier.height(6.dp))
            }

            if (tab == 0) {
                if (shownDocs.isEmpty()) item {
                    EmptyState(
                        Icons.Outlined.DocumentScanner,
                        if (q.isEmpty()) "No scans yet" else "Nothing matches “$q”",
                        if (q.isEmpty()) "Tap Scan document to turn paper into a crisp, searchable PDF." else "Try a different name."
                    )
                }
                items(shownDocs, key = { it.id }) { d ->
                    DocRow(
                        d,
                        onOpen = { nav.push(Screen.Viewer(PdfRef.Local(d.id, d.name))) },
                        onShare = { sharePdf(ctx, shareUriFor(ctx, d), d.name) },
                        onRename = { renameDoc = d },
                        onSave = { exportDoc = d; saveTo.launch(safeFileName(d.name) + ".pdf") },
                        onDelete = { deleteDoc = d }
                    )
                }
            } else {
                if (shownRecents.isEmpty()) item {
                    EmptyState(
                        Icons.Outlined.FolderOpen,
                        if (q.isEmpty()) "No recent PDFs" else "Nothing matches “$q”",
                        "PDFs you open from your phone, WhatsApp or e-mail show up here."
                    )
                }
                items(shownRecents, key = { it.uri }) { r ->
                    RecentRow(
                        r,
                        onOpen = {
                            val uri = Uri.parse(r.uri)
                            Library.touchRecent(ctx, uri)
                            nav.push(Screen.Viewer(PdfRef.External(uri, r.name.removeSuffix(".pdf"))))
                        },
                        onRemove = { Library.removeRecent(r.uri) }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showFab,
            enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { ScanSession.clear(ctx); nav.push(Screen.Camera) },
                containerColor = WizYellow, contentColor = WizInk,
                icon = { Icon(Icons.Outlined.DocumentScanner, null) },
                text = { Text("Scan", fontWeight = FontWeight.Bold) }
            )
        }
    }

    importing?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importing photos") },
            text = {
                Column {
                    Text("Finding page edges… $done of $total", color = WizMuted)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, modifier = Modifier.fillMaxWidth(), color = WizYellow)
                }
            },
            containerColor = WizPanel
        )
    }

    renameDoc?.let { d ->
        var name by remember(d.id) { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { renameDoc = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) Library.rename(d.id, name.trim())
                    renameDoc = null
                }) { Text("Save", color = WizYellow) }
            },
            dismissButton = { TextButton(onClick = { renameDoc = null }) { Text("Cancel", color = WizMuted) } },
            containerColor = WizPanel
        )
    }

    deleteDoc?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteDoc = null },
            title = { Text("Delete “${d.name}”?") },
            text = { Text("This removes the PDF from Wiz PDF Master. Copies you shared or saved elsewhere are not affected.", color = WizMuted) },
            confirmButton = {
                TextButton(onClick = { Library.delete(ctx, d.id); deleteDoc = null }) { Text("Delete", color = WizBad) }
            },
            dismissButton = { TextButton(onClick = { deleteDoc = null }) { Text("Cancel", color = WizMuted) } },
            containerColor = WizPanel
        )
    }
}

@Composable
private fun HeroScan(onClick: () -> Unit) {
    val tick = rememberTick()
    Surface(
        onClick = { tick(); onClick() },
        shape = RoundedCornerShape(26.dp),
        color = WizYellow,
        contentColor = WizInk,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scan document", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Auto edge detection, shadow removal and text recognition",
                    style = MaterialTheme.typography.bodyMedium, color = WizInk.copy(alpha = .72f)
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(WizInk),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.DocumentScanner, null, tint = WizYellow, modifier = Modifier.size(34.dp)) }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(WizPanel),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = WizYellow, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = WizMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun DocRow(
    d: Doc,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, d.id, d.modified) {
        value = withContext(Dispatchers.IO) { Library.thumbnail(ctx, d.id) }
    }
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = WizPanel,
        border = BorderStroke(1.dp, WizLine),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(thumb)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${d.pages} page${if (d.pages == 1) "" else "s"} · ${humanSize(d.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dateFmt.format(Date(d.created)), style = MaterialTheme.typography.labelSmall, color = WizMuted)
                    if (d.searchable) {
                        Spacer(Modifier.width(8.dp))
                        Badge("SEARCHABLE")
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = WizMuted) }
                DropdownMenu(menu, { menu = false }, containerColor = WizPanelHi) {
                    MenuItem("Share", Icons.Outlined.Share) { menu = false; onShare() }
                    MenuItem("Save to device", Icons.Outlined.Save) { menu = false; onSave() }
                    MenuItem("Rename", Icons.Outlined.DriveFileRenameOutline) { menu = false; onRename() }
                    MenuItem("Delete", Icons.Outlined.Delete, WizBad) { menu = false; onDelete() }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(r: Recent, onOpen: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, r.uri) {
        value = withContext(Dispatchers.IO) { recentThumb(ctx, r.uri) }
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = WizPanel,
        border = BorderStroke(1.dp, WizLine),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(thumb, Icons.Outlined.PictureAsPdf)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(if (r.sizeBytes > 0) humanSize(r.sizeBytes) else null, "Opened " + dateFmt.format(Date(r.opened))).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
            }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Remove from recent", tint = WizMuted) }
        }
    }
}

@Composable
private fun Thumb(bmp: Bitmap?, fallback: androidx.compose.ui.graphics.vector.ImageVector = Icons.AutoMirrored.Outlined.InsertDriveFile) {
    Box(
        Modifier.size(width = 54.dp, height = 70.dp).clip(RoundedCornerShape(10.dp)).background(if (bmp != null) Color.White else WizPanelHi),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(fallback, null, tint = WizYellow)
    }
}

@Composable
fun Badge(text: String, color: Color = WizYellow) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .14f)).padding(horizontal = 6.dp, vertical = 2.dp)
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = color) }
}

@Composable
fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = WizText, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = tint) },
        leadingIcon = { Icon(icon, null, tint = tint) },
        onClick = onClick
    )
}

/** First-page thumbnail for a PDF from another app, cached on disk. */
private fun recentThumb(ctx: Context, uri: String): Bitmap? = runCatching {
    val f = File(ctx.cacheDir, "rthumbs/${uri.hashCode()}.jpg")
    if (f.exists()) return@runCatching android.graphics.BitmapFactory.decodeFile(f.path)
    ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
        PdfRenderer(pfd).use { r ->
            if (r.pageCount == 0) return@use null
            r.openPage(0).use { p ->
                val w = 220
                val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ScanEngine.saveJpeg(bmp, f, 80)
                bmp
            }
        }
    }
}.getOrNull()

/** Decode, auto-crop and stage gallery images as scan pages. */
suspend fun importImages(ctx: Context, uris: List<Uri>, onProgress: (Int) -> Unit) = withContext(Dispatchers.Default) {
    ScanEngine.ensure()
    val filter = Prefs.defaultFilter(ctx)
    uris.forEachIndexed { i, uri ->
        val bmp = ScanEngine.decodeOriented(ctx, uri)
        if (bmp != null) {
            val f = File(ScanSession.dir(ctx), "${System.nanoTime()}.jpg")
            ScanEngine.saveJpeg(bmp, f)
            val quad = ScanEngine.detect(bmp) ?: Quad.FULL
            bmp.recycle()
            withContext(Dispatchers.Main) { ScanSession.pages.add(ScanPage(original = f, quad = quad, filter = filter)) }
        }
        withContext(Dispatchers.Main) { onProgress(i + 1) }
    }
}

