package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** A saved signature: a transparent PNG in private storage plus its preferred ink. */
data class SavedSignature(
    val id: String,
    val name: String,
    val created: Long,
    val used: Long,
    /** ARGB ink, or [SignatureEngine.ORIGINAL]. */
    val ink: Int,
    val w: Int,
    val h: Int
) {
    val aspect get() = w.toFloat() / h.coerceAtLeast(1)
}

/** Signatures live only in filesDir/signatures (never shared, not backed up). Loaded lazily. */
object SignatureStore {
    private val _items = MutableStateFlow<List<SavedSignature>>(emptyList())
    val items: StateFlow<List<SavedSignature>> = _items
    private var dir: File? = null

    fun dir(ctx: Context) = File(ctx.filesDir, "signatures").apply { mkdirs() }
    fun fileOf(ctx: Context, id: String) = File(dir(ctx), "$id.png")

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (dir != null) return
        val d = dir(ctx); dir = d
        val f = File(d, "index.json")
        if (!f.exists()) return
        runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("items") ?: JSONArray()
            _items.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedSignature(
                    o.getString("id"), o.optString("name", "Signature"), o.optLong("created"), o.optLong("used"),
                    o.optInt("ink"), o.optInt("w", 1), o.optInt("h", 1)
                )
            }.filter { fileOf(ctx, it.id).exists() }.sortedByDescending { it.used }
        }
    }

    @Synchronized
    private fun save() {
        val d = dir ?: return
        val arr = JSONArray()
        _items.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("created", it.created)
                .put("used", it.used).put("ink", it.ink).put("w", it.w).put("h", it.h))
        }
        val tmp = File(d, "index.json.tmp")
        tmp.writeText(JSONObject().put("v", 1).put("items", arr).toString())
        tmp.renameTo(File(d, "index.json"))
    }

    suspend fun add(ctx: Context, bmp: Bitmap, ink: Int): SavedSignature = withContext(Dispatchers.IO) {
        ensureLoaded(ctx)
        val id = Library.newId()
        val part = File(dir(ctx), "$id.png.part")
        FileOutputStream(part).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        part.renameTo(fileOf(ctx, id))
        val now = System.currentTimeMillis()
        val sig = SavedSignature(id, "Signature ${_items.value.size + 1}", now, now, ink, bmp.width, bmp.height)
        _items.value = listOf(sig) + _items.value
        save()
        sig
    }

    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    fun update(sig: SavedSignature) {
        _items.value = _items.value.map { if (it.id == sig.id) sig else it }
        save()
    }

    fun touch(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        _items.value = _items.value.map { if (it.id in ids) it.copy(used = now) else it }.sortedByDescending { it.used }
        save()
    }

    fun delete(ctx: Context, id: String) {
        fileOf(ctx, id).delete()
        _items.value = _items.value.filter { it.id != id }
        save()
    }

    /** Decode a signature, long side ≤ [maxSide] (0 = full size). */
    fun decode(ctx: Context, id: String, maxSide: Int = 0): Bitmap? {
        val f = fileOf(ctx, id)
        if (!f.exists()) return null
        return if (maxSide <= 0) android.graphics.BitmapFactory.decodeFile(f.path) else ScanEngine.decodeFile(f, maxSide)
    }
}
