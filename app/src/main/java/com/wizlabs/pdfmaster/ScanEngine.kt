package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

enum class ScanFilter(val label: String) {
    AUTO("Auto"), ORIGINAL("Original"), MAGIC("Magic"), GRAY("Grayscale"), BW("B & W"), LIGHTEN("No shadow")
}

/**
 * All the image work for scanning. Every function here is synchronous and CPU-bound; call it from
 * Dispatchers.Default. Mats are always released in finally blocks so long sessions don't leak.
 */
object ScanEngine {
    @Volatile var ready = false

    fun ensure(): Boolean {
        if (!ready) ready = OpenCVLoader.initLocal()
        return ready
    }

    /** Longest side we keep for captured pages: plenty for 300-dpi A4 while staying memory-safe. */
    const val MAX_SIDE = 3200

    // ---- Detection -----------------------------------------------------------------------

    /** Find the document outline (slightly trimmed). Returns null when nothing page-like is visible. */
    fun detect(src: Bitmap): Quad? = detectRaw(src)?.inset(QuadMath.INSET_COARSE)

    /** The outline exactly as found, without the safety trim. */
    fun detectRaw(src: Bitmap): Quad? {
        if (!ensure()) return null
        val (w, h) = fitWithin(src.width, src.height, 480)
        val small = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val rgba = Mat(); val gray = Mat()
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return detectRaw(gray, null) { saturationOf(rgba) }
        } catch (_: Throwable) {
            return null
        } finally {
            rgba.release(); gray.release()
            if (small !== src) small.recycle()
        }
    }

    /** [detectRaw] with the usual trim, for callers that use the outline as is. */
    fun detectGray(gray: Mat, prev: Quad? = null, saturation: () -> Mat?): Quad? =
        detectRaw(gray, prev, saturation)?.inset(QuadMath.INSET_COARSE)

    /** Reused per thread: the edge map of one small frame, read when scoring candidates. */
    private val edgeBuf = ThreadLocal<ByteArray>()

    /**
     * Find the outline on a small, unblurred gray frame (the camera's Y plane works as is).
     * Every strategy (contours of the edge map, straight lines, a brightness mask and, when those
     * are unconvincing, a colour mask) proposes outlines; the one with the best score wins.
     * [prev] is the outline of the previous frame, which steadies the choice. [saturation] returns
     * a single-channel "how colourful" image of any size, which this function releases.
     */
    fun detectRaw(gray: Mat, prev: Quad?, saturation: () -> Mat?): Quad? {
        val w = gray.cols(); val h = gray.rows()
        val blurred = Mat(); val thin = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            val cands = ArrayList<Quad>(24)
            val canny = edgesCanny(blurred, thin)
            var edges = edgeBuf.get()
            if (edges == null || edges.size != w * h) { edges = ByteArray(w * h); edgeBuf.set(edges) }
            canny.get(0, 0, edges)
            contourQuads(canny, cands)
            lineQuads(thin, cands)
            contourQuads(edgesThreshold(blurred), cands)
            var best = bestOf(cands, edges, w, h, prev)
            if (best == null || best.second < 0.5f) {
                val before = cands.size
                saturation()?.let { contourQuads(saturationMask(it), cands) }
                if (cands.size > before) best = bestOf(cands, edges, w, h, prev)
            }
            return best?.takeIf { it.second >= QuadMath.MIN_SCORE }?.first
        } finally {
            blurred.release(); thin.release()
        }
    }

    private fun bestOf(cands: List<Quad>, edges: ByteArray, w: Int, h: Int, prev: Quad?): Pair<Quad, Float>? {
        var best: Quad? = null; var bestScore = -1f
        for (q in cands) {
            val s = QuadMath.score(q, QuadMath.edgeSupport(q, edges, w, h), w, h, prev)
            if (s > bestScore) { bestScore = s; best = q }
        }
        return best?.let { it to bestScore }
    }

    /** Thick, closed edge map for contours; [thin] receives the raw one-pixel edges for line finding. */
    private fun edgesCanny(gray: Mat, thin: Mat): Mat {
        val med = median(gray)
        val lo = max(10.0, 0.66 * med)
        val hi = min(255.0, max(lo + 30, 1.33 * med))
        val edges = Mat()
        Imgproc.Canny(gray, thin, lo, hi)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(thin, edges, k, Point(-1.0, -1.0), 2)
        Imgproc.erode(edges, edges, k)
        k.release()
        return edges
    }

    private fun edgesThreshold(gray: Mat): Mat {
        val bin = Mat()
        Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, k)
        k.release()
        return bin
    }

    /** The S channel of an RGBA image. */
    private fun saturationOf(rgba: Mat): Mat {
        val hsv = Mat(); val rgb = Mat()
        val channels = ArrayList<Mat>()
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.split(hsv, channels)
            return channels.removeAt(1)
        } finally {
            hsv.release(); rgb.release(); channels.forEach { it.release() }
        }
    }

    /** White paper on a coloured desk: low saturation stands out. Consumes (releases) [s]. */
    private fun saturationMask(s: Mat): Mat {
        val inv = Mat()
        try {
            Core.bitwise_not(s, inv)
            Imgproc.GaussianBlur(inv, inv, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(inv, inv, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        } catch (t: Throwable) {
            inv.release(); throw t
        } finally {
            s.release()
        }
        return inv
    }

    /** Four-cornered outlines among the big contours of [edges], added to [out]. Consumes (releases) [edges]. */
    private fun contourQuads(edges: Mat, out: MutableList<Quad>) {
        val w = edges.cols(); val h = edges.rows()
        val minArea = w * h * 0.12
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val ranked = contours.map { it to Imgproc.contourArea(it) }
                .filter { it.second >= minArea }
                .sortedByDescending { it.second }
                .take(6)
            for ((c, _) in ranked) {
                val c2f = MatOfPoint2f(*c.toArray())
                try {
                    val peri = Imgproc.arcLength(c2f, true)
                    var found = false
                    for (eps in doubleArrayOf(0.02, 0.03, 0.045)) {
                        val approx = MatOfPoint2f()
                        Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
                        val pts = approx.toArray()
                        approx.release()
                        if (pts.size == 4) {
                            val q = Quad.ordered(pts.map { P((it.x / w).toFloat(), (it.y / h).toFloat()) })
                            if (q.isConvex() && q.area() >= 0.12f && !touchesEverywhere(q)) { out += q.clamped(); found = true; break }
                        }
                    }
                    if (found) continue
                    // Rounded or torn corners: fall back to the rotated bounding box of a big contour.
                    val rect = Imgproc.minAreaRect(c2f)
                    val box = arrayOfNulls<Point>(4); rect.points(box)
                    val rq = Quad.ordered(box.map { P((it!!.x / w).toFloat(), (it.y / h).toFloat()) }).clamped()
                    if (rq.area() in 0.2f..0.97f && rq.isConvex()) out += rq
                } finally {
                    c2f.release()
                }
            }
        } finally {
            edges.release(); hierarchy.release(); contours.forEach { it.release() }
        }
    }

    /** Longest straight segments kept per frame; keeps the line strategy's cost flat. */
    private const val MAX_SEGMENTS = 40

    /**
     * Outlines from straight lines: survives a corner hidden by a thumb or a page edge that is
     * broken by shadow, where no closed contour exists. [thin] is a one-pixel edge map.
     */
    private fun lineQuads(thin: Mat, out: MutableList<Quad>) {
        val w = thin.cols(); val h = thin.rows()
        val lines = Mat()
        try {
            Imgproc.HoughLinesP(thin, lines, 1.0, Math.PI / 180, 50, min(w, h) * 0.25, 6.0)
            val n = lines.rows()
            if (n < 4) return
            val raw = IntArray(n * 4); lines.get(0, 0, raw)
            val order = (0 until n).sortedByDescending {
                val dx = raw[4 * it + 2] - raw[4 * it]; val dy = raw[4 * it + 3] - raw[4 * it + 1]
                dx * dx + dy * dy
            }.take(MAX_SEGMENTS)
            val segs = FloatArray(order.size * 4)
            order.forEachIndexed { i, j -> for (k in 0..3) segs[4 * i + k] = raw[4 * j + k].toFloat() }
            out += QuadMath.fromSegments(segs, w, h, 8).filter { !touchesEverywhere(it) }
        } catch (_: Throwable) {
            // Lines are a bonus; the contour strategies still stand.
        } finally {
            lines.release()
        }
    }

    // ---- Corner refinement ---------------------------------------------------------------

    /** Long side of the picture the corners are refined on. */
    const val REFINE_SIDE = 2000

    /**
     * Sharpen a coarse outline on the detailed photo: around each side, the real paper edge is
     * found in a narrow band and a straight line is fitted to it; the corners are where those
     * lines cross. Returns null (keep the coarse outline) when the result looks wrong.
     */
    fun refine(photo: Bitmap, quad: Quad): Quad? {
        if (!ensure() || quad == Quad.FULL) return null
        val (w, h) = fitWithin(photo.width, photo.height, REFINE_SIDE)
        var src: Bitmap? = null
        val rgba = Mat(); val gray = Mat()
        try {
            src = if (w == photo.width && h == photo.height) photo else Bitmap.createScaledBitmap(photo, w, h, true)
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            val p = quad.points.map { doubleArrayOf(it.x * w.toDouble(), it.y * h.toDouble()) }
            var refined = 0
            val lines = Array(4) { i ->
                val a = p[i]; val b = p[(i + 1) % 4]
                val fit = fitSide(gray, a, b)
                if (fit != null) refined++
                val l = fit ?: Line2.through(a[0], a[1], b[0], b[1])
                Line2(l.x / w, l.y / h, l.dx / w, l.dy / h)
            }
            if (refined == 0) return null
            val q = QuadMath.fromLines(lines[0], lines[1], lines[2], lines[3]) ?: return null
            return if (QuadMath.acceptRefined(quad, q)) q.clamped() else null
        } catch (_: Throwable) {       // includes running out of memory: the coarse outline is fine
            return null
        } finally {
            rgba.release(); gray.release()
            if (src != null && src !== photo) src.recycle()
        }
    }

    /** Fit a line to the edge pixels in a band around the side a→b (pixels). Null when no clear edge is there. */
    private fun fitSide(gray: Mat, a: DoubleArray, b: DoubleArray): Line2? {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val len = kotlin.math.hypot(dx, dy)
        if (len < 60) return null
        val band = max(4.0, QuadMath.REFINE_BAND * len)
        val x0 = (min(a[0], b[0]) - band - 2).toInt().coerceIn(0, gray.cols() - 1)
        val y0 = (min(a[1], b[1]) - band - 2).toInt().coerceIn(0, gray.rows() - 1)
        val x1 = (max(a[0], b[0]) + band + 3).toInt().coerceIn(x0 + 1, gray.cols())
        val y1 = (max(a[1], b[1]) + band + 3).toInt().coerceIn(y0 + 1, gray.rows())
        if (x1 - x0 < 3 || y1 - y0 < 3) return null
        val roi = gray.submat(y0, y1, x0, x1)
        val soft = Mat(); val e = Mat(); val line = Mat()
        var pts: MatOfPoint2f? = null
        try {
            Imgproc.GaussianBlur(roi, soft, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(soft, e, 40.0, 120.0)
            val rw = x1 - x0; val rh = y1 - y0
            val bytes = ByteArray(rw * rh); e.get(0, 0, bytes)
            val found = ArrayList<Point>(1024)
            for (y in 0 until rh) {
                val row = y * rw
                for (x in 0 until rw) {
                    if (bytes[row + x].toInt() == 0) continue
                    val px = x + x0 - a[0]; val py = y + y0 - a[1]
                    val t = (px * dx + py * dy) / (len * len)
                    if (t < 0.06 || t > 0.94) continue                    // corners are often rounded or curled
                    if (kotlin.math.abs(px * dy - py * dx) / len > band) continue
                    found += Point(x + x0.toDouble(), y + y0.toDouble())
                }
            }
            if (found.size < len * 0.25) return null
            // A robust first fit, then a plain one on the points that agree with it.
            val step = (found.size / 3000).coerceAtLeast(1)
            val sample = if (step == 1) found else found.filterIndexed { i, _ -> i % step == 0 }
            pts = MatOfPoint2f(*sample.toTypedArray())
            Imgproc.fitLine(pts, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            val f = FloatArray(4); line.get(0, 0, f)
            val tol = max(1.5, band * 0.2)
            val close = sample.filter { kotlin.math.abs((it.x - f[2]) * f[1] - (it.y - f[3]) * f[0]) <= tol }
            if (close.size >= len * 0.2 / step) {
                pts.release()
                pts = MatOfPoint2f(*close.toTypedArray())
                Imgproc.fitLine(pts, line, Imgproc.DIST_L2, 0.0, 0.01, 0.01)
                line.get(0, 0, f)
            }
            val fit = Line2(f[2].toDouble(), f[3].toDouble(), f[0].toDouble(), f[1].toDouble())
            return if (fit.angleTo(Line2(a[0], a[1], dx, dy)) <= 3.0) fit else null
        } finally {
            roi.release(); soft.release(); e.release(); line.release(); pts?.release()
        }
    }

    // ---- Quality checks ------------------------------------------------------------------

    private fun fillQuad(mask: Mat, quad: Quad) {
        val w = mask.cols(); val h = mask.rows()
        val pts = MatOfPoint(*quad.points.map { Point(it.x * w.toDouble(), it.y * h.toDouble()) }.toTypedArray())
        Imgproc.fillConvexPoly(mask, pts, Scalar(255.0))
        pts.release()
    }

    /**
     * Variance of the Laplacian inside [quad] on an unblurred gray image: high for crisp print,
     * low when the picture is out of focus or shaken. The paper edge itself is left out.
     */
    fun sharpness(gray: Mat, quad: Quad): Double {
        val lap = Mat(); val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val mean = MatOfDouble(); val std = MatOfDouble()
        try {
            Imgproc.Laplacian(gray, lap, CvType.CV_16S)
            fillQuad(mask, quad.inset(0.06f))
            Core.meanStdDev(lap, mean, std, mask)
            val s = std.get(0, 0)?.firstOrNull() ?: 0.0
            return s * s
        } catch (_: Throwable) {
            return Double.MAX_VALUE      // can't tell: never block on it
        } finally {
            lap.release(); mask.release(); mean.release(); std.release()
        }
    }

    /** [sharpness] of a decoded photo. */
    fun sharpness(bmp: Bitmap, quad: Quad): Double {
        if (!ensure()) return Double.MAX_VALUE
        val rgba = Mat(); val gray = Mat()
        try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return sharpness(gray, quad)
        } catch (_: Throwable) {
            return Double.MAX_VALUE
        } finally {
            rgba.release(); gray.release()
        }
    }

    /** Chroma level (see FrameAnalyzer) under which a blown-out pixel counts as a reflection. */
    private const val GLARE_CHROMA = 30.0

    /**
     * Share of the page covered by glare: pixels that are blown out (brightness above 250) and
     * colourless. [chroma] is a small single-channel "how colourful" image of the same frame.
     */
    fun glare(gray: Mat, chroma: Mat, quad: Quad): Float {
        val g = Mat(); val bright = Mat(); val flat = Mat()
        val mask = Mat.zeros(chroma.size(), CvType.CV_8UC1)
        try {
            Imgproc.resize(gray, g, chroma.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.threshold(g, bright, 250.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(chroma, flat, GLARE_CHROMA, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(bright, flat, bright)
            fillQuad(mask, quad.inset(0.03f))
            val area = Core.countNonZero(mask)
            if (area == 0) return 0f
            Core.bitwise_and(bright, mask, bright)
            return Core.countNonZero(bright) / area.toFloat()
        } catch (_: Throwable) {
            return 0f
        } finally {
            g.release(); bright.release(); flat.release(); mask.release()
        }
    }

    /** A quad hugging all four image borders is just the frame edge, not a document. */
    private fun touchesEverywhere(q: Quad): Boolean {
        val m = 0.01f
        return q.tl.x < m && q.tl.y < m && q.tr.x > 1 - m && q.br.y > 1 - m && q.bl.x < m
    }

    private fun median(gray: Mat): Double {
        val hist = IntArray(256)
        val small = Mat()
        Imgproc.resize(gray, small, Size(64.0, 64.0))
        val buf = ByteArray((small.total() * small.channels()).toInt())
        small.get(0, 0, buf); small.release()
        buf.forEach { hist[it.toInt() and 0xFF]++ }
        var acc = 0; val half = buf.size / 2
        for (i in 0..255) { acc += hist[i]; if (acc >= half) return i.toDouble() }
        return 127.0
    }

    // ---- Correction ----------------------------------------------------------------------

    private const val UNSHARP_AMOUNT = 0.6

    /**
     * Perspective-correct [src] to the [quad] (normalised corners). The page comes out with exact
     * A4, Letter or ID-card proportions when it is close to one of them ([shape] forces one), and
     * turned anticlockwise by [skew] degrees to level text that was measured as tilted clockwise.
     */
    fun warp(src: Bitmap, quad: Quad, skew: Float = 0f, shape: PaperShape? = null): Bitmap {
        if (!ensure() || (quad == Quad.FULL && skew == 0f && shape == null)) return src.copy(Bitmap.Config.ARGB_8888, false)
        val (ow, oh) = warpSize(quad, src.width, src.height)
        val (aw, ah) = when {
            shape != null -> AspectSnap.force(ow, oh, shape)
            quad == Quad.FULL -> ow to oh                   // an uncropped photo keeps its own shape
            else -> AspectSnap.snap(ow, oh)
        }
        val (tw, th) = fitWithin(aw, ah, MAX_SIDE)
        val s = Mat(); val d = Mat()
        val srcPts = MatOfPoint2f(*quad.points.map { Point(it.x * src.width.toDouble(), it.y * src.height.toDouble()) }.toTypedArray())
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(tw - 1.0, 0.0), Point(tw - 1.0, th - 1.0), Point(0.0, th - 1.0)
        )
        try {
            Utils.bitmapToMat(src, s)
            var m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            if (skew != 0f) {
                // Fold the small levelling turn into the same transform: one resampling pass only.
                val r2 = Imgproc.getRotationMatrix2D(Point(tw / 2.0, th / 2.0), skew.toDouble(), 1.0)
                val r3 = Mat.eye(3, 3, CvType.CV_64F)
                for (r in 0..1) for (c in 0..2) r3.put(r, c, r2.get(r, c)[0])
                val both = Mat()
                Core.gemm(r3, m, 1.0, Mat(), 0.0, both)
                r2.release(); r3.release(); m.release()
                m = both
            }
            Imgproc.warpPerspective(s, d, m, Size(tw.toDouble(), th.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            m.release()
            s.release()
            // Light unsharp mask: brings back the crispness that resampling takes out of small print.
            Imgproc.GaussianBlur(d, s, Size(0.0, 0.0), 1.0)
            Core.addWeighted(d, 1.0 + UNSHARP_AMOUNT, s, -UNSHARP_AMOUNT, 0.0, d)
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(d, out)
            return out
        } finally {
            s.release(); d.release(); srcPts.release(); dstPts.release()
        }
    }

    /** Apply a scan look. Returns a new bitmap; [src] is untouched. */
    fun filter(src: Bitmap, f: ScanFilter): Bitmap {
        if (f == ScanFilter.ORIGINAL || !ensure()) return src.copy(Bitmap.Config.ARGB_8888, false)
        if (f == ScanFilter.AUTO) return filter(src, pickFilter(src))
        val rgba = Mat(); val out = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            when (f) {
                ScanFilter.MAGIC -> {
                    val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                    val flat = removeShadows(rgb)
                    // Levels from the page itself: darkest 3% to black, brightest 5% to white.
                    val (gain, offset) = Levels.stretch(histogramOf(flat))
                    flat.convertTo(flat, -1, gain, offset)
                    // Saturation lift so ink and stamps pop on white paper.
                    val hsv = Mat(); Imgproc.cvtColor(flat, hsv, Imgproc.COLOR_RGB2HSV)
                    val ch = ArrayList<Mat>(); Core.split(hsv, ch)
                    ch[1].convertTo(ch[1], -1, 1.25, 0.0)
                    Core.merge(ch, hsv); ch.forEach { it.release() }
                    Imgproc.cvtColor(hsv, flat, Imgproc.COLOR_HSV2RGB)
                    Imgproc.cvtColor(flat, out, Imgproc.COLOR_RGB2RGBA)
                    rgb.release(); flat.release(); hsv.release()
                }
                ScanFilter.LIGHTEN -> {
                    val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                    val flat = removeShadows(rgb)
                    Imgproc.cvtColor(flat, out, Imgproc.COLOR_RGB2RGBA)
                    rgb.release(); flat.release()
                }
                ScanFilter.GRAY -> {
                    val g = Mat(); Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
                    val flat = flattenChannel(g)
                    val clahe = Imgproc.createCLAHE(1.6, Size(8.0, 8.0))
                    clahe.apply(flat, flat)
                    Imgproc.cvtColor(flat, out, Imgproc.COLOR_GRAY2RGBA)
                    g.release(); flat.release()
                }
                ScanFilter.BW -> {
                    val g = Mat(); Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
                    rgba.release()
                    val flat = flattenChannel(g); g.release()
                    val bin = sauvola(flat); flat.release()
                    Imgproc.medianBlur(bin, bin, 3)
                    Imgproc.cvtColor(bin, out, Imgproc.COLOR_GRAY2RGBA)
                    bin.release()
                }
                else -> rgba.copyTo(out)
            }
            val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bmp)
            return bmp
        } finally {
            rgba.release(); out.release()
        }
    }

    /** Brightness histogram of an RGB image, measured on a small copy. */
    private fun histogramOf(rgb: Mat): IntArray {
        val small = Mat(); val g = Mat()
        try {
            val s = min(1.0, 400.0 / max(rgb.cols(), rgb.rows()))
            Imgproc.resize(rgb, small, Size(), s, s, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, g, Imgproc.COLOR_RGB2GRAY)
            val buf = ByteArray(g.cols() * g.rows()); g.get(0, 0, buf)
            val hist = IntArray(256)
            for (v in buf) hist[v.toInt() and 0xFF]++
            return hist
        } finally {
            small.release(); g.release()
        }
    }

    /** Rows binarised per pass: bounds the memory of the summed-area tables on big pages. */
    private const val SAUVOLA_STRIP = 256

    /**
     * Sauvola binarisation of a gray page: each pixel is compared with a threshold made from the
     * mean and the spread of its neighbourhood, so faint print on uneven paper stays readable and
     * blank areas stay clean. Mean and spread come from summed-area tables (integral2).
     */
    private fun sauvola(gray: Mat): Mat {
        val w = gray.cols(); val h = gray.rows()
        val win = Sauvola.window(max(w, h)); val rad = win / 2
        val n = (win * win).toDouble()
        val out = Mat(h, w, CvType.CV_8UC1)
        val padded = Mat(); val sum = Mat(); val sq = Mat()
        val mean = Mat(); val dev = Mat(); val t = Mat(); val g32 = Mat()
        try {
            var y0 = 0
            while (y0 < h) {
                val y1 = min(h, y0 + SAUVOLA_STRIP)
                val top = max(0, y0 - rad); val bot = min(h, y1 + rad)
                val strip = gray.submat(top, bot, 0, w)
                Core.copyMakeBorder(strip, padded, rad - (y0 - top), rad - (bot - y1), rad, rad, Core.BORDER_REPLICATE)
                strip.release()
                Imgproc.integral2(padded, sum, sq, CvType.CV_64F, CvType.CV_64F)
                val rows = y1 - y0
                boxSum(sum, rows, w, win, mean)
                boxSum(sq, rows, w, win, dev)
                mean.convertTo(mean, CvType.CV_32F, 1.0 / n)
                dev.convertTo(dev, CvType.CV_32F, 1.0 / n)
                Core.multiply(mean, mean, t)
                Core.subtract(dev, t, dev)
                Core.max(dev, Scalar(0.0), dev)
                Core.sqrt(dev, dev)
                // threshold = mean · (1 − k + k · stddev / R)
                dev.convertTo(dev, CvType.CV_32F, Sauvola.K / Sauvola.R, 1.0 - Sauvola.K)
                Core.multiply(mean, dev, t)
                val g = gray.submat(y0, y1, 0, w)
                g.convertTo(g32, CvType.CV_32F); g.release()
                val o = out.submat(y0, y1, 0, w)
                Core.compare(g32, t, o, Core.CMP_GT)
                o.release()
                y0 = y1
            }
            return out
        } catch (e: Throwable) {
            out.release(); throw e
        } finally {
            padded.release(); sum.release(); sq.release(); mean.release(); dev.release(); t.release(); g32.release()
        }
    }

    /** Sum over every [win]×[win] window of a summed-area table, for a [rows]×[cols] result. */
    private fun boxSum(table: Mat, rows: Int, cols: Int, win: Int, dst: Mat) {
        val a = table.submat(0, rows, 0, cols); val b = table.submat(0, rows, win, win + cols)
        val c = table.submat(win, win + rows, 0, cols); val d = table.submat(win, win + rows, win, win + cols)
        Core.subtract(d, b, dst)
        Core.subtract(dst, c, dst)
        Core.add(dst, a, dst)
        a.release(); b.release(); c.release(); d.release()
    }

    /**
     * Choose a look for a page: colourful pages keep their colour (Magic), plain ink on paper
     * becomes black & white, anything with soft shading becomes grayscale.
     */
    fun pickFilter(src: Bitmap): ScanFilter {
        if (!ensure()) return ScanFilter.MAGIC
        val (w, h) = fitWithin(src.width, src.height, 480)
        var small: Bitmap? = null
        val rgba = Mat(); val rgb = Mat(); val hsv = Mat()
        val ch = ArrayList<Mat>()
        var flat: Mat? = null
        try {
            small = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.split(hsv, ch)
            flat = flattenChannel(ch[2])
            val n = w * h
            val sat = ByteArray(n); val value = ByteArray(n); val level = ByteArray(n)
            ch[1].get(0, 0, sat); ch[2].get(0, 0, value); flat.get(0, 0, level)
            var colourful = 0; var content = 0; var mid = 0; var satSum = 0L
            for (i in 0 until n) {
                val s = sat[i].toInt() and 0xFF; val l = level[i].toInt() and 0xFF
                if (s > 90 && (value[i].toInt() and 0xFF) > 60) colourful++
                if (l < 200) {
                    content++; satSum += s
                    if (l >= 90) mid++
                }
            }
            return AutoLook.decide(
                colourful / n.toFloat(), if (content == 0) 0f else satSum / content.toFloat(),
                content / n.toFloat(), mid / n.toFloat()
            )
        } catch (_: Throwable) {
            return ScanFilter.MAGIC
        } finally {
            rgba.release(); rgb.release(); hsv.release(); ch.forEach { it.release() }; flat?.release()
            if (small != null && small !== src) small.recycle()
        }
    }

    /**
     * The picture text recognition reads best: gray with shadows flattened out, at most [maxSide]
     * px long. (A hard black & white page loses the thin strokes the recogniser relies on.)
     */
    fun ocrImage(src: Bitmap, maxSide: Int = 2400): Bitmap {
        val (w, h) = fitWithin(src.width, src.height, maxSide)
        if (!ensure()) return Bitmap.createScaledBitmap(src, w, h, true)
        val small = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val rgba = Mat(); val g = Mat(); val out = Mat()
        var flat: Mat? = null
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
            flat = flattenChannel(g)
            Imgproc.cvtColor(flat, out, Imgproc.COLOR_GRAY2RGBA)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bmp)
            return bmp
        } finally {
            rgba.release(); g.release(); out.release(); flat?.release()
            if (small !== src) small.recycle()
        }
    }

    /** Divide each channel by an estimate of its background so paper turns evenly white. */
    private fun removeShadows(rgb: Mat): Mat {
        val ch = ArrayList<Mat>(); Core.split(rgb, ch)
        val flat = ch.map { flattenChannel(it) }
        val out = Mat(); Core.merge(flat, out)
        ch.forEach { it.release() }; flat.forEach { it.release() }
        return out
    }

    /** Long side of the copy the paper background is estimated on. */
    private const val BACKGROUND_SIDE = 400.0

    /**
     * Background estimate of one channel (paper brightness incl. shadows), same size as [c].
     * On a ~400 px copy, a morphological close of [closeK] px paints the print over with the
     * paper around it, and a median blur smooths what is left; the result is scaled back up.
     */
    internal fun backgroundOf(c: Mat, closeK: Int = 17): Mat {
        val small = Mat()
        val s = min(1.0, BACKGROUND_SIDE / max(c.cols(), c.rows()))
        Imgproc.resize(c, small, Size(), s, s, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(closeK.toDouble(), closeK.toDouble()))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, k); k.release()
        val ks = (max(small.cols(), small.rows()) / 30).let { if (it % 2 == 0) it + 1 else it }.coerceIn(5, 21)
        Imgproc.medianBlur(small, small, ks)
        val bg = Mat(); Imgproc.resize(small, bg, c.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); small.release()
        return bg
    }

    /** [c] divided by its background: paper becomes 255 everywhere, print keeps its contrast. */
    private fun flattenChannel(c: Mat): Mat {
        val bg = backgroundOf(c)
        val out = Mat()
        Core.max(bg, Scalar(1.0), bg)
        Core.divide(c, bg, out, 255.0)       // saturating 8-bit divide: no float copies of a big page
        bg.release()
        return out
    }

    // ---- Bitmap I/O ----------------------------------------------------------------------

    /** Decode an image with EXIF orientation applied, scaled so the long side ≤ [maxSide]. */
    fun decodeOriented(ctx: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val orientation = ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        scaleAndRotate(raw, deg, maxSide)
    }.getOrNull()

    fun scaleAndRotate(src: Bitmap, degrees: Int, maxSide: Int = MAX_SIDE): Bitmap {
        val (w, h) = fitWithin(src.width, src.height, maxSide)
        if (degrees == 0 && w == src.width && h == src.height) return src
        val m = Matrix()
        m.postScale(w.toFloat() / src.width, h.toFloat() / src.height)
        if (degrees != 0) m.postRotate(degrees.toFloat())
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Clockwise rotation recorded in a photo's EXIF data (camera JPEGs are stored unrotated). */
    fun exifDegrees(f: File): Int = runCatching { ExifInterface(f.path).rotationDegrees }.getOrDefault(0)

    /**
     * Decode a photo upright, scaled so the long side is at most [maxSide]. Reads only as many
     * pixels as needed (inSampleSize) and backs off to a smaller decode when memory is tight.
     */
    fun decodeFile(f: File, maxSide: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        if (b.outWidth <= 0) return null
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val degrees = exifDegrees(f)
        repeat(3) {
            var raw: Bitmap? = null
            try {
                raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
                return scaleAndRotate(raw, degrees, maxSide)
            } catch (_: OutOfMemoryError) {
                raw?.recycle()
                sample *= 2
            }
        }
        return null
    }

    fun saveJpeg(bmp: Bitmap, f: File, quality: Int = 92) {
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun thumb(bmp: Bitmap, maxSide: Int = 360): Bitmap {
        val (w, h) = fitWithin(bmp.width, bmp.height, maxSide)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }
}
