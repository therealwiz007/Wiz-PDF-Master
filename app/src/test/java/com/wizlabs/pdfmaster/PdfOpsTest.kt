package com.wizlabs.pdfmaster

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.filter.FilterFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** PDFBox logic that runs on a plain JVM (no Android classes on these code paths). */
class PdfOpsTest {
    private fun docWithPages(n: Int): PDDocument = PDDocument().apply {
        repeat(n) { addPage(PDPage(PDRectangle(100f + it, 200f))) }
    }

    private fun roundTrip(doc: PDDocument): PDDocument {
        val bytes = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        doc.close()
        return PDDocument.load(bytes)
    }

    @Test fun reordersDeletesAndRotates() {
        val doc = docWithPages(5)
        PdfOps.arrange(doc, listOf(PdfOps.PageOp(3), PdfOps.PageOp(0, 90), PdfOps.PageOp(4, 270)))
        roundTrip(doc).use { out ->
            assertEquals(3, out.numberOfPages)
            assertEquals(listOf(103f, 100f, 104f), (0 until 3).map { out.getPage(it).mediaBox.width })
            assertEquals(listOf(0, 90, 270), (0 until 3).map { out.getPage(it).rotation })
        }
    }

    @Test fun rotationAddsToExisting() {
        val doc = docWithPages(1)
        doc.getPage(0).rotation = 270
        PdfOps.arrange(doc, listOf(PdfOps.PageOp(0, 180)))
        roundTrip(doc).use { assertEquals(90, it.getPage(0).rotation) }
    }

    @Test fun rejectsBadPageLists() {
        docWithPages(2).use { doc ->
            assertTrue(runCatching { PdfOps.arrange(doc, emptyList()) }.exceptionOrNull() is PdfOps.OpError)
            assertTrue(runCatching { PdfOps.arrange(doc, listOf(PdfOps.PageOp(5))) }.exceptionOrNull() is PdfOps.OpError)
            assertTrue(runCatching { PdfOps.arrange(doc, listOf(PdfOps.PageOp(0), PdfOps.PageOp(0))) }.exceptionOrNull() is PdfOps.OpError)
        }
    }

    @Test fun faxCodingRoundTripsWithPdfPolarity() {
        // 16 x 4 picture: a black bar on white paper.
        val w = 16; val h = 4
        val white = 0xFFFFFFFF.toInt(); val black = 0xFF000000.toInt()
        val px = IntArray(w * h) { i -> if (i % w in 3..9 && i / w in 1..2) black else white }
        val rb = Bilevel.rowBytes(w)
        val forFax = ByteArray(rb * h); val forGray = ByteArray(rb * h)
        for (y in 0 until h) {
            Bilevel.packRow(px, y * w, w, forFax, y * rb, 128, blackIsOne = true)
            Bilevel.packRow(px, y * w, w, forGray, y * rb, 128, blackIsOne = false)
        }
        val encoded = BilevelCodec.g4(forFax, w, h)
        // Decode the way a PDF reader would: the stream dictionary BilevelCodec.describe writes.
        val dict = COSDictionary()
        BilevelCodec.describe(dict, BilevelCodec.Encoded(encoded, true), w, h)
        val decoded = ByteArrayOutputStream()
        FilterFactory.INSTANCE.getFilter(COSName.CCITTFAX_DECODE).decode(ByteArrayInputStream(encoded), decoded, dict, 0)
        // In 1-bit DeviceGray a 1 is white, so the decoded bits must equal the white-is-one packing.
        assertArrayEquals(forGray, decoded.toByteArray())
    }

    @Test fun deflateFallbackIsLossless() {
        val data = ByteArray(4000) { (it % 7).toByte() }
        val packed = BilevelCodec.deflate(data)
        assertTrue(packed.size < data.size)
        val inflater = java.util.zip.Inflater().apply { setInput(packed) }
        val back = ByteArray(data.size)
        assertEquals(data.size, inflater.inflate(back))
        assertArrayEquals(data, back)
    }

    // ---- Passwords ------------------------------------------------------------------------------

    private fun protectedBytes(p: PdfOps.Protection, bits: Int): ByteArray {
        val doc = docWithPages(2)
        PdfOps.applyProtection(doc, p, bits)
        return ByteArrayOutputStream().also { doc.save(it) }.toByteArray().also { doc.close() }
    }

    private fun opensWithout(bytes: ByteArray): Boolean = try {
        PDDocument.load(bytes).close(); true
    } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
        false
    }

    @Test fun passwordLocksAndUnlocks() {
        for (bits in intArrayOf(128, 256)) {
            val bytes = protectedBytes(PdfOps.Protection("s3cret"), bits)
            assertTrue("needs a password at $bits", !opensWithout(bytes))
            PDDocument.load(bytes, "s3cret").use { doc ->
                assertTrue(doc.isEncrypted)
                assertEquals(2, doc.numberOfPages)
                assertEquals(bits, doc.encryption.length)
                // No limits were asked for: the password gives full rights, so it can be removed again.
                assertTrue(doc.currentAccessPermission.isOwnerPermission)
                doc.isAllSecurityToBeRemoved = true
                val plain = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                assertTrue(opensWithout(plain))
            }
        }
    }

    @Test fun limitsApplyToTheOpenPassword() {
        val bytes = protectedBytes(PdfOps.Protection("open", "boss", allowPrint = false, allowCopy = false), 128)
        PDDocument.load(bytes, "open").use { doc ->
            val p = doc.currentAccessPermission
            assertTrue(!p.isOwnerPermission && !p.canPrint() && !p.canExtractContent() && p.canModify())
        }
        PDDocument.load(bytes, "boss").use { assertTrue(it.currentAccessPermission.isOwnerPermission) }
    }

    @Test fun limitsWithoutOwnerPasswordCannotBeLifted() {
        val bytes = protectedBytes(PdfOps.Protection("open", allowEdit = false), 128)
        PDDocument.load(bytes, "open").use { doc ->
            assertTrue(!doc.currentAccessPermission.isOwnerPermission && !doc.currentAccessPermission.canModify())
        }
    }
}
