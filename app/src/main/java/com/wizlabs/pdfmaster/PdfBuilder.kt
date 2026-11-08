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

    suspend fun build(
        ctx: Context,
        pages: List<ScanPage>,
        name: String,
        ocr: Boolean,
        pageSize: PageSize,
        quality: Int = 82,
        onProgress: (Progress) -> Unit
    ): Doc = withContext(Dispatchers.Default) {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx)
        val id = Library.newId()
        val out = Library.fileOf(ctx, id)
        val tmp = File(out.path + ".part")
        var anyText = false
        PDDocument().use { doc ->
            doc.documentInformation = PDDocumentInformation().apply {
                title = name
                creator = "Wiz PDF Master"
                producer = "Wiz PDF Master"
                creationDate = Calendar.getInstance()
            }
            pages.forEachIndexed { i, page ->
                coroutineContext.ensureActive()
                onProgress(Progress(i + 1, pages.size, "Enhancing"))
                val bmp = ScanSession.render(page) ?: return@forEachIndexed
                if (i == 0) ScanEngine.saveJpeg(ScanEngine.thumb(bmp, 420), Library.thumbOf(ctx, id), 85)
                val text = if (ocr) {
                    onProgress(Progress(i + 1, pages.size, "Reading text"))
                    runCatching { Ocr.recognize(bmp) }.getOrNull()
                } else null
                if (!text?.words.isNullOrEmpty()) anyText = true
                onProgress(Progress(i + 1, pages.size, "Writing"))
                addImagePage(doc, bmp, text, pageSize, quality)
                bmp.recycle()
            }
            doc.save(tmp)
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        val now = System.currentTimeMillis()
        val d = Doc(id, name, pages.size, now, now, out.length(), anyText)
        Library.add(d)
        d
    }

    private fun addImagePage(doc: PDDocument, bmp: Bitmap, ocr: OcrResult?, size: PageSize, quality: Int) {
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        val img = JPEGFactory.createFromByteArray(doc, bytes)
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
            if (ocr != null && ocr.words.isNotEmpty()) writeInvisibleText(cs, ocr, ox, oy, scale)
        }
    }

    /** Lay each recognised word exactly over its pixels, rendered invisible, so search/copy work. */
    private fun writeInvisibleText(cs: PDPageContentStream, ocr: OcrResult, ox: Float, oy: Float, scale: Float) {
        val font = PDType1Font.HELVETICA
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        for (w in ocr.words) {
            val t = pdfSafe(w.text).trim()
            if (t.isEmpty()) continue
            val bw = w.box.width() * scale
            val bh = w.box.height() * scale
            if (bw <= 0f || bh <= 0f) continue
            val fontSize = (bh * 0.92f).coerceIn(1f, 300f)
            val tw = runCatching { font.getStringWidth(t) / 1000f * fontSize }.getOrDefault(0f)
            if (tw <= 0f) continue
            val x = ox + w.box.left * scale
            val y = oy + (ocr.height - w.box.bottom) * scale + bh * 0.18f
            runCatching {
                cs.setFont(font, fontSize)
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
