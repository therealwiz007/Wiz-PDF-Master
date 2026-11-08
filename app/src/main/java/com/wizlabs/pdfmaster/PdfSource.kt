package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/** Where a PDF lives: our own library file, or a content Uri from another app. */
sealed class PdfRef {
    abstract val key: String
    abstract val title: String

    data class Local(val docId: String, override val title: String) : PdfRef() {
        override val key get() = "doc:$docId"
    }

    data class External(val uri: Uri, override val title: String) : PdfRef() {
        override val key get() = uri.toString()
    }
}

class PasswordRequired : Exception("This PDF is password-protected")

/** A search hit: page index and rectangles normalised to the page (0..1, top-left origin). */
data class Hit(val page: Int, val rects: List<RectF>)

/**
 * Thin, thread-safe wrapper around the platform PdfRenderer. The renderer can only have one page
 * open at a time, so every call goes through a single mutex on a single-thread dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfSource private constructor(
    private val ctx: Context,
    val ref: PdfRef,
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val password: String?
) {
    val pageCount = renderer.pageCount
    /** Width / height of each page in PDF points. */
    val sizes: List<Pair<Int, Int>>
    private val mutex = Mutex()
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        sizes = (0 until pageCount).map { i ->
            renderer.openPage(i).use { it.width to it.height }
        }
    }

    fun cached(page: Int, widthPx: Int): Bitmap? = cache.get("$page:$widthPx")

    /** Best already-rendered bitmap for [page] at any resolution (shown while a sharper one loads). */
    fun anyCached(page: Int): Bitmap? =
        cache.snapshot().entries.filter { it.key.startsWith("$page:") }.maxByOrNull { it.value.width }?.value

    suspend fun render(page: Int, widthPx: Int): Bitmap? = withContext(dispatcher) {
        val key = "$page:$widthPx"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            runCatching {
                renderer.openPage(page).use { p ->
                    val w = widthPx.coerceIn(64, 4096)
                    val h = (w.toFloat() * p.height / p.width).toInt().coerceIn(64, 6000)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bmp)
                    bmp
                }
            }.getOrNull()
        }
    }

    /** Render without caching (OCR, export). */
    suspend fun renderFresh(page: Int, widthPx: Int): Bitmap? = withContext(dispatcher) {
        mutex.withLock {
            runCatching {
                renderer.openPage(page).use { p ->
                    val h = (widthPx.toFloat() * p.height / p.width).toInt()
                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    bmp
                }
            }.getOrNull()
        }
    }

    // ---- Text (search / copy) ----------------------------------------------------------------

    private class PageText(val text: String, val boxes: List<RectF>)
    private var index: List<PageText>? = null
    private val indexLock = Mutex()

    val isIndexed get() = index != null
    /** True when the document has (almost) no text layer, i.e. it is a scan without OCR. */
    var looksScanned = false; private set

    /** Extract text with positions for every page (once). [onProgress] gets (done, total). */
    suspend fun buildIndex(onProgress: (Int, Int) -> Unit): Boolean = withContext(Dispatchers.Default) {
        indexLock.withLock {
            if (index != null) return@withLock true
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx)
            val result = runCatching {
                openStream().use { input ->
                    PDDocument.load(input, password ?: "").use { doc ->
                        val pages = ArrayList<PageText>(doc.numberOfPages)
                        for (i in 0 until doc.numberOfPages) {
                            pages += extractPage(doc, i)
                            onProgress(i + 1, doc.numberOfPages)
                        }
                        pages
                    }
                }
            }.getOrNull() ?: return@withLock false
            index = result
            looksScanned = result.sumOf { it.text.count { c -> !c.isWhitespace() } } < 10 * result.size.coerceAtLeast(1) / 4 + 5
            true
        }
    }

    private fun extractPage(doc: PDDocument, i: Int): PageText {
        val pd = doc.getPage(i)
        val rot = ((pd.rotation % 360) + 360) % 360
        val box = pd.cropBox
        val pw = if (rot == 90 || rot == 270) box.height else box.width
        val ph = if (rot == 90 || rot == 270) box.width else box.height
        val sb = StringBuilder()
        val boxes = ArrayList<RectF>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                val tps = textPositions ?: return
                for (tp in tps) {
                    val u = tp.unicode ?: continue
                    val x = tp.xDirAdj
                    val y = tp.yDirAdj
                    val w = tp.widthDirAdj
                    val h = tp.heightDir.coerceAtLeast(1f) * 1.25f
                    val r = RectF(x / pw, (y - h) / ph, (x + w) / pw, (y + h * 0.2f) / ph)
                    for (c in u) { sb.append(c); boxes += r }
                }
            }

            override fun writeWordSeparator() { sb.append(' '); boxes += RectF() }
            override fun writeLineSeparator() { sb.append('\n'); boxes += RectF() }
        }
        stripper.sortByPosition = true
        stripper.startPage = i + 1
        stripper.endPage = i + 1
        runCatching { stripper.getText(doc) }
        return PageText(sb.toString(), boxes)
    }

    fun search(query: String): List<Hit> {
        val idx = index ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        idx.forEachIndexed { page, pt ->
            if (hits.size >= MAX_HITS) return hits
            for (start in findAll(pt.text, q)) {
                if (hits.size >= MAX_HITS) break
                val rects = mergeLineRects(pt.boxes.subList(start, (start + q.length).coerceAtMost(pt.boxes.size)))
                if (rects.isNotEmpty()) hits += Hit(page, rects)
            }
        }
        return hits
    }

    fun pageText(page: Int): String? = index?.getOrNull(page)?.text

    /** Join per-character boxes into one rectangle per line. */
    private fun mergeLineRects(chars: List<RectF>): List<RectF> {
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (r in chars) {
            if (r.width() <= 0f) continue
            val c = cur
            if (c != null && kotlin.math.abs(c.centerY() - r.centerY()) < c.height() * 0.6f) c.union(r)
            else { cur = RectF(r); out += cur }
        }
        return out
    }

    private fun openStream() = when (ref) {
        is PdfRef.Local -> FileInputStream(Library.fileOf(ctx, ref.docId))
        is PdfRef.External -> ctx.contentResolver.openInputStream(ref.uri) ?: error("Cannot open")
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        cache.evictAll()
    }

    companion object {
        const val MAX_HITS = 500

        /** Open [ref]. Throws [PasswordRequired] for locked files (unlockable on Android 15+). */
        suspend fun open(ctx: Context, ref: PdfRef, password: String? = null): PdfSource = withContext(Dispatchers.IO) {
            val pfd = if (password != null && Build.VERSION.SDK_INT < 35) {
                // Older Android can't unlock PDFs itself: decrypt a private temporary copy with PDFBox.
                ParcelFileDescriptor.open(decryptedCopy(ctx, ref, password), ParcelFileDescriptor.MODE_READ_ONLY)
            } else when (ref) {
                is PdfRef.Local -> ParcelFileDescriptor.open(Library.fileOf(ctx, ref.docId), ParcelFileDescriptor.MODE_READ_ONLY)
                is PdfRef.External -> ctx.contentResolver.openFileDescriptor(ref.uri, "r") ?: error("Cannot open file")
            }
            val renderer = try {
                if (password != null && Build.VERSION.SDK_INT >= 35) {
                    PdfRenderer(pfd, android.graphics.pdf.LoadParams.Builder().setPassword(password).build())
                } else PdfRenderer(pfd)
            } catch (e: SecurityException) {
                pfd.close(); throw PasswordRequired()
            } catch (e: Exception) {
                pfd.close()
                if (e.message?.contains("password", ignoreCase = true) == true) throw PasswordRequired()
                throw e
            }
            PdfSource(ctx.applicationContext, ref, pfd, renderer, password)
        }

        private fun decryptedCopy(ctx: Context, ref: PdfRef, password: String): File {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx)
            val out = File(ctx.cacheDir, "unlocked/${ref.key.hashCode()}.pdf").apply { parentFile?.mkdirs() }
            val input = when (ref) {
                is PdfRef.Local -> FileInputStream(Library.fileOf(ctx, ref.docId))
                is PdfRef.External -> ctx.contentResolver.openInputStream(ref.uri) ?: error("Cannot open")
            }
            try {
                input.use {
                    PDDocument.load(it, password).use { doc ->
                        doc.isAllSecurityToBeRemoved = true
                        doc.save(out)
                    }
                }
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                throw PasswordRequired()
            }
            return out
        }

        /** Remove decrypted temporary copies (called when the viewer closes). */
        fun clearUnlocked(ctx: Context) {
            File(ctx.cacheDir, "unlocked").listFiles()?.forEach { it.delete() }
        }
    }
}
