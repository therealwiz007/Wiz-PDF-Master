package com.wizlabs.pdfmaster

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

/** What one live camera frame told us. The quad is already rotated upright. */
class FrameInfo(val quad: Quad?, val sharpness: Double, val glare: Boolean)

/**
 * Looks at live frames for the page outline, focus and glare. Works straight on the camera's
 * YUV planes: the Y plane is wrapped as a gray image without copying or rotating anything, and
 * the outline is rotated afterwards (four points instead of a whole bitmap). Not thread-safe;
 * call it from the single analysis thread only.
 */
class FrameAnalyzer {
    private var lastRun = 0L
    private var glareRun = 0
    /** Outline of the previous analysed frame (sensor orientation, untrimmed): steadies the choice between candidates. */
    private var lastQuad: Quad? = null
    private var rowU = ByteArray(0)
    private var rowV = ByteArray(0)
    private var chromaBytes = ByteArray(0)
    private var yCopy = ByteArray(0)

    /** Returns null when the frame is skipped to keep the analysis rate (and the CPU) down. */
    fun analyze(image: ImageProxy): FrameInfo? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRun < ScanTuning.ANALYSIS_INTERVAL_MS) return null
        lastRun = now
        if (!ScanEngine.ensure() || image.planes.size < 3) return FrameInfo(null, Double.MAX_VALUE, false)

        val w = image.width; val h = image.height
        val (sw, sh) = fitWithin(w, h, 480)
        val small = Mat()
        var chroma: Mat? = null
        try {
            val y = wrapY(image, w, h)
            try {
                Imgproc.resize(y, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            } finally {
                y.release()
            }
            // detectGray releases the Mat it is handed, so give it a copy and keep ours for glare.
            val raw = ScanEngine.detectRaw(small, lastQuad) {
                chromaOf(image).also { chroma = it }.clone()
            }
            lastQuad = raw
            if (raw == null) {
                glareRun = 0
                return FrameInfo(null, Double.MAX_VALUE, false)
            }
            val quad = raw.inset(QuadMath.INSET_COARSE)
            val sharp = ScanEngine.sharpness(small, quad)
            val c = chroma ?: chromaOf(image).also { chroma = it }
            val glaring = ScanEngine.glare(small, c, quad) > ScanTuning.GLARE_FRACTION
            // A few frames of hysteresis so the hint doesn't flicker.
            glareRun = if (glaring) min(glareRun + 1, 6) else (glareRun - 1).coerceAtLeast(0)
            return FrameInfo(quad.rotatedBy(image.imageInfo.rotationDegrees), sharp, glareRun >= 3)
        } finally {
            small.release()
            chroma?.release()
        }
    }

    /** The Y plane as a gray Mat: zero-copy for direct buffers, otherwise one copy into a reused array. */
    private fun wrapY(image: ImageProxy, w: Int, h: Int): Mat {
        val plane = image.planes[0]
        val buf = plane.buffer
        if (buf.isDirect) return Mat(h, w, CvType.CV_8UC1, buf, plane.rowStride.toLong())
        if (yCopy.size != w * h) yCopy = ByteArray(w * h)
        for (r in 0 until h) {
            buf.position(r * plane.rowStride)
            buf.get(yCopy, r * w, min(w, buf.remaining()))
        }
        return Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, yCopy) }
    }

    /**
     * Quarter-size "how colourful" image from the U and V planes: 0 for gray, rising with colour.
     * Used for the paper-on-coloured-desk fallback and to tell glare (white) from bright colours.
     */
    private fun chromaOf(image: ImageProxy): Mat {
        val u = image.planes[1]; val v = image.planes[2]
        val cw = image.width / 4; val ch = image.height / 4
        if (chromaBytes.size != cw * ch) chromaBytes = ByteArray(cw * ch)
        if (rowU.size < u.rowStride) rowU = ByteArray(u.rowStride)
        if (rowV.size < v.rowStride) rowV = ByteArray(v.rowStride)
        val stepU = 2 * u.pixelStride; val stepV = 2 * v.pixelStride
        for (r in 0 until ch) {
            val nu = readRow(u.buffer, r * 2 * u.rowStride, rowU, u.rowStride)
            val nv = readRow(v.buffer, r * 2 * v.rowStride, rowV, v.rowStride)
            val base = r * cw
            for (c in 0 until cw) {
                val iu = c * stepU; val iv = c * stepV
                val value = if (iu < nu && iv < nv) {
                    (abs((rowU[iu].toInt() and 0xFF) - 128) + abs((rowV[iv].toInt() and 0xFF) - 128)) * 2
                } else 0
                chromaBytes[base + c] = min(255, value).toByte()
            }
        }
        return Mat(ch, cw, CvType.CV_8UC1).also { it.put(0, 0, chromaBytes) }
    }

    private fun readRow(buf: ByteBuffer, pos: Int, into: ByteArray, max: Int): Int {
        if (pos >= buf.limit()) return 0
        buf.position(pos)
        val n = min(max, buf.remaining())
        buf.get(into, 0, n)
        return n
    }
}
