package com.wizlabs.pdfmaster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScanMathTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) <= eps
    private fun quadNear(a: Quad, b: Quad, eps: Float = 1e-3f) = a.points.zip(b.points).all { (p, q) -> near(p.x, q.x, eps) && near(p.y, q.y, eps) }

    // ---- Lines and corner refinement ----------------------------------------------------------

    @Test fun linesIntersect() {
        val p = Line2.through(0.0, 0.0, 2.0, 2.0).intersect(Line2.through(0.0, 2.0, 2.0, 0.0))!!
        assertEquals(1.0, p.first, 1e-9); assertEquals(1.0, p.second, 1e-9)
    }

    @Test fun parallelLinesDoNotIntersect() {
        assertNull(Line2.through(0.0, 0.0, 1.0, 0.0).intersect(Line2.through(0.0, 1.0, 5.0, 1.0)))
    }

    @Test fun angleBetweenLinesIgnoresDirection() {
        val a = Line2(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, a.angleTo(Line2(3.0, 1.0, -2.0, 0.0)), 1e-6)
        assertEquals(90.0, a.angleTo(Line2(0.0, 0.0, 0.0, 4.0)), 1e-6)
        assertEquals(45.0, a.angleTo(Line2(0.0, 0.0, 1.0, 1.0)), 1e-6)
    }

    @Test fun quadFromFourLines() {
        val q = QuadMath.fromLines(
            top = Line2.through(0.0, 0.1, 1.0, 0.1), right = Line2.through(0.9, 0.0, 0.9, 1.0),
            bottom = Line2.through(1.0, 0.8, 0.0, 0.8), left = Line2.through(0.2, 1.0, 0.2, 0.0)
        )!!
        assertTrue(quadNear(q, Quad(P(.2f, .1f), P(.9f, .1f), P(.9f, .8f), P(.2f, .8f))))
    }

    @Test fun quadFromLinesNeedsCrossingNeighbours() {
        val h = Line2.through(0.0, 0.1, 1.0, 0.1)
        assertNull(QuadMath.fromLines(h, h, Line2.through(0.0, 0.8, 1.0, 0.8), Line2.through(0.2, 0.0, 0.2, 1.0)))
    }

    @Test fun refinementAcceptsSmallCorrections() {
        val coarse = Quad(P(.2f, .1f), P(.9f, .1f), P(.9f, .8f), P(.2f, .8f))
        assertTrue(QuadMath.acceptRefined(coarse, Quad(P(.21f, .11f), P(.89f, .09f), P(.91f, .81f), P(.19f, .79f))))
    }

    @Test fun refinementRejectsBigJumpsAndBrokenShapes() {
        val coarse = Quad(P(.2f, .1f), P(.9f, .1f), P(.9f, .8f), P(.2f, .8f))
        assertFalse(QuadMath.acceptRefined(coarse, coarse.copy(tl = P(.26f, .1f))))            // 6% jump
        assertFalse(QuadMath.acceptRefined(coarse, coarse.copy(tl = P(Float.NaN, .1f))))
        // Two corners swapped: not convex any more, however small the tolerance check would be.
        assertFalse(QuadMath.acceptRefined(coarse, Quad(P(.9f, .1f), P(.2f, .1f), P(.9f, .8f), P(.2f, .8f)), maxMove = 2f))
    }

    // ---- Scoring ------------------------------------------------------------------------------

    @Test fun rightAnglesScoreBest() {
        val rect = Quad(P(.1f, .1f), P(.9f, .1f), P(.9f, .9f), P(.1f, .9f))
        val skewed = Quad(P(.1f, .1f), P(.9f, .3f), P(.9f, .9f), P(.3f, .6f))
        assertEquals(1f, QuadMath.angleScore(rect, 480, 360), 1e-4f)
        assertTrue(QuadMath.angleScore(skewed, 480, 360) < 0.8f)
    }

    @Test fun edgeSupportCountsPixelsUnderTheSides() {
        val w = 100; val h = 100
        val edges = ByteArray(w * h)
        val q = Quad(P(.2f, .2f), P(.8f, .2f), P(.8f, .8f), P(.2f, .8f))
        assertEquals(0f, QuadMath.edgeSupport(q, edges, w, h), 0f)
        for (x in 20..80) { edges[20 * w + x] = -1; edges[80 * w + x] = -1 }      // top and bottom only
        assertEquals(0.5f, QuadMath.edgeSupport(q, edges, w, h), 0.01f)
        for (y in 20..80) { edges[y * w + 20] = -1; edges[y * w + 80] = -1 }
        assertEquals(1f, QuadMath.edgeSupport(q, edges, w, h), 0f)
    }

    @Test fun scorePrefersSupportedOutline() {
        val page = Quad(P(.1f, .1f), P(.9f, .1f), P(.9f, .9f), P(.1f, .9f))
        val inner = Quad(P(.3f, .3f), P(.7f, .3f), P(.7f, .7f), P(.3f, .7f))
        assertTrue(QuadMath.score(page, 0.9f, 480, 360, null) > QuadMath.score(inner, 0.4f, 480, 360, null))
        // With equal evidence, the bigger outline wins, and so does the one near the last frame's.
        assertTrue(QuadMath.score(page, 0.6f, 480, 360, null) > QuadMath.score(inner, 0.6f, 480, 360, null))
        assertTrue(QuadMath.score(inner, 0.6f, 480, 360, inner) > QuadMath.score(inner, 0.6f, 480, 360, page))
    }

    @Test fun scoreStaysInUnitRange() {
        val page = Quad.FULL
        assertTrue(QuadMath.score(page, 1f, 480, 360, page) <= 1.0001f)
        assertTrue(QuadMath.score(Quad(P(.4f, .4f), P(.5f, .4f), P(.5f, .5f), P(.4f, .5f)), 0f, 480, 360, null) >= 0f)
    }

    @Test fun quadFromLineSegments() {
        // A page from (60,40) to (420,320) in a 480x360 frame, each side broken in two, plus clutter.
        val segs = floatArrayOf(
            60f, 40f, 200f, 40f, 260f, 41f, 420f, 41f,          // top
            60f, 320f, 420f, 320f,                              // bottom
            60f, 40f, 60f, 150f, 61f, 200f, 61f, 320f,          // left
            420f, 40f, 420f, 320f,                              // right
            100f, 100f, 160f, 160f                              // diagonal clutter: ignored
        )
        val found = QuadMath.fromSegments(segs, 480, 360)
        assertTrue(found.isNotEmpty())
        val want = Quad(P(60f / 480, 40f / 360), P(420f / 480, 40f / 360), P(420f / 480, 320f / 360), P(60f / 480, 320f / 360))
        assertTrue(found.any { quadNear(it, want, 0.01f) })
    }

    @Test fun lineSegmentsNeedTwoPlusTwo() {
        assertTrue(QuadMath.fromSegments(floatArrayOf(60f, 40f, 420f, 40f, 60f, 320f, 420f, 320f, 60f, 40f, 60f, 320f), 480, 360).isEmpty())
        assertTrue(QuadMath.fromSegments(FloatArray(0), 480, 360).isEmpty())
        // Two "pages edges" only a few pixels apart are one edge, not a page.
        assertTrue(QuadMath.fromSegments(floatArrayOf(60f, 40f, 420f, 40f, 60f, 44f, 420f, 44f, 60f, 40f, 60f, 320f, 420f, 40f, 420f, 320f), 480, 360).isEmpty())
    }

    // ---- Page proportions ---------------------------------------------------------------------

    @Test fun snapsToStandardShapes() {
        assertEquals(PaperShape.A4, AspectSnap.shapeOf(1000, 1440))
        assertEquals(PaperShape.A4, AspectSnap.shapeOf(1440, 1000))
        assertEquals(PaperShape.LETTER, AspectSnap.shapeOf(1000, 1300))
        assertEquals(PaperShape.ID1, AspectSnap.shapeOf(1600, 1000))
        assertNull(AspectSnap.shapeOf(1000, 1000))
        assertNull(AspectSnap.shapeOf(1000, 2000))
        assertNull(AspectSnap.shapeOf(0, 10))
    }

    @Test fun snapKeepsLongSideAndOrientation() {
        assertEquals(1018 to 1440, AspectSnap.snap(1000, 1440))
        assertEquals(1440 to 1018, AspectSnap.snap(1440, 1000))
        assertEquals(700 to 800, AspectSnap.snap(700, 800))                 // not near anything: untouched
        val (w, h) = AspectSnap.force(900, 500, PaperShape.ID1)
        assertEquals(900, w); assertEquals(568, h)
    }

    @Test fun betweenA4AndLetterPicksTheNearer() {
        // 1.35 is 4.5% from A4 and 4.3% from Letter: outside both tolerances.
        assertNull(AspectSnap.shapeOf(1000, 1350))
        assertEquals(PaperShape.LETTER, AspectSnap.shapeOf(1000, 1330))
        assertEquals(PaperShape.A4, AspectSnap.shapeOf(1000, 1380))
    }

    // ---- Levels -------------------------------------------------------------------------------

    @Test fun percentilesOfHistogram() {
        val hist = IntArray(256); hist[10] = 50; hist[200] = 50
        assertEquals(10, Levels.percentile(hist, 0.03))
        assertEquals(10, Levels.percentile(hist, 0.5))
        assertEquals(200, Levels.percentile(hist, 0.95))
        assertEquals(0, Levels.percentile(IntArray(256), 0.5))
    }

    @Test fun stretchMapsInkToBlackAndPaperToWhite() {
        val hist = IntArray(256); hist[40] = 10; hist[240] = 90
        val (gain, offset) = Levels.stretch(hist)
        assertEquals(0.0, 40 * gain + offset, 1e-6)
        assertEquals(255.0, 240 * gain + offset, 1e-6)
    }

    @Test fun stretchIsGuardedOnBlankAndDarkPages() {
        val blank = IntArray(256); blank[235] = 1000                         // no ink at all
        val (g1, o1) = Levels.stretch(blank)
        assertTrue(g1 in 1.0..1.6); assertTrue(235 * g1 + o1 >= 200)          // paper stays paper
        val dark = IntArray(256); dark[20] = 500; dark[60] = 500              // a dark photo
        val (g2, _) = Levels.stretch(dark)
        assertTrue(g2 <= 1.6)
    }

    // ---- Sauvola ------------------------------------------------------------------------------

    @Test fun sauvolaWindowIsOddAndClamped() {
        assertEquals(15, Sauvola.window(300))
        assertEquals(61, Sauvola.window(4000))
        assertEquals(51, Sauvola.window(2000))
        assertTrue(Sauvola.window(1700) % 2 == 1)
    }

    @Test fun sauvolaThresholdFormula() {
        assertEquals(200 * (1 + 0.25 * (64.0 / 128 - 1)), Sauvola.threshold(200.0, 64.0), 1e-9)
        // Flat paper: threshold sits a quarter below the mean, so paper is never called ink.
        assertEquals(150.0, Sauvola.threshold(200.0, 0.0), 1e-9)
    }

    @Test fun sauvolaSeparatesInkFromShadedPaper() {
        val w = 40; val h = 40
        // Paper darkens from 230 on the left to 120 on the right; a dark stroke crosses it.
        val gray = IntArray(w * h) { i -> 230 - (i % w) * 110 / (w - 1) }
        for (x in 4 until 36) for (y in 19..20) gray[y * w + x] = (gray[y * w + x] * 0.25).toInt()
        val out = Sauvola.binarize(gray, w, h, 15)
        for (x in 4 until 36) for (y in 19..20) assertEquals("ink at $x,$y", 0, out[y * w + x])
        for (x in 0 until w) for (y in intArrayOf(2, 8, 30, 37)) assertEquals("paper at $x,$y", 255, out[y * w + x])
        // A single global threshold could not do this: the shaded paper is darker than 128.
        assertTrue(gray[5 * w + 39] < 128)
    }

    @Test fun sauvolaLeavesBlankPageWhite() {
        val out = Sauvola.binarize(IntArray(30 * 20) { 180 + (it % 7) }, 30, 20, 15)
        assertTrue(out.all { it == 255 })
    }

    // ---- Auto look ----------------------------------------------------------------------------

    @Test fun autoLookChoosesByContent() {
        assertEquals(ScanFilter.MAGIC, AutoLook.decide(colourful = .05f, meanSaturation = 40f, content = .2f, midtones = .05f))
        assertEquals(ScanFilter.MAGIC, AutoLook.decide(colourful = .01f, meanSaturation = 120f, content = .05f, midtones = .02f))
        assertEquals(ScanFilter.BW, AutoLook.decide(colourful = 0f, meanSaturation = 15f, content = .08f, midtones = .03f))
        assertEquals(ScanFilter.GRAY, AutoLook.decide(colourful = 0f, meanSaturation = 15f, content = .5f, midtones = .4f))
        assertEquals(ScanFilter.GRAY, AutoLook.decide(colourful = .002f, meanSaturation = 20f, content = .2f, midtones = .15f))
    }

    // ---- Orientation --------------------------------------------------------------------------

    @Test fun uprightPageIsLeftAlone() {
        assertEquals(PageAngle.Fix(0, 0f), PageAngle.fix(floatArrayOf(0.1f, -0.2f, 0f), floatArrayOf(20f, 30f, 25f)))
    }

    @Test fun sidewaysAndUpsideDownPagesAreTurnedBack() {
        assertEquals(270, PageAngle.fix(floatArrayOf(90f, 91f, 89f), floatArrayOf(20f, 20f, 20f))!!.rotate)
        assertEquals(90, PageAngle.fix(floatArrayOf(-90f, -89f), floatArrayOf(20f, 20f))!!.rotate)
        assertEquals(180, PageAngle.fix(floatArrayOf(180f, -179f, 179f), floatArrayOf(20f, 20f, 20f))!!.rotate)
    }

    @Test fun smallTiltBecomesSkew() {
        val f = PageAngle.fix(floatArrayOf(3f, 3.2f, 2.8f, 40f), floatArrayOf(30f, 30f, 30f, 2f))!!
        assertEquals(0, f.rotate); assertEquals(3f, f.skew, 0.01f)
        val g = PageAngle.fix(floatArrayOf(93f, 94f, 92f), floatArrayOf(10f, 10f, 10f))!!
        assertEquals(270, g.rotate); assertEquals(3f, g.skew, 0.01f)
        val neg = PageAngle.fix(floatArrayOf(-4f, -4f), floatArrayOf(10f, 10f))!!
        assertEquals(-4f, neg.skew, 0.01f)
    }

    @Test fun orientationNeedsEnoughAgreeingText() {
        assertNull(PageAngle.fix(FloatArray(0), FloatArray(0)))
        assertNull(PageAngle.fix(floatArrayOf(90f), floatArrayOf(4f)))                                  // one short word
        assertNull(PageAngle.fix(floatArrayOf(0f, 90f, 180f), floatArrayOf(10f, 10f, 10f)))             // no agreement
        // Tilt beyond the limit is not corrected (it would crop the page badly).
        assertEquals(0f, PageAngle.fix(floatArrayOf(20f, 21f), floatArrayOf(20f, 20f))!!.skew, 0f)
    }

    // ---- ID card sheet ------------------------------------------------------------------------

    @Test fun idCardsAreRealSizeCentredAndStacked() {
        val b = IdCardLayout.boxes(595.28f, 841.89f, listOf(true, true))
        assertEquals(2, b.size)
        for (box in b) {
            assertEquals(85.6f * 72f / 25.4f, box.w, 0.01f)
            assertEquals(54f * 72f / 25.4f, box.h, 0.01f)
            assertEquals((595.28f - box.w) / 2f, box.x, 0.01f)
        }
        assertTrue("front above back", b[0].y > b[1].y)
        assertEquals(IdCardLayout.GAP, b[0].y - (b[1].y + b[1].h), 0.01f)
        // The pair is centred: same margin above the front as below the back.
        assertEquals(841.89f - (b[0].y + b[0].h), b[1].y, 0.01f)
    }

    @Test fun uprightCardPictureGetsUprightBox() {
        val b = IdCardLayout.boxes(595.28f, 841.89f, listOf(false)).single()
        assertTrue(b.h > b.w)
        assertEquals(85.6f * 72f / 25.4f, b.h, 0.01f)
        assertEquals((841.89f - b.h) / 2f, b.y, 0.01f)
        assertTrue(IdCardLayout.boxes(595f, 842f, emptyList()).isEmpty())
    }

    @Test fun cardsShrinkOnlyWhenTheSheetIsTooSmall() {
        val b = IdCardLayout.boxes(200f, 300f, listOf(true, true))
        assertTrue(b.all { it.w <= 200f * 0.94f + 0.01f && it.x >= 0f && it.y >= 0f })
        assertEquals(85.6f / 54f, b[0].w / b[0].h, 0.001f)
    }

    // ---- Export -------------------------------------------------------------------------------

    @Test fun exportSizeFollowsDpi() {
        assertEquals(1240, exportWidthPx(595, 842, 150))
        assertEquals(2479, exportWidthPx(595, 842, 300))
        // A poster is capped at 4096 px on its long side.
        assertEquals(4096, exportWidthPx(3000, 2000, 300))
        assertEquals(2048, exportWidthPx(2000, 4000, 300))
    }

    @Test fun pageImageNames() {
        assertEquals("Invoice p03.jpg", pageImageName("Invoice", 2, 12))
        assertEquals("Invoice p003.jpg", pageImageName("Invoice", 2, 120))
        assertEquals("a_b p01.jpg", pageImageName("a/b", 0, 1))
    }

    // ---- Drafts -------------------------------------------------------------------------------

    private val draft = Draft(
        idCard = true, selected = 1,
        pages = listOf(
            DraftPage("id-1", "100.jpg", Quad(P(.1f, .12f), P(.9f, .1f), P(.88f, .93f), P(.11f, .9f)), 90, "AUTO", true, -2.5f, "BW", true),
            DraftPage("id-2", "200.jpg", Quad.FULL, 0, "MAGIC")
        )
    )

    @Test fun draftRoundTrips() {
        assertEquals(draft, DraftCodec.decode(DraftCodec.encode(draft)))
    }

    @Test fun truncatedOrForeignDraftIsRejected() {
        val text = DraftCodec.encode(draft)
        assertNull(DraftCodec.decode(""))
        assertNull(DraftCodec.decode("{\"pages\":[]}"))
        assertNull(DraftCodec.decode(text.substringBeforeLast("end")))            // cut off before the end marker
        assertNull(DraftCodec.decode(text.lines().filterNot { it.startsWith("page\tid-2") }.joinToString("\n")))
        assertNull(DraftCodec.decode(DraftCodec.encode(draft.copy(pages = emptyList()))))
    }

    @Test fun draftRejectsBadNumbersAndPaths() {
        val text = DraftCodec.encode(draft)
        assertNull(DraftCodec.decode(text.replace("0.12", "abc")))
        assertNull(DraftCodec.decode(text.replace("0.12", "7.5")))
        assertNull(DraftCodec.decode(text.replace("100.jpg", "../../x.jpg")))
    }

    @Test fun draftKeepsOddNamesAndClampsSelection() {
        val d = Draft(false, 9, listOf(DraftPage("a\tb", "1.jpg", Quad.FULL, 450, "GRAY")))
        val back = DraftCodec.decode(DraftCodec.encode(d))
        assertNotNull(back)
        assertEquals(0, back!!.selected)
        assertEquals("a b", back.pages[0].id)
        assertEquals(90, back.pages[0].rotation)
    }
}
