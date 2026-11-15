package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a photo of a signature on paper into a transparent cut-out.
 *
 * The constructor does the slider-independent work once (darkness map with shadows flattened out,
 * and the paper-normalised colours); [cut] then only thresholds, so the live preview is cheap.
 * Call everything from Dispatchers.Default after [ScanEngine.ensure].
 */
class SignatureCutter(src: Bitmap) : Closeable {
    private val dark = Mat()      // CV_8U: 0 = paper, 255 = ink
    private val rgbN = Mat()      // CV_8UC3: colours with the paper made white
    private val presetAlpha: Mat? // alpha of an already-transparent image (fast path)
    val width: Int
    val height: Int
    val otsu: Double

    init {
        val rgba = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            width = rgba.cols(); height = rgba.rows()
            val ch = ArrayList<Mat>(); Core.split(rgba, ch)
            val a = ch[3]
            val clear = thresholdInv(a, 16.0)
            val transparent = Core.countNonZero(clear) > width * height * 0.2
            clear.release()
            presetAlpha = if (transparent) a.clone() else null
            if (transparent) {
                // Composite on white so the colours are sensible, then use the image's own alpha.
                for (i in 0..2) whiteUnder(ch[i], a)
            }
            // Darkness: min(R,G,B) gives the strongest contrast for black, blue and red pens.
            val m = Mat(); Core.min(ch[0], ch[1], m); Core.min(m, ch[2], m)
            val bg = ScanEngine.backgroundOf(m, closeK = 21)
            val bf = Mat(); bg.convertTo(bf, CvType.CV_32F); Core.max(bf, Scalar(1.0), bf)
            val mf = Mat(); m.convertTo(mf, CvType.CV_32F)
            Core.divide(mf, bf, mf, 255.0)
            mf.convertTo(dark, CvType.CV_8U)
            Core.bitwise_not(dark, dark)
            Imgproc.GaussianBlur(dark, dark, Size(3.0, 3.0), 0.0)
            // Paper-normalised colours for "Original" ink.
            val norm = ArrayList<Mat>()
            for (i in 0..2) {
                val cf = Mat(); ch[i].convertTo(cf, CvType.CV_32F)
                Core.divide(cf, bf, cf, 255.0)
                val c8 = Mat(); cf.convertTo(c8, CvType.CV_8U); cf.release()
                norm += c8
            }
            Core.merge(norm, rgbN)
            norm.forEach { it.release() }
            val tmp = Mat()
            otsu = Imgproc.threshold(dark, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU).coerceIn(24.0, 110.0)
            tmp.release(); m.release(); bg.release(); bf.release(); mf.release()
            ch.forEach { it.release() }
        } finally {
            rgba.release()
        }
    }

    private fun thresholdInv(a: Mat, t: Double): Mat {
        val out = Mat(); Imgproc.threshold(a, out, t, 255.0, Imgproc.THRESH_BINARY_INV); return out
    }

    /** c = c·a/255 + 255·(1 − a/255), in place. */
    private fun whiteUnder(c: Mat, a: Mat) {
        val cf = Mat(); val af = Mat()
        c.convertTo(cf, CvType.CV_32F); a.convertTo(af, CvType.CV_32F, 1.0 / 255.0)
        Core.multiply(cf, af, cf)
        val inv = Mat(); af.convertTo(inv, CvType.CV_32F, -255.0, 255.0)   // 255·(1 − a)
        Core.add(cf, inv, cf)
        cf.convertTo(c, CvType.CV_8U)
        cf.release(); af.release(); inv.release()
    }

    /**
     * Cut out the ink. [sensitivity] 0..1 (0.5 = automatic; higher keeps fainter strokes).
     * Returns null when no ink is found. With [crop], the result is trimmed to the signature.
     */
    fun cut(sensitivity: Float, crop: Boolean): Bitmap? {
        val alpha = Mat(); val core = Mat(); val labels = Mat(); val stats = Mat(); val cents = Mat()
        val keep = Mat(); val out = Mat()
        try {
            val total = width.toDouble() * height
            if (presetAlpha != null) {
                presetAlpha.copyTo(alpha)
                Imgproc.threshold(presetAlpha, keep, 16.0, 255.0, Imgproc.THRESH_BINARY)
            } else {
                val (t, lo, hi) = SignGeometry.thresholds(otsu, sensitivity)
                val k = 255.0 / (hi - lo)
                dark.convertTo(alpha, CvType.CV_8U, k, -lo * k)    // soft, anti-aliased edge
                Imgproc.threshold(dark, core, t, 255.0, Imgproc.THRESH_BINARY)

                // Drop specks, and big blobs touching the border (paper edge, desk, fingers).
                val n = Imgproc.connectedComponentsWithStats(core, labels, stats, cents, 8, CvType.CV_32S)
                val minArea = max(12.0, 0.00002 * total)
                val lut = BooleanArray(n)
                for (i in 1 until n) {
                    val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                    val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                    val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                    val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                    val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                    val touches = x <= 0 || y <= 0 || x + w >= width || y + h >= height
                    val huge = area > total * 0.005 || w > width * 0.3 || h > height * 0.3
                    lut[i] = area >= minArea && !(touches && huge)
                }
                val lab = IntArray(width * height); labels.get(0, 0, lab)
                val kb = ByteArray(lab.size) { if (lut[lab[it]]) -1 else 0 }
                keep.create(height, width, CvType.CV_8U); keep.put(0, 0, kb)
                val grown = Mat()
                val ell = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
                Imgproc.dilate(keep, grown, ell, Point(-1.0, -1.0), 2); ell.release()
                Core.min(alpha, grown, alpha)
                grown.release()
            }
            if (Core.countNonZero(keep) < max(60.0, 0.0002 * total)) return null

            var a = alpha; var c = rgbN
            var sub: Rect? = null
            if (crop) {
                val r = Imgproc.boundingRect(keep)
                val pad = max(6, (max(r.width, r.height) * 0.04).roundToInt())
                val x0 = (r.x - pad).coerceAtLeast(0); val y0 = (r.y - pad).coerceAtLeast(0)
                val x1 = (r.x + r.width + pad).coerceAtMost(width); val y1 = (r.y + r.height + pad).coerceAtMost(height)
                sub = Rect(x0, y0, x1 - x0, y1 - y0)
                a = alpha.submat(sub); c = rgbN.submat(sub)
            }
            val parts = ArrayList<Mat>(); Core.split(c, parts); parts += a
            Core.merge(parts, out)
            parts.take(3).forEach { it.release() }
            if (sub != null) { a.release(); c.release() }
            val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bmp, true)
            return bmp
        } finally {
            listOf(alpha, core, labels, stats, cents, keep, out).forEach { it.release() }
        }
    }

    override fun close() {
        dark.release(); rgbN.release(); presetAlpha?.release()
    }
}

object SignatureEngine {
    /** Ink presets: ARGB; 0 means keep the photo's own colours. */
    const val ORIGINAL = 0
    val BLACK = 0xFF111111.toInt()
    val BLUE = 0xFF1A3FBF.toInt()
    val RED = 0xFFC62828.toInt()

    fun prepare(ctx: Context, uri: Uri, maxSide: Int): Bitmap? =
        ScanEngine.decodeOriented(ctx, uri, maxSide)?.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }

    /** A copy of [src] painted in [ink] (alpha kept). [ORIGINAL] returns [src] itself. */
    fun tinted(src: Bitmap, ink: Int): Bitmap {
        if (ink == ORIGINAL) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(ink or 0xFF000000.toInt(), PorterDuff.Mode.SRC_IN)
        })
        return out
    }
}
