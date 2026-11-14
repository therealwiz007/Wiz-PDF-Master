package com.wizlabs.pdfmaster

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 2-D affine transform in PDF convention: x' = a·x + c·y + e ; y' = b·x + d·y + f.
 * Pure Kotlin so the signature maths can be unit-tested on the JVM.
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun map(x: Float, y: Float) = P(a * x + c * y + e, b * x + d * y + f)

    /** `this × o`: apply [o] first, then this. */
    operator fun times(o: Affine) = Affine(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f
    )

    companion object { val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f) }
}

/** Where a signature sits on a page, in display terms (what the user sees). */
data class PlacementData(
    val sigId: String,
    val page: Int,
    /** Centre, 0..1 of the displayed page, top-left origin. */
    val cx: Float,
    val cy: Float,
    /** Width as a fraction of the displayed page width. */
    val w: Float,
    /** Degrees, clockwise on screen. */
    val rot: Float,
    /** ARGB ink; 0 = original colours. */
    val ink: Int,
    /** Signature image width / height. */
    val aspect: Float
)

object SignGeometry {
    fun normRotation(r: Int): Int {
        val n = ((r % 360) + 360) % 360
        return if (n % 90 == 0) n else 0
    }

    /** Displayed page size for a crop box of [bw]×[bh] with /Rotate [rot]. */
    fun displaySize(rot: Int, bw: Float, bh: Float): Pair<Float, Float> =
        if (normRotation(rot) % 180 == 0) bw to bh else bh to bw

    /**
     * Maps display points (y-up, origin at the displayed page's bottom-left, in points) to PDF user
     * space, for a page whose crop box has lower-left ([bx],[by]) and size [bw]×[bh].
     */
    fun displayToUser(rot: Int, bx: Float, by: Float, bw: Float, bh: Float): Affine = when (normRotation(rot)) {
        90 -> Affine(0f, 1f, -1f, 0f, bx + bw, by)
        180 -> Affine(-1f, 0f, 0f, -1f, bx + bw, by + bh)
        270 -> Affine(0f, -1f, 1f, 0f, bx, by + bh)
        else -> Affine(1f, 0f, 0f, 1f, bx, by)
    }

    /** Unit square (image space) to display y-up points, upright image rotated clockwise on screen. */
    fun displayMatrix(p: PlacementData, W: Float, H: Float): Affine {
        val wd = p.w * W
        val hd = wd / p.aspect.coerceAtLeast(0.01f)
        val xc = p.cx * W
        val yc = (1f - p.cy) * H
        val phi = Math.toRadians(-p.rot.toDouble())
        val cs = cos(phi).toFloat(); val sn = sin(phi).toFloat()
        val a = wd * cs; val b = wd * sn; val c = -hd * sn; val d = hd * cs
        return Affine(a, b, c, d, xc - (a + c) / 2f, yc - (b + d) / 2f)
    }

    /** Full matrix for `cs.transform(); cs.drawImage(img, 0, 0, 1, 1)`. */
    fun placementMatrix(p: PlacementData, rot: Int, bx: Float, by: Float, bw: Float, bh: Float): Affine {
        val (W, H) = displaySize(rot, bw, bh)
        return displayToUser(rot, bx, by, bw, bh) * displayMatrix(p, W, H)
    }

    /** Snap to a right angle when within [tol] degrees; result in 0..360. */
    fun snapRotation(deg: Float, tol: Float = 4f): Float {
        val n = ((deg % 360f) + 360f) % 360f
        val nearest = (n / 90f).roundToInt() * 90f
        return if (abs(n - nearest) <= tol) nearest % 360f else n
    }

    /**
     * Hit-test a point (local px of the page) against a placement drawn at centre ([cx],[cy]) px,
     * size [w]×[h] px, rotated [rot] degrees clockwise. [slop] grows the box.
     */
    fun hit(px: Float, py: Float, cx: Float, cy: Float, w: Float, h: Float, rot: Float, slop: Float = 0f): Boolean {
        val r = Math.toRadians(-rot.toDouble())
        val dx = px - cx; val dy = py - cy
        val lx = (dx * cos(r) - dy * sin(r)).toFloat()
        val ly = (dx * sin(r) + dy * cos(r)).toFloat()
        return abs(lx) <= w / 2f + slop && abs(ly) <= h / 2f + slop
    }

    /** Rotate (x, y) by [deg] clockwise (screen, y-down) about the origin. */
    fun rotate(x: Float, y: Float, deg: Float): P {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return P(x * c - y * s, x * s + y * c)
    }

    /** "Lease (signed)", then "Lease (signed 2)" … avoiding [taken]. */
    fun uniqueName(base: String, taken: Set<String>): String {
        val root = "$base (signed)"
        if (root !in taken) return root
        var n = 2
        while ("$base (signed $n)" in taken) n++
        return "$base (signed $n)"
    }

    /** Cut-out threshold and soft-edge range from Otsu's value and the user's slider (0..1). */
    fun thresholds(otsu: Double, s: Float): Triple<Double, Double, Double> {
        val t = (otsu * (1.6 - 1.2 * s)).coerceIn(8.0, 200.0)
        return Triple(t, 0.55 * t, 1.15 * t)
    }
}
