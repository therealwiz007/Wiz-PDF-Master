package com.wizlabs.pdfmaster

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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

/** The reported bug: a 1-page PDF zoomed in could not be moved up or down at all. */
@RunWith(AndroidJUnit4::class)
class ViewerShortDocTest {
    @get:Rule val rule = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val docId = "shortdoctest"
    private lateinit var src: PdfSource
    private val zoom = ZoomState()
    private var rootH = 0f

    @Before fun setUp() {
        val pdf = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 28f }
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawColor(Color.WHITE)
        for (l in 0 until 30) page.canvas.drawText("Line $l", 40f, 60f + l * 26f, paint)
        pdf.finishPage(page)
        Library.fileOf(ctx, docId).outputStream().use { pdf.writeTo(it) }
        pdf.close()
        src = runBlocking { PdfSource.open(ctx, PdfRef.Local(docId, "Short")) }
        rule.setContent {
            WizTheme {
                ZoomableDocument(src, LazyListState(), zoom, night = false, hits = emptyList(), currentHit = null,
                    topPadding = 100.dp, onTap = {}, onVisiblePage = {})
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty() }
        rootH = rule.onRoot().getBoundsInRoot().bottom.value
    }

    @After fun tearDown() {
        src.close()
        Library.fileOf(ctx, docId).delete()
    }

    private fun pageBounds() = rule.onNodeWithContentDescription("Page 1").getBoundsInRoot()

    private fun pinchIn(atY: Float) = rule.onRoot().performTouchInput {
        val c = Offset(centerX, atY)
        down(0, c - Offset(0f, 40f))
        advanceEventTime(80)
        down(1, c + Offset(0f, 40f))
        repeat(15) {
            updatePointerBy(0, Offset(0f, -25f))
            updatePointerBy(1, Offset(0f, 25f))
            move()
        }
        up(0); up(1)
    }

    @Test fun zoomedSinglePageCanBeScrolledToItsBottomAndBack() {
        pinchIn(atY = 300f)
        rule.waitForIdle()
        assertTrue("zoomed in (scale=${zoom.scale})", zoom.scale > 2.5f)
        // Bounds are clipped to the screen: while zoomed the page runs past the bottom edge.
        assertTrue("page runs off the bottom when zoomed", pageBounds().bottom.value >= rootH - 1f)

        repeat(6) { rule.onRoot().performTouchInput { swipeUp(durationMillis = 400) }; rule.waitForIdle() }
        val down = pageBounds()
        assertTrue("bottom edge of the page is now on screen (bottom ${down.bottom}, screen $rootH)", down.bottom.value < rootH - 40f)
        assertTrue("view panned (offsetY=${zoom.offsetY})", zoom.offsetY < -200f)

        repeat(6) { rule.onRoot().performTouchInput { swipeDown(durationMillis = 400) }; rule.waitForIdle() }
        val up = pageBounds()
        assertTrue("back at the top with no grey gap (top ${up.top})", up.top.value <= 100f * 1.1f && up.top.value >= 0f)
    }

    @Test fun zoomingNearTheBottomKeepsThePageUnderTheFingersAndNoGapAbove() {
        pinchIn(atY = rootH * 0.6f)
        rule.waitForIdle()
        assertTrue("zoomed in", zoom.scale > 2.5f)
        val b = pageBounds()
        assertTrue("no gap above the page (top ${b.top})", b.top.value <= 100f * 1.1f)
        assertTrue("page still covers the screen bottom (bottom ${b.bottom}, screen $rootH)", b.bottom.value >= rootH - 1f)
    }

    @Test fun zoomingBackOutResetsThePan() {
        pinchIn(atY = 300f)
        repeat(4) { rule.onRoot().performTouchInput { swipeUp(durationMillis = 400) } }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            down(0, center - Offset(0f, 400f)); advanceEventTime(60); down(1, center + Offset(0f, 400f))
            repeat(20) { updatePointerBy(0, Offset(0f, 20f)); updatePointerBy(1, Offset(0f, -20f)); move() }
            up(0); up(1)
        }
        rule.waitForIdle()
        assertEquals(1f, zoom.scale, 0.001f)
        assertEquals("pan reset at normal size", 0f, zoom.offsetY, 0.5f)
    }
}
