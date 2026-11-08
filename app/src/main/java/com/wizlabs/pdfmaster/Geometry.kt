package com.wizlabs.pdfmaster

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A point in normalised image space (0..1 on both axes). Pure Kotlin so it can be unit tested. */
data class P(val x: Float, val y: Float)

/** Four document corners in order: top-left, top-right, bottom-right, bottom-left. */
data class Quad(val tl: P, val tr: P, val br: P, val bl: P) {
    val points get() = listOf(tl, tr, br, bl)

    fun with(index: Int, p: P): Quad = when (index) {
        0 -> copy(tl = p); 1 -> copy(tr = p); 2 -> copy(br = p); else -> copy(bl = p)
    }

    /** Area in normalised units (shoelace formula). */
    fun area(): Float {
        val pts = points
        var s = 0f
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    fun isConvex(): Boolean {
        val pts = points
        var sign = 0
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % 4]; val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return sign != 0
    }

    /** Average corner distance to [other]; used to decide whether the live outline is steady. */
    fun distanceTo(other: Quad): Float =
        points.zip(other.points).map { (a, b) -> hypot(a.x - b.x, a.y - b.y) }.average().toFloat()

    fun lerp(other: Quad, t: Float): Quad = Quad(
        lerpP(tl, other.tl, t), lerpP(tr, other.tr, t), lerpP(br, other.br, t), lerpP(bl, other.bl, t)
    )

    /** Rotate the quad together with an image rotated clockwise by 90°. */
    fun rotatedCw(): Quad {
        fun r(p: P) = P(1f - p.y, p.x)
        // After rotating, the old bottom-left becomes the new top-left.
        return Quad(r(bl), r(tl), r(tr), r(br))
    }

    /** Pull every corner [f] of the way towards the centre (trims a sliver of desk at the edges). */
    fun inset(f: Float): Quad {
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        fun m(p: P) = P(p.x + (cx - p.x) * f, p.y + (cy - p.y) * f)
        return Quad(m(tl), m(tr), m(br), m(bl))
    }

    fun clamped(): Quad = Quad(clampP(tl), clampP(tr), clampP(br), clampP(bl))

    companion object {
        val FULL = Quad(P(0f, 0f), P(1f, 0f), P(1f, 1f), P(0f, 1f))

        /** Sort any four points into TL, TR, BR, BL using the classic sum / difference trick. */
        fun ordered(pts: List<P>): Quad {
            require(pts.size == 4)
            val idx = pts.indices.toList()
            val tlI = idx.minBy { pts[it].x + pts[it].y }
            val brI = idx.filter { it != tlI }.maxBy { pts[it].x + pts[it].y }
            val rest = idx.filter { it != tlI && it != brI }
            val trI = rest.minBy { pts[it].y - pts[it].x }
            val blI = rest.first { it != trI }
            return Quad(pts[tlI], pts[trI], pts[brI], pts[blI])
        }
    }
}

private fun lerpP(a: P, b: P, t: Float) = P(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
private fun clampP(p: P) = P(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))

/** Output size of a perspective-corrected page, from a quad in pixel space. */
fun warpSize(q: Quad, imgW: Int, imgH: Int): Pair<Int, Int> {
    fun d(a: P, b: P) = hypot((a.x - b.x) * imgW, (a.y - b.y) * imgH)
    val w = max(d(q.tl, q.tr), d(q.bl, q.br))
    val h = max(d(q.tl, q.bl), d(q.tr, q.br))
    return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
}

/** Scale (w, h) down so the long side is at most [maxSide]. */
fun fitWithin(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
    val longSide = max(w, h)
    if (longSide <= maxSide) return w to h
    val s = maxSide.toFloat() / longSide
    return (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
}

/** Find every case-insensitive occurrence of [query] in [text]; returns start indices. */
fun findAll(text: String, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    val out = ArrayList<Int>()
    val t = text.lowercase()
    val q = query.lowercase()
    var i = t.indexOf(q)
    while (i >= 0) {
        out += i
        i = t.indexOf(q, i + q.length)
    }
    return out
}

/** Human file size, e.g. "1.4 MB". */
fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/** Keep only characters a standard PDF font can encode (for the invisible OCR layer). */
fun pdfSafe(s: String): String = buildString {
    for (c in s) {
        when {
            c.code in 32..126 -> append(c)
            c.code in 160..255 -> append(c)
            c == '‘' || c == '’' -> append('\'')
            c == '“' || c == '”' -> append('"')
            c == '–' || c == '—' -> append('-')
            c == '₹' -> append("Rs")
            else -> {}
        }
    }
}

/** Make a string safe as a file name. */
fun safeFileName(name: String): String =
    name.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").ifBlank { "Document" }.take(80)
