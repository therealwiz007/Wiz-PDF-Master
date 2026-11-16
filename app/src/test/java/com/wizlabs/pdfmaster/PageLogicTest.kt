package com.wizlabs.pdfmaster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLogicTest {
    private fun ok(text: String, n: Int) = (parsePageRanges(text, n) as RangeResult.Ok).groups

    @Test fun parsesSinglesAndRanges() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(4)), ok("1-3,5", 9))
        assertEquals(listOf(listOf(0, 1, 2), listOf(4)), ok(" 1 - 3 ; 5 ".replace(" - ", "-"), 9))
    }

    @Test fun openEndedAndReversedRanges() {
        assertEquals(listOf(listOf(6, 7, 8)), ok("7-", 9))
        assertEquals(listOf(listOf(0, 1)), ok("-2", 9))
        assertEquals(listOf(listOf(4, 3, 2)), ok("5-3", 9))
    }

    @Test fun rejectsBadInput() {
        assertTrue(parsePageRanges("", 9) is RangeResult.Error)
        assertTrue(parsePageRanges("abc", 9) is RangeResult.Error)
        assertTrue(parsePageRanges("-", 9) is RangeResult.Error)
        assertTrue(parsePageRanges("1-2-3", 9) is RangeResult.Error)
        assertTrue(parsePageRanges("0", 9) is RangeResult.Error)
        assertTrue(parsePageRanges("3-12", 9) is RangeResult.Error)
    }

    @Test fun splitsEveryN() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6)), splitEvery(7, 3))
        assertEquals(emptyList<List<Int>>(), splitEvery(7, 0))
        assertEquals(1, splitEvery(4, 10).size)
    }

    @Test fun movesListItems() {
        val l = mutableListOf(1, 2, 3, 4)
        l.move(0, 2); assertEquals(listOf(2, 3, 1, 4), l)
        l.move(3, 0); assertEquals(listOf(4, 2, 3, 1), l)
        l.move(1, 9); assertEquals(listOf(4, 2, 3, 1), l)
    }

    // ---- Compression maths

    @Test fun budgetLeavesMarginAndFlagsUnreachable() {
        assertEquals(1_000_000L - 200_000L - 20_000L, CompressMath.imageBudget(1_000_000, 200_000))
        assertTrue(CompressMath.unreachable(100_000, 99_000))
        assertFalse(CompressMath.unreachable(100_000, 50_000))
        assertEquals(300L, SizeBreakdown(1000, 700).overhead)
        assertEquals(0L, SizeBreakdown(1000, 1700).overhead)
    }

    @Test fun dpiScaleNeverUpscales() {
        // 2480 px across an A4 long side (842 pt) is about 212 dpi.
        assertEquals(212f, CompressMath.effectiveDpi(2480, 842f), 1f)
        assertEquals(1f, CompressMath.scaleFor(1000, 842f, 200), 1e-6f)
        assertEquals(150f / 212f, CompressMath.scaleFor(2480, 842f, 150), 0.01f)
    }

    @Test fun samplesAreSpreadOut() {
        assertEquals(listOf(0, 1, 2), CompressMath.sampleIndices(3))
        val s = CompressMath.sampleIndices(100)
        assertEquals(5, s.size)
        assertEquals(listOf(10, 30, 50, 70, 90), s)
        assertTrue(CompressMath.sampleIndices(0).isEmpty())
    }

    @Test fun curveInterpolatesAndClamps() {
        val c = SizeCurve(intArrayOf(30, 50, 90), longArrayOf(100, 200, 600))
        assertEquals(100L, c.sizeAt(10))
        assertEquals(150L, c.sizeAt(40))
        assertEquals(400L, c.sizeAt(70))
        assertEquals(600L, c.sizeAt(99))
    }

    @Test fun findsHighestQualityThatFits() {
        val c = SizeCurve(intArrayOf(30, 50, 90), longArrayOf(100, 200, 600))
        assertNull(c.maxQualityWithin(90, 35, 85))
        assertEquals(85, c.maxQualityWithin(10_000, 35, 85))
        val q = c.maxQualityWithin(400, 35, 85)!!
        assertTrue(c.sizeAt(q) <= 400)
        assertTrue("within a step of the true answer (70), got $q", q in 68..70)
    }

    /** Synthetic estimator: bytes grow with dpi² and linearly with quality. */
    private fun curve(dpi: Int) = SizeCurve(
        CompressMath.Q_GRID, LongArray(CompressMath.Q_GRID.size) { dpi.toLong() * dpi * CompressMath.Q_GRID[it] / 100 }
    )

    @Test fun planPrefersResolutionWhileQualityStaysDecent() {
        // 200 dpi at q85 = 34 000
        assertEquals(CompressMath.Plan(200, 85, 34_000, true), CompressMath.pickPlan(40_000, curveFor = ::curve))
        // 200 dpi needs q < 50 for 15 000 (q50 = 20 000) but 150 dpi fits at q66
        val p = CompressMath.pickPlan(15_000, curveFor = ::curve)
        assertEquals(150, p.dpi); assertTrue(p.fits); assertTrue(p.quality in 60..66)
        assertTrue(p.estimatedImageBytes <= 15_000)
    }

    @Test fun planFallsBackToLowQualityThenFloor() {
        // 96 dpi q50 = 4608: too big for 4000, so round 2 picks the highest DPI that fits at q >= 35.
        val p = CompressMath.pickPlan(4_000, curveFor = ::curve)
        assertTrue(p.fits); assertTrue(p.quality in CompressMath.Q_MIN until CompressMath.Q_GOOD)
        assertEquals(96, p.dpi)
        val floor = CompressMath.pickPlan(1_000, curveFor = ::curve)
        assertFalse(floor.fits)
        assertEquals(CompressMath.DPI_FLOOR, floor.dpi); assertEquals(CompressMath.Q_FLOOR, floor.quality)
    }

    @Test fun correctionAimsLowerWhenEstimateWasOptimistic() {
        // Saved file: 600 000 with 500 000 of pictures (estimated 400 000). Target 500 000.
        val b = CompressMath.correctedBudget(500_000, 600_000, 500_000, 400_000)
        // Real budget = 500 000 - 100 000 - 10 000 = 390 000, scaled by 400/500.
        assertEquals(312_000L, b)
    }

    @Test fun classifiesPixels() {
        val white = 0xFFFFFFFF.toInt(); val black = 0xFF000000.toInt()
        val doc = IntArray(1000) { if (it % 20 == 0) black else white }
        assertEquals(ImgKind.BILEVEL, CompressMath.classify(doc))
        val gray = IntArray(1000) { val v = it % 256; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertEquals(ImgKind.GRAY, CompressMath.classify(gray))
        val colour = IntArray(1000) { if (it % 10 == 0) 0xFFFF0000.toInt() else white }
        assertEquals(ImgKind.COLOR, CompressMath.classify(colour))
        assertEquals("blank paper is not bilevel", ImgKind.GRAY, CompressMath.classify(IntArray(100) { white }))
    }

    @Test fun packsBitsWithPadding() {
        val w = 0xFFFFFFFF.toInt(); val b = 0xFF000000.toInt()
        val row = intArrayOf(b, w, w, w, w, w, w, w, b, w)          // 10 px
        assertEquals(2, Bilevel.rowBytes(10))
        val gray = ByteArray(2)
        Bilevel.packRow(row, 0, 10, gray, 0, 128, blackIsOne = false)
        assertEquals(0x7F, gray[0].toInt() and 0xFF)
        assertEquals(0x7F, gray[1].toInt() and 0xFF)                // 0 1 then six white pad bits
        val fax = ByteArray(2)
        Bilevel.packRow(row, 0, 10, fax, 0, 128, blackIsOne = true)
        assertEquals(0x80, fax[0].toInt() and 0xFF)
        assertEquals(0x80, fax[1].toInt() and 0xFF)
    }

    // ---- Update prompt

    @Test fun whatsNewOnlyAfterRealUpdate() {
        assertFalse("fresh install", UpdateLogic.showWhatsNew(0, 8, 500, 500))
        assertTrue("updated from a version without the marker", UpdateLogic.showWhatsNew(0, 8, 500, 900))
        assertTrue(UpdateLogic.showWhatsNew(7, 8, 500, 900))
        assertFalse(UpdateLogic.showWhatsNew(8, 8, 500, 900))
    }

    // ---- Scanning

    private val page = Quad(P(.1f, .1f), P(.9f, .1f), P(.9f, .9f), P(.1f, .9f))

    @Test fun autoCaptureWaitsForSteadyPage() {
        val t = StabilityTracker(steadyMs = 800, blurGraceMs = 2500)
        assertEquals(0f, t.feed(page, 1000).second, 1e-6f)
        assertEquals(0.5f, t.feed(page, 1400).second, 1e-6f)
        assertEquals(1f, t.feed(page, 1800).second, 1e-6f)
    }

    @Test fun blurHoldsCaptureUntilGraceRunsOut() {
        val t = StabilityTracker(steadyMs = 800, blurGraceMs = 2500)
        t.feed(page, 1000, sharp = false)
        val held = t.feed(page, 1900, sharp = false)
        assertTrue(held.second < 1f); assertTrue(t.blurHeld)
        assertEquals("sharp again: fire", 1f, t.feed(page, 2000, sharp = true).second, 1e-6f)
        assertFalse(t.blurHeld)
        assertEquals("grace over: fire anyway", 1f, t.feed(page, 1000 + 800 + 2500, sharp = false).second, 1e-6f)
    }

    @Test fun staysDisarmedAfterCaptureUntilPageChanges() {
        val t = StabilityTracker(steadyMs = 800)
        t.feed(page, 1000); t.feed(page, 1800)
        t.reset(now = 1800)
        assertEquals(0f, t.feed(page, 4000).second, 1e-6f)
        assertEquals(0f, t.feed(page, 6000).second, 1e-6f)
    }

    @Test fun rotatesSensorQuadUpright() {
        val q = Quad(P(.1f, .2f), P(.9f, .1f), P(.8f, .9f), P(.2f, .85f))
        assertTrue(q.distanceTo(q.rotatedBy(0)) < 1e-6f)
        assertTrue(q.rotatedCw().distanceTo(q.rotatedBy(90)) < 1e-6f)
        assertTrue(q.distanceTo(q.rotatedBy(360)) < 1e-6f)
        assertTrue(q.rotatedBy(270).distanceTo(q.rotatedBy(-90)) < 1e-6f)
        assertTrue(isSharp(120.0)); assertFalse(isSharp(20.0))
    }
}
