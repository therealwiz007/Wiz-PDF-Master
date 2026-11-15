package com.wizlabs.pdfmaster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Page orientation from on-device text recognition, and the draft that keeps an unfinished scan. */
@RunWith(AndroidJUnit4::class)
class ScanIntakeTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A page of printed text, tilted clockwise by [tilt] degrees. */
    private fun textPage(tilt: Float = 0f): Bitmap {
        val bmp = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 38f }
        c.rotate(tilt, 500f, 700f)
        val lines = listOf(
            "Invoice number 20481 for the month of March", "Please pay the amount shown below within", "thirty days of the date printed on this page.",
            "Total amount due: 1,284.50", "Thank you for your business and your trust.", "Questions? Write to the address on the back.",
            "Delivery address: 14 Station Road, Riverside", "Order placed on Monday and shipped on Friday"
        )
        lines.forEachIndexed { i, s -> c.drawText(s, 110f, 220f + i * 120f, p) }
        return bmp
    }

    private fun turned(b: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees) }, true)

    @Test fun uprightTextNeedsNoFix() = runBlocking {
        assertTrue(ScanEngine.ensure())
        assertEquals(PageAngle.Fix(0, 0f), Ocr.pageAngle(textPage()))
    }

    @Test fun sidewaysAndUpsideDownPagesAreTurnedUpright() = runBlocking {
        assertTrue(ScanEngine.ensure())
        // The page was turned clockwise by 90°, 180°, 270°: the fix is the turn that completes the circle.
        for (deg in intArrayOf(90, 180, 270)) {
            val fix = Ocr.pageAngle(turned(textPage(), deg.toFloat()))
            assertNotNull("text found at $deg", fix)
            assertEquals("turn for $deg", 360 - deg, fix!!.rotate)
        }
    }

    @Test fun tiltIsMeasuredWithItsSign() = runBlocking {
        assertTrue(ScanEngine.ensure())
        for (tilt in floatArrayOf(4f, -3f)) {
            val fix = Ocr.pageAngle(textPage(tilt))
            assertNotNull(fix)
            assertEquals(0, fix!!.rotate)
            assertEquals("tilt $tilt", tilt, fix.skew, 0.8f)
        }
    }

    @Test fun unfinishedScanIsWrittenAndComesBack() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ScanSession.clear(ctx) }
        val dir = ScanSession.dir(ctx)
        val photo = File(dir, "1.jpg")
        ScanEngine.saveJpeg(textPage(), photo)
        val quad = Quad(P(.1f, .1f), P(.9f, .1f), P(.9f, .9f), P(.1f, .9f))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScanSession.idCard.value = true
            ScanSession.add(ctx, ScanPage(original = photo, quad = quad, rotation = 90, filter = ScanFilter.AUTO, skew = 1.5f, autoPick = ScanFilter.BW, card = true))
            ScanSession.update(ctx, 0) { it.copy(rotation = 180) }
        }
        val file = File(dir, "draft.txt")
        var waited = 0
        while (!file.exists() && waited < 5000) { delay(100); waited += 100 }
        val draft = DraftCodec.decode(file.readText())
        assertNotNull(draft)
        assertTrue(draft!!.idCard)
        val page = draft.pages.single()
        assertEquals("1.jpg", page.file); assertEquals(quad, page.quad); assertEquals(180, page.rotation)
        assertEquals("AUTO", page.filter); assertEquals("BW", page.autoPick); assertEquals(1.5f, page.skew, 0f); assertTrue(page.card)

        // As after the app was killed: the page list is gone from memory, the draft is offered again.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScanSession.pages.clear(); ScanSession.idCard.value = false
            ScanSession.pending.value = draft
            assertTrue(ScanSession.resume(ctx))
            assertEquals(1, ScanSession.pages.size)
            val back = ScanSession.pages[0]
            assertEquals(photo, back.original); assertEquals(quad, back.quad); assertEquals(ScanFilter.BW, back.look)
            assertTrue(ScanSession.idCard.value)
            // Saving or discarding removes every trace.
            ScanSession.clear(ctx)
        }
        assertTrue(!photo.exists() && !file.exists())
    }

    @Test fun savesSearchablePdfAndIdCardSheet() = runBlocking {
        assertTrue(ScanEngine.ensure())
        val dir = File(ctx.cacheDir, "intake-test").apply { mkdirs() }
        val a = File(dir, "a.jpg"); val b = File(dir, "b.jpg")
        ScanEngine.saveJpeg(textPage(), a); ScanEngine.saveJpeg(textPage(2f), b)
        val quad = Quad(P(.05f, .05f), P(.95f, .05f), P(.95f, .95f), P(.05f, .95f))
        val made = ArrayList<Doc>()
        try {
            // Black & white page: stored as 1-bit, yet the text is read (from the gray page) and searchable.
            val pages = listOf(ScanPage(original = a, quad = Quad.FULL, filter = ScanFilter.BW), ScanPage(original = b, quad = quad, filter = ScanFilter.AUTO, skew = 2f))
            val doc = PdfBuilder.build(ctx, pages, "Intake test", ocr = true, pageSize = PageSize.A4) {}
            made += doc
            assertEquals(2, doc.pages); assertTrue(doc.searchable)
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(Library.fileOf(ctx, doc.id)).use { pdf ->
                val text = com.tom_roush.pdfbox.text.PDFTextStripper().getText(pdf)
                assertTrue(text, text.contains("Invoice", ignoreCase = true))
            }
            // ID card: both sides on a single A4 sheet.
            val cards = listOf(ScanPage(original = a, quad = quad, filter = ScanFilter.MAGIC, card = true), ScanPage(original = b, quad = quad, filter = ScanFilter.MAGIC, card = true))
            val id = PdfBuilder.build(ctx, cards, "Intake test ID", ocr = false, pageSize = PageSize.A4, idCard = true) {}
            made += id
            assertEquals(1, id.pages)
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(Library.fileOf(ctx, id.id)).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                assertEquals(595f, pdf.getPage(0).mediaBox.width, 1f)
                assertEquals(2, pdf.getPage(0).resources.xObjectNames.count())
            }
            assertTrue(Library.thumbOf(ctx, id.id).length() > 0)
            // Lock it, then unlock it again.
            val locked = PdfOps.setPassword(ctx, PdfRef.Local(id.id, id.name), null, PdfOps.Protection("pw-1234"), "Intake test locked")
            made += locked.doc
            assertTrue("AES-${locked.bits}", locked.bits == 256 || locked.bits == 128)
            val open = PdfOps.setPassword(ctx, PdfRef.Local(locked.doc.id, locked.doc.name), "pw-1234", null, "Intake test open")
            made += open.doc
            assertEquals(0, open.bits)
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(Library.fileOf(ctx, open.doc.id)).use { assertTrue(!it.isEncrypted) }
        } finally {
            made.forEach { Library.delete(ctx, it.id) }
            dir.deleteRecursively()
        }
    }
}
