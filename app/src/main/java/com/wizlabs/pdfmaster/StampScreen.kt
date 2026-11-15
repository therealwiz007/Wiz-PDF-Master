package com.wizlabs.pdfmaster

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MarkColors = listOf("Grey" to 0x808080, "Red" to 0xD92D20, "Blue" to 0x1D4ED8, "Black" to 0x111114)

@Composable
fun StampScreen(nav: Nav, ref: PdfRef, password: String?, numbers: Boolean) {
    PdfGate(nav, ref, password) { src -> StampContent(nav, src, numbers) }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = WizPanel, border = BorderStroke(1.dp, WizLine), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun StampContent(nav: Nav, src: PdfSource, startNumbers: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var numbers by remember { mutableStateOf(startNumbers) }
    var busy by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }

    // Watermark
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var picture by remember { mutableStateOf<Bitmap?>(null) }
    var size by remember { mutableFloatStateOf(0.7f) }
    var opacity by remember { mutableFloatStateOf(0.25f) }
    var diagonal by remember { mutableStateOf(true) }
    var color by remember { mutableIntStateOf(MarkColors[0].second) }
    // Page numbers
    var pos by remember { mutableStateOf(NumberPos.BOTTOM_CENTER) }
    var format by remember { mutableStateOf(NumberFormat.PLAIN) }
    var startText by remember { mutableStateOf("1") }
    var fontSize by remember { mutableFloatStateOf(11f) }

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.Default) { ScanEngine.decodeOriented(ctx, uri, 1200) }
            if (bmp == null) toast(ctx, "Couldn't read that picture") else { picture?.recycle(); picture = bmp }
        }
    }
    DisposableEffect(Unit) { onDispose { picture?.recycle() } }

    val start = startText.toIntOrNull()?.coerceIn(0, 99_999) ?: 1
    val angle = if (diagonal) 45f else 0f
    val ready = if (numbers) startText.toIntOrNull() != null else picture != null || pdfSafe(text).isNotBlank() || OcrLangs.scriptOf(text) != Script.LATIN

    Column(Modifier.fillMaxSize().background(WizBg)) {
        ToolTopBar(if (numbers) "Page numbers" else "Watermark", PdfOps.baseName(src.ref), onBack = { nav.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WizChip("Watermark", !numbers, { numbers = false })
                WizChip("Page numbers", numbers, { numbers = true })
            }
            Spacer(Modifier.height(12.dp))

            // ---- Preview on the first page
            val (pw, ph) = src.sizes.firstOrNull() ?: (595 to 842)
            val page by produceState<Bitmap?>(src.anyCached(0)) { value = src.render(0, 700) }
            val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxWidth(0.62f).aspectRatio(if (ph <= 0) 0.707f else pw.toFloat() / ph)
                        .clip(RoundedCornerShape(4.dp)).background(Color.White)
                ) {
                    page?.let { Image(it.asImageBitmap(), "First page", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                    Canvas(Modifier.fillMaxSize()) {
                        val w = this.size.width; val h = this.size.height
                        val k = w / pw.coerceAtLeast(1)                     // screen pixels per PDF point
                        drawIntoCanvas { c ->
                            val n = c.nativeCanvas
                            if (numbers) {
                                paint.color = 0xFF222222.toInt(); paint.alpha = 255; paint.textSize = fontSize * k
                                val label = PageNumbers.label(format, 0, src.pageCount, start)
                                val at = PageNumbers.anchor(pos, pw.toFloat(), ph.toFloat(), paint.measureText(label) / k, fontSize)
                                n.drawText(label, at.x * k, h - at.y * k, paint)
                            } else {
                                val pic = picture
                                n.save()
                                n.rotate(-angle, w / 2, h / 2)
                                if (pic != null && !pic.isRecycled) {
                                    val m = WatermarkGeometry.imageMatrix(w, h, pic.width.toFloat() / pic.height.coerceAtLeast(1), size, 0f)
                                    paint.alpha = (opacity * 255).toInt()
                                    n.drawBitmap(pic, null, android.graphics.RectF(m.e, h - m.f - m.d, m.e + m.a, h - m.f), paint)
                                } else if (text.isNotBlank()) {
                                    paint.textSize = 100f
                                    val unit = paint.measureText(text.trim()) / 100f
                                    val fs = WatermarkGeometry.fontSize(w, h, unit, angle, size)
                                    paint.textSize = fs
                                    paint.color = color or 0xFF000000.toInt(); paint.alpha = (opacity * 255).toInt()
                                    n.drawText(text.trim(), w / 2 - unit * fs / 2, h / 2 + fs * 0.35f, paint)
                                }
                                n.restore()
                            }
                        }
                    }
                }
            }

            if (!numbers) {
                SectionHeader("Mark")
                Panel {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WizChip("Text", picture == null, { picture?.recycle(); picture = null })
                        WizChip(if (picture == null) "Picture…" else "Picture", picture != null, {
                            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                    }
                    if (picture == null) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(text, { text = it.take(60) }, label = { Text("Watermark text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MarkColors.forEach { (name, c) -> WizChip(name, color == c, { color = c }) }
                        }
                    }
                }
                SectionHeader("Look")
                Panel {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WizChip("Small", size == 0.45f, { size = 0.45f })
                        WizChip("Medium", size == 0.7f, { size = 0.7f })
                        WizChip("Large", size == 0.9f, { size = 0.9f })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WizChip("Diagonal 45°", diagonal, { diagonal = true })
                        WizChip("Straight", !diagonal, { diagonal = false })
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Opacity ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        opacity, { opacity = it }, valueRange = 0.05f..0.8f,
                        colors = SliderDefaults.colors(thumbColor = WizYellow, activeTrackColor = WizYellow, inactiveTrackColor = WizPanelHi)
                    )
                }
            } else {
                SectionHeader("Position")
                Panel {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberPos.entries.forEach { p -> WizChip(p.label, pos == p, { pos = p }) }
                    }
                }
                SectionHeader("Format")
                Panel {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberFormat.entries.forEach { f -> WizChip(f.label, format == f, { format = f }) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            startText, { v -> startText = v.filter { it.isDigit() }.take(5) }, label = { Text("Start at") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(120.dp)
                        )
                        WizChip("Small", fontSize == 9f, { fontSize = 9f })
                        WizChip("Medium", fontSize == 11f, { fontSize = 11f })
                        WizChip("Large", fontSize == 14f, { fontSize = 14f })
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { askSave = true }, enabled = ready && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
                shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(8.dp))
                Text(if (numbers) "Add page numbers" else "Add watermark", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Added to all ${src.pageCount} page${if (src.pageCount == 1) "" else "s"}. The text of the PDF stays sharp and searchable.",
                style = MaterialTheme.typography.bodySmall, color = WizMuted
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (askSave) SaveChoiceDialog(
        if (numbers) "Add page numbers" else "Add watermark",
        "Once saved, it is part of the pages and can't be taken off again. Saving a copy keeps your original as it is.",
        canReplace = src.ref is PdfRef.Local,
        onDismiss = { askSave = false }
    ) { replace ->
        askSave = false
        busy = true
        val mark = WatermarkSpec(text, size, opacity, angle, color)
        val nums = NumberSpec(pos, format, start, fontSize)
        val pic = picture
        scope.launch {
            if (numbers) PdfDecor.save(ctx, nav, src, "numbered", replace, "Page numbers added") { doc, _ -> PdfDecor.pageNumbers(doc, nums) }
            else PdfDecor.save(ctx, nav, src, "watermarked", replace, "Watermark added") { doc, fonts -> PdfDecor.watermark(doc, mark, pic, fonts) }
            busy = false
        }
    }
    if (busy) BusyDialogPlain(if (numbers) "Adding page numbers" else "Adding watermark", "Working…")
}
