package com.wizlabs.pdfmaster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProLogicTest {

    // ---- Batch rename

    @Test fun renameFillsPlaceholders() {
        assertEquals("Invoice_3", RenamePattern.render("Invoice_{n}", "Scan", "2026-11-15", 3))
        assertEquals("Scan 2026-11-15", RenamePattern.render("{name} {date}", "Scan", "2026-11-15", 1))
        // A name that itself contains a placeholder is not expanded again.
        assertEquals("a{n}b-2", RenamePattern.render("{name}-{n}", "a{n}b", "", 2))
    }

    @Test fun renamePlanNumbersInOrder() {
        val out = RenamePattern.plan("Invoice_{n}", listOf("a", "b", "c"), listOf("", "", ""), emptySet())
        assertEquals(listOf("Invoice_1", "Invoice_2", "Invoice_3"), out)
        assertEquals(listOf("Invoice_7", "Invoice_8"), RenamePattern.plan("Invoice_{n}", listOf("a", "b"), listOf("", ""), emptySet(), start = 7))
    }

    @Test fun renamePlanAvoidsClashes() {
        val out = RenamePattern.plan("Report", listOf("a", "b", "c"), listOf("", "", ""), setOf("Report", "Report (3)"))
        assertEquals(listOf("Report (2)", "Report (4)", "Report (5)"), out)
        assertEquals(out.size, out.toSet().size)
        // A pattern that renders to nothing keeps the old name.
        assertEquals(listOf("a"), RenamePattern.plan("{date}", listOf("a"), listOf(""), emptySet()))
    }

    // ---- Page numbers

    @Test fun pageNumberLabels() {
        assertEquals("1", PageNumbers.label(NumberFormat.PLAIN, 0, 5))
        assertEquals("3 / 5", PageNumbers.label(NumberFormat.SLASH, 2, 5))
        assertEquals("Page 5 of 5", PageNumbers.label(NumberFormat.PAGE_OF, 4, 5))
        assertEquals("Page 12 of 14", PageNumbers.label(NumberFormat.PAGE_OF, 2, 5, start = 10))
    }

    @Test fun pageNumberPositions() {
        val w = 595f; val h = 842f; val tw = 40f; val fs = 11f
        val m = PageNumbers.margin(w, h)
        val bc = PageNumbers.anchor(NumberPos.BOTTOM_CENTER, w, h, tw, fs)
        assertEquals((w - tw) / 2f, bc.x, 0.01f); assertEquals(m, bc.y, 0.01f)
        val br = PageNumbers.anchor(NumberPos.BOTTOM_RIGHT, w, h, tw, fs)
        assertEquals(w - m - tw, br.x, 0.01f)
        val tc = PageNumbers.anchor(NumberPos.TOP_CENTER, w, h, tw, fs)
        assertTrue(tc.y > h - m - fs && tc.y < h - m)
        // Tiny pages get a smaller margin and never a negative start.
        val tiny = PageNumbers.anchor(NumberPos.BOTTOM_RIGHT, 30f, 30f, 60f, 8f)
        assertEquals(0f, tiny.x, 0.001f)
    }

    // ---- Watermark

    @Test fun watermarkIsCentred() {
        val w = 595f; val h = 842f; val tw = 300f; val fs = 60f
        for (deg in floatArrayOf(0f, 45f, 90f, -30f)) {
            val m = WatermarkGeometry.textMatrix(w, h, tw, fs, deg)
            val mid = m.map(tw / 2f, fs * 0.35f)
            assertEquals(w / 2f, mid.x, 0.05f); assertEquals(h / 2f, mid.y, 0.05f)
        }
        // 45 degrees: the end of the text is up and to the right of its start.
        val m = WatermarkGeometry.textMatrix(w, h, tw, fs, 45f)
        val a = m.map(0f, 0f); val b = m.map(tw, 0f)
        assertTrue(b.x > a.x && b.y > a.y)
        assertEquals(b.x - a.x, b.y - a.y, 0.05f)
    }

    @Test fun watermarkSizeFitsThePage() {
        val w = 595f; val h = 842f; val unit = 6f
        for (deg in floatArrayOf(0f, 45f, 90f)) {
            val fs = WatermarkGeometry.fontSize(w, h, unit, deg, 0.8f)
            val r = Math.toRadians(deg.toDouble())
            val bw = unit * fs * abs(Math.cos(r)) + fs * abs(Math.sin(r))
            val bh = unit * fs * abs(Math.sin(r)) + fs * abs(Math.cos(r))
            assertTrue(bw <= 0.8 * w + 0.5 && bh <= 0.8 * h + 0.5)
            assertTrue(bw >= 0.8 * w - 0.5 || bh >= 0.8 * h - 0.5)
        }
    }

    @Test fun watermarkImageKeepsItsShape() {
        val m = WatermarkGeometry.imageMatrix(600f, 800f, 2f, 0.5f, 0f)
        assertEquals(300f, m.a, 0.01f); assertEquals(150f, m.d, 0.01f)
        val mid = m.map(0.5f, 0.5f)
        assertEquals(300f, mid.x, 0.01f); assertEquals(400f, mid.y, 0.01f)
        // A very tall picture is limited by the page height instead.
        val tall = WatermarkGeometry.imageMatrix(600f, 800f, 0.1f, 0.5f, 0f)
        assertEquals(400f, tall.d, 0.01f); assertEquals(40f, tall.a, 0.01f)
    }

    // ---- Scanner choice

    @Test fun scannerChoice() {
        assertEquals(ScannerKind.SMART, ScannerChoice.pick(null, playServices = true, idCard = false))
        assertEquals(ScannerKind.CLASSIC, ScannerChoice.pick(null, playServices = false, idCard = false))
        assertEquals(ScannerKind.CLASSIC, ScannerChoice.pick(ScannerKind.SMART, playServices = false, idCard = false))
        assertEquals(ScannerKind.CLASSIC, ScannerChoice.pick(ScannerKind.SMART, playServices = true, idCard = true))
        assertEquals(ScannerKind.CLASSIC, ScannerChoice.pick(ScannerKind.CLASSIC, playServices = true, idCard = false))
        assertEquals(ScannerKind.SMART, ScannerChoice.pick(ScannerKind.SMART, playServices = true, idCard = false))
        assertNull(ScannerChoice.decode(null)); assertNull(ScannerChoice.decode("x"))
        assertEquals(ScannerKind.CLASSIC, ScannerChoice.decode("CLASSIC"))
    }

    @Test fun scannerFallback() {
        assertFalse(ScannerChoice.fallBack(resultOk = true, cancelled = false, pages = 2))
        assertFalse(ScannerChoice.fallBack(resultOk = false, cancelled = true, pages = 0))
        assertTrue(ScannerChoice.fallBack(resultOk = false, cancelled = false, pages = 0))
        assertTrue(ScannerChoice.fallBack(resultOk = true, cancelled = false, pages = 0))
    }

    // ---- OCR languages

    @Test fun ocrLanguagesSurviveSaving() {
        assertEquals(setOf(OcrLang.LATIN), OcrLangs.decode(null))
        assertEquals(setOf(OcrLang.LATIN), OcrLangs.decode("nonsense,,"))
        val chosen = setOf(OcrLang.KOREAN, OcrLang.DEVANAGARI)
        val text = OcrLangs.encode(chosen)
        assertEquals("LATIN,DEVANAGARI,KOREAN", text)
        assertEquals(chosen + OcrLang.LATIN, OcrLangs.decode(text))
        // Latin can't be switched off.
        assertTrue(OcrLang.LATIN in OcrLangs.decode(OcrLangs.encode(emptySet())))
    }

    @Test fun ocrKeepsLatinUnlessClearlyBeaten() {
        assertEquals(-1, OcrLangs.best(emptyList()))
        assertEquals(0, OcrLangs.best(listOf(100f)))
        assertEquals(0, OcrLangs.best(listOf(100f, 105f)))
        assertEquals(1, OcrLangs.best(listOf(100f, 140f)))
        assertEquals(2, OcrLangs.best(listOf(10f, 140f, 150f)))
        assertEquals(1, OcrLangs.best(listOf(0f, 3f)))
        assertEquals(50f, OcrLangs.score(listOf(40 to 1f, 20 to 0.5f)), 0.001f)
    }

    @Test fun scriptsAreToldApart() {
        assertEquals(Script.LATIN, OcrLangs.scriptOf("Café €5"))
        assertEquals(Script.DEVANAGARI, OcrLangs.scriptOf("नमस्ते"))
        assertEquals(Script.CJK, OcrLangs.scriptOf("日本語"))
        assertEquals(Script.CJK, OcrLangs.scriptOf("한국어"))
        assertEquals(Script.CJK, OcrLangs.scriptOf("No.中"))
        assertEquals(Script.OTHER, OcrLangs.scriptOf("Привет"))
    }

    // ---- Curved pages

    @Test fun quadraticFitIsExact() {
        val xs = FloatArray(9) { it / 8f }
        val ys = FloatArray(9) { 0.2f + 0.1f * xs[it] - 0.3f * xs[it] * xs[it] }
        val c = CurveMath.fitQuadratic(xs, ys)!!
        assertEquals(0.2, c[0], 1e-4); assertEquals(0.1, c[1], 1e-4); assertEquals(-0.3, c[2], 1e-4)
        assertNull(CurveMath.fitQuadratic(floatArrayOf(0f, 1f), floatArrayOf(0f, 1f)))
        assertNull(CurveMath.fitQuadratic(floatArrayOf(.5f, .5f, .5f), floatArrayOf(0f, 1f, 2f)))
    }

    private fun bowed(level: Float, bend: Float) =
        // y = level + bend·(4(x-½)² - ⅓): sags at both ends, average exactly [level].
        CurveMath.Line(doubleArrayOf(level + bend * (1.0 - 1.0 / 3), -4.0 * bend, 4.0 * bend), 0f, 1f)

    @Test fun straightenedLinesComeOutFlat() {
        val lines = (1..8).map { bowed(it / 10f, 0.02f) }
        val m = CurveMath.build(lines)
        assertEquals(8, m.lines.size)
        assertTrue(CurveMath.confident(m))
        for (l in lines) {
            assertEquals(l.level.toDouble(), (l.c[0] + l.c[1] / 2 + l.c[2] / 3), 2e-3)
            for (x in floatArrayOf(0f, 0.25f, 0.5f, 1f)) {
                // Reading from (level + displacement) lands exactly on the bent line.
                assertEquals(l.at(x), l.level + CurveMath.displacement(m, x, l.level), 1e-4f)
            }
        }
        // Between two lines the bend is blended; above the first it is held.
        val mid = CurveMath.displacement(m, 0f, 0.15f)
        assertEquals(CurveMath.displacement(m, 0f, 0.1f), mid, 1e-3f)
        assertEquals(CurveMath.displacement(m, 0f, 0.1f), CurveMath.displacement(m, 0f, 0f), 1e-6f)
    }

    @Test fun flatOrWildPagesAreLeftAlone() {
        assertFalse(CurveMath.confident(CurveMath.build((1..8).map { bowed(it / 10f, 0.0002f) })))   // already straight
        assertFalse(CurveMath.confident(CurveMath.build((1..8).map { bowed(it / 10f, 0.4f) })))      // not a page bend
        assertFalse(CurveMath.confident(CurveMath.build((1..3).map { bowed(it / 10f, 0.02f) })))     // too few lines
        assertFalse(CurveMath.confident(CurveMath.build((1..8).map { bowed(0.4f + it / 100f, 0.02f) })))   // one small patch
    }

    @Test fun oddLinesAreDropped() {
        val lines = (1..8).map { bowed(it / 10f, 0.02f) } + bowed(0.85f, -0.3f)
        val m = CurveMath.build(lines)
        assertEquals(8, m.lines.size)
        val g = CurveMath.grid(m, 5, 4)
        assertEquals(20, g.size)
        assertEquals(CurveMath.displacement(m, 1f, 1f), g[19], 1e-6f)
    }

    // ---- QR

    @Test fun qrLinksAndText() {
        assertEquals(QrContent.Url("https://example.com/a?b=1"), QrParse.parse(" https://example.com/a?b=1 "))
        assertEquals(QrContent.Url("https://www.example.com"), QrParse.parse("www.example.com"))
        assertTrue(QrParse.parse("see https://example.com now") is QrContent.Text)
        assertTrue(QrParse.parse("hello") is QrContent.Text)
    }

    @Test fun qrWifi() {
        val w = QrParse.parse("WIFI:T:WPA;S:My\\;Net;P:pa\\:ss\\\\word;H:true;;") as QrContent.Wifi
        assertEquals("My;Net", w.ssid); assertEquals("pa:ss\\word", w.password); assertEquals("WPA", w.security); assertTrue(w.hidden)
        val open = QrParse.parse("WIFI:S:Cafe;T:nopass;;") as QrContent.Wifi
        assertEquals("Open", open.security); assertEquals("", open.password)
        assertTrue(QrParse.plain(w, "").contains("pa:ss\\word"))
    }

    @Test fun qrContacts() {
        val v = QrParse.parse("BEGIN:VCARD\nVERSION:3.0\nN:Rao;Asha;;;\nFN:Asha Rao\nORG:Wiz Labs\nTEL;TYPE=CELL:+91 98765 43210\nEMAIL:asha@example.com\nEND:VCARD") as QrContent.Contact
        assertEquals("Asha Rao", v.name); assertEquals(listOf("+91 98765 43210"), v.phones)
        assertEquals(listOf("asha@example.com"), v.emails); assertEquals("Wiz Labs", v.org)
        val m = QrParse.parse("MECARD:N:Rao,Asha;TEL:123;TEL:456;EMAIL:a@b.co;;") as QrContent.Contact
        assertEquals("Asha Rao", m.name); assertEquals(listOf("123", "456"), m.phones)
    }

    @Test fun qrOtherKinds() {
        assertEquals(QrContent.Phone("+123"), QrParse.parse("tel:+123"))
        assertEquals(QrContent.Sms("123", "hi there"), QrParse.parse("SMSTO:123:hi there"))
        assertEquals(QrContent.Geo("12.5", "77.1"), QrParse.parse("geo:12.5,77.1?q=x"))
        assertEquals(QrContent.Email("a@b.co", "Hi you", "Body"), QrParse.parse("mailto:a@b.co?subject=Hi%20you&body=Body"))
        assertEquals(QrContent.Email("a@b.co", "S", "B"), QrParse.parse("MATMSG:TO:a@b.co;SUB:S;BODY:B;;"))
    }

    // ---- Annotations

    @Test fun annotationPointsFollowPageRotation() {
        // Upright A4-ish page: top-left of the display is the top-left of the box.
        var p = AnnotGeometry.toUser(0f, 0f, 0, 10f, 20f, 200f, 400f)
        assertEquals(10f, p.x, 0.01f); assertEquals(420f, p.y, 0.01f)
        p = AnnotGeometry.toUser(1f, 1f, 0, 10f, 20f, 200f, 400f)
        assertEquals(210f, p.x, 0.01f); assertEquals(20f, p.y, 0.01f)
        // Turned 90: the display's top-left is the box's bottom-left.
        p = AnnotGeometry.toUser(0f, 0f, 90, 10f, 20f, 200f, 400f)
        assertEquals(10f, p.x, 0.01f); assertEquals(20f, p.y, 0.01f)
        p = AnnotGeometry.toUser(0f, 0f, 180, 10f, 20f, 200f, 400f)
        assertEquals(210f, p.x, 0.01f); assertEquals(20f, p.y, 0.01f)
    }

    @Test fun strokesAreThinnedAndHit() {
        val pts = floatArrayOf(0f, 0f, 0.001f, 0f, 0.002f, 0f, 0.1f, 0f, 0.1005f, 0f, 0.2f, 0f)
        val thin = AnnotGeometry.thin(pts, 0.01f)
        assertEquals(listOf(0f, 0f, 0.1f, 0f, 0.2f, 0f), thin.toList())
        assertTrue(AnnotGeometry.nearStroke(thin, 0.15f, 0.005f, 0.01f))
        assertFalse(AnnotGeometry.nearStroke(thin, 0.15f, 0.05f, 0.01f))
        assertFalse(AnnotGeometry.nearStroke(thin, 0.3f, 0f, 0.01f))
        assertNotNull(AnnotGeometry.thin(floatArrayOf(1f, 2f), 0.5f))
    }
}
