package com.wizlabs.pdfmaster

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.coroutineContext

/**
 * Beta: straightens the lines of a page that bulges, like a book page near the spine. The text
 * lines are found on a small copy (letters smeared sideways into bars), a parabola is fitted to
 * each, and the page is resampled so every line comes out level. See [CurveMath] for the maths.
 *
 * Only offered on phones with memory to spare; a page that shows no clear bend is left untouched.
 */
object CurveFlatten {
    private const val DETECT_SIDE = 1200
    private const val TIMEOUT_MS = 8000L
    private const val GRID_COLS = 33
    private const val GRID_ROWS = 49
    private const val STRIP = 256

    /** Not on low-RAM phones: needs a 4 GB phone (which reports a little under 4 GB to apps). */
    fun supported(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        !am.isLowRamDevice && info.totalMem >= 3_500L * 1024 * 1024
    }.getOrDefault(false)

    private val models = HashMap<String, CurveMath.Model?>()

    private fun key(p: ScanPage) = "${p.id}:${p.quad.hashCode()}:${p.rotation}:${p.skew}:${p.card}"

    /**
     * Look for a bend on [page] (in the background, for at most 8 seconds). True when one was found
     * and the page can be flattened; false leaves the page as it is.
     */
    suspend fun prepare(page: ScanPage): Boolean = withContext(Dispatchers.Default) {
        val k = key(page)
        val model = withTimeoutOrNull(TIMEOUT_MS) {
            val small = try { ScanSession.renderBase(page.copy(curve = false), DETECT_SIDE) } catch (_: OutOfMemoryError) { null } ?: return@withTimeoutOrNull null
            try {
                coroutineContext.ensureActive()
                detect(small)
            } catch (_: OutOfMemoryError) {
                null
            } finally {
                small.recycle()
            }
        }
        synchronized(models) { if (models.size > 64) models.clear(); models[k] = model }
        model != null
    }

    /** [bmp] is the cropped, turned page; the result is a new bitmap, or [bmp] itself when nothing was done. */
    fun flatten(page: ScanPage, bmp: Bitmap): Bitmap {
        val k = key(page)
        val known = synchronized(models) { models.containsKey(k) }
        val model = if (known) synchronized(models) { models[k] } else {
            // After a restart (or a new crop) the bend is measured again.
            val m = try {
                val small = if (maxOf(bmp.width, bmp.height) > DETECT_SIDE) ScanEngine.thumb(bmp, DETECT_SIDE) else bmp
                try { detect(small) } finally { if (small !== bmp) small.recycle() }
            } catch (_: Throwable) { null }
            synchronized(models) { models[k] = m }
            m
        } ?: return bmp
        return try { apply(bmp, model) } catch (_: Throwable) { bmp } catch (_: OutOfMemoryError) { bmp }
    }

    /** The text lines of [bmp] as parabolas, or null when the page shows no trustworthy bend. */
    fun detect(bmp: Bitmap): CurveMath.Model? {
        if (!ScanEngine.ensure()) return null
        val w = bmp.width; val h = bmp.height
        if (w < 200 || h < 200) return null
        val rgba = Mat(); val gray = Mat(); val bin = Mat(); val hier = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size((w / 28).coerceAtLeast(15).toDouble(), 1.0))
        val contours = ArrayList<MatOfPoint>()
        try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(gray, bin, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 12.0)
            // Smear the letters sideways so every text line becomes one long bar.
            Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(bin, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
            val lines = ArrayList<CurveMath.Line>()
            val bins = 24
            val lo = FloatArray(bins); val hi = FloatArray(bins)
            for (c in contours) {
                val r = Imgproc.boundingRect(c)
                if (r.width < w * 0.35 || r.height < 3 || r.height > h * 0.08) continue
                lo.fill(Float.MAX_VALUE); hi.fill(-Float.MAX_VALUE)
                val pts = IntArray(c.rows() * 2)
                c.get(0, 0, pts)
                for (i in 0 until pts.size / 2) {
                    val b = ((pts[i * 2] - r.x) * bins / r.width).coerceIn(0, bins - 1)
                    val y = pts[i * 2 + 1].toFloat()
                    if (y < lo[b]) lo[b] = y
                    if (y > hi[b]) hi[b] = y
                }
                val xs = ArrayList<Float>(bins); val ys = ArrayList<Float>(bins)
                for (b in 0 until bins) if (hi[b] >= lo[b]) {
                    xs += (r.x + (b + 0.5f) * r.width / bins) / w
                    ys += (lo[b] + hi[b]) / 2f / h
                }
                if (xs.size < 10) continue
                val xa = xs.toFloatArray(); val ya = ys.toFloatArray()
                val coef = CurveMath.fitQuadratic(xa, ya) ?: continue
                // A bar that the parabola doesn't follow is not one clean text line.
                var err = 0.0
                for (i in xa.indices) { val d = coef[0] + coef[1] * xa[i] + coef[2] * xa[i] * xa[i] - ya[i]; err += d * d }
                if (Math.sqrt(err / xa.size) > 0.004) continue
                lines += CurveMath.Line(coef, r.x / w.toFloat(), (r.x + r.width) / w.toFloat())
            }
            val model = CurveMath.build(lines)
            return if (CurveMath.confident(model)) model else null
        } finally {
            rgba.release(); gray.release(); bin.release(); hier.release(); kernel.release()
            contours.forEach { it.release() }
        }
    }

    /** Resample [bmp] so the lines of [model] come out level. Works in strips to keep memory flat. */
    fun apply(bmp: Bitmap, model: CurveMath.Model): Bitmap {
        if (!ScanEngine.ensure()) return bmp
        val w = bmp.width; val h = bmp.height
        val grid = CurveMath.grid(model, GRID_COLS, GRID_ROWS)
        // Column positions in the coarse grid are the same for every row: work them out once.
        val ci = IntArray(w); val cf = FloatArray(w)
        for (x in 0 until w) {
            val g = x * (GRID_COLS - 1f) / (w - 1).coerceAtLeast(1)
            ci[x] = g.toInt().coerceAtMost(GRID_COLS - 2); cf[x] = g - ci[x]
        }
        val src = Mat(); val dst = Mat(h, w, CvType.CV_8UC4)
        val mapX = Mat(STRIP, w, CvType.CV_32FC1); val mapY = Mat(STRIP, w, CvType.CV_32FC1)
        val bufX = FloatArray(STRIP * w); val bufY = FloatArray(STRIP * w)
        for (r in 0 until STRIP) for (x in 0 until w) bufX[r * w + x] = x.toFloat()
        mapX.put(0, 0, bufX)
        try {
            Utils.bitmapToMat(bmp, src)
            var y0 = 0
            while (y0 < h) {
                val rows = minOf(STRIP, h - y0)
                for (r in 0 until rows) {
                    val y = y0 + r
                    val g = y * (GRID_ROWS - 1f) / (h - 1).coerceAtLeast(1)
                    val ri = g.toInt().coerceAtMost(GRID_ROWS - 2); val rf = g - ri
                    val top = ri * GRID_COLS; val bot = top + GRID_COLS
                    for (x in 0 until w) {
                        val c = ci[x]; val f = cf[x]
                        val a = grid[top + c] * (1 - f) + grid[top + c + 1] * f
                        val b = grid[bot + c] * (1 - f) + grid[bot + c + 1] * f
                        bufY[r * w + x] = y + (a * (1 - rf) + b * rf) * h
                    }
                }
                mapY.put(0, 0, bufY)
                val mx = mapX.submat(0, rows, 0, w); val my = mapY.submat(0, rows, 0, w)
                val part = dst.submat(y0, y0 + rows, 0, w)
                Imgproc.remap(src, part, mx, my, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
                mx.release(); my.release(); part.release()
                y0 += rows
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dst, out)
            return out
        } finally {
            src.release(); dst.release(); mapX.release(); mapY.release()
        }
    }
}
