package com.wizlabs.pdfmaster

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The arithmetic behind the scanner: line fitting, outline scoring, page proportions, levels,
 * binarisation and page orientation. Pure Kotlin (no Android, no OpenCV) so it can be unit tested.
 */

/** An infinite line through ([x], [y]) with direction ([dx], [dy]), in any consistent unit. */
data class Line2(val x: Double, val y: Double, val dx: Double, val dy: Double) {
    /** Where this line crosses [o]; null when they are (nearly) parallel. */
    fun intersect(o: Line2): Pair<Double, Double>? {
        val det = dx * o.dy - dy * o.dx
        if (abs(det) < 1e-9 * (hypot(dx, dy) * hypot(o.dx, o.dy)).coerceAtLeast(1e-12)) return null
        val t = ((o.x - x) * o.dy - (o.y - y) * o.dx) / det
        return (x + dx * t) to (y + dy * t)
    }

    /** Unsigned angle to [o] in degrees, 0..90. */
    fun angleTo(o: Line2): Double {
        val c = abs(dx * o.dx + dy * o.dy) / (hypot(dx, dy) * hypot(o.dx, o.dy)).coerceAtLeast(1e-12)
        return Math.toDegrees(acos(c.coerceIn(0.0, 1.0)))
    }

    companion object {
        fun through(ax: Double, ay: Double, bx: Double, by: Double) = Line2(ax, ay, bx - ax, by - ay)
    }
}

object QuadMath {
    /** A corner may move at most this far (share of the picture's long side) when refined. */
    const val REFINE_MAX_MOVE = 0.03f
    /** Width of the search band on each side of an edge, as a share of that edge's length. */
    const val REFINE_BAND = 0.02
    /** Trim applied to a coarse outline, and to one whose corners were refined on the full photo. */
    const val INSET_COARSE = 0.012f
    const val INSET_REFINED = 0.003f

    /**
     * Corners from four edge lines given in the order top, right, bottom, left (normalised units).
     * Null when two neighbouring lines never meet.
     */
    fun fromLines(top: Line2, right: Line2, bottom: Line2, left: Line2): Quad? {
        fun p(a: Line2, b: Line2) = a.intersect(b)?.let { P(it.first.toFloat(), it.second.toFloat()) }
        return Quad(p(left, top) ?: return null, p(top, right) ?: return null, p(right, bottom) ?: return null, p(bottom, left) ?: return null)
    }

    /** A refined outline is only trusted when it is a sane, small correction of the coarse one. */
    fun acceptRefined(coarse: Quad, refined: Quad, maxMove: Float = REFINE_MAX_MOVE): Boolean {
        if (!refined.isConvex()) return false
        if (refined.points.any { it.x.isNaN() || it.y.isNaN() || it.x < -0.02f || it.x > 1.02f || it.y < -0.02f || it.y > 1.02f }) return false
        return coarse.points.zip(refined.points).all { (a, b) -> hypot(a.x - b.x, a.y - b.y) <= maxMove }
    }

    /** 1 when all four corners are right angles (measured in pixels), falling to 0 at 45° off. */
    fun angleScore(q: Quad, w: Int, h: Int): Float {
        val p = q.points
        var s = 0f
        for (i in 0..3) {
            val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
            val ux = (a.x - b.x) * w; val uy = (a.y - b.y) * h
            val vx = (c.x - b.x) * w; val vy = (c.y - b.y) * h
            val n = hypot(ux, uy) * hypot(vx, vy)
            if (n < 1e-6f) return 0f
            val deg = Math.toDegrees(acos(((ux * vx + uy * vy) / n).coerceIn(-1f, 1f)).toDouble()).toFloat()
            s += (1f - abs(deg - 90f) / 45f).coerceIn(0f, 1f)
        }
        return s / 4f
    }

    /** Share of sample points along the four sides that sit on an edge pixel of [edges] ([w]×[h], non-zero = edge). */
    fun edgeSupport(q: Quad, edges: ByteArray, w: Int, h: Int, samplesPerSide: Int = 24): Float {
        val p = q.points
        var hit = 0; var total = 0
        for (i in 0..3) {
            val a = p[i]; val b = p[(i + 1) % 4]
            for (k in 0 until samplesPerSide) {
                // Skip the corners themselves: rounded or dog-eared corners carry no edge.
                val t = 0.08f + 0.84f * (k + 0.5f) / samplesPerSide
                val x = ((a.x + (b.x - a.x) * t) * w).toInt().coerceIn(0, w - 1)
                val y = ((a.y + (b.y - a.y) * t) * h).toInt().coerceIn(0, h - 1)
                total++
                if (edges[y * w + x].toInt() != 0) hit++
            }
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }

    /** Overall "is this the page?" score, 0..1. [prev] is the outline found on the previous frame. */
    fun score(q: Quad, edgeSupport: Float, w: Int, h: Int, prev: Quad?): Float {
        val area = sqrt(q.area().coerceIn(0f, 1f) / 0.6f).coerceAtMost(1f)
        val near = if (prev == null) 0.5f else (1f - prev.distanceTo(q) / 0.15f).coerceIn(0f, 1f)
        return 0.45f * edgeSupport + 0.25f * angleScore(q, w, h) + 0.18f * area + 0.12f * near
    }

    /** Lowest score still shown as an outline. */
    const val MIN_SCORE = 0.30f

    /**
     * Page outlines built from straight line segments ([segs] holds x1,y1,x2,y2 per segment, in
     * pixels of a [w]×[h] image): near-horizontal and near-vertical segments are grouped into
     * edges, and the strongest pairs are crossed. At most [maxQuads] candidates come back.
     */
    fun fromSegments(segs: FloatArray, w: Int, h: Int, maxQuads: Int = 16): List<Quad> {
        class Edge(var pos: Float, var len: Float, var x1: Float, var y1: Float, var x2: Float, var y2: Float)
        val hor = ArrayList<Edge>(); val ver = ArrayList<Edge>()
        val n = segs.size / 4
        for (i in 0 until n) {
            val x1 = segs[4 * i]; val y1 = segs[4 * i + 1]; val x2 = segs[4 * i + 2]; val y2 = segs[4 * i + 3]
            val dx = x2 - x1; val dy = y2 - y1
            val len = hypot(dx, dy)
            if (len < 1f) continue
            val horizontal = abs(dy) <= abs(dx) * 0.58f          // within 30° of the x axis
            val vertical = abs(dx) <= abs(dy) * 0.58f
            if (!horizontal && !vertical) continue
            // Where the segment's line crosses the middle of the picture: its "position".
            val pos = if (horizontal) y1 + dy * ((w / 2f - x1) / dx) else x1 + dx * ((h / 2f - y1) / dy)
            val list = if (horizontal) hor else ver
            val tol = 0.03f * if (horizontal) h else w
            val e = list.firstOrNull { abs(it.pos - pos) <= tol }
            if (e == null) list += Edge(pos, len, x1, y1, x2, y2)
            else {
                // Keep the longest segment as the edge's direction; add up the evidence.
                if (len > hypot(e.x2 - e.x1, e.y2 - e.y1)) { e.x1 = x1; e.y1 = y1; e.x2 = x2; e.y2 = y2; e.pos = pos }
                e.len += len
            }
        }
        val hs = hor.sortedByDescending { it.len }.take(4)
        val vs = ver.sortedByDescending { it.len }.take(4)
        fun line(e: Edge) = Line2.through(e.x1 / w.toDouble(), e.y1 / h.toDouble(), e.x2 / w.toDouble(), e.y2 / h.toDouble())
        val out = ArrayList<Pair<Quad, Float>>()
        for (a in hs.indices) for (b in a + 1 until hs.size) {
            val (top, bottom) = if (hs[a].pos < hs[b].pos) hs[a] to hs[b] else hs[b] to hs[a]
            if (bottom.pos - top.pos < 0.2f * h) continue
            for (c in vs.indices) for (d in c + 1 until vs.size) {
                val (left, right) = if (vs[c].pos < vs[d].pos) vs[c] to vs[d] else vs[d] to vs[c]
                if (right.pos - left.pos < 0.2f * w) continue
                val q = fromLines(line(top), line(right), line(bottom), line(left)) ?: continue
                if (q.points.any { it.x < -0.05f || it.x > 1.05f || it.y < -0.05f || it.y > 1.05f }) continue
                val c2 = q.clamped()
                if (!c2.isConvex() || c2.area() < 0.12f) continue
                out += c2 to (top.len + bottom.len + left.len + right.len)
            }
        }
        return out.sortedByDescending { it.second }.take(maxQuads).map { it.first }
    }
}

/** Standard paper and card proportions (long side : short side). */
enum class PaperShape(val label: String, val ratio: Float) {
    A4("A4", 1.4142f), LETTER("Letter", 1.2941f), ID1("ID card", 85.6f / 54f)
}

object AspectSnap {
    const val TOLERANCE = 0.04f

    /** The standard shape a [w]×[h] page is within [tolerance] of, if any. */
    fun shapeOf(w: Int, h: Int, tolerance: Float = TOLERANCE): PaperShape? {
        if (w <= 0 || h <= 0) return null
        val r = max(w, h).toFloat() / min(w, h)
        return PaperShape.entries.map { it to abs(r / it.ratio - 1f) }.filter { it.second <= tolerance }.minByOrNull { it.second }?.first
    }

    /** Give a page exactly [shape]'s proportions, keeping its long side and its orientation. */
    fun force(w: Int, h: Int, shape: PaperShape): Pair<Int, Int> {
        val long = max(w, h)
        val short = (long / shape.ratio).roundToInt().coerceAtLeast(1)
        return if (w >= h) long to short else short to long
    }

    /** Snap to A4, Letter or ID-1 when close enough; otherwise the size is returned unchanged. */
    fun snap(w: Int, h: Int, tolerance: Float = TOLERANCE): Pair<Int, Int> =
        shapeOf(w, h, tolerance)?.let { force(w, h, it) } ?: (w to h)
}

object Levels {
    /** Smallest value v such that at least [fraction] of the pixels counted in [hist] are ≤ v. */
    fun percentile(hist: IntArray, fraction: Double): Int {
        val total = hist.sumOf { it.toLong() }
        if (total == 0L) return 0
        val want = (total * fraction).toLong().coerceAtLeast(1)
        var acc = 0L
        for (i in hist.indices) { acc += hist[i]; if (acc >= want) return i }
        return hist.lastIndex
    }

    /**
     * Gain and offset (out = in·gain + offset) that stretch the 3rd percentile to black and the
     * 95th to white. Guarded so a nearly blank page or a dark photo is not blown apart.
     */
    fun stretch(hist: IntArray, lowP: Double = 0.03, highP: Double = 0.95): Pair<Double, Double> {
        val black = percentile(hist, lowP).coerceIn(0, 90)
        val white = percentile(hist, highP).coerceIn(170, 255)
        val gain = (255.0 / (white - black)).coerceIn(1.0, 1.6)
        return gain to -black * gain
    }
}

object Sauvola {
    const val K = 0.25
    const val R = 128.0

    /** Odd window of about a 40th of the long side, kept between 15 and 61 px. */
    fun window(maxSide: Int): Int = (maxSide / 40).let { if (it % 2 == 0) it + 1 else it }.coerceIn(15, 61)

    /** Local threshold: mean · (1 + k · (stddev / R − 1)). */
    fun threshold(mean: Double, std: Double, k: Double = K, r: Double = R): Double = mean * (1 + k * (std / r - 1))

    /**
     * Reference implementation on a plain gray array (0..255) using summed-area tables, with the
     * border replicated. Returns 255 for paper and 0 for ink. The app runs the same maths in OpenCV.
     */
    fun binarize(gray: IntArray, w: Int, h: Int, window: Int, k: Double = K, r: Double = R): IntArray {
        val rad = window / 2
        val pw = w + 2 * rad; val ph = h + 2 * rad
        val sum = DoubleArray((pw + 1) * (ph + 1)); val sq = DoubleArray((pw + 1) * (ph + 1))
        for (y in 0 until ph) {
            val sy = (y - rad).coerceIn(0, h - 1)
            var rowS = 0.0; var rowQ = 0.0
            for (x in 0 until pw) {
                val v = gray[sy * w + (x - rad).coerceIn(0, w - 1)].toDouble()
                rowS += v; rowQ += v * v
                sum[(y + 1) * (pw + 1) + x + 1] = sum[y * (pw + 1) + x + 1] + rowS
                sq[(y + 1) * (pw + 1) + x + 1] = sq[y * (pw + 1) + x + 1] + rowQ
            }
        }
        val n = (window * window).toDouble()
        val out = IntArray(w * h)
        fun box(t: DoubleArray, x: Int, y: Int) =
            t[(y + window) * (pw + 1) + x + window] - t[y * (pw + 1) + x + window] - t[(y + window) * (pw + 1) + x] + t[y * (pw + 1) + x]
        for (y in 0 until h) for (x in 0 until w) {
            val mean = box(sum, x, y) / n
            val std = sqrt((box(sq, x, y) / n - mean * mean).coerceAtLeast(0.0))
            out[y * w + x] = if (gray[y * w + x] > threshold(mean, std, k, r)) 255 else 0
        }
        return out
    }
}

object AutoLook {
    /**
     * Pick a look from a few page statistics, all shares of the page area unless noted:
     * [colourful] strongly coloured pixels, [meanSaturation] (0..255) of everything that is not
     * paper, [content] non-paper pixels, [midtones] pixels that are neither paper nor ink.
     */
    fun decide(colourful: Float, meanSaturation: Float, content: Float, midtones: Float): ScanFilter = when {
        colourful > 0.015f || (meanSaturation > 70f && content > 0.01f) -> ScanFilter.MAGIC
        content < 0.30f && midtones < 0.10f -> ScanFilter.BW       // ink on paper: text, forms, line art
        else -> ScanFilter.GRAY                                     // pencil, photos, soft shading
    }
}

object PageAngle {
    /** A skew this small is left alone; anything above [MAX_SKEW] is not a skew but a guess. */
    const val MIN_SKEW = 0.4f
    const val MAX_SKEW = 10f

    /** Quarter turns clockwise (as degrees, 0/90/180/270) to bring the page upright, plus the skew left over. */
    data class Fix(val rotate: Int, val skew: Float)

    private fun norm(a: Float): Float { var v = a % 360f; if (v > 180f) v -= 360f; if (v <= -180f) v += 360f; return v }

    /**
     * [angles] are the clockwise angles of the text lines (degrees), [weights] how much each line
     * counts (its length). Null when there is too little text to judge.
     */
    fun fix(angles: FloatArray, weights: FloatArray, minWeight: Float = 12f): Fix? {
        if (angles.isEmpty() || angles.size != weights.size || weights.sum() < minWeight) return null
        // Vote for the quarter turn first, then take the weighted median of what is left over.
        val votes = FloatArray(4)
        for (i in angles.indices) votes[(((norm(angles[i]) / 90f).roundToInt() % 4) + 4) % 4] += weights[i]
        val quarter = votes.indices.maxBy { votes[it] }
        if (votes[quarter] < 0.6f * weights.sum()) return null      // lines disagree: leave the page alone
        val rest = angles.indices.filter { (((norm(angles[it]) / 90f).roundToInt() % 4) + 4) % 4 == quarter }
            .map { norm(angles[it] - quarter * 90f) to weights[it] }.sortedBy { it.first }
        var acc = 0f; val half = rest.sumOf { it.second.toDouble() }.toFloat() / 2f
        var skew = 0f
        for ((a, wt) in rest) { acc += wt; if (acc >= half) { skew = a; break } }
        if (abs(skew) < MIN_SKEW || abs(skew) > MAX_SKEW) skew = 0f
        return Fix((360 - quarter * 90) % 360, skew)
    }
}

/** Where ID cards go on a sheet, in PDF points (origin bottom-left, like PDF). */
object IdCardLayout {
    const val MM = 72f / 25.4f
    const val CARD_W = 85.6f * MM
    const val CARD_H = 54f * MM
    const val GAP = 14f * MM
    const val PER_SHEET = 2

    data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * Boxes for the cards on one sheet, first card on top. [landscape] says, per card, whether its
     * picture is wider than tall; an upright picture gets an upright card. Everything is at real
     * size, centred, stacked with a gap (shrunk only if the sheet is too small).
     */
    fun boxes(pageW: Float, pageH: Float, landscape: List<Boolean>): List<Box> {
        if (landscape.isEmpty()) return emptyList()
        val sizes = landscape.map { if (it) CARD_W to CARD_H else CARD_H to CARD_W }
        val totalH = sizes.sumOf { it.second.toDouble() }.toFloat() + GAP * (sizes.size - 1)
        val maxW = sizes.maxOf { it.first }
        val s = min(1f, min((pageW * 0.94f) / maxW, (pageH * 0.94f) / totalH))
        var top = (pageH + totalH * s) / 2f
        return sizes.map { (cw, ch) ->
            val b = Box((pageW - cw * s) / 2f, top - ch * s, cw * s, ch * s)
            top -= ch * s + GAP * s
            b
        }
    }
}

/** Pixel width for rendering a page [widthPt] points wide at [dpi], capped at [maxPx] on the long side. */
fun exportWidthPx(widthPt: Int, heightPt: Int, dpi: Int, maxPx: Int = 4096): Int {
    val w = widthPt * dpi / 72f
    val h = heightPt * dpi / 72f
    val s = min(1f, maxPx / max(w, h))
    return (w * s).roundToInt().coerceAtLeast(1)
}

/** File name for one exported page picture, e.g. "Invoice p03.jpg". */
fun pageImageName(docName: String, page: Int, pageCount: Int): String {
    val digits = pageCount.toString().length.coerceAtLeast(2)
    return safeFileName(docName).take(60) + " p" + (page + 1).toString().padStart(digits, '0') + ".jpg"
}
