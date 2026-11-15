package com.wizlabs.pdfmaster

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Stamping, form filling and phone fonts: these need PDFBox's fonts, which only load on a device. */
@RunWith(AndroidJUnit4::class)
class PdfDecorDeviceTest {
    @Before fun setUp() { PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().targetContext) }

    private fun roundTrip(doc: PDDocument): PDDocument {
        val bytes = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        doc.close()
        return PDDocument.load(bytes)
    }

    private fun content(page: PDPage): String = page.contentStreams.asSequence().joinToString("\n") { String(it.toByteArray(), Charsets.ISO_8859_1) }

    @Test fun pageNumbersGoOnEveryPage() {
        val doc = PDDocument().apply { repeat(3) { addPage(PDPage(PDRectangle.A4)) } }
        doc.getPage(1).rotation = 90
        PdfDecor.pageNumbers(doc, NumberSpec(NumberPos.BOTTOM_CENTER, NumberFormat.PAGE_OF, 1, 11f))
        roundTrip(doc).use { out ->
            assertEquals(3, out.numberOfPages)
            (0 until 3).forEach { assertTrue(content(out.getPage(it)).contains("(Page ${it + 1} of 3) Tj")) }
        }
    }

    @Test fun watermarkAndMarksAreDrawn() {
        val doc = PDDocument().apply { repeat(2) { addPage(PDPage(PDRectangle.A4)) } }
        PdfDecor.watermark(doc, WatermarkSpec("DRAFT", 0.7f, 0.25f, 45f, 0x808080))
        PdfDecor.drawMarks(
            doc, listOf(
                Mark.Box(0, 0.1f, 0.1f, 0.5f, 0.15f),
                Mark.Stroke(0, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), 0xFF0000, 0.004f, false),
                Mark.Note(1, 0.2f, 0.2f, "Check this"),
                Mark.Box(9, 0f, 0f, 1f, 1f)                   // a page that doesn't exist is skipped
            )
        )
        roundTrip(doc).use { out ->
            val first = content(out.getPage(0)); val second = content(out.getPage(1))
            assertTrue(first.contains("(DRAFT) Tj") && second.contains("(DRAFT) Tj"))
            assertTrue(first.contains(" re") && first.contains(" l\n"))
            assertTrue(second.contains("(Check this) Tj"))
            assertFalse(first.contains("Check this"))
        }
        assertTrue(runCatching { PDDocument().use { PdfDecor.watermark(it.apply { addPage(PDPage()) }, WatermarkSpec("  ", 0.7f, 0.2f, 0f, 0)) } }.exceptionOrNull() is PdfOps.OpError)
    }

    private fun formDoc(): PDDocument {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4).also { doc.addPage(it) }
        val form = PDAcroForm(doc).also { doc.documentCatalog.acroForm = it }
        form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
        form.defaultAppearance = "/Helv 0 Tf 0 g"
        val name = PDTextField(form).apply { partialName = "name"; defaultAppearance = "/Helv 12 Tf 0 g" }
        name.widgets[0].apply { rectangle = PDRectangle(50f, 700f, 200f, 20f); setPage(page) }
        val agree = PDCheckBox(form).apply { partialName = "agree" }
        agree.widgets[0].apply { rectangle = PDRectangle(50f, 650f, 14f, 14f); setPage(page) }
        page.annotations.addAll(listOf<PDAnnotationWidget>(name.widgets[0], agree.widgets[0]))
        form.fields.addAll(listOf(name, agree))
        return doc
    }

    @Test fun formFieldsAreReadAndFilled() {
        val doc = formDoc()
        val info = PdfForms.read(doc) as PdfForms.Info.Fields
        assertEquals(listOf("name" to FieldKind.TEXT, "agree" to FieldKind.CHECK), info.list.map { it.name to it.kind })
        val missed = PdfForms.fill(doc, info.list, mapOf("name" to "Asha Rao"), flatten = false)
        assertEquals(0, missed)
        roundTrip(doc).use { out ->
            val again = PdfForms.read(out) as PdfForms.Info.Fields
            assertEquals("Asha Rao", again.list.first { it.name == "name" }.value)
        }
    }

    @Test fun flattenedFormHasNoFieldsLeft() {
        val doc = formDoc()
        val info = PdfForms.read(doc) as PdfForms.Info.Fields
        PdfForms.fill(doc, info.list, mapOf("name" to "Asha"), flatten = true)
        roundTrip(doc).use { out ->
            assertTrue(PdfForms.read(out) is PdfForms.Info.None)
            // The answer is now page content (a form XObject), no longer a field.
            assertTrue(com.tom_roush.pdfbox.text.PDFTextStripper().getText(out).contains("Asha"))
        }
    }

    /** A phone font for Devanagari can be embedded as a subset and the text comes back out of the saved PDF. */
    @Test fun devanagariNoteUsesAPhoneFont() {
        val doc = PDDocument().apply { addPage(PDPage(PDRectangle.A4)) }
        val fonts = UnicodeFonts(doc)
        val font = fonts.font(Script.DEVANAGARI)
        org.junit.Assume.assumeTrue("no embeddable Devanagari font on this device", font != null)
        assertNotNull(font)
        PdfDecor.drawMarks(doc, listOf(Mark.Note(0, 0.2f, 0.2f, "\u0928\u092e\u0938\u094d\u0924\u0947")), fonts)
        roundTrip(doc).use { out ->
            val text = com.tom_roush.pdfbox.text.PDFTextStripper().getText(out)
            assertTrue(text, text.contains("\u0928"))
        }
    }

    @Test fun curveFlatteningStraightensBentLines() {
        org.junit.Assume.assumeTrue(ScanEngine.ensure())
        // Twelve dark bars that sag in the middle, like text lines on a bulging page.
        val w = 800; val h = 1000
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 8f; style = android.graphics.Paint.Style.STROKE }
        for (i in 1..12) {
            val path = android.graphics.Path()
            for (x in 60..740 step 4) {
                val t = (x - 400) / 340f
                val y = i * 75f + 22f * (1 - t * t)
                if (x == 60) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
            }
            c.drawPath(path, p)
        }
        val model = CurveFlatten.detect(bmp)
        assertNotNull(model)
        assertTrue(model!!.lines.size >= 8)
        val flat = CurveFlatten.apply(bmp, model)
        // After flattening, each bar is level: the same row is dark at the left, middle and right.
        val again = CurveFlatten.detect(flat)
        assertTrue(again == null || CurveMath.maxBend(again) < CurveMath.maxBend(model) / 3)
        assertEquals(w, flat.width); assertEquals(h, flat.height)
        assertFalse(flat === bmp)
    }
}
