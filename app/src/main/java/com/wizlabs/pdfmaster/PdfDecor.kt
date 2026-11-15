package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CancellationException

/** Something drawn on a page in annotate mode. Positions are 0..1 of the displayed page, from its top-left corner. */
sealed class Mark {
    abstract val page: Int

    /** A highlighted rectangle. */
    class Box(override val page: Int, val l: Float, val t: Float, val r: Float, val b: Float) : Mark()

    /** A freehand line: [pts] is x,y,x,y…; [width] is a fraction of the page width. [marker] = see-through highlighter. */
    class Stroke(override val page: Int, val pts: FloatArray, val color: Int, val width: Float, val marker: Boolean) : Mark()

    /** A sticky note with its top-left corner at ([x],[y]). */
    class Note(override val page: Int, val x: Float, val y: Float, val text: String) : Mark()
}

data class WatermarkSpec(
    val text: String,
    /** Share of the page the mark spans (0..1). */
    val size: Float,
    val opacity: Float,
    /** Degrees anticlockwise as seen on the page: 45 = rising to the right. */
    val angle: Float,
    /** RGB. */
    val color: Int
)

data class NumberSpec(val pos: NumberPos, val format: NumberFormat, val start: Int, val fontSize: Float)

/**
 * Draws on top of existing pages: annotations, watermarks and page numbers. Everything is added
 * to the page content itself (not as PDF annotation objects), so it shows in every viewer and in
 * print exactly as it did on screen. The original content of the page is untouched underneath.
 */
object PdfDecor {
    const val HIGHLIGHT = 0xFFFFE000.toInt()
    const val NOTE_PAPER = 0xFFFFF2A8.toInt()
    /** A note's text size and width, as fractions of the page width (shared with the on-screen preview). */
    const val NOTE_FONT = 1f / 52f
    const val NOTE_CHARS = 26

    private fun rgb(c: Int) = PDColor(
        floatArrayOf((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f), PDDeviceRGB.INSTANCE
    )

    private fun gstate(alpha: Float, multiply: Boolean) = PDExtendedGraphicsState().apply {
        nonStrokingAlphaConstant = alpha
        strokingAlphaConstant = alpha
        if (multiply) blendMode = BlendMode.MULTIPLY
    }

    /** Break [text] into lines of at most [max] characters, at spaces where possible. */
    fun wrap(text: String, max: Int = NOTE_CHARS): List<String> {
        val out = ArrayList<String>()
        for (para in text.replace("\r", "").split('\n')) {
            var line = StringBuilder()
            for (word in para.split(' ')) {
                var w = word
                while (w.length > max) {                       // a word longer than a line is cut
                    if (line.isNotEmpty()) { out += line.toString(); line = StringBuilder() }
                    out += w.take(max); w = w.drop(max)
                }
                if (line.isNotEmpty() && line.length + 1 + w.length > max) { out += line.toString(); line = StringBuilder() }
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
            out += line.toString()
        }
        return out.take(40)
    }

    /** A drawing surface in display points (y up, origin bottom-left of the page as shown). */
    private inline fun onPage(doc: PDDocument, page: PDPage, draw: (PDPageContentStream, Float, Float) -> Unit) {
        val box = page.cropBox
        val rot = page.rotation
        val (w, h) = SignGeometry.displaySize(rot, box.width, box.height)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val m = SignGeometry.displayToUser(rot, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
            cs.transform(Matrix(m.a, m.b, m.c, m.d, m.e, m.f))
            draw(cs, w, h)
        }
    }

    /** The font that can write [text]: Helvetica for Latin, a phone font for Devanagari / CJK, else Helvetica with the rest dropped. */
    private fun fontFor(text: String, fonts: UnicodeFonts?): Pair<PDFont, String> {
        val script = OcrLangs.scriptOf(text)
        val f = if (script == Script.LATIN) null else fonts?.font(script)
        if (f != null) {
            val clean = text.filter { !it.isISOControl() }
            if (runCatching { f.getStringWidth(clean) }.isSuccess) return f to clean
        }
        return PDType1Font.HELVETICA to pdfSafe(text)
    }

    fun drawMarks(doc: PDDocument, marks: List<Mark>, fonts: UnicodeFonts? = null) {
        for ((index, list) in marks.groupBy { it.page }.toSortedMap()) {
            if (index !in 0 until doc.numberOfPages) continue
            onPage(doc, doc.getPage(index)) { cs, w, h ->
                for (m in list) {
                    cs.saveGraphicsState()
                    when (m) {
                        is Mark.Box -> {
                            cs.setGraphicsStateParameters(gstate(0.45f, true))
                            cs.setNonStrokingColor(rgb(HIGHLIGHT))
                            cs.addRect(minOf(m.l, m.r) * w, (1f - maxOf(m.t, m.b)) * h, Math.abs(m.r - m.l) * w, Math.abs(m.b - m.t) * h)
                            cs.fill()
                        }
                        is Mark.Stroke -> if (m.pts.size >= 2) {
                            if (m.marker) cs.setGraphicsStateParameters(gstate(0.45f, true))
                            cs.setStrokingColor(rgb(m.color))
                            cs.setLineWidth((m.width * w).coerceAtLeast(0.3f))
                            cs.setLineCapStyle(if (m.marker) 0 else 1)
                            cs.setLineJoinStyle(1)
                            cs.moveTo(m.pts[0] * w, (1f - m.pts[1]) * h)
                            if (m.pts.size == 2) cs.lineTo(m.pts[0] * w + 0.1f, (1f - m.pts[1]) * h)
                            for (i in 1 until m.pts.size / 2) cs.lineTo(m.pts[i * 2] * w, (1f - m.pts[i * 2 + 1]) * h)
                            cs.stroke()
                        }
                        is Mark.Note -> {
                            val fs = w * NOTE_FONT
                            val lines = wrap(m.text).map { fontFor(it, fonts) }
                            val pad = fs * 0.6f
                            val lead = fs * 1.25f
                            val tw = lines.maxOfOrNull { (f, t) -> runCatching { f.getStringWidth(t) / 1000f * fs }.getOrDefault(0f) } ?: 0f
                            val bw = tw + pad * 2
                            val bh = lines.size * lead + pad * 2 - (lead - fs)
                            val x = (m.x * w).coerceIn(0f, (w - bw).coerceAtLeast(0f))
                            val top = ((1f - m.y) * h).coerceIn(minOf(bh, h), h)
                            cs.setNonStrokingColor(rgb(NOTE_PAPER))
                            cs.setStrokingColor(rgb(0xC9A400))
                            cs.setLineWidth(0.6f)
                            cs.addRect(x, top - bh, bw, bh)
                            cs.fillAndStroke()
                            cs.setNonStrokingColor(rgb(0x111114))
                            lines.forEachIndexed { i, (f, t) ->
                                if (t.isEmpty()) return@forEachIndexed
                                runCatching {
                                    cs.beginText()
                                    cs.setFont(f, fs)
                                    cs.setTextMatrix(Matrix.getTranslateInstance(x + pad, top - pad - fs * 0.8f - i * lead))
                                    cs.showText(t)
                                    cs.endText()
                                }
                            }
                        }
                    }
                    cs.restoreGraphicsState()
                }
            }
        }
    }

    /** The same mark across every page: [image] when given, otherwise the text of [spec]. */
    fun watermark(doc: PDDocument, spec: WatermarkSpec, image: Bitmap? = null, fonts: UnicodeFonts? = null) {
        val picture = image?.let { LosslessFactory.createFromImage(doc, it) }
        val (font, text) = fontFor(spec.text.trim(), fonts)
        if (picture == null && text.isEmpty()) throw PdfOps.OpError("Type the watermark text first.")
        val unit = if (picture == null) font.getStringWidth(text) / 1000f else 0f
        val gs = gstate(spec.opacity.coerceIn(0.02f, 1f), false)
        for (page in doc.pages) onPage(doc, page) { cs, w, h ->
            cs.setGraphicsStateParameters(gs)
            if (picture != null) {
                val m = WatermarkGeometry.imageMatrix(w, h, picture.width.toFloat() / picture.height.coerceAtLeast(1), spec.size, spec.angle)
                cs.transform(Matrix(m.a, m.b, m.c, m.d, m.e, m.f))
                cs.drawImage(picture, 0f, 0f, 1f, 1f)
            } else {
                val fs = WatermarkGeometry.fontSize(w, h, unit, spec.angle, spec.size)
                val m = WatermarkGeometry.textMatrix(w, h, unit * fs, fs, spec.angle)
                cs.setNonStrokingColor(rgb(spec.color))
                cs.beginText()
                cs.setFont(font, fs)
                cs.setTextMatrix(Matrix(m.a, m.b, m.c, m.d, m.e, m.f))
                cs.showText(text)
                cs.endText()
            }
        }
    }

    fun pageNumbers(doc: PDDocument, spec: NumberSpec) {
        val font = PDType1Font.HELVETICA
        val count = doc.numberOfPages
        doc.pages.forEachIndexed { i, page ->
            onPage(doc, page) { cs, w, h ->
                val label = PageNumbers.label(spec.format, i, count, spec.start)
                val tw = font.getStringWidth(label) / 1000f * spec.fontSize
                val at = PageNumbers.anchor(spec.pos, w, h, tw, spec.fontSize)
                cs.setNonStrokingColor(rgb(0x222222))
                cs.beginText()
                cs.setFont(font, spec.fontSize)
                cs.setTextMatrix(Matrix.getTranslateInstance(at.x, at.y))
                cs.showText(label)
                cs.endText()
            }
        }
    }

    /**
     * Apply [change] to the PDF behind [src] and open the result. Saves a copy called
     * "name (suffix)", or replaces the library document when [replace] is set. Problems are
     * shown as a toast; returns whether it worked.
     */
    suspend fun save(ctx: Context, nav: Nav, src: PdfSource, suffix: String, replace: Boolean, done: String, change: (PDDocument, UnicodeFonts) -> Unit): Boolean {
        val local = src.ref as? PdfRef.Local
        return try {
            val name = uniqueDocName(PdfOps.baseName(src.ref), suffix, Library.docs.value.map { it.name }.toSet())
            val d = PdfOps.edit(ctx, src.ref, src.password, name, if (replace) local?.docId else null) { doc -> change(doc, UnicodeFonts(doc)) }
            nav.popTo(Screen.Home)
            nav.push(Screen.Viewer(PdfRef.Local(d.id, d.name)))
            toast(ctx, done)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: PasswordRequired) {
            toast(ctx, "This PDF is locked. Open it with its password first."); false
        } catch (e: PdfOps.OpError) {
            toast(ctx, e.message ?: "Couldn't save"); false
        } catch (e: Exception) {
            toast(ctx, "Couldn't save: ${e.message ?: "unknown error"}"); false
        }
    }
}

/** "Save a copy" or, for a document in the library, "Replace the original" (asked twice). */
@Composable
fun SaveChoiceDialog(title: String, body: String, canReplace: Boolean, onDismiss: () -> Unit, onSave: (replace: Boolean) -> Unit) {
    var sure by remember { mutableStateOf(false) }
    if (!sure) AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { Text(body, color = WizMuted) } },
        confirmButton = { TextButton(onClick = { onSave(false) }) { Text("Save a copy", color = WizYellow, fontWeight = FontWeight.Bold) } },
        dismissButton = {
            if (canReplace) TextButton(onClick = { sure = true }) { Text("Replace original", color = WizMuted) }
            else TextButton(onClick = onDismiss) { Text("Cancel", color = WizMuted) }
        },
        containerColor = WizPanel
    ) else AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace the original?") },
        text = { Text("The current version is overwritten and can't be brought back.", color = WizMuted) },
        confirmButton = { TextButton(onClick = { onSave(true) }) { Text("Replace", color = WizBad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
}
