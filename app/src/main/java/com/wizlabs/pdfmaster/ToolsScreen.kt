package com.wizlabs.pdfmaster

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun externalRef(ctx: Context, uri: Uri): PdfRef.External {
    runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val (name, _) = queryNameSize(ctx, uri)
    return PdfRef.External(uri, name.removeSuffix(".pdf").removeSuffix(".PDF"))
}

@Composable
internal fun ToolTopBar(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = WizMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Hub ----------------------------------------------------------------------------------------

@Composable
fun ToolsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickFor by remember { mutableStateOf<((PdfRef) -> Screen)?>(null) }
    var importing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val starter = rememberScanStarter(nav)
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = 0 to uris.size
            importImages(ctx, uris) { done -> importing = done to uris.size }
            importing = null
            if (ScanSession.pages.isNotEmpty()) nav.push(Screen.Review) else toast(ctx, "Couldn't read those images")
        }
    }

    Column(Modifier.fillMaxSize().background(WizBg)) {
        ToolTopBar("PDF tools", "Everything happens on this phone", onBack = { nav.pop() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SlimTile(Icons.Outlined.GridView, "Organise pages", "Reorder, rotate, delete or extract pages",
                onClick = { pickFor = { Screen.Pages(it) } })
            SlimTile(Icons.AutoMirrored.Outlined.MergeType, "Merge PDFs", "Join several PDFs into one",
                onClick = { nav.push(Screen.Merge) })
            SlimTile(Icons.AutoMirrored.Outlined.CallSplit, "Split PDF", "By page ranges or every few pages",
                onClick = { pickFor = { Screen.Pages(it, split = true) } })
            SlimTile(Icons.Outlined.Compress, "Compress PDF", "Shrink to a size you choose",
                onClick = { pickFor = { Screen.Compress(it) } })
            SlimTile(Icons.Outlined.PhotoLibrary, "Images to PDF", "Pick photos, arrange them, save",
                onClick = { starter.fresh { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } })
            SlimTile(Icons.Outlined.Image, "PDF to JPG", "Save pages as pictures, or share them",
                onClick = { pickFor = { Screen.ToJpg(it) } })
            SlimTile(Icons.Outlined.Lock, "Password", "Lock a PDF, or remove its password",
                onClick = { pickFor = { Screen.Protect(it) } })
            SlimTile(Icons.Outlined.Badge, "Scan ID card", "Front and back on one page, real size",
                onClick = { starter.scan(idCard = true) })
            SlimTile(Icons.Outlined.Edit, "Annotate", "Highlight, draw and add notes",
                onClick = { pickFor = { Screen.Annotate(it) } })
            SlimTile(Icons.Outlined.WaterDrop, "Watermark", "Text or a picture across every page",
                onClick = { pickFor = { Screen.Stamp(it) } })
            SlimTile(Icons.Outlined.FormatListNumbered, "Page numbers", "Position, format and start number",
                onClick = { pickFor = { Screen.Stamp(it, numbers = true) } })
            SlimTile(Icons.Outlined.EditNote, "Fill a form", "Type into PDF form fields",
                onClick = { pickFor = { Screen.Form(it) } })
            SlimTile(Icons.Outlined.QrCodeScanner, "Scan QR or barcode", "Links, Wi-Fi, contacts and more",
                onClick = { nav.push(Screen.Qr) })
        }
    }

    ScanStarterUi(starter)
    pickFor?.let { target ->
        PickDocDialog(onDismiss = { pickFor = null }) { ref -> pickFor = null; nav.push(target(ref)) }
    }
    importing?.let { (done, total) -> BusyDialogPlain("Importing photos", "Finding page edges… $done of $total") }
}

@Composable
internal fun BusyDialogPlain(title: String, text: String) {
    AlertDialog(
        onDismissRequest = {}, confirmButton = {}, title = { Text(title) },
        text = { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = WizYellow, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp)); Text(text, color = WizMuted)
        } },
        containerColor = WizPanel
    )
}

/** Choose one PDF: from My scans, or anywhere on the phone. */
@Composable
private fun PickDocDialog(onDismiss: () -> Unit, onPick: (PdfRef) -> Unit) {
    val ctx = LocalContext.current
    val docs by Library.docs.collectAsState()
    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(externalRef(ctx, uri))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a PDF") },
        text = {
            if (docs.isEmpty()) Text("No scans yet. Browse your phone for a PDF.", color = WizMuted)
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(docs, key = { it.id }) { d ->
                    Surface(onClick = { onPick(PdfRef.Local(d.id, d.name)) }, color = androidx.compose.ui.graphics.Color.Transparent, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 9.dp)) {
                            Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${d.pages} page${if (d.pages == 1) "" else "s"} · ${humanSize(d.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { browse.launch(arrayOf("application/pdf")) }) { Text("Browse phone", color = WizYellow) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
}

// ---- Merge --------------------------------------------------------------------------------------

@Composable
fun MergeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<PdfOps.MergeItem>() }
    var pickLibrary by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf<PdfRef.External?>(null) }     // waiting for its password
    var wrong by remember { mutableStateOf(false) }
    val queue = remember { mutableStateListOf<PdfRef.External>() }
    var askName by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Busy?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    /** Find the page count; a locked file is parked until the user types its password. */
    suspend fun add(ref: PdfRef.External, password: String?): Boolean = try {
        val src = PdfSource.open(ctx, ref, password)
        val n = src.pageCount
        src.close()
        items += PdfOps.MergeItem(ref, ref.title, n, password)
        true
    } catch (e: PasswordRequired) {
        wrong = password != null
        locked = ref
        false
    } catch (e: Exception) {
        toast(ctx, "Couldn't read “${ref.title}”")
        true
    }

    suspend fun drain() {
        while (queue.isNotEmpty() && locked == null) {
            val next = queue.removeAt(0)
            add(next, null)
        }
        PdfSource.clearUnlocked(ctx)
    }

    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { queue += uris.map { externalRef(ctx, it) }; drain() }
    }

    Column(Modifier.fillMaxSize().background(WizBg)) {
        ToolTopBar(
            "Merge PDFs",
            if (items.isEmpty()) "Add two or more PDFs" else "${items.size} PDFs · ${items.sumOf { it.pages }} pages",
            onBack = { nav.pop() }
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WizChip("Add from My scans", false, { pickLibrary = true })
            WizChip("Add from phone", false, { browse.launch(arrayOf("application/pdf")) })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            if (items.isEmpty()) item {
                Text(
                    "The PDFs are joined in the order shown here. Use the arrows to change it.",
                    color = WizMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp)
                )
            }
            itemsIndexed(items, key = { _, it -> it.ref.key + "#" + System.identityHashCode(it) }) { i, item ->
                Surface(
                    shape = RoundedCornerShape(16.dp), color = WizPanel, border = BorderStroke(1.dp, WizLine),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateItem()
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = WizYellow, modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.pages} page${if (item.pages == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                        }
                        IconButton(onClick = { items.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { items.move(i, i + 1) }, enabled = i < items.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down") }
                        IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Outlined.Close, "Remove", tint = WizMuted) }
                    }
                }
            }
        }
        Button(
            onClick = { askName = true }, enabled = items.size >= 2,
            colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
            shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(vertical = 16.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(Icons.AutoMirrored.Outlined.MergeType, null); Spacer(Modifier.width(8.dp))
            Text(if (items.size >= 2) "Merge ${items.size} PDFs" else "Merge", fontWeight = FontWeight.Bold)
        }
    }

    if (pickLibrary) {
        val docs by Library.docs.collectAsState()
        val chosen = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { pickLibrary = false },
            title = { Text("Add from My scans") },
            text = {
                if (docs.isEmpty()) Text("No scans yet.", color = WizMuted)
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(docs, key = { it.id }) { d ->
                        val on = d.id in chosen
                        Surface(onClick = { if (on) chosen -= d.id else chosen += d.id }, color = androidx.compose.ui.graphics.Color.Transparent) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = WizYellow, checkmarkColor = WizInk))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${d.pages} page${if (d.pages == 1) "" else "s"} · ${humanSize(d.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Added in the order they were ticked.
                    chosen.mapNotNull { id -> Library.doc(id) }.forEach { items += PdfOps.MergeItem(PdfRef.Local(it.id, it.name), it.name, it.pages) }
                    pickLibrary = false
                }) { Text("Add", color = WizYellow, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { pickLibrary = false }) { Text("Cancel", color = WizMuted) } },
            containerColor = WizPanel
        )
    }

    locked?.let { ref ->
        PasswordPrompt(ref.title, wrong, onCancel = { locked = null; wrong = false; scope.launch { drain() } }) { pw ->
            locked = null
            scope.launch { if (add(ref, pw)) { wrong = false; drain() } }
        }
    }

    if (askName) {
        var name by remember { mutableStateOf(uniqueDocName(items.first().name, "merged", Library.docs.value.map { it.name }.toSet())) }
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("Merge PDFs") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    askName = false
                    val list = items.toList()
                    busy = Busy("Merging PDFs", 0 to list.size)
                    job = scope.launch {
                        try {
                            val d = PdfOps.merge(ctx, list, name.trim().ifBlank { "Merged PDF" }) { done, total -> busy = Busy("Merging PDFs", done to total) }
                            nav.popTo(Screen.Home)
                            nav.push(Screen.Viewer(PdfRef.Local(d.id, d.name)))
                            toast(ctx, "Merged ${list.size} PDFs")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: PdfOps.OpError) {
                            toast(ctx, e.message ?: "Couldn't merge")
                        } catch (e: Exception) {
                            toast(ctx, "Couldn't merge: ${e.message ?: "unknown error"}")
                        } finally {
                            busy = null
                        }
                    }
                }) { Text("Merge", color = WizYellow, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Cancel", color = WizMuted) } },
            containerColor = WizPanel
        )
    }

    busy?.let { BusyDialog(it) { job?.cancel(); busy = null } }
}

// ---- Compress -----------------------------------------------------------------------------------

@Composable
fun CompressScreen(nav: Nav, ref: PdfRef, password: String?) {
    // The gate checks the password (and asks for it) before any heavy work starts.
    PdfGate(nav, ref, password) { src -> CompressContent(nav, src) }
}

@Composable
private fun CompressContent(nav: Nav, src: PdfSource) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var analysis by remember { mutableStateOf<PdfCompress.Analysis?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var preset by remember { mutableStateOf<PdfCompress.Preset?>(PdfCompress.Preset.MEDIUM) }   // null = custom size
    var sizeText by remember { mutableStateOf("") }
    var inMb by remember { mutableStateOf(false) }
    var estimate by remember { mutableStateOf<PdfCompress.Estimate?>(null) }
    var busy by remember { mutableStateOf<Busy?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<PdfCompress.Outcome?>(null) }
    val baseName = remember(src) {
        when (val r = src.ref) {
            is PdfRef.Local -> Library.doc(r.docId)?.name ?: r.title
            is PdfRef.External -> r.title.removeSuffix(".pdf").removeSuffix(".PDF")
        }
    }
    val target: Long? = if (preset != null) null else sizeText.replace(',', '.').toDoubleOrNull()
        ?.let { (it * if (inMb) 1024 * 1024 else 1024).toLong() }?.takeIf { it > 0 }

    LaunchedEffect(src) {
        try {
            analysis = PdfCompress.analyze(ctx, src.ref, src.password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = (e as? PdfOps.OpError)?.message ?: "This PDF couldn't be analysed."
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            analysis?.dispose()
            outcome?.file?.delete()
        }
    }
    // Size estimate for the current choice, measured on a few sample pictures off the main thread.
    LaunchedEffect(analysis, preset, target) {
        val a = analysis ?: return@LaunchedEffect
        estimate = null
        if (a.imageCount == 0 || (preset == null && target == null)) return@LaunchedEffect
        delay(350)                                     // wait for typing to pause
        estimate = runCatching { preset?.let { PdfCompress.estimate(a, it) } ?: PdfCompress.estimate(a, target!!) }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    Column(Modifier.fillMaxSize().background(WizBg)) {
        ToolTopBar("Compress PDF", baseName, onBack = { nav.pop() })
        val a = analysis
        when {
            failed != null -> Text(failed!!, color = WizMuted, modifier = Modifier.padding(24.dp))
            a == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = WizYellow)
                    Spacer(Modifier.height(12.dp))
                    Text("Looking inside the PDF…", color = WizMuted)
                }
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Surface(shape = RoundedCornerShape(18.dp), color = WizPanel, border = BorderStroke(1.dp, WizLine), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Now", style = MaterialTheme.typography.labelMedium, color = WizMuted)
                        Text(humanSize(a.size.fileBytes), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${a.pages} page${if (a.pages == 1) "" else "s"} · pictures ${humanSize(a.size.imageBytes)} · text and fonts ${humanSize(a.size.overhead)}",
                            style = MaterialTheme.typography.bodySmall, color = WizMuted
                        )
                    }
                }
                if (a.imageCount == 0) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "This PDF has no pictures that can be shrunk. It is mostly text and already compact.",
                        color = WizMuted, style = MaterialTheme.typography.bodyMedium
                    )
                    return@Column
                }
                SectionHeader("Make it")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfCompress.Preset.entries.reversed().forEach { p -> WizChip(p.label, preset == p, { preset = p }) }
                }
                Spacer(Modifier.height(8.dp))
                WizChip("Exact size…", preset == null, { preset = null })
                if (preset == null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            sizeText, { v -> sizeText = v.filter { it.isDigit() || it == '.' || it == ',' }.take(7) },
                            label = { Text("Target size") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        WizChip("KB", !inMb, { inMb = false })
                        WizChip("MB", inMb, { inMb = true })
                    }
                }

                SectionHeader("Estimated result")
                val e = estimate
                Surface(shape = RoundedCornerShape(18.dp), color = WizPanel, border = BorderStroke(1.dp, WizLine), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        when {
                            preset == null && target == null -> Text("Type the size you need, for example 500 KB.", color = WizMuted)
                            e == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = WizYellow, strokeWidth = 3.dp, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp)); Text("Measuring sample pages…", color = WizMuted)
                            }
                            else -> {
                                val total = minOf(e.totalBytes, a.size.fileBytes)
                                Text("About ${humanSize(total)}", style = MaterialTheme.typography.headlineSmall, color = if (e.plan.fits) WizText else WizBad)
                                Text(
                                    "${e.plan.dpi} DPI · picture quality ${e.plan.quality}",
                                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                                )
                                if (e.unreachable) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "That size can't be reached: text and fonts alone take ${humanSize(a.size.overhead)}. This is the smallest we can make it.",
                                        style = MaterialTheme.typography.bodySmall, color = WizBad
                                    )
                                } else if (!e.plan.fits) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Even at the lowest quality this stays above your target. You can still compress and keep the closest result.",
                                        style = MaterialTheme.typography.bodySmall, color = WizBad
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        busy = Busy("Compressing", null, "Preparing…")
                        job = scope.launch {
                            try {
                                outcome = PdfCompress.compress(ctx, a, target, preset) { pass, done, total ->
                                    busy = Busy("Compressing", done to total, (if (pass == 2) "Fine-tuning · " else "") + "picture $done of $total")
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: PdfOps.OpError) {
                                toast(ctx, e.message ?: "Couldn't compress")
                            } catch (e: Exception) {
                                toast(ctx, "Couldn't compress: ${e.message ?: "unknown error"}")
                            } finally {
                                busy = null
                            }
                        }
                    },
                    enabled = estimate != null && busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
                    shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(vertical = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Compress, null); Spacer(Modifier.width(8.dp))
                    Text("Compress", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Saved as a new copy. Text stays sharp and searchable; only pictures are re-encoded.",
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
            }
        }
    }

    busy?.let { BusyDialog(it) { job?.cancel(); busy = null } }

    val a = analysis
    val o = outcome
    if (a != null && o != null) {
        val discard = { o.file.delete(); outcome = null }
        val gained = o.bytes < a.size.fileBytes
        AlertDialog(
            onDismissRequest = discard,
            title = { Text(if (!gained) "Already compact" else if (o.met) "Compressed" else "Closest result") },
            text = {
                Column {
                    if (!gained) Text("This PDF can't be made smaller without losing its text.", color = WizMuted)
                    else {
                        Text("${humanSize(a.size.fileBytes)}  →  ${humanSize(o.bytes)}", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (o.met) "${o.plan.dpi} DPI · picture quality ${o.plan.quality}"
                            else "Your target of ${humanSize(o.targetBytes ?: 0)} couldn't be reached. This is as small as it gets without ruining the pages. Keep it?",
                            color = WizMuted
                        )
                    }
                }
            },
            confirmButton = {
                if (gained) TextButton(onClick = {
                    outcome = null
                    scope.launch {
                        val name = uniqueDocName(baseName, "compressed", Library.docs.value.map { it.name }.toSet())
                        runCatching { PdfCompress.keep(ctx, a, o, name) }
                            .onSuccess { d ->
                                nav.popTo(Screen.Home)
                                nav.push(Screen.Viewer(PdfRef.Local(d.id, d.name)))
                                toast(ctx, "Saved “${d.name}”")
                            }
                            .onFailure { o.file.delete(); toast(ctx, "Couldn't save the compressed copy") }
                    }
                }) { Text(if (o.met) "Save copy" else "Keep it", color = WizYellow, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = discard) { Text(if (gained) "Discard" else "OK", color = WizMuted) } },
            containerColor = WizPanel
        )
    }
}
