package com.wizlabs.pdfmaster

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// ---- Page ranges ("1-3,5") -------------------------------------------------------------------

sealed class RangeResult {
    /** One group of zero-based page indices per range the user typed. */
    data class Ok(val groups: List<List<Int>>) : RangeResult()
    data class Error(val message: String) : RangeResult()
}

/**
 * Parse page ranges such as "1-3, 5, 8-" (one-based, inclusive). "8-" runs to the last page,
 * "-3" starts at the first and "5-3" counts backwards.
 */
fun parsePageRanges(text: String, pageCount: Int): RangeResult {
    val tokens = text.split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return RangeResult.Error("Type the pages you want, for example 1-3,5")
    val groups = ArrayList<List<Int>>()
    for (t in tokens) {
        val parts = t.split('-', '–')
        val bad = RangeResult.Error("“$t” isn't a page or a range")
        val (from, to) = when (parts.size) {
            1 -> { val n = parts[0].toIntOrNull() ?: return bad; n to n }
            2 -> {
                val a = if (parts[0].isEmpty()) 1 else parts[0].toIntOrNull() ?: return bad
                val b = if (parts[1].isEmpty()) pageCount else parts[1].toIntOrNull() ?: return bad
                if (parts[0].isEmpty() && parts[1].isEmpty()) return bad
                a to b
            }
            else -> return bad
        }
        for (n in intArrayOf(from, to)) {
            if (n < 1 || n > pageCount) {
                return RangeResult.Error("Page $n doesn't exist (this PDF has $pageCount page${if (pageCount == 1) "" else "s"})")
            }
        }
        groups += if (from <= to) (from - 1..to - 1).toList() else (from - 1 downTo to - 1).toList()
    }
    return RangeResult.Ok(groups)
}

/** Cut [pageCount] pages into consecutive chunks of [n]. */
fun splitEvery(pageCount: Int, n: Int): List<List<Int>> {
    if (pageCount <= 0 || n <= 0) return emptyList()
    return (0 until pageCount).chunked(n)
}

/** Move the element at [from] to [to] (used by drag-to-reorder). */
fun <T> MutableList<T>.move(from: Int, to: Int) {
    if (from == to || from !in indices || to !in indices) return
    add(to, removeAt(from))
}

// ---- Compress to a target size ----------------------------------------------------------------

enum class ImgKind { BILEVEL, GRAY, COLOR }

/** What we know about the file before compressing. Sizes in bytes. */
data class SizeBreakdown(val fileBytes: Long, val imageBytes: Long) {
    /** Everything that is not a recompressible picture: text, fonts, structure. */
    val overhead: Long get() = max(0L, fileBytes - imageBytes)
}

object CompressMath {
    val DPI_STEPS = intArrayOf(200, 150, 120, 96)
    /** JPEG qualities each sample is actually encoded at; sizes in between are interpolated. */
    val Q_GRID = intArrayOf(30, 35, 42, 50, 60, 70, 85)
    const val Q_MAX = 85
    const val Q_GOOD = 50
    const val Q_MIN = 35
    const val Q_FLOOR = 30
    const val DPI_FLOOR = 96
    const val MAX_SAMPLES = 5

    /** Bytes the pictures may use: the target minus the fixed part minus a 2 % safety margin. */
    fun imageBudget(targetBytes: Long, overheadBytes: Long): Long = targetBytes - overheadBytes - targetBytes / 50

    /** The target is below what the file needs even with every picture removed. */
    fun unreachable(targetBytes: Long, overheadBytes: Long): Boolean = imageBudget(targetBytes, overheadBytes) <= 0

    /** Resolution a picture has on paper, assuming it fills the page along its long side. */
    fun effectiveDpi(imageLongSidePx: Int, pageLongSidePt: Float): Float =
        if (pageLongSidePt <= 0f) 72f else imageLongSidePx / (pageLongSidePt / 72f)

    /** Scale factor (never above 1) that brings a picture down to [dpi]. */
    fun scaleFor(imageLongSidePx: Int, pageLongSidePt: Float, dpi: Int): Float =
        min(1f, dpi / effectiveDpi(imageLongSidePx, pageLongSidePt))

    /** Evenly spread sample positions over [count] items. */
    fun sampleIndices(count: Int, maxSamples: Int = MAX_SAMPLES): List<Int> {
        if (count <= 0) return emptyList()
        if (count <= maxSamples) return (0 until count).toList()
        return (0 until maxSamples).map { ((it + 0.5f) * count / maxSamples).toInt() }.distinct()
    }

    /**
     * Classify a small sample of ARGB pixels. Near-bilevel: almost everything is very dark or very
     * light. Gray: no real colour. Everything else stays colour.
     */
    fun classify(pixels: IntArray): ImgKind {
        if (pixels.isEmpty()) return ImgKind.COLOR
        var colourful = 0
        var extreme = 0
        var dark = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            if (mx - mn > 28) colourful++
            val l = (r * 77 + g * 150 + b * 29) shr 8
            if (l < 56) { extreme++; dark++ } else if (l > 200) extreme++
        }
        val n = pixels.size.toFloat()
        if (colourful / n > 0.015f) return ImgKind.COLOR
        // Needs some ink too: a photo of a white wall is not a document.
        return if (extreme / n > 0.93f && dark / n in 0.002f..0.5f) ImgKind.BILEVEL else ImgKind.GRAY
    }

    /** Picks the compression settings; see [pickPlan]. */
    data class Plan(val dpi: Int, val quality: Int, val estimatedImageBytes: Long, val fits: Boolean)

    /**
     * Choose the gentlest settings whose estimated picture bytes fit [budget].
     * Round 1 keeps quality at [Q_GOOD] or better and walks the DPI ladder down; round 2 allows
     * qualities down to [Q_MIN]; if nothing fits, the floor (96 DPI, quality 30) is returned with
     * fits = false so the caller can offer the closest result.
     */
    fun pickPlan(budget: Long, dpis: IntArray = DPI_STEPS, curveFor: (Int) -> SizeCurve): Plan {
        for (dpi in dpis) {
            val q = curveFor(dpi).maxQualityWithin(budget, Q_GOOD, Q_MAX) ?: continue
            return Plan(dpi, q, curveFor(dpi).sizeAt(q), true)
        }
        for (dpi in dpis) {
            val q = curveFor(dpi).maxQualityWithin(budget, Q_MIN, Q_GOOD - 1) ?: continue
            return Plan(dpi, q, curveFor(dpi).sizeAt(q), true)
        }
        val floor = curveFor(DPI_FLOOR).sizeAt(Q_FLOOR)
        return Plan(DPI_FLOOR, Q_FLOOR, floor, floor <= budget)
    }

    /**
     * After a full pass missed the target: work out a new picture budget from what the saved file
     * really contains. Returns the budget expressed in "estimate" bytes, i.e. already corrected
     * for how far the estimate was off.
     */
    fun correctedBudget(targetBytes: Long, actualFileBytes: Long, actualImageBytes: Long, estimatedImageBytes: Long): Long {
        val overhead = max(0L, actualFileBytes - actualImageBytes)
        val real = imageBudget(targetBytes, overhead)
        if (actualImageBytes <= 0L || estimatedImageBytes <= 0L) return real
        // If pictures came out 10 % bigger than estimated, aim 10 % lower on the estimate scale.
        return (real.toDouble() * estimatedImageBytes / actualImageBytes).toLong()
    }
}

/** Estimated total picture bytes at each JPEG quality (ascending), for one DPI. */
class SizeCurve(private val qualities: IntArray, private val sizes: LongArray) {
    init { require(qualities.size == sizes.size && qualities.isNotEmpty()) }

    /** Linear interpolation between measured qualities; flat outside the measured range. */
    fun sizeAt(q: Int): Long {
        if (q <= qualities.first()) return sizes.first()
        if (q >= qualities.last()) return sizes.last()
        var i = 1
        while (qualities[i] < q) i++
        val q0 = qualities[i - 1]; val q1 = qualities[i]
        val t = (q - q0).toDouble() / (q1 - q0)
        return (sizes[i - 1] + (sizes[i] - sizes[i - 1]) * t).roundToLong()
    }

    /** Highest quality in [lo]..[hi] whose size fits [budget] (binary search), or null. */
    fun maxQualityWithin(budget: Long, lo: Int, hi: Int): Int? {
        if (sizeAt(lo) > budget) return null
        if (sizeAt(hi) <= budget) return hi
        var a = lo; var b = hi          // invariant: a fits, b does not
        repeat(6) {
            if (b - a <= 1) return a
            val m = (a + b) / 2
            if (sizeAt(m) <= budget) a = m else b = m
        }
        return a
    }
}

// ---- 1-bit packing --------------------------------------------------------------------------------

object Bilevel {
    fun rowBytes(width: Int) = (width + 7) / 8

    /**
     * Pack one row of ARGB pixels into bits, most significant bit first. A pixel counts as white
     * when its luminance is at least [threshold]. With [blackIsOne] dark pixels become 1 bits
     * (what the fax encoder wants); otherwise white is 1 (plain 1-bit DeviceGray).
     */
    fun packRow(pixels: IntArray, offset: Int, width: Int, out: ByteArray, outOffset: Int, threshold: Int, blackIsOne: Boolean) {
        var acc = 0
        var bits = 0
        var o = outOffset
        for (x in 0 until width) {
            val p = pixels[offset + x]
            val l = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            val white = l >= threshold
            acc = (acc shl 1) or (if (white != blackIsOne) 1 else 0)
            if (++bits == 8) { out[o++] = acc.toByte(); acc = 0; bits = 0 }
        }
        if (bits > 0) {
            // Pad the last byte with "white" so no stray dark edge appears.
            val pad = 8 - bits
            acc = acc shl pad
            if (!blackIsOne) acc = acc or ((1 shl pad) - 1)
            out[o] = acc.toByte()
        }
    }
}

// ---- Update prompt -------------------------------------------------------------------------------

object UpdateLogic {
    const val SNOOZE_MS = 3L * 24 * 60 * 60 * 1000

    /** The "update available" card stays hidden for three days after ✕, per offered version. */
    fun snoozed(offeredVersion: Int, snoozedVersion: Int, snoozedAt: Long, now: Long): Boolean =
        offeredVersion == snoozedVersion && now - snoozedAt in 0 until SNOOZE_MS

    /**
     * Show "What's new" once after an update. [lastSeen] is 0 for installs that predate the
     * feature, so those count as updated only when the package was really updated in place.
     */
    fun showWhatsNew(lastSeen: Int, current: Int, firstInstall: Long, lastUpdate: Long): Boolean =
        if (lastSeen > 0) lastSeen < current else lastUpdate > firstInstall
}

/** "Lease (pages 1-3)", then "Lease (pages 1-3) 2" … avoiding names already in [taken]. */
fun uniqueDocName(base: String, suffix: String, taken: Set<String>): String {
    val root = if (suffix.isEmpty()) base else "$base ($suffix)"
    if (root !in taken) return root
    var n = 2
    while ("$root $n" in taken) n++
    return "$root $n"
}

/** Short label for a group of zero-based pages: "page 5", "pages 1-3" or "4 pages". */
fun pagesLabel(pages: List<Int>): String = when {
    pages.size == 1 -> "page ${pages[0] + 1}"
    pages.zipWithNext().all { (a, b) -> b == a + 1 } -> "pages ${pages.first() + 1}-${pages.last() + 1}"
    else -> "${pages.size} pages"
}

/** Otsu's threshold (0..255) over the luminance of ARGB [pixels]: the cut between ink and paper. */
fun otsuThreshold(pixels: IntArray): Int {
    if (pixels.isEmpty()) return 128
    val hist = IntArray(256)
    for (p in pixels) hist[(((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8]++
    val total = pixels.size
    var sumAll = 0.0
    for (i in 0..255) sumAll += i * hist[i].toDouble()
    var sumB = 0.0; var wB = 0; var best = 0.0; var threshold = 128
    for (i in 0..255) {
        wB += hist[i]
        if (wB == 0) continue
        val wF = total - wB
        if (wF == 0) break
        sumB += i * hist[i].toDouble()
        val mB = sumB / wB; val mF = (sumAll - sumB) / wF
        val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
        if (between > best) { best = between; threshold = i + 1 }
    }
    return threshold.coerceIn(40, 215)
}
