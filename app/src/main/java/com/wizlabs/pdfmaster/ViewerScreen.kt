@file:OptIn(ExperimentalMaterial3Api::class)

package com.wizlabs.pdfmaster

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val InvertMatrix = ColorMatrix(
    floatArrayOf(
        -0.9f, 0f, 0f, 0f, 235f,
        0f, -0.9f, 0f, 0f, 235f,
        0f, 0f, -0.9f, 0f, 235f,
        0f, 0f, 0f, 1f, 0f
    )
)

@Composable
fun ViewerScreen(nav: Nav, ref: PdfRef) {
    val ctx = LocalContext.current
    var source by remember(ref) { mutableStateOf<PdfSource?>(null) }
    var error by remember(ref) { mutableStateOf<String?>(null) }
    var needPassword by remember(ref) { mutableStateOf(false) }
    var wrongPassword by remember(ref) { mutableStateOf(false) }
    var password by remember(ref) { mutableStateOf<String?>(null) }

    LaunchedEffect(ref, password) {
        error = null
        runCatching { PdfSource.open(ctx, ref, password) }
            .onSuccess { source = it; needPassword = false }
            .onFailure {
                when (it) {
                    is PasswordRequired -> { wrongPassword = password != null; needPassword = true }
                    else -> error = when {
                        it is SecurityException -> "Permission to read this file has expired. Open it again from your file manager."
                        it is java.io.FileNotFoundException -> "This file is no longer available."
                        else -> "This PDF couldn't be opened. It may be damaged."
                    }
                }
            }
    }
    DisposableEffect(ref) {
        onDispose {
            source?.close()
            PdfSource.clearUnlocked(ctx)
        }
    }

    val src = source
    when {
        src != null -> ViewerContent(nav, src)
        needPassword -> PasswordPrompt(ref.title, wrongPassword, onCancel = { nav.pop() }) { password = it }
        error != null -> Column(
            Modifier.fillMaxSize().background(WizBg).statusBarsPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Can't open “${ref.title}”", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = WizMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(onClick = {
                if (ref is PdfRef.External) Library.removeRecent(ref.uri.toString())
                nav.pop()
            }, colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk)) { Text("Go back") }
        }
        else -> Box(Modifier.fillMaxSize().background(WizBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = WizYellow)
        }
    }
}

@Composable
private fun PasswordPrompt(title: String, wrong: Boolean, onCancel: () -> Unit, onSubmit: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(WizBg))
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Lock, null, tint = WizYellow) },
        title = { Text("Password needed") },
        text = {
            Column {
                Text("“$title” is protected. The password is only used on this phone to unlock the file.", color = WizMuted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pw, { pw = it }, singleLine = true, label = { Text("Password") },
                    isError = wrong, supportingText = { if (wrong) Text("Wrong password, try again") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pw.isNotEmpty()) onSubmit(pw) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (pw.isNotEmpty()) onSubmit(pw) }) { Text("Unlock", color = WizYellow) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
}

@Composable
private fun ViewerContent(nav: Nav, src: PdfSource) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var chrome by remember { mutableStateOf(true) }
    var night by remember { mutableStateOf(Prefs.nightReading(ctx)) }
    var menu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var hitIndex by remember { mutableIntStateOf(0) }
    var indexing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showGrid by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var textSheet by remember { mutableStateOf<TextSheetState?>(null) }
    var visiblePage by remember { mutableIntStateOf(0) }
    val zoom = remember { ZoomState() }

    // Restore the last page you were reading.
    LaunchedEffect(src) {
        val last = Prefs.lastPage(ctx, src.ref.key).coerceIn(0, (src.pageCount - 1).coerceAtLeast(0))
        if (last > 0) listState.scrollToItem(last)
    }
    DisposableEffect(src) { onDispose { Prefs.setLastPage(ctx, src.ref.key, visiblePage) } }

    fun jumpToHit(i: Int) {
        val h = hits.getOrNull(i) ?: return
        hitIndex = i
        scope.launch {
            val itemH = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
            val top = h.rects.minOfOrNull { it.top } ?: 0f
            listState.animateScrollToItem(h.page, ((top * itemH) - 160).roundToInt().coerceAtLeast(0))
        }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.length < 2) { hits = emptyList(); if (q.isNotEmpty()) toast(ctx, "Type at least 2 characters"); return }
        scope.launch {
            if (!src.isIndexed) {
                indexing = 0 to src.pageCount
                val ok = src.buildIndex { d, t -> scope.launch { indexing = d to t } }
                indexing = null
                if (!ok) { toast(ctx, "Couldn't read text from this PDF"); return@launch }
            }
            hits = src.search(q)
            hitIndex = 0
            when {
                hits.isNotEmpty() -> jumpToHit(0)
                src.looksScanned -> toast(ctx, "This PDF is a picture of text. Use “Extract text” to read it with OCR.")
                else -> toast(ctx, "No matches for “$q”")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(if (night) Color(0xFF050505) else Color(0xFF2A2A2E))) {
        ZoomableDocument(
            src = src,
            listState = listState,
            zoom = zoom,
            night = night,
            hits = hits,
            currentHit = hits.getOrNull(hitIndex),
            topPadding = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 70.dp,
            onTap = { chrome = !chrome },
            onVisiblePage = { visiblePage = it }
        )

        // ---- Top bar
        AnimatedVisibility(
            chrome || searching,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            Surface(color = WizBar.copy(alpha = .94f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.statusBarsPadding()) {
                    if (!searching) Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(src.ref.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${src.pageCount} page${if (src.pageCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                        }
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search") }
                        IconButton(onClick = { showGrid = true }) { Icon(Icons.Outlined.GridView, "Pages") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }, containerColor = WizPanelHi) {
                                MenuItem(if (night) "Day reading" else "Night reading", if (night) Icons.Outlined.LightMode else Icons.Outlined.DarkMode) {
                                    menu = false; night = !night; Prefs.setNightReading(ctx, night)
                                }
                                MenuItem("Extract text (this page)", Icons.Outlined.TextSnippet) {
                                    menu = false
                                    textSheet = TextSheetState(visiblePage)
                                }
                                MenuItem("Go to page", Icons.Outlined.ShortText) { menu = false; showGoTo = true }
                                MenuItem("Share", Icons.Outlined.Share) {
                                    menu = false
                                    when (val r = src.ref) {
                                        is PdfRef.Local -> Library.doc(r.docId)?.let { sharePdf(ctx, shareUriFor(ctx, it), it.name) }
                                        is PdfRef.External -> sharePdf(ctx, r.uri, r.title)
                                    }
                                }
                                MenuItem("Add to home screen", Icons.Outlined.AddToHomeScreen) {
                                    menu = false
                                    scope.launch {
                                        val firstPage = src.renderFresh(0, 300)
                                        val ref = when (val r = src.ref) {
                                            is PdfRef.Local -> PdfRef.Local(r.docId, Library.doc(r.docId)?.name ?: r.title)
                                            is PdfRef.External -> r
                                        }
                                        if (!HomeShortcuts.pin(ctx, ref, firstPage)) toast(ctx, "Your home screen app doesn't support shortcuts")
                                    }
                                }
                                MenuItem("Document info", Icons.Outlined.Info) { menu = false; showInfo = true }
                            }
                        }
                    } else SearchBar(
                        query = query,
                        onQuery = { query = it },
                        onSubmit = { runSearch() },
                        count = hits.size,
                        index = hitIndex,
                        indexing = indexing,
                        onPrev = { if (hits.isNotEmpty()) jumpToHit((hitIndex - 1 + hits.size) % hits.size) },
                        onNext = { if (hits.isNotEmpty()) jumpToHit((hitIndex + 1) % hits.size) },
                        onClose = { searching = false; hits = emptyList(); query = "" }
                    )
                    indexing?.let { (d, t) ->
                        LinearProgressIndicator(progress = { if (t == 0) 0f else d / t.toFloat() }, modifier = Modifier.fillMaxWidth(), color = WizYellow)
                    }
                }
            }
        }

        // ---- Bottom: page indicator + seek slider
        AnimatedVisibility(
            chrome && src.pageCount > 1,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            var dragValue by remember { mutableStateOf<Float?>(null) }
            Surface(color = WizBar.copy(alpha = .94f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${((dragValue?.roundToInt() ?: visiblePage) + 1)} / ${src.pageCount}",
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp)
                    )
                    Slider(
                        value = dragValue ?: visiblePage.toFloat(),
                        onValueChange = { v -> dragValue = v; scope.launch { listState.scrollToItem(v.roundToInt()) } },
                        onValueChangeFinished = { dragValue = null },
                        valueRange = 0f..(src.pageCount - 1).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = WizYellow, activeTrackColor = WizYellow, inactiveTrackColor = WizPanelHi),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Floating page pill when the chrome is hidden.
        AnimatedVisibility(
            !chrome, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            enter = fadeIn(), exit = fadeOut()
        ) {
            Surface(shape = RoundedCornerShape(50), color = WizGlass) {
                Text("${visiblePage + 1} / ${src.pageCount}", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }

    if (showGrid) PageGridSheet(src, visiblePage, onDismiss = { showGrid = false }) { p ->
        showGrid = false
        scope.launch { zoom.reset(); listState.scrollToItem(p) }
    }
    if (showGoTo) GoToPageDialog(src.pageCount, onDismiss = { showGoTo = false }) { p ->
        showGoTo = false; scope.launch { listState.scrollToItem(p) }
    }
    if (showInfo) InfoDialog(src) { showInfo = false }
    textSheet?.let { st -> TextSheet(src, st.page) { textSheet = null } }
}

private data class TextSheetState(val page: Int)

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    count: Int,
    index: Int,
    indexing: Pair<Int, Int>?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close search") }
        TextField(
            value = query, onValueChange = onQuery,
            modifier = Modifier.weight(1f).focusRequester(focus),
            singleLine = true,
            placeholder = { Text(if (indexing != null) "Reading text… ${indexing.first}/${indexing.second}" else "Find in document") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = WizYellow, unfocusedIndicatorColor = WizLine, cursorColor = WizYellow
            )
        )
        if (count > 0) Text("${index + 1}/$count", style = MaterialTheme.typography.labelLarge, color = WizMuted, modifier = Modifier.padding(horizontal = 6.dp))
        IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous") }
        IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Outlined.KeyboardArrowDown, "Next") }
    }
}

// ---- Zoom & pan ------------------------------------------------------------------------------

internal class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var width = 1f
    fun clampX(x: Float) = x.coerceIn(width - width * scale, 0f)
    suspend fun reset() { scale = 1f; offsetX = 0f }
}

@Composable
internal fun ZoomableDocument(
    src: PdfSource,
    listState: LazyListState,
    zoom: ZoomState,
    night: Boolean,
    hits: List<Hit>,
    currentHit: Hit?,
    topPadding: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
    onVisiblePage: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val density = LocalDensity.current
    val flingJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        zoom.width = widthPx
        val side = with(density) { 10.dp.toPx() }
        val itemWidthPx = (widthPx - side * 2).coerceAtLeast(1f)

        // Re-render sharper pages once a zoom level settles.
        val wanted = when { zoom.scale < 1.4f -> 1f; zoom.scale < 2.6f -> 2f; else -> 3.2f }
        var bucket by remember { mutableFloatStateOf(1f) }
        LaunchedEffect(wanted) { delay(220); bucket = wanted }

        // Which page sits in the middle of the screen (accounts for zoom).
        val centerPage by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val layoutY = heightPx / 2f / zoom.scale
                info.visibleItemsInfo.firstOrNull { layoutY >= it.offset && layoutY < it.offset + it.size }?.index
                    ?: listState.firstVisibleItemIndex
            }
        }
        LaunchedEffect(centerPage) { onVisiblePage(centerPage) }

        val topPadPx = with(density) { topPadding.toPx() }

        fun applyZoom(newScaleRaw: Float, centroid: Offset, pan: Offset) {
            val newScale = newScaleRaw.coerceIn(1f, 5f)
            val factor = newScale / zoom.scale
            // The top gap (room for the title bar) shrinks with zoom so it never grows on screen;
            // shift the scroll by the same amount so the point under the fingers stays put.
            val padShift = topPadPx / zoom.scale - topPadPx / newScale
            val dy = centroid.y / zoom.scale - centroid.y / newScale - pan.y / newScale - padShift
            zoom.offsetX = (centroid.x - (centroid.x - zoom.offsetX) * factor + pan.x)
            zoom.scale = newScale
            zoom.offsetX = zoom.clampX(zoom.offsetX)
            listState.dispatchRawDelta(dy)
        }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(src) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { pt ->
                            scope.launch {
                                val target = if (zoom.scale > 1.2f) 1f else 2.5f
                                val anim = Animatable(zoom.scale)
                                anim.animateTo(target, tween(260)) { applyZoom(value, pt, Offset.Zero) }
                                if (target == 1f) zoom.offsetX = 0f
                            }
                        }
                    )
                }
                .pointerInput(src) {
                    // One handler owns every touch on the document: one-finger scroll/pan with fling,
                    // and two-finger pinch. The list never scrolls by itself, so a pinch can't
                    // accidentally turn into a scroll or fling (fingers rarely land at the same time).
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        flingJob.value?.cancel()                  // touching stops a running fling
                        val tracker = VelocityTracker()
                        var panning = false
                        var pinched = false
                        var travelled = 0f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                pinched = true
                                panning = false
                                applyZoom(zoom.scale * event.calculateZoom(), event.calculateCentroid(), event.calculatePan())
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1 && !pinched) {
                                val ch = pressed[0]
                                val d = ch.positionChange()
                                if (!panning) {
                                    travelled += d.getDistance()
                                    if (travelled > viewConfiguration.touchSlop) panning = true
                                }
                                if (panning) {
                                    if (zoom.scale > 1.01f) zoom.offsetX = zoom.clampX(zoom.offsetX + d.x)
                                    listState.dispatchRawDelta(-d.y / zoom.scale)
                                    tracker.addPosition(ch.uptimeMillis, ch.position)
                                    ch.consume()
                                }
                            } else if (pinched) {
                                // One finger left after a pinch: ignore it until it lifts (no jump, no fling).
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (panning && !pinched) {
                            val v = tracker.calculateVelocity()
                            flingJob.value = flingList(scope, listState, -v.y / zoom.scale, decay)
                        }
                    }
                }
        ) {
            LazyColumn(
                state = listState,
                userScrollEnabled = false,   // all touch scrolling is handled above
                // Padding is divided by the zoom so the gap above the first page stays the same size on screen.
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = topPadding / zoom.scale, bottom = 120.dp / zoom.scale),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale; scaleY = zoom.scale
                    translationX = zoom.offsetX
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            ) {
                items(src.pageCount, key = { it }) { i ->
                    PageView(
                        src, i, (itemWidthPx * bucket).roundToInt().coerceAtMost(4096), night,
                        hits.filter { it.page == i }, currentHit?.takeIf { it.page == i }
                    )
                }
            }
        }
    }
}

private fun flingList(scope: CoroutineScope, state: LazyListState, velocity: Float, decay: androidx.compose.animation.core.DecayAnimationSpec<Float>): kotlinx.coroutines.Job? {
    if (kotlin.math.abs(velocity) < 50f) return null
    return scope.launch {
        state.scroll {
            var last = 0f
            AnimationState(0f, velocity).animateDecay(decay) {
                val delta = value - last
                val consumed = scrollBy(delta)
                last = value
                if (kotlin.math.abs(delta - consumed) > 0.5f) cancelAnimation()
            }
        }
    }
}

@Composable
private fun PageView(src: PdfSource, index: Int, renderWidth: Int, night: Boolean, hits: List<Hit>, current: Hit?) {
    val (pw, ph) = src.sizes[index]
    val aspect = if (ph <= 0) 0.707f else pw.toFloat() / ph
    val bmp by produceState(initialValue = src.cached(index, renderWidth) ?: src.anyCached(index), index, renderWidth) {
        src.render(index, renderWidth)?.let { value = it }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect)
            .clip(RoundedCornerShape(3.dp))
            .background(if (night) Color(0xFF151515) else Color.White)
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), "Page ${index + 1}",
                contentScale = ContentScale.FillBounds,
                colorFilter = if (night) ColorFilter.colorMatrix(InvertMatrix) else null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                "${index + 1}", Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.headlineSmall, color = Color(0xFFBBBBBB)
            )
        }
        if (hits.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            hits.forEach { h ->
                val active = h === current
                h.rects.forEach { r ->
                    drawRect(
                        if (active) Color(0x99FF8A00) else Color(0x66FFD400),
                        topLeft = Offset(r.left * size.width, r.top * size.height),
                        size = Size(r.width() * size.width, r.height() * size.height)
                    )
                }
            }
        }
    }
}

// ---- Sheets & dialogs -------------------------------------------------------------------------

@Composable
private fun PageGridSheet(src: PdfSource, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WizPanel) {
        Text("Pages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.heightIn(max = 620.dp)
        ) {
            items(src.pageCount) { i ->
                val (pw, ph) = src.sizes[i]
                val t by produceState<Bitmap?>(src.cached(i, 260), i) { value = src.render(i, 260) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = { onPick(i) },
                        shape = RoundedCornerShape(6.dp),
                        color = Color.White,
                        border = if (i == current) androidx.compose.foundation.BorderStroke(3.dp, WizYellow) else null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(if (ph <= 0) .707f else pw.toFloat() / ph)
                    ) { t?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds) } }
                    Spacer(Modifier.height(4.dp))
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (i == current) WizYellow else WizMuted)
                }
            }
        }
    }
}

@Composable
private fun GoToPageDialog(count: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.toIntOrNull()
    val valid = n != null && n in 1..count
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(6) },
                label = { Text("1 – $count") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(n!! - 1) })
            )
        },
        confirmButton = { TextButton(onClick = { if (valid) onGo(n!! - 1) }, enabled = valid) { Text("Go", color = if (valid) WizYellow else WizMuted) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
}

@Composable
private fun InfoDialog(src: PdfSource, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val size = when (val r = src.ref) {
        is PdfRef.Local -> Library.fileOf(ctx, r.docId).length()
        is PdfRef.External -> queryNameSize(ctx, r.uri).second
    }
    val (pw, ph) = src.sizes.firstOrNull() ?: (0 to 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Document info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("Name", src.ref.title)
                InfoRow("Pages", "${src.pageCount}")
                if (size > 0) InfoRow("Size", humanSize(size))
                InfoRow("Page size", "%.0f × %.0f mm".format(pw / 72f * 25.4f, ph / 72f * 25.4f))
                InfoRow("Stored", if (src.ref is PdfRef.Local) "Inside Wiz PDF Master" else "On your phone (opened)")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = WizYellow) } },
        containerColor = WizPanel
    )
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row {
        Text(k, color = WizMuted, modifier = Modifier.width(90.dp))
        Text(v, modifier = Modifier.weight(1f))
    }
}

/** Shows the text of a page: from the PDF's own text layer, or read with OCR when it's a scan. */
@Composable
private fun TextSheet(src: PdfSource, page: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember(page) { mutableStateOf<String?>(null) }
    var viaOcr by remember(page) { mutableStateOf(false) }
    var failed by remember(page) { mutableStateOf(false) }
    LaunchedEffect(page) {
        if (!src.isIndexed) src.buildIndex { _, _ -> }
        val embedded = src.pageText(page)?.trim().orEmpty()
        if (embedded.count { !it.isWhitespace() } > 20) { text = embedded; return@LaunchedEffect }
        viaOcr = true
        val bmp = src.renderFresh(page, 2000)
        if (bmp == null) { failed = true; return@LaunchedEffect }
        val r = runCatching { Ocr.recognize(bmp) }.getOrNull()
        bmp.recycle()
        if (r == null) failed = true else text = r.text.ifBlank { "" }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WizPanel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Text on page ${page + 1}", style = MaterialTheme.typography.titleMedium)
                    Text(if (viaOcr) "Read on your phone with OCR" else "From the PDF's text", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                }
            }
            Spacer(Modifier.height(12.dp))
            val t = text
            when {
                failed -> Text("Couldn't read text from this page.", color = WizMuted)
                t == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = WizYellow, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(if (viaOcr) "Recognising text…" else "Reading…", color = WizMuted)
                }
                t.isBlank() -> Text("No text found on this page.", color = WizMuted)
                else -> {
                    Surface(shape = RoundedCornerShape(14.dp), color = WizBg, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        SelectionContainer {
                            Text(t, Modifier.verticalScroll(rememberScrollState()).padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { copyToClipboard(ctx, t); toast(ctx, "Text copied") },
                            colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
                            modifier = Modifier.weight(1f)
                        ) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy all") }
                        OutlinedButton(onClick = { shareText(ctx, t) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Share, null, tint = WizText); Spacer(Modifier.width(6.dp)); Text("Share", color = WizText)
                        }
                    }
                }
            }
        }
    }
}

fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Text", text))
}

