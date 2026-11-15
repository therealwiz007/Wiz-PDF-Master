package com.wizlabs.pdfmaster

import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.io.RandomAccessFile

/**
 * Fonts for text the standard PDF fonts can't write (Devanagari, Chinese, Japanese, Korean),
 * borrowed from the phone's own font folder and embedded as a subset (only the glyphs used).
 * A phone without a usable font simply gets no font: callers then leave such words out.
 */
class UnicodeFonts(private val doc: PDDocument, private val dir: File = File("/system/fonts")) {
    private val loaded = HashMap<Script, PDFont?>()
    /** True once any of these fonts has been handed out (the document then needs them at save time). */
    var used = false
        private set

    fun font(script: Script): PDFont? {
        if (script == Script.LATIN || script == Script.OTHER) return null
        if (!loaded.containsKey(script)) loaded[script] = load(script)
        return loaded[script]?.also { used = true }
    }

    private fun load(script: Script): PDFont? {
        for (name in candidates(script)) {
            val f = File(dir, name)
            if (!f.canRead() || f.length() > MAX_BYTES) continue
            val font = runCatching {
                // PDFBox can only embed TrueType outlines ("glyf"); CFF-based fonts are passed over.
                if (name.endsWith(".ttc", true)) {
                    if (!hasGlyf(f, firstFontOffset(f))) return@runCatching null
                    var found: PDFont? = null
                    TrueTypeCollection(f).processAllFonts { ttf -> if (found == null) found = PDType0Font.load(doc, ttf, true) }
                    found
                } else {
                    if (!hasGlyf(f, 0)) return@runCatching null
                    f.inputStream().use { PDType0Font.load(doc, it, true) }
                }
            }.getOrNull()
            if (font != null) return font
        }
        return null
    }

    companion object {
        /** Bigger files (full CJK collections) would be read into memory whole: too much for small phones. */
        private const val MAX_BYTES = 24L shl 20

        fun candidates(script: Script): List<String> = when (script) {
            Script.DEVANAGARI -> listOf(
                "NotoSansDevanagari-Regular.ttf", "NotoSansDevanagari-VF.ttf", "NotoSansDevanagariUI-Regular.ttf",
                "NotoSansDevanagariUI-VF.ttf", "NotoSerifDevanagari-Regular.ttf", "NotoSerifDevanagari-VF.ttf", "Lohit-Devanagari.ttf"
            )
            Script.CJK -> listOf(
                "DroidSansFallback.ttf", "DroidSansFallbackFull.ttf", "NotoSansSC-Regular.ttf", "NotoSansCJKsc-Regular.ttf",
                "NotoSansCJK-Regular.ttc", "NotoSerifCJK-Regular.ttc"
            )
            else -> emptyList()
        }

        /** Where the first font of a collection starts (0 for a plain font file). */
        fun firstFontOffset(f: File): Long = RandomAccessFile(f, "r").use { r ->
            val tag = ByteArray(4); r.readFully(tag)
            if (String(tag, Charsets.ISO_8859_1) != "ttcf") return 0
            r.skipBytes(4)
            if (r.readInt() < 1) return 0
            r.readInt().toLong() and 0xFFFFFFFFL
        }

        /** Does the font starting at [offset] carry TrueType outlines? */
        fun hasGlyf(f: File, offset: Long): Boolean = RandomAccessFile(f, "r").use { r ->
            r.seek(offset + 4)
            val tables = r.readUnsignedShort()
            r.seek(offset + 12)
            val tag = ByteArray(4)
            repeat(tables.coerceAtMost(200)) {
                r.readFully(tag)
                if (String(tag, Charsets.ISO_8859_1) == "glyf") return true
                r.skipBytes(12)
            }
            false
        }
    }
}
