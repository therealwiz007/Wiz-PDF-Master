package com.wizlabs.pdfmaster

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
 * Same layout as the real viewer: a title bar drawn over the page and a slider at the bottom,
 * with pinch/drag handled for the whole screen. Covers what a real phone showed:
 * the upper finger landing on the title bar, and dragging with the finger left after a pinch.
 */
@RunWith(AndroidJUnit4::class)
class ViewerOverlayTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val docId = "overlaytest"
    private lateinit var src: PdfSource
    private val zoom = ZoomState()
    private var clicks = 0
    private var sliderValue = 0f

    @Before fun setUp() {
        val pdf = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 28f }
        val p = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        p.canvas.drawColor(Color.WHITE)
        for (l in 0 until 30) p.canvas.drawText("Line $l", 40f, 60f + l * 26f, paint)
        pdf.finishPage(p)
        Library.fileOf(ctx, docId).outputStream().use { pdf.writeTo(it) }
        pdf.close()
        src = runBlocking { PdfSource.open(ctx, PdfRef.Local(docId, "Overlay")) }
        rule.setContent {
            WizTheme {
                val list = remember { LazyListState() }
                val c = rememberZoomController(list, zoom)
                Box(Modifier.fillMaxSize().documentGestures(c)) {
                    ZoomableDocument(src, list, zoom, night = false, hits = emptyList(), currentHit = null,
                        topPadding = 100.dp, onTap = {}, onVisiblePage = {}, controller = c, handleGestures = false)
                    Surface(Modifier.fillMaxWidth().height(90.dp)) {
                        Box(Modifier.fillMaxSize()) {
                            androidx.compose.material3.IconButton(onClick = { clicks++ }, modifier = Modifier.align(Alignment.CenterEnd).testTag("btn")) {}
                        }
                    }
                    // Stands in for the page slider: a horizontal drag control, like Slider (draggable).
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp).padding(bottom = 0.dp).testTag("slider")
                        .draggable(
                            rememberDraggableState { sliderValue += it / 1000f },
                            Orientation.Horizontal))
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() { src.close(); Library.fileOf(ctx, docId).delete() }

    private fun barBottom() = with(rule.density) { 90.dp.toPx() }

    @Test fun pinchWithUpperFingerOnTitleBarZoomsAroundBothFingers() {
        val bar = barBottom()
        var a = Offset(500f, bar * 0.5f)            // upper finger on the title bar
        var b = Offset(560f, bar + 500f)            // lower finger on the page
        rule.onRoot().performTouchInput { down(0, a); advanceEventTime(60); down(1, b) }
        repeat(14) {
            a += Offset(-8f, -2f); b += Offset(8f, 30f)
            rule.onRoot().performTouchInput { updatePointerTo(0, a); updatePointerTo(1, b); move() }
        }
        rule.onRoot().performTouchInput { up(0); up(1) }
        rule.waitForIdle()
        assertTrue("zoomed with one finger on the title bar (scale=${zoom.scale})", zoom.scale > 1.5f)
    }

    @Test fun fingerLeftAfterPinchKeepsMovingThePage() {
        rule.onRoot().performTouchInput {
            down(0, center - Offset(0f, 100f)); advanceEventTime(60); down(1, center + Offset(0f, 100f))
            repeat(12) { updatePointerBy(0, Offset(0f, -25f)); updatePointerBy(1, Offset(0f, 25f)); move() }
            up(0)                                       // lift one finger, keep the other down
        }
        rule.waitForIdle()
        val s = zoom.scale; val ox = zoom.offsetX; val oy = zoom.offsetY
        rule.onRoot().performTouchInput { repeat(10) { moveBy(1, Offset(-20f, -40f)) } }
        rule.waitForIdle()
        assertEquals("no zoom change while dragging", s, zoom.scale, 0.001f)
        assertTrue("page followed the finger up (offsetY $oy -> ${zoom.offsetY})", zoom.offsetY < oy - 300f)
        assertTrue("page followed the finger left (offsetX $ox -> ${zoom.offsetX})", zoom.offsetX < ox - 150f)
        val atRelease = zoom.offsetY
        rule.onRoot().performTouchInput { up(1) }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        assertEquals("no fling after a pinch", atRelease, zoom.offsetY, 1f)
    }

    @Test fun draggingTheSliderDoesNotMoveThePage() {
        rule.onRoot().performTouchInput {     // zoom in first so the page could move
            down(0, center - Offset(0f, 100f)); advanceEventTime(60); down(1, center + Offset(0f, 100f))
            repeat(12) { updatePointerBy(0, Offset(0f, -25f)); updatePointerBy(1, Offset(0f, 25f)); move() }
            up(0); up(1)
        }
        rule.waitForIdle()
        val ox = zoom.offsetX; val oy = zoom.offsetY
        val sb = rule.onNodeWithTag("slider").getBoundsInRoot()
        rule.onNodeWithTag("slider").performTouchInput { swipe(centerLeft + Offset(20f, 0f), centerRight - Offset(20f, 0f), 400) }
        rule.waitForIdle()
        assertTrue("slider moved (value $sliderValue)", sliderValue > 0.5f)
        assertTrue("page stayed put (dx ${zoom.offsetX - ox}, dy ${zoom.offsetY - oy}, slider $sb)",
            abs(zoom.offsetX - ox) < 2f && abs(zoom.offsetY - oy) < 2f)
    }

    @Test fun sliderWorksAtNormalSize() {
        rule.onNodeWithTag("slider").performTouchInput { swipe(centerLeft + Offset(20f, 0f), centerRight - Offset(20f, 0f), 400) }
        rule.waitForIdle()
        assertTrue("slider moved (value $sliderValue)", sliderValue > 0.5f)
    }

    @Test fun titleBarButtonStillClicks() {
        rule.onNodeWithTag("btn").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }
}
