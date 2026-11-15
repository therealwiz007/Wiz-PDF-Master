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
    ORIGINAL("Original"), MAGIC("Magic"), GRAY("Grayscale"), BW("B & W"), LIGHTEN("No shadow")
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

    /** Find the document outline. Returns null when nothing page-like is visible. */
    fun detect(src: Bitmap): Quad? {
        if (!ensure()) return null
        val (w, h) = fitWithin(src.width, src.height, 480)
        val small = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val rgba = Mat(); val gray = Mat()
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return detectGray(gray) { saturationOf(rgba) }
        } catch (_: Throwable) {
            return null
        } finally {
            rgba.release(); gray.release()
            if (small !== src) small.recycle()
        }
    }

    /**
     * Find the outline on a small, unblurred gray frame (the camera's Y plane works as is).
     * [saturation] is only asked for when the brightness-based passes find nothing; it returns a
     * single-channel "how colourful" image of any size, which this function releases.
     */
    fun detectGray(gray: Mat, saturation: () -> Mat?): Quad? {
        val blurred = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            return (findQuad(edgesCanny(blurred))
                ?: findQuad(edgesThreshold(blurred))
                ?: saturation()?.let { findQuadFromSaturation(it) })?.inset(0.012f)
        } finally {
            blurred.release()
        }
    }

    private fun edgesCanny(gray: Mat): Mat {
        val med = median(gray)
        val lo = max(10.0, 0.66 * med)
        val hi = min(255.0, max(lo + 30, 1.33 * med))
        val edges = Mat()
        Imgproc.Canny(gray, edges, lo, hi)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, k, Point(-1.0, -1.0), 2)
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
    private fun findQuadFromSaturation(s: Mat): Quad? {
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
        return findQuad(inv)
    }

    /** Consumes (releases) [edges]. */
    private fun findQuad(edges: Mat): Quad? {
        val w = edges.cols(); val h = edges.rows()
        val minArea = w * h * 0.12
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val ranked = contours.map { it to Imgproc.contourArea(it) }
                .filter { it.second >= minArea }
                .sortedByDescending { it.second }
                .take(8)
            for ((c, _) in ranked) {
                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                for (eps in doubleArrayOf(0.02, 0.03, 0.045)) {
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
                    val pts = approx.toArray()
                    approx.release()
                    if (pts.size == 4) {
                        val q = Quad.ordered(pts.map { P((it.x / w).toFloat(), (it.y / h).toFloat()) })
                        if (q.isConvex() && q.area() >= 0.12f && !touchesEverywhere(q)) {
                            c2f.release(); return q.clamped()
                        }
                    }
                }
                // Rounded or torn corners: fall back to the rotated bounding box of a big contour.
                val rect = Imgproc.minAreaRect(c2f)
                c2f.release()
                val box = arrayOfNulls<Point>(4); rect.points(box)
                val rq = Quad.ordered(box.map { P((it!!.x / w).toFloat(), (it.y / h).toFloat()) }).clamped()
                if (rq.area() in 0.2f..0.97f && rq.isConvex()) return rq
            }
            return null
        } finally {
            edges.release(); hierarchy.release(); contours.forEach { it.release() }
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

    /** Perspective-correct [src] to the [quad] (normalised corners). */
    fun warp(src: Bitmap, quad: Quad): Bitmap {
        if (!ensure() || quad == Quad.FULL) return src.copy(Bitmap.Config.ARGB_8888, false)
        val (ow, oh) = warpSize(quad, src.width, src.height)
        val (tw, th) = fitWithin(ow, oh, MAX_SIDE)
        val s = Mat(); val d = Mat()
        val srcPts = MatOfPoint2f(*quad.points.map { Point(it.x * src.width.toDouble(), it.y * src.height.toDouble()) }.toTypedArray())
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(tw - 1.0, 0.0), Point(tw - 1.0, th - 1.0), Point(0.0, th - 1.0)
        )
        try {
            Utils.bitmapToMat(src, s)
            val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
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
        val rgba = Mat(); val out = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            when (f) {
                ScanFilter.MAGIC -> {
                    val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                    val flat = removeShadows(rgb)
                    // Gentle contrast + saturation lift so ink and stamps pop on white paper.
                    flat.convertTo(flat, -1, 1.18, -18.0)
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
                    val flat = flattenChannel(g)
                    val block = (max(src.width, src.height) / 40).let { if (it % 2 == 0) it + 1 else it }.coerceIn(15, 61)
                    Imgproc.adaptiveThreshold(flat, flat, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
                    Imgproc.medianBlur(flat, flat, 3)
                    Imgproc.cvtColor(flat, out, Imgproc.COLOR_GRAY2RGBA)
                    g.release(); flat.release()
                }
                ScanFilter.ORIGINAL -> rgba.copyTo(out)
            }
            val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bmp)
            return bmp
        } finally {
            rgba.release(); out.release()
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

    /** Background estimate of one channel (paper brightness incl. shadows), same size as [c]. */
    internal fun backgroundOf(c: Mat, dilateK: Int = 5, dilateIter: Int = 1): Mat {
        val small = Mat(); Imgproc.resize(c, small, Size(), 0.25, 0.25, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(dilateK.toDouble(), dilateK.toDouble()))
        Imgproc.dilate(small, small, k, Point(-1.0, -1.0), dilateIter); k.release()
        val ks = (max(small.cols(), small.rows()) / 30).let { if (it % 2 == 0) it + 1 else it }.coerceIn(5, 51)
        Imgproc.medianBlur(small, small, ks)
        val bg = Mat(); Imgproc.resize(small, bg, c.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); small.release()
        return bg
    }

    private fun flattenChannel(c: Mat): Mat {
        // Work on a downscaled copy for the background estimate: much faster, same result.
        val scale = 0.25
        val small = Mat(); Imgproc.resize(c, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.dilate(small, small, k); k.release()
        val ks = (max(small.cols(), small.rows()) / 30).let { if (it % 2 == 0) it + 1 else it }.coerceIn(5, 51)
        Imgproc.medianBlur(small, small, ks)
        val bg = Mat(); Imgproc.resize(small, bg, c.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); small.release()
        val out = Mat()
        // out = c / bg * 255
        val cf = Mat(); val bf = Mat()
        c.convertTo(cf, CvType.CV_32F); bg.convertTo(bf, CvType.CV_32F)
        Core.max(bf, Scalar(1.0), bf)
        Core.divide(cf, bf, cf, 255.0)
        cf.convertTo(out, CvType.CV_8U)
        cf.release(); bf.release(); bg.release()
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
