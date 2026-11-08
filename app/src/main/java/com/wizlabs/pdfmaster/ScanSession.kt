package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
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
    val version: Int = 0            // bumps on every edit so previews refresh
)

/**
 * The pages of the scan in progress. Lives for the whole activity so the user can hop between
 * camera, review and crop without losing work. Previews are cached in memory at screen size.
 */
object ScanSession {
    val pages = mutableStateListOf<ScanPage>()
    val selected = mutableIntStateOf(0)
    /** When set, new captures are appended to an existing library document (not used yet). */
    val title = mutableStateOf("")

    private val previewCache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(16 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val lock = Mutex()

    fun dir(ctx: Context) = File(ctx.cacheDir, "scan").apply { mkdirs() }

    fun clear(ctx: Context) {
        pages.clear()
        selected.intValue = 0
        title.value = ""
        previewCache.evictAll()
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    fun update(index: Int, transform: (ScanPage) -> ScanPage) {
        if (index !in pages.indices) return
        val p = transform(pages[index])
        pages[index] = p.copy(version = p.version + 1)
    }

    fun remove(ctx: Context, index: Int) {
        if (index !in pages.indices) return
        val p = pages.removeAt(index)
        if (pages.none { it.original == p.original }) p.original.delete()
        if (selected.intValue >= pages.size) selected.intValue = (pages.size - 1).coerceAtLeast(0)
    }

    fun move(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val p = pages.removeAt(from)
        pages.add(to, p)
        selected.intValue = to
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
    fun render(page: ScanPage, maxSide: Int = ScanEngine.MAX_SIDE): Bitmap? {
        val src = ScanEngine.decodeFile(page.original, maxSide) ?: return null
        val warped = ScanEngine.warp(src, page.quad)
        src.recycle()
        val rotated = ScanEngine.rotate(warped, page.rotation)
        if (rotated !== warped) warped.recycle()
        val filtered = ScanEngine.filter(rotated, page.filter)
        rotated.recycle()
        return filtered
    }

    /** Original photo at preview size, for the crop screen. */
    suspend fun originalPreview(page: ScanPage, maxSide: Int = 1600): Bitmap? = withContext(Dispatchers.Default) {
        val key = "${page.original.path}:orig:$maxSide"
        previewCache.get(key) ?: ScanEngine.decodeFile(page.original, maxSide)?.also { previewCache.put(key, it) }
    }
}
