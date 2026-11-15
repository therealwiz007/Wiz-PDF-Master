package com.wizlabs.pdfmaster

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Plain-Kotlin rules behind the 1.5 tools (no Android classes), so they run in unit tests:
 * batch rename, page numbers, watermark placement, which scanner to open, OCR languages,
 * curved-page maths, QR contents and annotation geometry.
 */

// ---- Batch rename -------------------------------------------------------------------------------

object RenamePattern {
    const val NAME = "{name}"
    const val DATE = "{date}"
    const val N = "{n}"
    private val token = Regex("\\{(name|date|n)\\}")

    /** Fill one pattern in. Each placeholder is replaced once, in a single pass. */
    fun render(pattern: String, name: String, date: String, n: Int): String =
        token.replace(pattern) { m ->
            when (m.groupValues[1]) { "name" -> name; "date" -> date; else -> n.toString() }
        }.replace(Regex("[\\n\\r\\t]"), " ").trim().take(120)

    /**
     * New names for [names] (in list order, numbered from [start]). No two results are equal and
     * none collides with [others] (documents that keep their name): clashes get " (2)", " (3)" …
     */
    fun plan(pattern: String, names: List<String>, dates: List<String>, others: Set<String>, start: Int = 1): List<String> {
        val used = HashSet(others)
        val out = ArrayList<String>(names.size)
        names.forEachIndexed { i, name ->
            val base = render(pattern, name, dates.getOrElse(i) { "" }, start + i).ifBlank { name }
            var candidate = base
            var k = 2
            while (candidate in used) candidate = "$base (${k++})"
            used += candidate
            out += candidate
        }
        return out
    }
}

// ---- Page numbers -------------------------------------------------------------------------------

enum class NumberFormat(val label: String) { PLAIN("1"), SLASH("1 / N"), PAGE_OF("Page 1 of N") }

enum class NumberPos(val label: String, val top: Boolean, val right: Boolean) {
    BOTTOM_CENTER("Bottom centre", false, false), BOTTOM_RIGHT("Bottom right", false, true),
    TOP_CENTER("Top centre", true, false), TOP_RIGHT("Top right", true, true)
}

object PageNumbers {
    /** The text for the page at [index] (zero-based) of [count], numbering from [start]. */
    fun label(format: NumberFormat, index: Int, count: Int, start: Int = 1): String {
        val n = start + index
        val last = start + count - 1
        return when (format) {
            NumberFormat.PLAIN -> "$n"
            NumberFormat.SLASH -> "$n / $last"
            NumberFormat.PAGE_OF -> "Page $n of $last"
        }
    }

    /** Distance from the page edge, in points: about 1 cm, less on small pages. */
    fun margin(pageW: Float, pageH: Float): Float = minOf(28f, pageW * 0.06f, pageH * 0.06f)

    /**
     * Where the text starts (left end of its baseline) on a page of [pageW]×[pageH] points as it
     * is displayed, y measured up from the bottom edge.
     */
    fun anchor(pos: NumberPos, pageW: Float, pageH: Float, textW: Float, fontSize: Float): P {
        val m = margin(pageW, pageH)
        val x = if (pos.right) pageW - m - textW else (pageW - textW) / 2f
        val y = if (pos.top) pageH - m - fontSize * 0.72f else m
        return P(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
    }
}

// ---- Watermark ----------------------------------------------------------------------------------

object WatermarkGeometry {
    /**
     * Font size at which a line of text ([unitWidth] wide at size 1), turned [deg] degrees, fills
     * [fraction] of the page in whichever direction is tighter.
     */
    fun fontSize(pageW: Float, pageH: Float, unitWidth: Float, deg: Float, fraction: Float): Float {
        val r = Math.toRadians(deg.toDouble())
        val c = abs(cos(r)).toFloat(); val s = abs(sin(r)).toFloat()
        val u = unitWidth.coerceAtLeast(0.1f)
        val byW = fraction * pageW / (u * c + s).coerceAtLeast(1e-3f)
        val byH = fraction * pageH / (u * s + c).coerceAtLeast(1e-3f)
        return minOf(byW, byH).coerceIn(4f, 400f)
    }

    /**
     * Text matrix (display points, y up) that turns the text [deg] degrees anticlockwise and puts
     * its middle on the middle of the page.
     */
    fun textMatrix(pageW: Float, pageH: Float, textW: Float, fontSize: Float, deg: Float): Affine {
        val r = Math.toRadians(deg.toDouble())
        val cs = cos(r).toFloat(); val sn = sin(r).toFloat()
        val mx = textW / 2f; val my = fontSize * 0.35f          // middle of the text: half its x-height up
        return Affine(cs, sn, -sn, cs, pageW / 2f - (cs * mx - sn * my), pageH / 2f - (sn * mx + cs * my))
    }

    /** Unit square to a centred picture [fraction] of the page wide, turned [deg] degrees anticlockwise. */
    fun imageMatrix(pageW: Float, pageH: Float, aspect: Float, fraction: Float, deg: Float): Affine {
        var w = fraction * pageW
        var h = w / aspect.coerceAtLeast(0.01f)
        if (h > fraction * pageH) { h = fraction * pageH; w = h * aspect }
        val r = Math.toRadians(deg.toDouble())
        val cs = cos(r).toFloat(); val sn = sin(r).toFloat()
        val a = w * cs; val b = w * sn; val c = -h * sn; val d = h * cs
        return Affine(a, b, c, d, pageW / 2f - (a + c) / 2f, pageH / 2f - (b + d) / 2f)
    }
}

// ---- Which scanner ------------------------------------------------------------------------------

enum class ScannerKind(val label: String) { SMART("Smart (Google)"), CLASSIC("Classic (Wiz)") }

object ScannerChoice {
    /**
     * [pref] is what the user picked in Settings (null = never touched). The Google scanner needs
     * Play services; ID cards always use the app's own camera (front and back, real size).
     */
    fun pick(pref: ScannerKind?, playServices: Boolean, idCard: Boolean): ScannerKind =
        if (idCard || !playServices) ScannerKind.CLASSIC else pref ?: ScannerKind.SMART

    fun decode(s: String?): ScannerKind? = ScannerKind.entries.firstOrNull { it.name == s }

    /** After the Google scanner closed: open the app's own camera instead? (Not when the user simply backed out.) */
    fun fallBack(resultOk: Boolean, cancelled: Boolean, pages: Int): Boolean = !cancelled && (!resultOk || pages == 0)
}

// ---- OCR languages ------------------------------------------------------------------------------

enum class OcrLang(val label: String, val detail: String) {
    LATIN("English & Latin", "English, Spanish, French, German and more"),
    DEVANAGARI("Devanagari", "Hindi, Marathi, Nepali, Sanskrit"),
    CHINESE("Chinese", "Simplified and traditional"),
    JAPANESE("Japanese", "Kanji, hiragana, katakana"),
    KOREAN("Korean", "Hangul")
}

enum class Script { LATIN, DEVANAGARI, CJK, OTHER }

object OcrLangs {
    /** Latin ships inside the app and is always on. */
    fun normalize(set: Set<OcrLang>): Set<OcrLang> = (set + OcrLang.LATIN).toSortedSet()

    fun encode(set: Set<OcrLang>): String = normalize(set).joinToString(",") { it.name }

    fun decode(s: String?): Set<OcrLang> = normalize(
        s.orEmpty().split(',').mapNotNull { n -> OcrLang.entries.firstOrNull { it.name == n.trim() } }.toSet()
    )

    /** How much a reading is worth: letters and digits read, weighted by how sure the reader was. */
    fun score(lines: List<Pair<Int, Float>>): Float = lines.sumOf { (chars, conf) -> (chars * conf.coerceIn(0f, 1f)).toDouble() }.toFloat()

    /**
     * Index of the reading to keep. The first one (Latin) wins unless another is clearly better,
     * so plain English pages never flip to a different reader over a rounding difference.
     */
    fun best(scores: List<Float>, margin: Float = 1.1f): Int {
        if (scores.isEmpty()) return -1
        var best = 0
        for (i in 1 until scores.size) if (scores[i] > scores[best] * (if (best == 0) margin else 1f)) best = i
        return best
    }

    /** The writing system of a word, judged by its first character outside the Latin ranges. */
    fun scriptOf(word: String): Script {
        for (ch in word) {
            val c = ch.code
            when {
                c < 0x250 || c in 0x2000..0x20CF -> continue
                c in 0x0900..0x097F || c in 0xA8E0..0xA8FF -> return Script.DEVANAGARI
                c in 0x2E80..0x9FFF || c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF -> return Script.CJK
                else -> return Script.OTHER
            }
        }
        return Script.LATIN
    }
}

// ---- Curved pages -------------------------------------------------------------------------------

/**
 * Straightens the text lines of a page that bulges (a book near its spine). Everything is in
 * page fractions: x and y run 0..1 across the width and down the height.
 */
object CurveMath {
    /** One text line: y = c0 + c1·x + c2·x² between [x0] and [x1]. */
    class Line(val c: DoubleArray, val x0: Float, val x1: Float) {
        fun at(x: Float): Float { val t = x.coerceIn(x0, x1).toDouble(); return (c[0] + c[1] * t + c[2] * t * t).toFloat() }
        /** Where the line sits once it is straight: its average height. */
        val level: Float = run {
            var s = 0f
            for (i in 0..20) s += at(x0 + (x1 - x0) * i / 20f)
            s / 21f
        }
    }

    class Model(val lines: List<Line>)

    /** Least-squares parabola through the points; null with fewer than 3 or when they don't spread in x. */
    fun fitQuadratic(xs: FloatArray, ys: FloatArray): DoubleArray? {
        val n = minOf(xs.size, ys.size)
        if (n < 3) return null
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (i in 0 until n) {
            val x = xs[i].toDouble(); val y = ys[i].toDouble(); val x2 = x * x
            s0 += 1; s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2
            t0 += y; t1 += x * y; t2 += x2 * y
        }
        // Normal equations, solved with Cramer's rule.
        val det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
        if (abs(det) < 1e-12) return null
        val c0 = (t0 * (s2 * s4 - s3 * s3) - s1 * (t1 * s4 - s3 * t2) + s2 * (t1 * s3 - s2 * t2)) / det
        val c1 = (s0 * (t1 * s4 - t2 * s3) - t0 * (s1 * s4 - s3 * s2) + s2 * (s1 * t2 - s2 * t1)) / det
        val c2 = (s0 * (s2 * t2 - s3 * t1) - s1 * (s1 * t2 - s2 * t1) + t0 * (s1 * s3 - s2 * s2)) / det
        return doubleArrayOf(c0, c1, c2)
    }

    /**
     * Keep the lines that agree with each other (a stray table rule or picture edge bends
     * differently from the text around it) and sort them top to bottom.
     */
    fun build(lines: List<Line>): Model {
        if (lines.size < 3) return Model(lines.sortedBy { it.level })
        val bends = lines.map { it.c[2] }.sorted()
        val med = bends[bends.size / 2]
        val mad = bends.map { abs(it - med) }.sorted()[bends.size / 2]
        val tol = 3 * mad + 0.02
        return Model(lines.filter { abs(it.c[2] - med) <= tol }.sortedBy { it.level })
    }

    /**
     * How far down (in page heights) the picture must be read from to fill the straightened
     * point ([x], [y]): the bend of the nearest lines above and below, blended by distance.
     */
    fun displacement(m: Model, x: Float, y: Float): Float {
        val ls = m.lines
        if (ls.isEmpty()) return 0f
        if (y <= ls.first().level) return ls.first().let { it.at(x) - it.level }
        if (y >= ls.last().level) return ls.last().let { it.at(x) - it.level }
        var lo = 0; var hi = ls.lastIndex
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (ls[mid].level <= y) lo = mid else hi = mid }
        val a = ls[lo]; val b = ls[hi]
        val span = b.level - a.level
        val t = if (span <= 1e-6f) 0f else (y - a.level) / span
        return (a.at(x) - a.level) * (1 - t) + (b.at(x) - b.level) * t
    }

    /** The largest bend anywhere on the page, in page heights. */
    fun maxBend(m: Model): Float {
        var best = 0f
        for (l in m.lines) for (i in 0..10) best = maxOf(best, abs(l.at(l.x0 + (l.x1 - l.x0) * i / 10f) - l.level))
        return best
    }

    const val MIN_LINES = 5
    const val MIN_BEND = 0.003f
    const val MAX_BEND = 0.09f

    /** Worth applying? Enough lines, spread over the page, bent visibly but not absurdly. */
    fun confident(m: Model): Boolean {
        if (m.lines.size < MIN_LINES) return false
        if (m.lines.last().level - m.lines.first().level < 0.3f) return false
        return maxBend(m) in MIN_BEND..MAX_BEND
    }

    /** Displacements on a coarse [cols]×[rows] grid (row-major), to be enlarged into a remap table. */
    fun grid(m: Model, cols: Int, rows: Int): FloatArray {
        val out = FloatArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols)
            out[r * cols + c] = displacement(m, c / (cols - 1f), r / (rows - 1f))
        return out
    }
}

// ---- QR contents --------------------------------------------------------------------------------

sealed class QrContent {
    abstract val title: String
    data class Url(val url: String) : QrContent() { override val title get() = "Link" }
    data class Wifi(val ssid: String, val password: String, val security: String, val hidden: Boolean) : QrContent() { override val title get() = "Wi-Fi network" }
    data class Contact(val name: String, val phones: List<String>, val emails: List<String>, val org: String) : QrContent() { override val title get() = "Contact" }
    data class Email(val to: String, val subject: String, val body: String) : QrContent() { override val title get() = "E-mail" }
    data class Phone(val number: String) : QrContent() { override val title get() = "Phone number" }
    data class Sms(val number: String, val body: String) : QrContent() { override val title get() = "Text message" }
    data class Geo(val lat: String, val lon: String) : QrContent() { override val title get() = "Location" }
    data class Text(val text: String) : QrContent() { override val title get() = "Text" }
}

object QrParse {
    fun parse(raw: String): QrContent {
        val s = raw.trim()
        val up = s.uppercase()
        return when {
            up.startsWith("WIFI:") -> wifi(s.substring(5))
            up.startsWith("BEGIN:VCARD") -> vcard(s)
            up.startsWith("MECARD:") -> mecard(s.substring(7))
            up.startsWith("MAILTO:") -> mailto(s.substring(7))
            up.startsWith("MATMSG:") -> fields(s.substring(7)).let { QrContent.Email(it["TO"].orEmpty(), it["SUB"].orEmpty(), it["BODY"].orEmpty()) }
            up.startsWith("TEL:") -> QrContent.Phone(s.substring(4).trim())
            up.startsWith("SMSTO:") || up.startsWith("SMS:") -> s.substringAfter(':').let { QrContent.Sms(it.substringBefore(':').trim(), it.substringAfter(':', "")) }
            up.startsWith("GEO:") -> s.substring(4).substringBefore('?').split(',').let { QrContent.Geo(it.getOrElse(0) { "" }.trim(), it.getOrElse(1) { "" }.trim()) }
            isUrl(s) -> QrContent.Url(if (up.startsWith("WWW.")) "https://$s" else s)
            else -> QrContent.Text(raw)
        }
    }

    private fun isUrl(s: String): Boolean {
        if (s.any { it.isWhitespace() }) return false
        val l = s.lowercase()
        return (l.startsWith("http://") || l.startsWith("https://")) && l.length > 10 || l.startsWith("www.") && l.count { it == '.' } >= 2
    }

    /** Split "K:value;K2:value2;" where ; : , and \ inside values are escaped with a backslash. */
    private fun fields(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val cur = StringBuilder()
        var key: String? = null
        var i = 0
        fun flush() { key?.let { k -> if (k !in out) out[k] = cur.toString() }; key = null; cur.setLength(0) }
        while (i < body.length) {
            val ch = body[i]
            when {
                ch == '\\' && i + 1 < body.length -> { cur.append(body[i + 1]); i++ }
                ch == ':' && key == null -> { key = cur.toString().trim().uppercase(); cur.setLength(0) }
                ch == ';' -> flush()
                else -> cur.append(ch)
            }
            i++
        }
        flush()
        return out
    }

    private fun wifi(body: String): QrContent {
        val f = fields(body)
        val type = f["T"].orEmpty().ifBlank { "nopass" }
        return QrContent.Wifi(
            ssid = f["S"].orEmpty().removeSurrounding("\""),
            password = f["P"].orEmpty().removeSurrounding("\""),
            security = if (type.equals("nopass", true)) "Open" else type.uppercase(),
            hidden = f["H"].equals("true", true)
        )
    }

    private fun mecard(body: String): QrContent {
        val phones = ArrayList<String>(); val emails = ArrayList<String>()
        var name = ""; var org = ""
        // A card may list several TEL / EMAIL entries: read them one by one.
        for (part in splitUnescaped(body, ';')) {
            val k = part.substringBefore(':').trim().uppercase()
            val v = part.substringAfter(':', "").replace("\\", "").trim()
            if (v.isEmpty()) continue
            when (k) {
                "N" -> name = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.reversed().joinToString(" ")
                "TEL" -> phones += v
                "EMAIL" -> emails += v
                "ORG" -> org = v
            }
        }
        return QrContent.Contact(name, phones, emails, org)
    }

    private fun splitUnescaped(s: String, sep: Char): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) { cur.append(ch).append(s[i + 1]); i += 2; continue }
            if (ch == sep) { out += cur.toString(); cur.setLength(0) } else cur.append(ch)
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun vcard(s: String): QrContent {
        val phones = ArrayList<String>(); val emails = ArrayList<String>()
        var fn = ""; var n = ""; var org = ""
        for (line in s.lines()) {
            val head = line.substringBefore(':').uppercase()
            val v = line.substringAfter(':', "").trim()
            if (v.isEmpty()) continue
            val prop = head.substringBefore(';').substringAfterLast('.')
            when (prop) {
                "FN" -> fn = v
                "N" -> n = v.split(';').map { it.trim() }.filter { it.isNotEmpty() }.let { p -> (p.drop(1) + p.take(1)).joinToString(" ") }
                "TEL" -> phones += v
                "EMAIL" -> emails += v
                "ORG" -> org = v.replace(';', ' ').trim()
            }
        }
        return QrContent.Contact(fn.ifBlank { n }, phones, emails, org)
    }

    private fun mailto(rest: String): QrContent {
        val to = rest.substringBefore('?')
        val q = rest.substringAfter('?', "").split('&').associate { it.substringBefore('=').lowercase() to pct(it.substringAfter('=', "")) }
        return QrContent.Email(pct(to), q["subject"].orEmpty(), q["body"].orEmpty())
    }

    private fun pct(s: String): String = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** The text to copy or share for this content. */
    fun plain(c: QrContent, raw: String): String = when (c) {
        is QrContent.Url -> c.url
        is QrContent.Wifi -> "Network: ${c.ssid}\nPassword: ${c.password}\nSecurity: ${c.security}"
        is QrContent.Contact -> listOf(c.name, c.org).filter { it.isNotBlank() }.plus(c.phones).plus(c.emails).joinToString("\n")
        is QrContent.Email -> listOf(c.to, c.subject, c.body).filter { it.isNotBlank() }.joinToString("\n")
        is QrContent.Phone -> c.number
        is QrContent.Sms -> listOf(c.number, c.body).filter { it.isNotBlank() }.joinToString("\n")
        is QrContent.Geo -> "${c.lat}, ${c.lon}"
        is QrContent.Text -> raw
    }
}

// ---- Annotations --------------------------------------------------------------------------------

object AnnotGeometry {
    /**
     * A point on the displayed page (0..1 from its top-left corner) in PDF user space, for a page
     * whose crop box starts at ([bx],[by]), measures [bw]×[bh] and is shown turned by [rot].
     */
    fun toUser(x: Float, y: Float, rot: Int, bx: Float, by: Float, bw: Float, bh: Float): P {
        val (w, h) = SignGeometry.displaySize(rot, bw, bh)
        return SignGeometry.displayToUser(rot, bx, by, bw, bh).map(x * w, (1f - y) * h)
    }

    /** Drop points closer than [minDist] to the one kept before them (the ends always stay). */
    fun thin(points: FloatArray, minDist: Float): FloatArray {
        val n = points.size / 2
        if (n <= 2) return points
        val out = ArrayList<Float>(points.size)
        var lx = points[0]; var ly = points[1]
        out += lx; out += ly
        for (i in 1 until n - 1) {
            val x = points[i * 2]; val y = points[i * 2 + 1]
            if (hypot(x - lx, y - ly) >= minDist) { out += x; out += y; lx = x; ly = y }
        }
        out += points[(n - 1) * 2]; out += points[(n - 1) * 2 + 1]
        return out.toFloatArray()
    }

    /** Is ([x],[y]) within [tol] of the polyline? (All in the same units.) */
    fun nearStroke(points: FloatArray, x: Float, y: Float, tol: Float): Boolean {
        val n = points.size / 2
        if (n == 0) return false
        if (n == 1) return hypot(points[0] - x, points[1] - y) <= tol
        for (i in 0 until n - 1) {
            val ax = points[i * 2]; val ay = points[i * 2 + 1]
            val bx = points[i * 2 + 2]; val by = points[i * 2 + 3]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-12f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
            if (hypot(ax + dx * t - x, ay + dy * t - y) <= tol) return true
        }
        return false
    }
}
