package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import kotlin.coroutines.coroutineContext

/** Turns the scan session into a PDF in the library, optionally with an invisible OCR text layer. */
object PdfBuilder {

    data class Progress(val page: Int, val total: Int, val stage: String)

    /**
     * Build the PDF. Words in other scripts are written with a font borrowed from the phone; if a
     * phone's font turns out to be one PDFBox can't embed, the PDF is built again without them.
     */
    suspend fun build(
        ctx: Context,
        pages: List<ScanPage>,
        name: String,
        ocr: Boolean,
        pageSize: PageSize,
        quality: Int = 82,
        idCard: Boolean = false,
        onProgress: (Progress) -> Unit
    ): Doc {
        val langs = if (ocr) Prefs.ocrLangs(ctx) else setOf(OcrLang.LATIN)
        return try {
            buildOnce(ctx, pages, name, ocr, langs, pageSize, quality, idCard, unicode = true, onProgress)
        } catch (e: UnicodeFontFailure) {
            buildOnce(ctx, pages, name, ocr, langs, pageSize, quality, idCard, unicode = false, onProgress)
        }
    }

    private class UnicodeFontFailure(cause: Throwable) : Exception(cause)

    private suspend fun buildOnce(
        ctx: Context,
        pages: List<ScanPage>,
        name: String,
        ocr: Boolean,
        langs: Set<OcrLang>,
        pageSize: PageSize,
        quality: Int,
        idCard: Boolean,
        unicode: Boolean,
        onProgress: (Progress) -> Unit
    ): Doc = withContext(Dispatchers.Default) {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx)
        val id = Library.newId()
        val out = Library.fileOf(ctx, id)
        val tmp = File(out.path + ".part")
        var anyText = false
        var sheets = 0
        PDDocument().use { doc ->
            val fonts = if (unicode) UnicodeFonts(doc) else null
            doc.documentInformation = PDDocumentInformation().apply {
                title = name
                creator = "Wiz PDF Master"
                producer = "Wiz PDF Master"
                creationDate = Calendar.getInstance()
            }
            if (idCard) {
                pages.chunked(IdCardLayout.PER_SHEET).forEachIndexed { s, group ->
                    coroutineContext.ensureActive()
                    onProgress(Progress(s * IdCardLayout.PER_SHEET + 1, pages.size, "Enhancing"))
                    val cards = group.mapNotNull { renderCard(it) }
                    if (cards.isEmpty()) return@forEachIndexed
                    if (sheets == 0) saveCardThumb(ctx, id, cards)
                    onProgress(Progress(s * IdCardLayout.PER_SHEET + group.size, pages.size, "Writing"))
                    addCardSheet(doc, cards)
                    cards.forEach { it.recycle() }
                    sheets++
                }
            } else pages.forEachIndexed { i, page ->
                coroutineContext.ensureActive()
                onProgress(Progress(i + 1, pages.size, "Enhancing"))
                var text: OcrResult? = null
                var look = page.look
                val bmp = ScanSession.lowMemoryRetry(ScanEngine.MAX_SIDE) { side ->
                    val base = ScanSession.renderBase(page, side) ?: return@lowMemoryRetry null
                    try {
                        val l = look ?: ScanEngine.pickFilter(base).also { look = it }
                        if (ocr && text == null) {
                            onProgress(Progress(i + 1, pages.size, "Reading text"))
                            // Text is read from the gray page, never from the black & white one.
                            text = runCatching { Ocr.recognize(base, flatten = true, langs = langs) }.getOrNull()
                        }
                        ScanEngine.filter(base, l)
                    } finally {
                        base.recycle()
                    }
                } ?: return@forEachIndexed
                if (sheets == 0) ScanEngine.saveJpeg(ScanEngine.thumb(bmp, 420), Library.thumbOf(ctx, id), 85)
                if (!text?.words.isNullOrEmpty()) anyText = true
                onProgress(Progress(i + 1, pages.size, "Writing"))
                addImagePage(doc, bmp, text, pageSize, quality, bilevel = look == ScanFilter.BW, fonts = fonts)
                bmp.recycle()
                sheets++
            }
            if (sheets == 0) throw java.io.IOException("No page could be prepared")
            try {
                doc.save(tmp)
            } catch (e: Exception) {
                tmp.delete()
                if (fonts?.used == true) throw UnicodeFontFailure(e) else throw e
            }
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        val now = System.currentTimeMillis()
        val d = Doc(id, name, sheets, now, now, out.length(), anyText)
        Library.add(d)
        d
    }

    /** 600 dpi on a real card: more only makes the file bigger. */
    private const val CARD_MAX_SIDE = 2024

    private fun renderCard(page: ScanPage): Bitmap? {
        val full = ScanSession.render(page, ScanEngine.MAX_SIDE) ?: return null
        if (maxOf(full.width, full.height) <= CARD_MAX_SIDE) return full
        return ScanEngine.thumb(full, CARD_MAX_SIDE).also { full.recycle() }
    }

    /** Library thumbnail of an ID scan: the cards stacked on a white sheet, like the saved page. */
    private fun saveCardThumb(ctx: Context, id: String, cards: List<Bitmap>) = runCatching {
        val w = 420; val h = (w * PDRectangle.A4.height / PDRectangle.A4.width).toInt()
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        sheet.eraseColor(android.graphics.Color.WHITE)
        val c = android.graphics.Canvas(sheet)
        val k = w / PDRectangle.A4.width
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        IdCardLayout.boxes(PDRectangle.A4.width, PDRectangle.A4.height, cards.map { it.width >= it.height }).forEachIndexed { i, b ->
            val top = (PDRectangle.A4.height - b.y - b.h) * k
            c.drawBitmap(cards[i], null, android.graphics.RectF(b.x * k, top, (b.x + b.w) * k, top + b.h * k), paint)
        }
        ScanEngine.saveJpeg(sheet, Library.thumbOf(ctx, id), 85)
        sheet.recycle()
    }

    /** One A4 sheet with the cards at their real size (85.6 × 54 mm), centred, one above the other. */
    private fun addCardSheet(doc: PDDocument, cards: List<Bitmap>) {
        val rect = PDRectangle.A4
        val page = PDPage(rect)
        doc.addPage(page)
        val boxes = IdCardLayout.boxes(rect.width, rect.height, cards.map { it.width >= it.height })
        PDPageContentStream(doc, page).use { cs ->
            cards.forEachIndexed { i, bmp ->
                val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                val b = boxes[i]
                cs.drawImage(JPEGFactory.createFromByteArray(doc, bytes), b.x, b.y, b.w, b.h)
            }
        }
    }

    private fun addImagePage(doc: PDDocument, bmp: Bitmap, ocr: OcrResult?, size: PageSize, quality: Int, bilevel: Boolean, fonts: UnicodeFonts? = null) {
        // Black & white pages are pure ink-or-paper: store them as 1-bit fax-coded images, which is
        // both sharper and several times smaller than a JPEG. Everything else stays JPEG.
        val img = (if (bilevel) BilevelCodec.image(doc, bmp) else null) ?: run {
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            JPEGFactory.createFromByteArray(doc, bytes)
        }
        val landscape = bmp.width > bmp.height
        val rect = when (size) {
            PageSize.A4 -> if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
            PageSize.LETTER -> if (landscape) PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width) else PDRectangle.LETTER
            // Treat the long side of the photo as 11.7" (A4 height) so prints come out at a sane size.
            PageSize.FIT -> {
                val s = 842f / maxOf(bmp.width, bmp.height)
                PDRectangle(bmp.width * s, bmp.height * s)
            }
        }
        val page = PDPage(rect)
        doc.addPage(page)
        val scale = minOf(rect.width / bmp.width, rect.height / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val ox = (rect.width - dw) / 2f
        val oy = (rect.height - dh) / 2f
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(img, ox, oy, dw, dh)
            // The text may have been read on a differently sized copy of the page.
            if (ocr != null && ocr.words.isNotEmpty()) writeInvisibleText(cs, ocr, ox, oy, scale * bmp.width / ocr.width, fonts)
        }
    }

    /**
     * Lay each recognised word exactly over its pixels, rendered invisible, so search/copy work.
     * Latin words use Helvetica as before; Devanagari and CJK words use a font from the phone when
     * it has one ([fonts]), and are left out of the layer when it has none.
     */
    private fun writeInvisibleText(cs: PDPageContentStream, ocr: OcrResult, ox: Float, oy: Float, scale: Float, fonts: UnicodeFonts?) {
        val helvetica = PDType1Font.HELVETICA
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        for (w in ocr.words) {
            val script = OcrLangs.scriptOf(w.text)
            val font = if (script == Script.LATIN) helvetica else fonts?.font(script)
            val t = (if (font === helvetica || font == null) pdfSafe(w.text) else w.text.filter { !it.isISOControl() }).trim()
            if (t.isEmpty()) continue
            val bw = w.box.width() * scale
            val bh = w.box.height() * scale
            if (bw <= 0f || bh <= 0f) continue
            val fontSize = (bh * 0.92f).coerceIn(1f, 300f)
            val use = font ?: helvetica
            val tw = runCatching { use.getStringWidth(t) / 1000f * fontSize }.getOrDefault(0f)
            if (tw <= 0f) continue
            val x = ox + w.box.left * scale
            val y = oy + (ocr.height - w.box.bottom) * scale + bh * 0.18f
            runCatching {
                cs.setFont(use, fontSize)
                cs.setHorizontalScaling((bw / tw * 100f).coerceIn(10f, 1000f))
                cs.setTextMatrix(Matrix.getTranslateInstance(x, y))
                cs.showText(t)
            }
        }
        cs.endText()
    }

    fun defaultName(): String {
        val c = Calendar.getInstance()
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "Scan %02d %s %d %02d.%02d".format(
            c.get(Calendar.DAY_OF_MONTH), months[c.get(Calendar.MONTH)], c.get(Calendar.YEAR),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }
}
