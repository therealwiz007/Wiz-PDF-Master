package com.wizlabs.pdfmaster

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import java.io.File
import java.util.UUID

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        appScope.launch(Dispatchers.Default) {
            ScanEngine.ready = OpenCVLoader.initLocal()
            PDFBoxResourceLoader.init(this@App)
        }
        Prefs.migrate(this)
        com.wizlabs.ads.WizAds.install(this, AdsSetup.config())   // cheap: no SDK, no network
        Library.load(this)
        ScanSession.init(this)
    }

    companion object {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** A PDF created by the scanner and kept in the app's private storage. */
data class Doc(
    val id: String,
    val name: String,
    val pages: Int,
    val created: Long,
    val modified: Long,
    val sizeBytes: Long,
    val searchable: Boolean
)

/** A PDF from elsewhere on the phone that the user opened recently. */
data class Recent(val uri: String, val name: String, val opened: Long, val sizeBytes: Long)

/** Simple JSON-backed store. Small enough (hundreds of entries) to rewrite on every change. */
object Library {
    private lateinit var root: File
    private val _docs = MutableStateFlow<List<Doc>>(emptyList())
    private val _recents = MutableStateFlow<List<Recent>>(emptyList())
    val docs: StateFlow<List<Doc>> = _docs
    val recents: StateFlow<List<Recent>> = _recents

    fun docsDir(ctx: Context) = File(ctx.filesDir, "docs").apply { mkdirs() }
    fun thumbsDir(ctx: Context) = File(ctx.filesDir, "thumbs").apply { mkdirs() }
    fun fileOf(ctx: Context, id: String) = File(docsDir(ctx), "$id.pdf")
    fun thumbOf(ctx: Context, id: String) = File(thumbsDir(ctx), "$id.jpg")

    fun load(ctx: Context) {
        root = ctx.filesDir
        val f = File(root, "library.json")
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            val ds = o.optJSONArray("docs") ?: JSONArray()
            _docs.value = (0 until ds.length()).map { i ->
                val d = ds.getJSONObject(i)
                Doc(
                    d.getString("id"), d.getString("name"), d.optInt("pages"),
                    d.optLong("created"), d.optLong("modified"), d.optLong("size"), d.optBoolean("searchable")
                )
            }.filter { fileOf(ctx, it.id).exists() }
            val rs = o.optJSONArray("recents") ?: JSONArray()
            _recents.value = (0 until rs.length()).map { i ->
                val r = rs.getJSONObject(i)
                Recent(r.getString("uri"), r.getString("name"), r.optLong("opened"), r.optLong("size"))
            }
        }
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
        o.put("docs", JSONArray().apply {
            _docs.value.forEach { d ->
                put(JSONObject().put("id", d.id).put("name", d.name).put("pages", d.pages)
                    .put("created", d.created).put("modified", d.modified).put("size", d.sizeBytes)
                    .put("searchable", d.searchable))
            }
        })
        o.put("recents", JSONArray().apply {
            _recents.value.forEach { r ->
                put(JSONObject().put("uri", r.uri).put("name", r.name).put("opened", r.opened).put("size", r.sizeBytes))
            }
        })
        val tmp = File(root, "library.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(root, "library.json"))
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun add(doc: Doc) {
        _docs.value = listOf(doc) + _docs.value.filter { it.id != doc.id }
        save()
    }

    fun rename(id: String, name: String) {
        _docs.value = _docs.value.map { if (it.id == id) it.copy(name = name, modified = System.currentTimeMillis()) else it }
        save()
    }

    fun delete(ctx: Context, id: String) {
        HomeShortcuts.disableFor(ctx, id)
        fileOf(ctx, id).delete()
        thumbOf(ctx, id).delete()
        _docs.value = _docs.value.filter { it.id != id }
        save()
    }

    fun touchRecent(ctx: Context, uri: Uri) {
        val (name, size) = queryNameSize(ctx, uri)
        val r = Recent(uri.toString(), name, System.currentTimeMillis(), size)
        _recents.value = (listOf(r) + _recents.value.filter { it.uri != r.uri }).take(40)
        save()
    }

    fun removeRecent(uri: String) {
        _recents.value = _recents.value.filter { it.uri != uri }
        save()
    }

    /** Rename several documents at once ([names]: document id to new name). */
    fun renameAll(names: Map<String, String>) {
        val now = System.currentTimeMillis()
        _docs.value = _docs.value.map { d -> names[d.id]?.takeIf { it.isNotBlank() && it != d.name }?.let { d.copy(name = it, modified = now) } ?: d }
        save()
    }

    fun doc(id: String) = _docs.value.firstOrNull { it.id == id }

    fun thumbnail(ctx: Context, id: String): Bitmap? {
        val f = thumbOf(ctx, id)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }
}

fun queryNameSize(ctx: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
    var size = 0L
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
    }
    return name to size
}

/** User preferences. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /**
     * Run once at start-up. New installs start with the "Auto" look; phones that already used the
     * app keep what they had (Magic, unless they chose something else).
     */
    fun migrate(ctx: Context) {
        val p = sp(ctx)
        if (p.contains("filter")) return
        val usedBefore = p.all.isNotEmpty() || File(ctx.filesDir, "library.json").exists()
        p.edit().putString("filter", (if (usedBefore) ScanFilter.MAGIC else ScanFilter.AUTO).name).apply()
    }

    fun defaultFilter(ctx: Context) = runCatching {
        ScanFilter.valueOf(sp(ctx).getString("filter", ScanFilter.MAGIC.name)!!)
    }.getOrDefault(ScanFilter.MAGIC)

    /** Turn pages the right way up and level tilted text when a page is captured. */
    fun autoRotate(ctx: Context) = sp(ctx).getBoolean("autorotate", true)
    fun setAutoRotate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("autorotate", v).apply()
    fun setDefaultFilter(ctx: Context, f: ScanFilter) = sp(ctx).edit().putString("filter", f.name).apply()

    fun autoCapture(ctx: Context) = sp(ctx).getBoolean("auto", true)
    fun setAutoCapture(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto", v).apply()

    fun ocrDefault(ctx: Context) = sp(ctx).getBoolean("ocr", true)
    fun setOcrDefault(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("ocr", v).apply()

    fun pageSize(ctx: Context) = runCatching {
        PageSize.valueOf(sp(ctx).getString("page", PageSize.A4.name)!!)
    }.getOrDefault(PageSize.A4)
    fun setPageSize(ctx: Context, p: PageSize) = sp(ctx).edit().putString("page", p.name).apply()

    fun nightReading(ctx: Context) = sp(ctx).getBoolean("night", false)
    fun setNightReading(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("night", v).apply()

    /** Last app version that ran on this phone (0 before 1.3.0), for the one-time "What's new". */
    fun lastSeenVersion(ctx: Context) = sp(ctx).getInt("seen_v", 0)
    fun setLastSeenVersion(ctx: Context, v: Int) = sp(ctx).edit().putInt("seen_v", v).apply()

    /** Which scanner "Scan" opens; null until the user chooses (then: Google's when the phone has it). */
    fun scanner(ctx: Context): ScannerKind? = ScannerChoice.decode(sp(ctx).getString("scanner", null))
    fun setScanner(ctx: Context, k: ScannerKind) = sp(ctx).edit().putString("scanner", k.name).apply()

    /** Google scanner with its extra clean-up tools (stain and finger removal). */
    fun scannerCleanup(ctx: Context) = sp(ctx).getBoolean("scanner_full", false)
    fun setScannerCleanup(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("scanner_full", v).apply()

    /** Scripts text recognition reads; Latin is always among them. */
    fun ocrLangs(ctx: Context): Set<OcrLang> = OcrLangs.decode(sp(ctx).getString("ocr_langs", null))
    fun setOcrLangs(ctx: Context, v: Set<OcrLang>) = sp(ctx).edit().putString("ocr_langs", OcrLangs.encode(v)).apply()

    /** The one-time "Which languages do you scan?" question has been answered. */
    fun ocrLangsAsked(ctx: Context) = sp(ctx).getBoolean("ocr_langs_asked", false)
    fun setOcrLangsAsked(ctx: Context) = sp(ctx).edit().putBoolean("ocr_langs_asked", true).apply()

    fun lastPage(ctx: Context, key: String) = sp(ctx).getInt("lp_" + key.hashCode(), 0)
    fun setLastPage(ctx: Context, key: String, page: Int) = sp(ctx).edit().putInt("lp_" + key.hashCode(), page).apply()
}

enum class PageSize(val label: String) { A4("A4"), LETTER("Letter"), FIT("Fit image") }
