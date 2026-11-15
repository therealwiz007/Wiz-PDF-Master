package com.wizlabs.pdfmaster

/** Tunable numbers for live scanning, in one place. Pure Kotlin so the logic can be unit tested. */
object ScanTuning {
    /** How long the outline must hold still before auto-capture fires. */
    const val STEADY_MS = 800L
    /** Minimum Laplacian variance inside the page on the ~480 px analysis frame. Lower = blurry. */
    const val BLUR_MIN_LIVE = 90.0
    /** Same measure on the ~1000 px captured photo; below this the "Looks blurry" hint appears. */
    const val BLUR_MIN_CAPTURE = 60.0
    /** A page with very little print never looks "sharp": after this long, capture anyway. */
    const val BLUR_GRACE_MS = 2500L
    /** Share of blown-out, colourless pixels inside the page that triggers the glare hint. */
    const val GLARE_FRACTION = 0.015f
    /** Minimum gap between analysed frames (about 9 per second). */
    const val ANALYSIS_INTERVAL_MS = 110L
    /** Longest we wait for focus + exposure to settle before taking the photo. */
    const val FOCUS_TIMEOUT_MS = 1500L
}

/** True when a sharpness reading is good enough to shoot. */
fun isSharp(laplacianVariance: Double, min: Double = ScanTuning.BLUR_MIN_LIVE): Boolean = laplacianVariance >= min

/**
 * Smooths the live outline and measures how long it has been steady. After a capture it stays
 * disarmed until the page is taken away or replaced, so batch mode never shoots the same sheet twice.
 * A blurry frame holds the countdown just short of firing (for at most [blurGraceMs]).
 */
class StabilityTracker(
    private val steadyMs: Long = ScanTuning.STEADY_MS,
    private val blurGraceMs: Long = ScanTuning.BLUR_GRACE_MS
) {
    private var shown: Quad? = null
    private var lastSeen = 0L
    private var steadySince = 0L
    private var cooldownUntil = 0L
    private var armed = true
    private var capturedQuad: Quad? = null

    /** True while the countdown is finished but the picture is still too soft to shoot. */
    @Volatile var blurHeld = false
        private set

    @Synchronized fun reset(now: Long = System.currentTimeMillis()) {
        steadySince = 0L
        cooldownUntil = now + 1200
        armed = false
        capturedQuad = shown
        blurHeld = false
    }

    @Synchronized fun feed(q: Quad?, now: Long, sharp: Boolean = true): Pair<Quad?, Float> {
        blurHeld = false
        if (q == null) {
            if (now - lastSeen > 450) {
                shown = null; steadySince = 0L
                armed = true          // page removed: ready for the next one
            }
            return shown to 0f
        }
        lastSeen = now
        val prev = shown
        if (prev == null || prev.distanceTo(q) > 0.08f) {
            shown = q; steadySince = now
        } else {
            val moved = prev.distanceTo(q)
            shown = prev.lerp(q, 0.45f)
            if (moved > 0.02f) steadySince = now
        }
        if (!armed) {
            val cq = capturedQuad
            if (cq == null || cq.distanceTo(q) > 0.12f) { armed = true; steadySince = now } else return shown to 0f
        }
        if (now < cooldownUntil) return shown to 0f
        // Only auto-capture something page-sized; small rectangles (screens, frames) just get outlined.
        if ((shown?.area() ?: 0f) < 0.20f) return shown to 0f
        if (steadySince == 0L) return shown to 0f
        val held = now - steadySince
        val progress = (held / steadyMs.toFloat()).coerceIn(0f, 1f)
        if (progress >= 1f && !sharp && held < steadyMs + blurGraceMs) {
            blurHeld = true
            return shown to 0.97f
        }
        return shown to progress
    }
}

/** Rotate a quad found on an unrotated sensor frame so it matches the upright picture. */
fun Quad.rotatedBy(degrees: Int): Quad {
    var q = this
    repeat((((degrees % 360) + 360) % 360) / 90) { q = q.rotatedCw() }
    return q
}
