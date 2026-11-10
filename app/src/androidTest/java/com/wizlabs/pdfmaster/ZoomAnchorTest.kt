package com.wizlabs.pdfmaster

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Follows the exact document spot that starts under the fingers, frame by frame through a pinch,
 * and checks it stays under the fingers (it must not drift or jump).
 */
@RunWith(AndroidJUnit4::class)
class ZoomAnchorTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val docId = "anchortest"
    private var src: PdfSource? = null
    private val zoom = ZoomState()
    private val list = LazyListState()
    private val log = StringBuilder()

    private fun open(pages: Int) {
        val pdf = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 28f }
        repeat(pages) { i ->
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            p.canvas.drawColor(Color.WHITE)
            for (l in 0 until 30) p.canvas.drawText("Page ${i + 1} line $l", 40f, 60f + l * 26f, paint)
            pdf.finishPage(p)
        }
        Library.fileOf(ctx, docId).outputStream().use { pdf.writeTo(it) }
        pdf.close()
        src = runBlocking { PdfSource.open(ctx, PdfRef.Local(docId, "Anchor")) }
        rule.setContent {
            WizTheme {
                ZoomableDocument(src!!, list, zoom, night = false, hits = emptyList(), currentHit = null,
                    topPadding = 100.dp, onTap = {}, onVisiblePage = {})
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() { src?.close(); Library.fileOf(ctx, docId).delete() }

    /** Document spot as (page index, x and y inside the page in unzoomed px). */
    private data class Spot(val page: Int, val x: Float, val y: Float)

    private fun pageNodes() = (0 until src!!.pageCount).mapNotNull { i ->
        rule.onAllNodesWithContentDescription("Page ${i + 1}").fetchSemanticsNodes().firstOrNull()?.let { i to it }
    }

    /** Waits until every page on screen has drawn (pages are labelled once their image is ready). */
    private fun waitForPages() = rule.waitUntil(15_000) {
        val want = list.layoutInfo.visibleItemsInfo.map { it.index }
        val have = pageNodes().map { it.first }
        want.isNotEmpty() && have.containsAll(want)
    }

    private fun spotAt(screen: Offset): Spot {
        waitForPages()
        val s = zoom.scale
        val (i, n) = pageNodes().minBy { (_, n) ->
            val top = n.positionInRoot.y; val bot = top + n.size.height * s
            if (screen.y in top..bot) 0f else minOf(kotlin.math.abs(screen.y - top), kotlin.math.abs(screen.y - bot))
        }
        return Spot(i, (screen.x - n.positionInRoot.x) / s, (screen.y - n.positionInRoot.y) / s)
    }

    private fun screenOf(spot: Spot): Offset {
        rule.waitUntil(15_000) { pageNodes().any { it.first == spot.page } }
        val n = pageNodes().first { it.first == spot.page }.second
        return Offset(n.positionInRoot.x + spot.x * zoom.scale, n.positionInRoot.y + spot.y * zoom.scale)
    }

    /** Pinch in at [c] (with the hand drifting by [drift] per step); returns the worst drift in px. */
    private fun pinchAndMeasure(c: Offset, drift: Offset = Offset.Zero, spread: Float = 22f): Float {
        val spot = spotAt(c)
        var a = c - Offset(30f, 60f); var b = c + Offset(30f, 60f)
        rule.onRoot().performTouchInput { down(0, a) }
        rule.onRoot().performTouchInput { advanceEventTime(60); down(1, b) }
        var worst = 0f
        repeat(16) {
            a += Offset(-spread * 0.5f, -spread) + drift; b += Offset(spread * 0.5f, spread) + drift
            rule.onRoot().performTouchInput { updatePointerTo(0, a); updatePointerTo(1, b); move() }
            rule.waitForIdle()
            val centre = (a + b) / 2f
            val err = (screenOf(spot) - centre).getDistance()
            log.append("scale=%.2f err=%.1f oy=%.0f | ".format(zoom.scale, err, zoom.offsetY))
            worst = maxOf(worst, err)
        }
        rule.onRoot().performTouchInput { up(0); up(1) }
        rule.waitForIdle()
        return worst
    }

    private fun check(name: String, worst: Float) =
        assertTrue("$name: spot under fingers drifted ${"%.1f".format(worst)}px. $log", worst < 6f)

    @Test fun onePageTop() { open(1); check("1 page, top", pinchAndMeasure(Offset(400f, 450f))) }
    @Test fun onePageMiddle() { open(1); check("1 page, middle", pinchAndMeasure(Offset(540f, 1100f))) }
    @Test fun onePageBottom() { open(1); check("1 page, bottom", pinchAndMeasure(Offset(700f, 1600f))) }
    @Test fun onePageHandDrifts() { open(1); check("1 page, drifting", pinchAndMeasure(Offset(540f, 900f), drift = Offset(4f, 9f))) }
    @Test fun longDocMiddle() {
        open(12); rule.runOnIdle { list.requestScrollToItem(5, 300) }; rule.waitForIdle()
        check("12 pages, mid-document", pinchAndMeasure(Offset(540f, 1000f)))
    }
    @Test fun longDocTop() { open(12); check("12 pages, top", pinchAndMeasure(Offset(300f, 500f))) }
    @Test fun zoomFurtherWhenAlreadyZoomed() {
        open(12); rule.runOnIdle { list.requestScrollToItem(3, 0) }; rule.waitForIdle()
        pinchAndMeasure(Offset(540f, 1000f))
        log.clear()
        check("second pinch", pinchAndMeasure(Offset(250f, 700f), spread = 8f))
    }
}
