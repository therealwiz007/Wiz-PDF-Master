package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.filter.FilterFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.zip.Deflater
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** 1-bit black & white pictures: fax (CCITT G4) coding, with plain deflate as the fallback. */
object BilevelCodec {
    class Encoded(val bytes: ByteArray, val ccitt: Boolean)

    /** Pack [bmp] to one bit per pixel; a pixel is white when its luminance reaches [threshold]. */
    fun pack(bmp: Bitmap, threshold: Int, blackIsOne: Boolean): ByteArray {
        val w = bmp.width; val h = bmp.height
        val rowBytes = Bilevel.rowBytes(w)
        val out = ByteArray(rowBytes * h)
        val rows = (65536 / w).coerceIn(1, h)
        val px = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = minOf(rows, h - y)
            bmp.getPixels(px, 0, w, 0, y, w, n)
            for (r in 0 until n) Bilevel.packRow(px, r * w, w, out, (y + r) * rowBytes, threshold, blackIsOne)
            y += n
        }
        return out
    }

    /** Fax-encode rows packed with dark = 1. Pure PDFBox, no Android classes involved. */
    fun g4(packedBlackIsOne: ByteArray, width: Int, height: Int): ByteArray {
        val params = COSDictionary().apply { setInt(COSName.COLUMNS, width); setInt(COSName.ROWS, height) }
        val out = ByteArrayOutputStream(packedBlackIsOne.size / 8 + 64)
        FilterFactory.INSTANCE.getFilter(COSName.CCITTFAX_DECODE).encode(ByteArrayInputStream(packedBlackIsOne), out, params, 0)
        return out.toByteArray()
    }

    fun deflate(packedWhiteIsOne: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        val out = ByteArrayOutputStream(packedWhiteIsOne.size / 6 + 64)
        val buf = ByteArray(16 * 1024)
        try {
            d.setInput(packedWhiteIsOne); d.finish()
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        } finally {
            d.end()
        }
        return out.toByteArray()
    }

    fun encode(bmp: Bitmap, threshold: Int = 128): Encoded = try {
        Encoded(g4(pack(bmp, threshold, blackIsOne = true), bmp.width, bmp.height), true)
    } catch (_: Exception) {
        Encoded(deflate(pack(bmp, threshold, blackIsOne = false)), false)
    }

    /** Stream dictionary entries that describe [e] for a picture of the given size. */
    fun describe(dict: COSDictionary, e: Encoded, width: Int, height: Int) {
        dict.setInt(COSName.WIDTH, width)
        dict.setInt(COSName.HEIGHT, height)
        dict.setInt(COSName.BITS_PER_COMPONENT, 1)
        dict.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
        if (e.ccitt) {
            dict.setItem(COSName.FILTER, COSName.CCITTFAX_DECODE)
            dict.setItem(COSName.DECODE_PARMS, COSDictionary().apply {
                setInt(COSName.K, -1); setInt(COSName.COLUMNS, width); setInt(COSName.ROWS, height)
            })
        } else {
            dict.setItem(COSName.FILTER, COSName.FLATE_DECODE)
            dict.removeItem(COSName.DECODE_PARMS)
        }
    }

    /** A new 1-bit image for [doc], or null if it could not be made (caller falls back to JPEG). */
    fun image(doc: PDDocument, bmp: Bitmap, threshold: Int = 128): PDImageXObject? = try {
        val e = encode(bmp, threshold)
        val filter = if (e.ccitt) COSName.CCITTFAX_DECODE else COSName.FLATE_DECODE
        PDImageXObject(doc, ByteArrayInputStream(e.bytes), filter, bmp.width, bmp.height, 1, PDDeviceGray.INSTANCE)
            .also { describe(it.cosObject, e, bmp.width, bmp.height) }
    } catch (_: Throwable) {
        null
    }
}

/**
 * Shrinks a PDF towards a target size by re-encoding its pictures in place. Text, fonts and the
 * invisible OCR layer are never touched. Work happens one picture at a time so memory stays low.
 */
object PdfCompress {

    enum class Preset(val label: String, val dpi: Int, val quality: Int) {
        HIGH("High quality", 200, 80), MEDIUM("Medium", 150, 62), SMALL("Small", 110, 45)
    }

    /** A recompressible picture inside the open document. */
    private class Img(val stream: COSStream, val w: Int, val h: Int, val rawBytes: Long, val pageLongPt: Float, val jpeg: Boolean)

    internal class Sample(val file: File, val rawBytes: Long, val w: Int, val h: Int, val pageLongPt: Float) {
        var kind: ImgKind? = null
        var threshold = 128
    }

    /** What the file is made of, plus a few sample pictures on disk for size estimates. */
    class Analysis internal constructor(
        val ref: PdfRef,
        internal val password: String?,
        val size: SizeBreakdown,
        val imageCount: Int,
        val pages: Int,
        private val samples: List<Sample>,
        private val scale: Double
    ) {
        private val curves = HashMap<Int, SizeCurve>()

        /** Estimated picture bytes at each quality for [dpi] (measured once on the samples, then cached). */
        internal suspend fun curve(dpi: Int): SizeCurve {
            synchronized(curves) { curves[dpi] }?.let { return it }
            val grid = CompressMath.Q_GRID
            val sums = LongArray(grid.size)
            for (s in samples) {
                coroutineContext.ensureActive()
                val sizes = sampleSizes(s, dpi, grid)
                for (i in grid.indices) sums[i] += sizes[i]
            }
            val c = SizeCurve(grid, LongArray(grid.size) { (sums[it] * scale).toLong() })
            synchronized(curves) { curves[dpi] = c }
            return c
        }

        fun dispose() { samples.forEach { it.file.delete() } }
    }

    data class Estimate(val plan: CompressMath.Plan, val totalBytes: Long, val unreachable: Boolean)

    class Outcome(val file: File, val bytes: Long, val plan: CompressMath.Plan, val targetBytes: Long?) {
        val met get() = targetBytes == null || bytes <= targetBytes
    }

    // ---- Finding pictures -------------------------------------------------------------------

    private val THUMB = COSName.getPDFName("Thumb")
    private val PIECE_INFO = COSName.getPDFName("PieceInfo")
    private val JPEG_NAMES = setOf("DCTDecode", "DCT")

    private fun filterNames(s: COSStream): List<String> = when (val f = s.getDictionaryObject(COSName.FILTER)) {
        is COSName -> listOf(f.name)
        is COSArray -> (0 until f.size()).mapNotNull { (f.getObject(it) as? COSName)?.name }
        else -> emptyList()
    }

    private fun eligible(s: COSStream, pageLongPt: Float): Img? {
        if (s.getDictionaryObject(COSName.SMASK) != null || s.getDictionaryObject(COSName.MASK) != null) return null
        if (s.getBoolean(COSName.IMAGE_MASK, false)) return null
        val w = s.getInt(COSName.WIDTH, 0); val h = s.getInt(COSName.HEIGHT, 0)
        val raw = s.getLong(COSName.LENGTH, 0L)
        if (w < 64 || h < 64 || raw < 4096) return null
        if (s.getInt(COSName.BITS_PER_COMPONENT, 8) == 1) return null          // already 1-bit
        val filters = filterNames(s)
        if (filters.any { it == "JPXDecode" || it == "JBIG2Decode" }) return null   // no decoder on board
        val cs = s.getDictionaryObject(COSName.COLORSPACE)
        val plainColour = (cs is COSName && (cs == COSName.DEVICERGB || cs == COSName.DEVICEGRAY)) ||
            (cs is COSArray && cs.size() > 0 && (cs.getObject(0) as? COSName) == COSName.ICCBASED)
        val jpeg = filters.size == 1 && filters[0] in JPEG_NAMES && plainColour && s.getDictionaryObject(COSName.DECODE) == null
        return Img(s, w, h, raw, pageLongPt, jpeg)
    }

    private fun sha1(s: COSStream): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(64 * 1024)
        s.createRawInputStream().use { input ->
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Every recompressible picture once, in page order. Identical copies are folded into one. */
    private fun collect(doc: PDDocument): List<Img> {
        val seen = IdentityHashMap<COSBase, Boolean>()
        val out = ArrayList<Img>()
        // Pictures that could be byte-for-byte copies share size and encoding; hash only those.
        val bySignature = HashMap<String, MutableList<COSStream>>()
        val hashes = IdentityHashMap<COSStream, String?>()

        fun duplicateOf(s: COSStream): COSStream? {
            val cs = s.getDictionaryObject(COSName.COLORSPACE) as? COSName ?: return null
            if (s.getDictionaryObject(COSName.SMASK) != null || s.getDictionaryObject(COSName.MASK) != null ||
                s.getDictionaryObject(COSName.DECODE) != null) return null
            val sig = "${s.getLong(COSName.LENGTH, 0L)}:${s.getInt(COSName.WIDTH, 0)}x${s.getInt(COSName.HEIGHT, 0)}:" +
                "${s.getInt(COSName.BITS_PER_COMPONENT, 8)}:${cs.name}:${filterNames(s)}:${s.getDictionaryObject(COSName.DECODE_PARMS) != null}"
            val group = bySignature.getOrPut(sig) { ArrayList() }
            if (group.isNotEmpty() && s.getDictionaryObject(COSName.DECODE_PARMS) == null) {
                val mine = hashes.getOrPut(s) { sha1(s) }
                if (mine != null) for (other in group) {
                    if (other !== s && hashes.getOrPut(other) { sha1(other) } == mine) return other
                }
            }
            if (group.none { it === s }) group += s
            return null
        }

        fun walk(resources: COSDictionary, pageLongPt: Float, depth: Int) {
            val xobjects = resources.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: return
            for (key in xobjects.keySet().toList()) {
                val s = xobjects.getDictionaryObject(key) as? COSStream ?: continue
                when (s.getCOSName(COSName.SUBTYPE)) {
                    COSName.IMAGE -> {
                        val twin = duplicateOf(s)
                        if (twin != null) { xobjects.setItem(key, twin); continue }
                        if (seen.put(s, true) == null) eligible(s, pageLongPt)?.let { out += it }
                    }
                    COSName.FORM -> if (depth < 4 && seen.put(s, true) == null) {
                        (s.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.let { walk(it, pageLongPt, depth + 1) }
                    }
                    else -> {}
                }
            }
        }
        for (page in doc.pages) {
            val box = page.mediaBox
            page.resources?.cosObject?.let { walk(it, max(box.width, box.height), 0) }
        }
        return out
    }

    // ---- Decoding and encoding --------------------------------------------------------------

    private fun scaled(b: Bitmap, tw: Int, th: Int): Bitmap {
        if (b.width == tw && b.height == th) return b
        val out = Bitmap.createScaledBitmap(b, tw, th, true)
        if (out !== b) b.recycle()
        return out
    }

    private fun sampleFor(w: Int, h: Int, tw: Int, th: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        return sample
    }

    /** The picture as a bitmap of exactly [tw] x [th]; JPEGs are decoded directly at reduced size. */
    private fun decode(img: Img, tw: Int, th: Int): Bitmap? {
        if (img.jpeg) {
            val bytes = img.stream.createRawInputStream().use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(img.w, img.h, tw, th) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { return scaled(it, tw, th) }
        }
        val full = PDImageXObject(PDStream(img.stream), null).image ?: return null
        return scaled(full, tw, th)
    }

    private fun classifyBitmap(bmp: Bitmap): Pair<ImgKind, Int> {
        val (w, h) = fitWithin(bmp.width, bmp.height, 200)
        val small = if (w == bmp.width && h == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        val kind = CompressMath.classify(px)
        return kind to (if (kind == ImgKind.BILEVEL) otsuThreshold(px) else 128)
    }

    /** Single-channel JPEGs via OpenCV (Android's own encoder always writes three channels). */
    private fun grayJpegs(bmp: Bitmap, qualities: IntArray): List<ByteArray>? {
        if (!ScanEngine.ensure()) return null
        val rgba = Mat(); val gray = Mat()
        try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            return qualities.map { q ->
                val buf = MatOfByte(); val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, q)
                try {
                    if (!Imgcodecs.imencode(".jpg", gray, buf, params)) return null
                    buf.toArray()
                } finally {
                    buf.release(); params.release()
                }
            }
        } catch (_: Throwable) {
            return null
        } finally {
            rgba.release(); gray.release()
        }
    }

    private fun colourJpeg(bmp: Bitmap, q: Int, scratch: ByteArrayOutputStream): ByteArray {
        scratch.reset()
        bmp.compress(Bitmap.CompressFormat.JPEG, q, scratch)
        return scratch.toByteArray()
    }

    /** Pixel size for a picture at [dpi]. 1-bit pages keep at least 150 DPI so print stays readable. */
    private fun targetSize(w: Int, h: Int, pageLongPt: Float, dpi: Int): Pair<Int, Int> {
        val s = CompressMath.scaleFor(max(w, h), pageLongPt, dpi)
        return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
    }

    private const val BILEVEL_MIN_DPI = 150

    // ---- Estimating -------------------------------------------------------------------------

    /** Encoded size of one sample at [dpi] for every quality in [grid] (never more than it is now). */
    private fun sampleSizes(s: Sample, dpi: Int, grid: IntArray): LongArray {
        val fallback = LongArray(grid.size) { s.rawBytes }
        var bmp: Bitmap? = null
        try {
            val (bw, bh) = targetSize(s.w, s.h, s.pageLongPt, max(dpi, BILEVEL_MIN_DPI))
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(s.w, s.h, bw, bh) }
            bmp = scaled(BitmapFactory.decodeFile(s.file.path, opts) ?: return fallback, bw, bh)
            if (s.kind == null) classifyBitmap(bmp).let { s.kind = it.first; s.threshold = it.second }
            if (s.kind == ImgKind.BILEVEL) {
                val n = minOf(BilevelCodec.encode(bmp, s.threshold).bytes.size.toLong(), s.rawBytes)
                return LongArray(grid.size) { n }
            }
            val (tw, th) = targetSize(s.w, s.h, s.pageLongPt, dpi)
            bmp = scaled(bmp, tw, th)
            val gray = if (s.kind == ImgKind.GRAY) grayJpegs(bmp, grid) else null
            val scratch = ByteArrayOutputStream(256 * 1024)
            return LongArray(grid.size) { i ->
                val n = gray?.get(i)?.size ?: run { scratch.reset(); bmp!!.compress(Bitmap.CompressFormat.JPEG, grid[i], scratch); scratch.size() }
                minOf(n.toLong(), s.rawBytes)
            }
        } catch (_: OutOfMemoryError) {
            System.gc()
            return fallback
        } catch (_: Exception) {
            return fallback
        } finally {
            bmp?.recycle()
        }
    }

    /** Open the PDF, measure pictures against everything else and stash up to five samples. */
    suspend fun analyze(ctx: Context, ref: PdfRef, password: String?): Analysis = withContext(Dispatchers.IO) {
        PdfOps.sweep(ctx)
        val src = PdfOps.sourceFile(ctx, ref)
        try {
            PdfOps.load(ctx, src.file, password).use { doc ->
                PdfOps.checkEditable(doc)
                val imgs = collect(doc)
                val picked = CompressMath.sampleIndices(imgs.size).map { imgs[it] }
                val samples = ArrayList<Sample>()
                for ((i, img) in picked.withIndex()) {
                    coroutineContext.ensureActive()
                    val f = File(PdfOps.toolsDir(ctx), "sample-${System.nanoTime()}-$i.jpg")
                    val ok = runCatching {
                        if (img.jpeg) {
                            img.stream.createRawInputStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                            BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                .also { BitmapFactory.decodeFile(f.path, it) }.outWidth > 0
                        } else {
                            val bmp = decode(img, img.w, img.h) ?: return@runCatching false
                            ScanEngine.saveJpeg(bmp, f, 95); bmp.recycle(); true
                        }
                    }.getOrDefault(false)
                    if (ok) samples += Sample(f, img.rawBytes, img.w, img.h, img.pageLongPt) else f.delete()
                }
                val imageBytes = imgs.sumOf { it.rawBytes }
                val sampleBytes = samples.sumOf { it.rawBytes }
                // No readable sample: treat pictures as fixed so we never promise a size we can't reach.
                val usable = if (samples.isEmpty()) 0L else imageBytes
                Analysis(
                    ref, password, SizeBreakdown(src.file.length(), usable), if (samples.isEmpty()) 0 else imgs.size,
                    doc.numberOfPages, samples, if (sampleBytes > 0) imageBytes.toDouble() / sampleBytes else 0.0
                )
            }
        } catch (e: OutOfMemoryError) {
            throw PdfOps.OpError("This PDF is too large for this phone's memory.")
        } finally {
            src.done()
        }
    }

    /** Best settings for a target size, measured on the samples. */
    suspend fun estimate(a: Analysis, targetBytes: Long): Estimate = withContext(Dispatchers.Default) {
        val budget = CompressMath.imageBudget(targetBytes, a.size.overhead)
        val curves = HashMap<Int, SizeCurve>()
        for (dpi in CompressMath.DPI_STEPS) curves[dpi] = a.curve(dpi)
        val plan = CompressMath.pickPlan(budget) { curves.getValue(it) }
        Estimate(plan, a.size.overhead + plan.estimatedImageBytes, CompressMath.unreachable(targetBytes, a.size.overhead))
    }

    /** Estimated result of a fixed preset. */
    suspend fun estimate(a: Analysis, preset: Preset): Estimate = withContext(Dispatchers.Default) {
        val bytes = a.curve(preset.dpi).sizeAt(preset.quality)
        Estimate(CompressMath.Plan(preset.dpi, preset.quality, bytes, true), a.size.overhead + bytes, false)
    }

    // ---- Compressing ------------------------------------------------------------------------

    private class Pass(val file: File, val bytes: Long, val imageBytes: Long)

    /** Re-encode one picture in place. Returns its size afterwards (unchanged if it didn't shrink). */
    private fun recompress(img: Img, dpi: Int, quality: Int, scratch: ByteArrayOutputStream): Long {
        var bmp: Bitmap? = null
        try {
            val (bw, bh) = targetSize(img.w, img.h, img.pageLongPt, max(dpi, BILEVEL_MIN_DPI))
            bmp = decode(img, bw, bh) ?: return img.rawBytes
            val (kind, threshold) = classifyBitmap(bmp)
            val s = img.stream
            if (kind == ImgKind.BILEVEL) {
                val e = BilevelCodec.encode(bmp, threshold)
                if (e.bytes.size >= img.rawBytes) return img.rawBytes
                s.createRawOutputStream().use { it.write(e.bytes) }
                clean(s)
                BilevelCodec.describe(s, e, bmp.width, bmp.height)
                return e.bytes.size.toLong()
            }
            val (tw, th) = targetSize(img.w, img.h, img.pageLongPt, dpi)
            bmp = scaled(bmp, tw, th)
            val gray = if (kind == ImgKind.GRAY) grayJpegs(bmp, intArrayOf(quality))?.firstOrNull() else null
            val bytes = gray ?: colourJpeg(bmp, quality, scratch)
            if (bytes.size >= img.rawBytes) return img.rawBytes
            s.createRawOutputStream().use { it.write(bytes) }
            clean(s)
            s.setInt(COSName.WIDTH, bmp.width)
            s.setInt(COSName.HEIGHT, bmp.height)
            s.setInt(COSName.BITS_PER_COMPONENT, 8)
            s.setItem(COSName.COLORSPACE, if (gray != null) COSName.DEVICEGRAY else COSName.DEVICERGB)
            s.setItem(COSName.FILTER, COSName.DCT_DECODE)
            return bytes.size.toLong()
        } catch (_: OutOfMemoryError) {
            System.gc()
            return img.rawBytes
        } catch (_: Exception) {
            return img.rawBytes
        } finally {
            bmp?.recycle()
        }
    }

    /** Drop entries that described the old encoding or only add weight. */
    private fun clean(s: COSStream) {
        s.removeItem(COSName.DECODE_PARMS)
        s.removeItem(COSName.DECODE)
        s.removeItem(COSName.METADATA)
        s.removeItem(COSName.getPDFName("Intent"))
    }

    private fun strip(doc: PDDocument) {
        val catalog = doc.documentCatalog.cosObject
        catalog.removeItem(COSName.METADATA)
        catalog.removeItem(PIECE_INFO)
        for (page in doc.pages) {
            page.cosObject.removeItem(THUMB)
            page.cosObject.removeItem(PIECE_INFO)
            page.cosObject.removeItem(COSName.METADATA)
        }
    }

    private suspend fun pass(ctx: Context, a: Analysis, dpi: Int, quality: Int, onProgress: (Int, Int) -> Unit): Pass {
        val src = PdfOps.sourceFile(ctx, a.ref)
        val out = File(PdfOps.toolsDir(ctx), "compressed-${System.nanoTime()}.pdf")
        try {
            var after = 0L
            PdfOps.load(ctx, src.file, a.password).use { doc ->
                PdfOps.checkEditable(doc)
                val imgs = collect(doc)
                val scratch = ByteArrayOutputStream(512 * 1024)
                imgs.forEachIndexed { i, img ->
                    coroutineContext.ensureActive()
                    onProgress(i, imgs.size)
                    after += recompress(img, dpi, quality, scratch)
                }
                onProgress(imgs.size, imgs.size)
                strip(doc)
                PdfOps.keepSecurity(doc, a.password)
                coroutineContext.ensureActive()
                doc.save(out)
            }
            return Pass(out, out.length(), after)
        } catch (t: Throwable) {
            out.delete()
            throw when (t) {
                is java.io.IOException -> PdfOps.OpError("Couldn't write the smaller PDF. Check free storage and try again.")
                is OutOfMemoryError -> PdfOps.OpError("This PDF is too large for this phone's memory.")
                else -> t
            }
        } finally {
            src.done()
        }
    }

    /**
     * Compress to [targetBytes] (or with a fixed [preset]). One full pass, then, if the target
     * was missed and there is still room to go lower, one corrected pass. The result is a temporary
     * file: call [keep] to add it to the library or delete it. [onProgress] gets (pass, done, total).
     */
    suspend fun compress(
        ctx: Context,
        a: Analysis,
        targetBytes: Long?,
        preset: Preset?,
        onProgress: (Int, Int, Int) -> Unit
    ): Outcome = withContext(Dispatchers.Default) {
        val plan = if (targetBytes != null) estimate(a, targetBytes).plan else estimate(a, preset ?: Preset.MEDIUM).plan
        val first = pass(ctx, a, plan.dpi, plan.quality) { d, t -> onProgress(1, d, t) }
        if (targetBytes == null || first.bytes <= targetBytes) return@withContext Outcome(first.file, first.bytes, plan, targetBytes)

        // Missed: re-plan from what the saved file really contains, and try once more.
        val budget = CompressMath.correctedBudget(targetBytes, first.bytes, first.imageBytes, plan.estimatedImageBytes)
        val curves = HashMap<Int, SizeCurve>()
        for (dpi in CompressMath.DPI_STEPS) curves[dpi] = a.curve(dpi)
        val retry = CompressMath.pickPlan(budget) { curves.getValue(it) }
        val lower = retry.dpi < plan.dpi || (retry.dpi == plan.dpi && retry.quality < plan.quality)
        if (!lower) return@withContext Outcome(first.file, first.bytes, plan, targetBytes)
        val second = try {
            pass(ctx, a, retry.dpi, retry.quality) { d, t -> onProgress(2, d, t) }
        } catch (t: Throwable) {
            first.file.delete(); throw t
        }
        if (second.bytes < first.bytes) {
            first.file.delete(); Outcome(second.file, second.bytes, retry, targetBytes)
        } else {
            second.file.delete(); Outcome(first.file, first.bytes, plan, targetBytes)
        }
    }

    /** Add a finished result to the library as a new document. */
    suspend fun keep(ctx: Context, a: Analysis, outcome: Outcome, name: String): Doc = withContext(Dispatchers.IO) {
        val id = Library.newId()
        val out = Library.fileOf(ctx, id)
        PdfOps.moveInto(outcome.file, out)
        PdfOps.makeThumb(ctx, out, id, a.password)
        val searchable = when (val r = a.ref) {
            is PdfRef.Local -> Library.doc(r.docId)?.searchable ?: false
            is PdfRef.External -> false
        }
        val now = System.currentTimeMillis()
        Doc(id, name, a.pages, now, now, out.length(), searchable).also { Library.add(it) }
    }
}
