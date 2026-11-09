package com.wizlabs.pdfmaster

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Real two-finger gestures on the PDF viewer. Fingers land 80 ms apart, like a real hand,
 * which is exactly what used to make the page scroll/fling during a pinch.
 */
@RunWith(AndroidJUnit4::class)
class ViewerGestureTest {
    @get:Rule val rule = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val docId = "gesturetest"
    private lateinit var src: PdfSource
    private lateinit var list: LazyListState
    private lateinit var zoom: ZoomState

    @Before fun setUp() {
        // A 12-page A4 PDF generated on the device.
        val pdf = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 28f }
        repeat(12) { i ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawColor(Color.WHITE)
            for (l in 0 until 30) page.canvas.drawText("Page ${i + 1} line $l", 40f, 60f + l * 26f, paint)
            pdf.finishPage(page)
        }
        Library.fileOf(ctx, docId).outputStream().use { pdf.writeTo(it) }
        pdf.close()
        src = runBlocking { PdfSource.open(ctx, PdfRef.Local(docId, "Gesture test")) }
        list = LazyListState()
        zoom = ZoomState()
        rule.setContent {
            WizTheme {
                ZoomableDocument(src, list, zoom, night = false, hits = emptyList(), currentHit = null,
                    topPadding = 100.dp, onTap = {}, onVisiblePage = {})
            }
        }
        rule.waitForIdle()
    }

    @After fun tearDown() {
        src.close()
        Library.fileOf(ctx, docId).delete()
    }

    /** Absolute scroll position of the document in px (pages are all the same height). */
    private fun absScroll(): Float = rule.runOnIdle {
        val info = list.layoutInfo
        val size = info.visibleItemsInfo.first().size + info.mainAxisItemSpacing
        (list.firstVisibleItemIndex * size + list.firstVisibleItemScrollOffset).toFloat()
    }

    @Test fun pinchZoomsAroundFingersAndDoesNotScrollOrFling() {
        val before = absScroll()
        var centre = Offset.Zero
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput {
            centre = center
            down(0, center - Offset(0f, 80f))
            advanceEventTime(80)                       // second finger lands later, like a real hand
            down(1, center + Offset(0f, 80f))
            repeat(12) {
                updatePointerBy(0, Offset(0f, -18f))
                updatePointerBy(1, Offset(0f, 18f))
                move()
            }
            up(0); up(1)
        }
        rule.mainClock.advanceTimeByFrame()
        val atRelease = absScroll()
        rule.mainClock.advanceTimeBy(1500)            // any fling would show up here
        val later = absScroll()

        assertTrue("zoomed in (scale=${zoom.scale})", zoom.scale > 1.8f)
        // The document point that was under the fingers must stay under the fingers.
        val pad = with(rule.density) { 100.dp.toPx() }
        val anchorBefore = before + centre.y - pad
        val anchorAfter = atRelease + centre.y / zoom.scale - pad / zoom.scale
        assertEquals("zoom anchored to fingers", anchorBefore, anchorAfter, 40f)
        assertEquals("no scroll/fling after the pinch", atRelease, later, 1f)
    }

    @Test fun firstFingerMovingBeforeSecondLandsDoesNotFling() {
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput {
            down(0, center)
            repeat(6) { moveBy(0, Offset(0f, 12f)) }   // finger drifts past touch slop first
            down(1, center + Offset(0f, 200f))
            repeat(10) {
                updatePointerBy(0, Offset(0f, -15f))
                updatePointerBy(1, Offset(0f, 15f))
                move()
            }
            up(1)
            repeat(5) { moveBy(0, Offset(0f, 30f)) }   // leftover finger keeps moving: must be ignored
            up(0)
        }
        rule.mainClock.advanceTimeByFrame()
        val atRelease = absScroll()
        rule.mainClock.advanceTimeBy(1500)
        assertTrue("zoomed in", zoom.scale > 1.3f)
        assertEquals("no fling after pinch", atRelease, absScroll(), 1f)
    }

    /** The reported bug: pinching near the top left ~30% of the screen grey above the page. */
    @Test fun zoomingAtTheTopDoesNotLeaveABigGreyGap() {
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty() }
        rule.onRoot().performTouchInput {
            val c = Offset(centerX, 140f)               // pinch in the upper part of the screen
            down(0, c - Offset(0f, 40f))
            advanceEventTime(80)
            down(1, c + Offset(0f, 40f))
            repeat(12) {
                updatePointerBy(0, Offset(0f, -10f))
                updatePointerBy(1, Offset(0f, 26f))     // fingers drift down, like a real pinch
                move()
            }
            up(0); up(1)
        }
        rule.waitForIdle()
        val top = rule.onNodeWithContentDescription("Page 1").getBoundsInRoot().top
        val pad = 100.dp
        assertTrue("zoomed in (scale=${zoom.scale})", zoom.scale > 1.8f)
        assertTrue("gap above page is $top, should be at most the normal $pad", top.value <= pad.value * 1.1f)
    }

    @Test fun oneFingerSwipeStillScrollsAndFlings() {
        rule.onRoot().performTouchInput { swipeUp(durationMillis = 150) }
        rule.waitForIdle()
        assertTrue("scrolled down the document", absScroll() > 300f)
        assertEquals("no zoom from a swipe", 1f, zoom.scale, 0.001f)
    }

    @Test fun pinchingOutAtNormalSizeDoesNotScroll() {
        val before = absScroll()
        rule.onRoot().performTouchInput {
            down(0, center - Offset(0f, 200f))
            advanceEventTime(60)
            down(1, center + Offset(0f, 200f))
            repeat(10) {
                updatePointerBy(0, Offset(0f, 15f))
                updatePointerBy(1, Offset(0f, -15f))
                move()
            }
            up(0); up(1)
        }
        rule.waitForIdle()
        assertEquals("stays at normal size", 1f, zoom.scale, 0.001f)
        assertTrue("document did not jump (moved ${absScroll() - before}px)", abs(absScroll() - before) < 2f)
    }
}
