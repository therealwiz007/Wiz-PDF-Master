package com.wizlabs.pdfmaster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.hypot

/** The OpenCV side of scanning, on drawn test pictures: outline, corner refinement, warp and looks. */
@RunWith(AndroidJUnit4::class)
class ScanEngineTest {
    @Before fun load() { assertTrue(ScanEngine.ensure()) }

    private val corners = Quad(P(.18f, .12f), P(.84f, .16f), P(.88f, .86f), P(.14f, .82f))

    /** A white sheet with rows of "print" on a dark desk, seen slightly in perspective. */
    private fun photo(w: Int, h: Int, q: Quad = corners, paper: Int = Color.rgb(240, 238, 232)): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(70, 52, 40))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = paper
        c.drawPath(Path().apply {
            moveTo(q.tl.x * w, q.tl.y * h); lineTo(q.tr.x * w, q.tr.y * h)
            lineTo(q.br.x * w, q.br.y * h); lineTo(q.bl.x * w, q.bl.y * h); close()
        }, p)
        p.color = Color.rgb(30, 30, 34)
        for (row in 0 until 14) {
            val y = (0.24f + row * 0.04f) * h
            c.drawRect(0.28f * w, y, (0.72f - (row % 3) * 0.06f) * w, y + 0.008f * h, p)
        }
        return bmp
    }

    private fun worstCorner(a: Quad, b: Quad) = a.points.zip(b.points).maxOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) }

    @Test fun findsTheSheet() {
        val bmp = photo(960, 1280)
        val raw = ScanEngine.detectRaw(bmp)
        assertNotNull(raw)
        assertTrue("coarse outline within 2%: ${worstCorner(raw!!, corners)}", worstCorner(raw, corners) < 0.02f)
        assertNotNull(ScanEngine.detect(bmp))
    }

    @Test fun refinementTightensTheCorners() {
        val bmp = photo(1500, 2000)
        val raw = ScanEngine.detectRaw(bmp)!!
        // Start from a deliberately sloppy outline, as a 480 px frame would give.
        val sloppy = Quad(
            P(raw.tl.x + .008f, raw.tl.y - .006f), P(raw.tr.x - .007f, raw.tr.y + .008f),
            P(raw.br.x + .006f, raw.br.y + .007f), P(raw.bl.x - .008f, raw.bl.y - .005f)
        )
        val refined = ScanEngine.refine(bmp, sloppy)
        assertNotNull("refined", refined)
        val err = worstCorner(refined!!, corners)
        assertTrue("refined corners within 0.3%: $err", err < 0.003f)
        assertTrue(err < worstCorner(sloppy, corners))
    }

    @Test fun refinementGivesUpWhereThereIsNoEdge() {
        val blank = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        assertEquals(null, ScanEngine.refine(blank, corners))
        assertEquals(null, ScanEngine.refine(blank, Quad.FULL))
    }

    @Test fun warpSnapsToPaperShapes() {
        // A sheet of A4 proportions photographed straight on.
        val q = Quad(P(.1f, .1f), P(.9f, .1f), P(.9f, .1f + .8f * 1.40f * 0.75f), P(.1f, .1f + .8f * 1.40f * 0.75f))
        val out = ScanEngine.warp(photo(900, 1200, q), q)
        assertEquals(PaperShape.A4.ratio, out.height / out.width.toFloat(), 0.005f)
        val card = ScanEngine.warp(photo(900, 1200, q), q, 0f, PaperShape.ID1)
        assertEquals(PaperShape.ID1.ratio, card.height / card.width.toFloat(), 0.01f)
        // An uncropped photo keeps its own shape.
        val full = ScanEngine.warp(photo(900, 1200), Quad.FULL)
        assertEquals(900, full.width); assertEquals(1200, full.height)
    }

    @Test fun warpLevelsTiltedText() {
        // A full-frame page whose single dark bar runs downhill to the right by 5° (clockwise).
        val w = 800; val h = 600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        c.save(); c.rotate(5f, w / 2f, h / 2f)
        c.drawRect(150f, 290f, 650f, 310f, Paint().apply { color = Color.BLACK })
        c.restore()
        fun barY(b: Bitmap, x: Int): Float {
            var sum = 0f; var n = 0
            for (y in 0 until b.height) if (Color.red(b.getPixel(x, y)) < 100) { sum += y; n++ }
            return sum / n
        }
        assertTrue(barY(bmp, 600) - barY(bmp, 200) > 25f)
        val level = ScanEngine.warp(bmp, Quad.FULL, skew = 5f)
        assertEquals(w, level.width)
        assertTrue("bar is level", abs(barY(level, 600) - barY(level, 200)) < 2f)
    }

    @Test fun blackAndWhiteSurvivesAShadow() {
        val w = 900; val h = 1200
        val bmp = photo(w, h, Quad.FULL)
        // A soft shadow over the right half: paper there drops to about 45% brightness.
        val c = Canvas(bmp)
        val shade = Paint()
        for (x in w / 2 until w) {
            shade.color = Color.argb((140f * (x - w / 2) / (w / 2)).toInt(), 0, 0, 0)
            c.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), shade)
        }
        val bw = ScanEngine.filter(bmp, ScanFilter.BW)
        assertEquals(w, bw.width)
        var other = 0; var black = 0
        val px = IntArray(w * h); bw.getPixels(px, 0, w, 0, 0, w, h)
        for (v in px) when (v and 0xFF) { 0 -> black++; 255 -> {} else -> other++ }
        assertEquals("pure black or white only", 0, other)
        assertTrue("print kept: $black", black > w * h * 0.01)
        assertTrue("shadow not turned into ink: $black", black < w * h * 0.08)
        // Paper in the shadow is white, a bar of print in the shadow is black.
        assertEquals(255, px[(h / 2 + 30) * w + (w * 0.9f).toInt()] and 0xFF)
        assertEquals(0, px[(0.244f * h).toInt() * w + (w * 0.6f).toInt()] and 0xFF)
    }

    @Test fun looksKeepSizeAndBrightenPaper() {
        val bmp = photo(600, 800, Quad.FULL, paper = Color.rgb(190, 186, 176))
        for (f in ScanFilter.entries) {
            val out = ScanEngine.filter(bmp, f)
            assertEquals(f.name, 600, out.width); assertEquals(f.name, 800, out.height)
            if (f != ScanFilter.ORIGINAL) assertTrue("${f.name} paper is white", Color.green(out.getPixel(60, 60)) > 235)
            out.recycle()
        }
        assertTrue(Color.red(ScanEngine.filter(bmp, ScanFilter.MAGIC).getPixel(300, (0.244f * 800).toInt())) < 80)
        val ocr = ScanEngine.ocrImage(bmp, 400)
        assertEquals(400, ocr.height)
    }

    @Test fun autoLookTellsTextFromColour() {
        val text = photo(600, 800, Quad.FULL)
        assertEquals(ScanFilter.BW, ScanEngine.pickFilter(text))
        val colour = photo(600, 800, Quad.FULL)
        Canvas(colour).drawRect(60f, 500f, 540f, 760f, Paint().apply { color = Color.rgb(220, 40, 40) })
        assertEquals(ScanFilter.MAGIC, ScanEngine.pickFilter(colour))
        // Soft shading all over (a pencil drawing, a photo) is neither.
        val soft = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val c = Canvas(soft); val p = Paint()
        val rnd = java.util.Random(7)
        for (ty in 0 until 800 step 10) for (tx in 0 until 600 step 10) {
            val v = 70 + rnd.nextInt(150); p.color = Color.rgb(v, v, v)
            c.drawRect(tx.toFloat(), ty.toFloat(), tx + 10f, ty + 10f, p)
        }
        assertEquals(ScanFilter.GRAY, ScanEngine.pickFilter(soft))
    }
}
