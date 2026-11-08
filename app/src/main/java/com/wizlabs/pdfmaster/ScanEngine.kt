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
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            val minArea = w * h * 0.12
            return (findQuad(edgesCanny(gray), w, h, minArea)
                ?: findQuad(edgesThreshold(gray), w, h, minArea)
                ?: findQuadFromSaturation(rgba, w, h, minArea))?.inset(0.012f)
        } catch (_: Throwable) {
            return null
        } finally {
            rgba.release(); gray.release()
            if (small !== src) small.recycle()
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

    /** White paper on a coloured desk: low saturation + high brightness stands out. */
    private fun findQuadFromSaturation(rgba: Mat, w: Int, h: Int, minArea: Double): Quad? {
        val hsv = Mat(); val rgb = Mat()
        val channels = ArrayList<Mat>()
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.split(hsv, channels)
            val s = channels[1]
            val inv = Mat()
            Core.bitwise_not(s, inv)
            Imgproc.GaussianBlur(inv, inv, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(inv, inv, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            return findQuad(inv, w, h, minArea)
        } finally {
            hsv.release(); rgb.release(); channels.forEach { it.release() }
        }
    }

    /** Consumes (releases) [edges]. */
    private fun findQuad(edges: Mat, w: Int, h: Int, minArea: Double): Quad? {
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
            Imgproc.warpPerspective(s, d, m, Size(tw.toDouble(), th.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            m.release()
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
        Core.max(bf, org.opencv.core.Scalar(1.0), bf)
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

    fun decodeFile(f: File, maxSide: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        if (b.outWidth <= 0) return null
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val (w, h) = fitWithin(raw.width, raw.height, maxSide)
        if (w == raw.width) return raw
        val out = Bitmap.createScaledBitmap(raw, w, h, true)
        if (out !== raw) raw.recycle()
        return out
    }

    fun saveJpeg(bmp: Bitmap, f: File, quality: Int = 92) {
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun thumb(bmp: Bitmap, maxSide: Int = 360): Bitmap {
        val (w, h) = fitWithin(bmp.width, bmp.height, maxSide)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    @Suppress("unused")
    fun px(v: Float) = v.roundToInt()
}
