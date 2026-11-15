package com.wizlabs.pdfmaster

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Stamping and form filling with PDFBox on a plain JVM. */
class PdfDecorTest {
    private fun roundTrip(doc: PDDocument): PDDocument {
        val bytes = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        doc.close()
        return PDDocument.load(bytes)
    }

    private fun content(page: PDPage): String = page.contentStreams.asSequence().joinToString("\n") { String(it.toByteArray(), Charsets.ISO_8859_1) }

    @Test fun notesWrapAtWords() {
        assertEquals(listOf("one two", "three"), PdfDecor.wrap("one two three", 8))
        assertEquals(listOf("abcdefgh", "ij"), PdfDecor.wrap("abcdefghij", 8))
        assertEquals(listOf("a", "", "b"), PdfDecor.wrap("a\n\nb", 8))
    }

    @Test fun highlightsAndStrokesAreDrawn() {
        val doc = PDDocument().apply { repeat(2) { addPage(PDPage(PDRectangle.A4)) } }
        PdfDecor.drawMarks(
            doc, listOf(
                Mark.Box(0, 0.1f, 0.1f, 0.5f, 0.15f),
                Mark.Stroke(1, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), 0xFF0000, 0.004f, false),
                Mark.Box(9, 0f, 0f, 1f, 1f)                   // a page that doesn't exist is skipped
            )
        )
        roundTrip(doc).use { out ->
            assertTrue(content(out.getPage(0)).contains(" re\n"))
            assertTrue(content(out.getPage(1)).contains(" l\n"))
            assertFalse(content(out.getPage(1)).contains(" re\n"))
        }
    }

    @Test fun plainPdfHasNoForm() {
        PDDocument().use { it.addPage(PDPage()); assertTrue(PdfForms.read(it) is PdfForms.Info.None) }
    }

    @Test fun draftsRememberTheCurveSwitch() {
        val page = DraftPage("a", "1.jpg", Quad.FULL, 0, "AUTO", curve = true)
        val text = DraftCodec.encode(Draft(false, 0, listOf(page)))
        assertTrue(DraftCodec.decode(text)!!.pages[0].curve)
        // A draft written by 1.4 (one field fewer) still loads.
        val old = text.lines().joinToString("\n") { if (it.startsWith("page")) it.substringBeforeLast('\t') else it }
        assertFalse(DraftCodec.decode(old)!!.pages[0].curve)
    }
}
