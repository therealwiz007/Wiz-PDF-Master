package com.wizlabs.pdfmaster

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

// ---- Pages as pictures --------------------------------------------------------------------------

/** Turns PDF pages into JPEG pictures: into the phone's gallery, or into a share sheet. */
object ImageExport {
    const val FOLDER = "Wiz PDF Master"

    /** Saving straight into the gallery needs no permission from Android 10 on. */
    val canSaveToGallery get() = Build.VERSION.SDK_INT >= 29

    /** [page] at [dpi]; steps down to a smaller picture when memory is short. The caller recycles it. */
    suspend fun render(src: PdfSource, page: Int, dpi: Int): Bitmap? {
        val (wPt, hPt) = src.sizes.getOrNull(page) ?: return null
        var width = exportWidthPx(wPt, hPt, dpi)
        repeat(3) {
            src.renderFresh(page, width)?.let { return it }
            width = width * 2 / 3
        }
        return null
    }

    private fun shareDir(ctx: Context): File {
        val root = File(ctx.cacheDir, "share")
        // Pictures shared more than an hour ago are no longer needed by the receiving app.
        root.listFiles()?.filter { it.isDirectory && System.currentTimeMillis() - it.lastModified() > 3_600_000 }
            ?.forEach { it.deleteRecursively() }
        return File(root, "img-${System.nanoTime()}").apply { mkdirs() }
    }

    private fun writeShareable(ctx: Context, dir: File, bmp: Bitmap, name: String, quality: Int): Uri {
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    private fun writeToGallery(ctx: Context, bmp: Bitmap, name: String, quality: Int): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        try {
            (resolver.openOutputStream(uri) ?: throw java.io.IOException("no stream")).use {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw java.io.IOException("encode failed")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }

    /** One page as a picture that can be handed to another app. */
    suspend fun shareable(ctx: Context, src: PdfSource, page: Int, dpi: Int, quality: Int): Uri? {
        val bmp = render(src, page, dpi) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                runCatching { writeShareable(ctx, shareDir(ctx), bmp, pageImageName(src.ref.title, page, src.pageCount), quality) }.getOrNull()
            } finally {
                bmp.recycle()
            }
        }
    }

    /**
     * Export [pages] (zero-based) one after the other, so only one page is in memory at a time.
     * Returns the pictures that were written; [onProgress] gets (done, total).
     */
    suspend fun export(
        ctx: Context, src: PdfSource, pages: List<Int>, dpi: Int, quality: Int, toGallery: Boolean,
        onProgress: (Int, Int) -> Unit
    ): List<Uri> {
        val out = ArrayList<Uri>()
        val dir = if (toGallery) null else withContext(Dispatchers.IO) { shareDir(ctx) }
        pages.forEachIndexed { i, page ->
            coroutineContext.ensureActive()
            onProgress(i, pages.size)
            val bmp = render(src, page, dpi) ?: return@forEachIndexed
            val name = pageImageName(src.ref.title, page, src.pageCount)
            withContext(Dispatchers.IO) {
                try {
                    val uri = if (dir == null) writeToGallery(ctx, bmp, name, quality)
                    else runCatching { writeShareable(ctx, dir, bmp, name, quality) }.getOrNull()
                    if (uri != null) out += uri
                } finally {
                    bmp.recycle()
                }
            }
        }
        onProgress(pages.size, pages.size)
        return out
    }
}

fun shareImages(ctx: Context, uris: List<Uri>, title: String) {
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    send.type = "image/jpeg"
    send.putExtra(Intent.EXTRA_SUBJECT, title)
    // The receiving app gets read access through the clip data (needed for more than one picture).
    send.clipData = ClipData.newRawUri(title, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, if (uris.size == 1) "Share picture" else "Share pictures").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = WizPanel, border = BorderStroke(1.dp, WizLine), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun PdfToJpgScreen(nav: Nav, ref: PdfRef, password: String?, page: Int?) {
    PdfGate(nav, ref, password) { src -> PdfToJpgContent(nav, src, page) }
}

@Composable
private fun PdfToJpgContent(nav: Nav, src: PdfSource, startPage: Int?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf(startPage == null) }
    var ranges by remember { mutableStateOf(startPage?.let { "${it + 1}" } ?: "") }
    var dpi by remember { mutableIntStateOf(200) }
    var quality by remember { mutableIntStateOf(90) }
    var busy by remember { mutableStateOf<Busy?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val parsed = if (all) null else parsePageRanges(ranges, src.pageCount)
    val pages: List<Int>? = when {
        all -> (0 until src.pageCount).toList()
        parsed is RangeResult.Ok -> parsed.groups.flatten().distinct()
        else -> null
    }

    fun run(toGallery: Boolean) {
        val list = pages ?: return
        busy = Busy("Making pictures", 0 to list.size)
        job = scope.launch {
            try {
                val made = ImageExport.export(ctx, src, list, dpi, quality, toGallery) { done, total -> busy = Busy("Making pictures", done to total) }
                when {
                    made.isEmpty() -> toast(ctx, "Couldn't make the pictures. Try a lower resolution.")
                    toGallery -> toast(ctx, "${made.size} picture${if (made.size == 1) "" else "s"} saved to Pictures/${ImageExport.FOLDER}")
                    else -> shareImages(ctx, made, src.ref.title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(ctx, "Couldn't make the pictures: ${e.message ?: "unknown error"}")
            } finally {
                busy = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(WizBg)) {
        ToolTopBar("PDF to JPG", src.ref.title, onBack = { nav.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
            SectionHeader("Pages")
            Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizChip("All ${src.pageCount}", all, { all = true })
                    WizChip("Choose pages", !all, { all = false })
                }
                if (!all) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        ranges, { ranges = it.take(200) }, label = { Text("Pages, for example 1-3,5") }, singleLine = true,
                        isError = ranges.isNotBlank() && parsed is RangeResult.Error,
                        supportingText = { if (ranges.isNotBlank() && parsed is RangeResult.Error) Text(parsed.message) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            SectionHeader("Resolution")
            Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    intArrayOf(150, 200, 300).forEach { d -> WizChip("$d DPI", dpi == d, { dpi = d }) }
                }
                Spacer(Modifier.height(8.dp))
                val first = pages?.firstOrNull()?.let { src.sizes.getOrNull(it) }
                Text(
                    when {
                        first == null -> "150 for the screen, 200 for sharing, 300 for printing."
                        else -> exportWidthPx(first.first, first.second, dpi).let { w -> "About $w × ${(w.toLong() * first.second / first.first.coerceAtLeast(1))} pixels per page." }
                    },
                    style = MaterialTheme.typography.bodySmall, color = WizMuted
                )
            }
            SectionHeader("Picture quality")
            Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizChip("Small file", quality == 75, { quality = 75 })
                    WizChip("Balanced", quality == 90, { quality = 90 })
                    WizChip("Finest", quality == 97, { quality = 97 })
                }
            }
            Spacer(Modifier.height(18.dp))
            val count = pages?.size ?: 0
            if (ImageExport.canSaveToGallery) {
                Button(
                    onClick = { run(true) }, enabled = count > 0 && busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
                    shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(vertical = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(8.dp))
                    Text(if (count > 0) "Save $count picture${if (count == 1) "" else "s"} to gallery" else "Save to gallery", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(
                onClick = { run(false) }, enabled = count > 0 && busy == null,
                border = BorderStroke(1.dp, WizLine), shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Share, null, tint = WizYellow); Spacer(Modifier.width(8.dp))
                Text("Share", color = WizText, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (ImageExport.canSaveToGallery) "Saved pictures appear in your gallery, in the folder Pictures/${ImageExport.FOLDER}."
                else "On this version of Android, use Share and choose your gallery or files app to keep the pictures.",
                style = MaterialTheme.typography.bodySmall, color = WizMuted
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    busy?.let { BusyDialog(it) { job?.cancel(); busy = null } }
}

// ---- Password -----------------------------------------------------------------------------------

@Composable
fun ProtectScreen(nav: Nav, ref: PdfRef, password: String?) {
    // The gate asks for the current password first when the file is locked.
    PdfGate(nav, ref, password) { src -> ProtectContent(nav, src) }
}

@Composable
private fun ProtectContent(nav: Nav, src: PdfSource) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val locked = src.password != null
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var allowPrint by remember { mutableStateOf(true) }
    var allowCopy by remember { mutableStateOf(true) }
    var allowEdit by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val local = src.ref as? PdfRef.Local
    val baseName = remember(src) {
        when (val r = src.ref) {
            is PdfRef.Local -> Library.doc(r.docId)?.name ?: r.title
            is PdfRef.External -> r.title.removeSuffix(".pdf").removeSuffix(".PDF")
        }
    }
    val limited = !(allowPrint && allowCopy && allowEdit)
    val ready = locked || (pw.isNotEmpty() && pw == pw2 && (owner.isEmpty() || owner != pw))

    fun save(replace: Boolean) {
        val protection = if (locked) null else PdfOps.Protection(pw, owner.ifEmpty { null }, allowPrint, allowCopy, allowEdit)
        busy = true
        scope.launch {
            try {
                val name = uniqueDocName(baseName, if (locked) "unlocked" else "protected", Library.docs.value.map { it.name }.toSet())
                val r = PdfOps.setPassword(ctx, src.ref, src.password, protection, name, if (replace) local?.docId else null)
                nav.popTo(Screen.Home)
                nav.push(Screen.Viewer(PdfRef.Local(r.doc.id, r.doc.name)))
                toast(ctx, if (r.bits == 0) "Password removed" else "Locked with AES-${r.bits}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfOps.OpError) {
                toast(ctx, e.message ?: "Couldn't save")
            } catch (e: Exception) {
                toast(ctx, "Couldn't save: ${e.message ?: "unknown error"}")
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(WizBg)) {
        ToolTopBar(if (locked) "Remove password" else "Add password", baseName, onBack = { nav.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
            if (locked) {
                Panel {
                    Text("This PDF is locked", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "You opened it with its password. The copy saved here opens without one.",
                        style = MaterialTheme.typography.bodySmall, color = WizMuted
                    )
                }
            } else {
                SectionHeader("Password to open")
                Panel {
                    OutlinedTextField(
                        pw, { pw = it.take(64) }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        pw2, { pw2 = it.take(64) }, label = { Text("Type it again") }, singleLine = true,
                        isError = pw2.isNotEmpty() && pw2 != pw,
                        supportingText = { if (pw2.isNotEmpty() && pw2 != pw) Text("The two passwords are different") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "There is no way to recover a forgotten password. The password never leaves this phone.",
                        style = MaterialTheme.typography.bodySmall, color = WizMuted
                    )
                }
                SectionHeader("People who open it may")
                Panel {
                    AllowRow("Print", allowPrint) { allowPrint = it }
                    AllowRow("Copy text", allowCopy) { allowCopy = it }
                    AllowRow("Edit pages and notes", allowEdit) { allowEdit = it }
                    if (limited) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            owner, { owner = it.take(64) }, label = { Text("Owner password (optional)") }, singleLine = true,
                            isError = owner.isNotEmpty() && owner == pw,
                            supportingText = {
                                Text(
                                    if (owner.isNotEmpty() && owner == pw) "Must differ from the password to open"
                                    else "Lets you lift these limits later. Without it they stay for good on the locked copy."
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { save(false) }, enabled = ready && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
                shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(if (locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null); Spacer(Modifier.width(8.dp))
                Text(if (locked) "Save unlocked copy" else "Save locked copy", fontWeight = FontWeight.Bold)
            }
            if (local != null) TextButton(onClick = { confirmReplace = true }, enabled = ready && !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Replace the original instead", color = if (ready && !busy) WizMuted else WizMuted.copy(alpha = .4f))
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (busy) BusyDialogPlain(if (locked) "Removing password" else "Locking PDF", "Working…")

    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false },
        title = { Text("Replace “$baseName”?") },
        text = {
            Text(
                if (locked) "The locked version is replaced by one that opens without a password."
                else "The current version is replaced by the locked one. Without the password it can't be opened again.",
                color = WizMuted
            )
        },
        confirmButton = { TextButton(onClick = { confirmReplace = false; save(true) }) { Text("Replace", color = WizBad) } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
}

@Composable
private fun AllowRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = WizYellow, checkedThumbColor = WizInk))
    }
}
