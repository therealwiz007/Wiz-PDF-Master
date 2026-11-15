package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One captured page: the untouched photo on disk plus the edits the user picked. */
data class ScanPage(
    val id: String = UUID.randomUUID().toString(),
    val original: File,
    val quad: Quad,
    val rotation: Int = 0,          // extra clockwise rotation applied after the warp
    val filter: ScanFilter,
    val blurry: Boolean = false,    // the capture looked out of focus (hint only)
    val version: Int = 0,           // bumps on every edit so previews refresh
    val skew: Float = 0f,           // clockwise tilt of the text in degrees, levelled out in the warp
    val autoPick: ScanFilter? = null,   // what "Auto" chose for this page
    val card: Boolean = false,      // an ID card: forced to card proportions
    val curve: Boolean = false      // beta: straighten the lines of a bulging (book) page
) {
    /** The look actually applied; null while "Auto" has not looked at the page yet. */
    val look: ScanFilter? get() = if (filter == ScanFilter.AUTO) autoPick else filter
    /** Proportions forced on the page: a cropped ID card is always card-shaped. */
    val shape: PaperShape? get() = if (card && quad != Quad.FULL) PaperShape.ID1 else null
}

/**
 * The pages of the scan in progress. Lives for the whole activity so the user can hop between
 * camera, review and crop without losing work. Previews are cached in memory at screen size.
 *
 * The photos live in filesDir/drafts/<id>/ next to a small text file listing the pages and their
 * edits, so an unfinished scan survives the app being closed or killed: Home offers to continue it.
 */
object ScanSession {
    val pages = mutableStateListOf<ScanPage>()
    val selected = mutableIntStateOf(0)
    /** When set, new captures are appended to an existing library document (not used yet). */
    val title = mutableStateOf("")
    /** ID card scan: front and back, laid out together on one sheet when saved. */
    val idCard = mutableStateOf(false)
    /** An unfinished scan found on disk at start-up, waiting for "Continue" or "Discard". */
    val pending = mutableStateOf<Draft?>(null)

    private val previewCache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(16 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val lock = Mutex()
    private val writeLock = Any()
    @Volatile private var sessionId = newId()
    /** Set once the user has started or dropped a scan: a draft found later on disk is then stale. */
    @Volatile private var touched = false
    private const val DRAFT_FILE = "draft.txt"

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)
    private fun draftsRoot(ctx: Context) = File(ctx.filesDir, "drafts")

    fun dir(ctx: Context) = File(draftsRoot(ctx), sessionId).apply { mkdirs() }

    /** Called once at start-up: find an unfinished scan and start saving the session as it changes. */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        App.appScope.launch(Dispatchers.IO) {
            runCatching { File(app.cacheDir, "scan").deleteRecursively() }      // pre-1.4 location
            val found = draftsRoot(app).listFiles()?.filter { it.isDirectory }
                ?.sortedByDescending { File(it, DRAFT_FILE).lastModified() }
                ?.firstNotNullOfOrNull { d ->
                    val draft = runCatching { DraftCodec.decode(File(d, DRAFT_FILE).readText()) }.getOrNull()
                    val alive = draft?.pages?.filter { File(d, it.file).length() > 0 }.orEmpty()
                    if (draft == null || alive.isEmpty()) null
                    else d to draft.copy(pages = alive, selected = draft.selected.coerceIn(0, alive.lastIndex))
                }
            // A scan may already be under way by the time the disk has been read: then leave it be.
            withContext(Dispatchers.Main) {
                if (pages.isNotEmpty() || touched) return@withContext
                if (found != null) { sessionId = found.first.name; pending.value = found.second }
            }
            val keep = sessionId
            draftsRoot(app).listFiles()?.filter { it.name != keep }?.forEach { it.deleteRecursively() }
        }
    }

    private var saveJob: Job? = null

    /**
     * Write the page list to disk (a few hundred bytes) shortly after a change. Call on the main
     * thread; several changes in a row are written once.
     */
    private fun save(ctx: Context) {
        val app = ctx.applicationContext
        val list = pages.toList(); val card = idCard.value; val sel = selected.intValue
        saveJob?.cancel()
        if (list.isEmpty()) return
        saveJob = App.appScope.launch(Dispatchers.IO) {
            delay(150)
            writeDraft(app, list, card, sel)
        }
    }

    /** Add a freshly captured or imported page at the end. */
    fun add(ctx: Context, page: ScanPage) {
        pages.add(page)
        save(ctx)
    }

    private fun writeDraft(ctx: Context, list: List<ScanPage>, card: Boolean, sel: Int) = synchronized(writeLock) {
        runCatching {
            val d = dir(ctx)
            val mine = list.filter { it.original.parentFile == d }
            if (mine.isEmpty()) return@runCatching
            val text = DraftCodec.encode(Draft(card, sel.coerceIn(0, mine.lastIndex), mine.map {
                DraftPage(it.id, it.original.name, it.quad, it.rotation, it.filter.name, it.blurry, it.skew, it.autoPick?.name, it.card, it.curve)
            }))
            val tmp = File(d, "$DRAFT_FILE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(d, DRAFT_FILE))) { tmp.copyTo(File(d, DRAFT_FILE), overwrite = true); tmp.delete() }
        }
    }

    /** Bring the unfinished scan back as the current session. Returns false when nothing was left of it. */
    fun resume(ctx: Context): Boolean {
        val draft = pending.value ?: return false
        pending.value = null
        val d = dir(ctx)
        val restored = draft.pages.mapNotNull { p ->
            val f = File(d, p.file)
            if (f.length() == 0L) return@mapNotNull null
            ScanPage(
                id = p.id, original = f, quad = p.quad, rotation = p.rotation,
                filter = runCatching { ScanFilter.valueOf(p.filter) }.getOrDefault(ScanFilter.MAGIC),
                blurry = p.blurry, skew = p.skew,
                autoPick = p.autoPick?.let { runCatching { ScanFilter.valueOf(it) }.getOrNull() },
                card = p.card, curve = p.curve
            )
        }
        if (restored.isEmpty()) { clear(ctx); return false }
        previewCache.evictAll()
        pages.clear(); pages.addAll(restored)
        idCard.value = draft.idCard
        selected.intValue = draft.selected.coerceIn(0, restored.lastIndex)
        return true
    }

    /** Forget the session (and any unfinished scan on disk) and start a fresh, empty one. */
    fun clear(ctx: Context) {
        pages.clear()
        selected.intValue = 0
        title.value = ""
        idCard.value = false
        pending.value = null
        touched = true
        saveJob?.cancel()
        previewCache.evictAll()
        val root = draftsRoot(ctx)
        val old = sessionId
        sessionId = newId()
        synchronized(writeLock) { File(root, old).deleteRecursively() }
    }

    fun update(ctx: Context, index: Int, transform: (ScanPage) -> ScanPage) {
        if (index !in pages.indices) return
        val p = transform(pages[index])
        pages[index] = p.copy(version = p.version + 1)
        save(ctx)
    }

    /** Edit the page with this id, wherever it has moved to meanwhile. */
    fun updateById(ctx: Context, id: String, transform: (ScanPage) -> ScanPage) = update(ctx, pages.indexOfFirst { it.id == id }, transform)

    fun remove(ctx: Context, index: Int) {
        if (index !in pages.indices) return
        val p = pages.removeAt(index)
        if (pages.none { it.original == p.original }) p.original.delete()
        if (selected.intValue >= pages.size) selected.intValue = (pages.size - 1).coerceAtLeast(0)
        // Nothing left: there is no scan to continue any more.
        saveJob?.cancel()
        if (pages.isEmpty()) synchronized(writeLock) { File(dir(ctx), DRAFT_FILE).delete() } else save(ctx)
    }

    fun move(ctx: Context, from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val p = pages.removeAt(from)
        pages.add(to, p)
        selected.intValue = to
        save(ctx)
    }

    /** Warped + filtered page at preview size (cached). */
    suspend fun preview(page: ScanPage, maxSide: Int = 1600): Bitmap? = withContext(Dispatchers.Default) {
        val key = "${page.id}:${page.version}:$maxSide"
        previewCache.get(key)?.let { return@withContext it }
        lock.withLock {
            previewCache.get(key)?.let { return@withLock it }
            val bmp = render(page, maxSide) ?: return@withLock null
            previewCache.put(key, bmp)
            bmp
        }
    }

    /** Full-quality page for the final PDF (not cached). */
    fun render(page: ScanPage, maxSide: Int = ScanEngine.MAX_SIDE): Bitmap? = lowMemoryRetry(maxSide) { side ->
        val base = renderBase(page, side) ?: return@lowMemoryRetry null
        try {
            ScanEngine.filter(base, page.look ?: ScanEngine.pickFilter(base))
        } finally {
            base.recycle()
        }
    }

    /** Low-RAM phone: drop cached previews and settle for a smaller page rather than failing. */
    inline fun <T> lowMemoryRetry(maxSide: Int, block: (Int) -> T?): T? = try {
        block(maxSide)
    } catch (_: OutOfMemoryError) {
        trimMemory()
        try { block(maxSide * 2 / 3) } catch (_: OutOfMemoryError) { null }
    }

    fun trimMemory() {
        previewCache.evictAll()
        System.gc()
    }

    /** The page cropped, levelled and turned, before any look is applied. The caller recycles it. */
    fun renderBase(page: ScanPage, maxSide: Int = ScanEngine.MAX_SIDE): Bitmap? {
        val src = ScanEngine.decodeFile(page.original, maxSide) ?: return null
        val warped = try {
            ScanEngine.warp(src, page.quad, page.skew, page.shape)
        } finally {
            src.recycle()
        }
        val rotated = ScanEngine.rotate(warped, page.rotation)
        if (rotated !== warped) warped.recycle()
        if (!page.curve) return rotated
        val flat = CurveFlatten.flatten(page, rotated)
        if (flat !== rotated) rotated.recycle()
        return flat
    }

    /** Original photo at preview size, for the crop screen. */
    suspend fun originalPreview(page: ScanPage, maxSide: Int = 1600): Bitmap? = withContext(Dispatchers.Default) {
        val key = "${page.original.path}:orig:$maxSide"
        previewCache.get(key) ?: ScanEngine.decodeFile(page.original, maxSide)?.also { previewCache.put(key, it) }
    }
}

/** Works out everything a new page needs (outline, focus, look, which way is up), off the main thread. */
object ScanIntake {
    /**
     * [photo] is the picture in [file], decoded upright at up to [ScanEngine.REFINE_SIDE] px or more.
     * [hint] is the outline the live preview showed, used when the photo itself shows none.
     */
    suspend fun page(ctx: Context, file: File, photo: Bitmap, hint: Quad?, card: Boolean): ScanPage = withContext(Dispatchers.Default) {
        val raw = ScanEngine.detectRaw(photo)
        val quad = when {
            raw != null -> ScanEngine.refine(photo, raw)?.inset(QuadMath.INSET_REFINED) ?: raw.inset(QuadMath.INSET_COARSE)
            else -> hint ?: Quad.FULL
        }
        var blurry = false
        var pick: ScanFilter? = null
        var fix: PageAngle.Fix? = null
        runCatching {
            val small = if (maxOf(photo.width, photo.height) > 1000) ScanEngine.thumb(photo, 1000) else photo
            try {
                blurry = !isSharp(ScanEngine.sharpness(small, quad), ScanTuning.BLUR_MIN_CAPTURE)
                val flat = ScanEngine.warp(small, quad, 0f, if (card && quad != Quad.FULL) PaperShape.ID1 else null)
                try {
                    pick = ScanEngine.pickFilter(flat)
                    if (Prefs.autoRotate(ctx)) fix = Ocr.pageAngle(flat)
                } finally {
                    flat.recycle()
                }
            } finally {
                if (small !== photo) small.recycle()
            }
        }
        ScanPage(
            original = file, quad = quad, filter = Prefs.defaultFilter(ctx), blurry = blurry,
            rotation = fix?.rotate ?: 0, skew = fix?.skew ?: 0f, autoPick = pick, card = card
        )
    }

    /** After the corners were moved by hand: measure the leftover tilt again (the quarter turn stays as it is). */
    fun remeasure(ctx: Context, pageId: String) {
        val app = ctx.applicationContext
        App.appScope.launch(Dispatchers.Default) {
            val page = withContext(Dispatchers.Main) { ScanSession.pages.firstOrNull { it.id == pageId } } ?: return@launch
            val result = runCatching {
                val small = ScanEngine.decodeFile(page.original, 1000) ?: return@launch
                val flat = try {
                    ScanEngine.warp(small, page.quad, 0f, page.shape)
                } finally {
                    small.recycle()
                }
                try {
                    ScanEngine.pickFilter(flat) to (if (Prefs.autoRotate(app)) Ocr.pageAngle(flat) else null)
                } finally {
                    flat.recycle()
                }
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                // Only if the corners have not been changed again meanwhile.
                ScanSession.updateById(app, pageId) { if (it.quad == page.quad) it.copy(skew = result.second?.skew ?: 0f, autoPick = result.first) else it }
            }
        }
    }
}
