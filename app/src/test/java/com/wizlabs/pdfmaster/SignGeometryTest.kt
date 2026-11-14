package com.wizlabs.pdfmaster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignGeometryTest {
    private fun near(e: Float, a: Float, tol: Float = 0.01f) = assertEquals(e, a, tol)
    private fun near(e: P, a: P, tol: Float = 0.01f) { near(e.x, a.x, tol); near(e.y, a.y, tol) }
    private fun pd(cx: Float, cy: Float, w: Float, aspect: Float, rot: Float = 0f) =
        PlacementData("s", 0, cx, cy, w, rot, 0, aspect)

    @Test fun identityAndAssociativity() {
        val a = Affine(1f, 2f, 3f, 4f, 5f, 6f)
        assertEquals(a, a * Affine.IDENTITY)
        assertEquals(a, Affine.IDENTITY * a)
        val b = Affine(0f, 1f, -1f, 0f, 10f, 0f)
        val c = Affine(2f, 0f, 0f, 3f, 1f, 1f)
        val l = (a * b) * c; val r = a * (b * c)
        listOf(l.a to r.a, l.b to r.b, l.c to r.c, l.d to r.d, l.e to r.e, l.f to r.f).forEach { (x, y) -> near(x, y) }
    }

    @Test fun rotation0Vector() {
        val m = SignGeometry.placementMatrix(pd(.5f, .25f, .5f, 2f), 0, 0f, 0f, 600f, 800f)
        near(300f, m.a); near(0f, m.b); near(0f, m.c); near(150f, m.d); near(150f, m.e); near(525f, m.f)
    }

    @Test fun rotation90Vector() {
        val m = SignGeometry.placementMatrix(pd(.5f, .5f, .25f, 2f), 90, 0f, 0f, 600f, 800f)
        near(0f, m.a); near(200f, m.b); near(-100f, m.c); near(0f, m.d); near(350f, m.e); near(300f, m.f)
    }

    @Test fun displayCornersMapToUserCornersForEveryRotation() {
        val bx = 20f; val by = 30f; val bw = 560f; val bh = 740f
        for (rot in listOf(0, 90, 180, 270)) {
            val (W, H) = SignGeometry.displaySize(rot, bw, bh)
            val t = SignGeometry.displayToUser(rot, bx, by, bw, bh)
            // Every display corner must land on a crop-box corner, and the centre on the centre.
            val corners = setOf(P(bx, by), P(bx + bw, by), P(bx, by + bh), P(bx + bw, by + bh))
            for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                val p = t.map(u * W, (1 - v) * H)
                assertTrue("rot $rot corner $u,$v -> $p", corners.any { kotlin.math.abs(it.x - p.x) < .01f && kotlin.math.abs(it.y - p.y) < .01f })
            }
            near(P(bx + bw / 2, by + bh / 2), t.map(W / 2, H / 2))
        }
        // Rotate 90: the display's top-left is the unrotated page's bottom-left.
        near(P(bx, by), SignGeometry.displayToUser(90, bx, by, bw, bh).map(0f, bw))
        // Rotate 270: the display's top-left is the unrotated page's top-right.
        near(P(bx + bw, by + bh), SignGeometry.displayToUser(270, bx, by, bw, bh).map(0f, bw))
    }

    @Test fun rotated90OnScreenPointsImageXDown() {
        val m = SignGeometry.displayMatrix(pd(.5f, .5f, .2f, 2f, rot = 90f), 100f, 100f)
        // Image x-axis (a, b) points down the screen = -y in y-up space.
        near(0f, m.a); assertTrue(m.b < 0f)
    }

    @Test fun hitTestUnderRotation() {
        assertTrue(SignGeometry.hit(140f, 100f, 100f, 100f, 100f, 20f, 0f))
        assertFalse(SignGeometry.hit(100f, 140f, 100f, 100f, 100f, 20f, 0f))
        assertTrue(SignGeometry.hit(100f, 140f, 100f, 100f, 100f, 20f, 90f))
        assertFalse(SignGeometry.hit(140f, 100f, 100f, 100f, 100f, 20f, 90f))
    }

    @Test fun snapping() {
        near(0f, SignGeometry.snapRotation(3.9f))
        near(4.1f, SignGeometry.snapRotation(4.1f))
        near(270f, SignGeometry.snapRotation(268f))
        near(0f, SignGeometry.snapRotation(-2f))
        near(0f, SignGeometry.snapRotation(358f))
    }

    @Test fun uniqueNames() {
        assertEquals("A (signed)", SignGeometry.uniqueName("A", emptySet()))
        assertEquals("A (signed 2)", SignGeometry.uniqueName("A", setOf("A (signed)")))
        assertEquals("A (signed 3)", SignGeometry.uniqueName("A", setOf("A (signed)", "A (signed 2)")))
    }

    @Test fun thresholdsFollowSlider() {
        near(60f, SignGeometry.thresholds(60.0, .5f).first.toFloat())
        assertTrue(SignGeometry.thresholds(60.0, .2f).first > SignGeometry.thresholds(60.0, .8f).first)
    }
}
