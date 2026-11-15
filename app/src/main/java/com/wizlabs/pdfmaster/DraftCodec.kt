package com.wizlabs.pdfmaster

/** One page of an unfinished scan as written to disk. [file] is a name inside the draft folder. */
data class DraftPage(
    val id: String,
    val file: String,
    val quad: Quad,
    val rotation: Int,
    val filter: String,
    val blurry: Boolean = false,
    val skew: Float = 0f,
    val autoPick: String? = null,
    val card: Boolean = false,
    val curve: Boolean = false
)

/** An unfinished scan: enough to pick it up again after the app was closed or killed. */
data class Draft(val idCard: Boolean, val selected: Int, val pages: List<DraftPage>)

/**
 * A tiny line-based text format for drafts (one page per line, tab separated). Plain Kotlin on
 * purpose: it runs in unit tests, and a half-written or damaged file simply reads as "no draft".
 */
object DraftCodec {
    private const val MAGIC = "WIZDRAFT 1"

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun encode(d: Draft): String = buildString {
        append(MAGIC).append('\n')
        append("mode\t").append(if (d.idCard) "id" else "doc").append('\n')
        append("selected\t").append(d.selected).append('\n')
        for (p in d.pages) {
            append("page")
            append('\t').append(clean(p.id))
            append('\t').append(clean(p.file))
            for (c in p.quad.points) append('\t').append(c.x).append('\t').append(c.y)
            append('\t').append(p.rotation)
            append('\t').append(clean(p.filter))
            append('\t').append(if (p.blurry) 1 else 0)
            append('\t').append(p.skew)
            append('\t').append(p.autoPick?.let { clean(it) } ?: "-")
            append('\t').append(if (p.card) 1 else 0)
            append('\t').append(if (p.curve) 1 else 0)
            append('\n')
        }
        append("end\t").append(d.pages.size).append('\n')
    }

    /** Null for anything that is not a complete draft. */
    fun decode(text: String): Draft? {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        if (lines.firstOrNull() != MAGIC) return null
        var idCard = false; var selected = 0; var end = -1
        val pages = ArrayList<DraftPage>()
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            when (f[0]) {
                "mode" -> idCard = f.getOrNull(1) == "id"
                "selected" -> selected = f.getOrNull(1)?.toIntOrNull() ?: 0
                "end" -> end = f.getOrNull(1)?.toIntOrNull() ?: return null
                "page" -> {
                    if (f.size < 17) return null
                    val n = (3..10).map { f[it].toFloatOrNull() ?: return null }
                    if (n.any { it.isNaN() || it < -0.01f || it > 1.01f }) return null
                    pages += DraftPage(
                        id = f[1], file = f[2],
                        quad = Quad(P(n[0], n[1]), P(n[2], n[3]), P(n[4], n[5]), P(n[6], n[7])),
                        rotation = ((f[11].toIntOrNull() ?: return null) % 360 + 360) % 360,
                        filter = f[12],
                        blurry = f[13] == "1",
                        skew = f[14].toFloatOrNull()?.takeIf { !it.isNaN() } ?: 0f,
                        autoPick = f[15].takeIf { it != "-" },
                        card = f[16] == "1",
                        curve = f.getOrNull(17) == "1"      // added in 1.5: older drafts have no such field
                    )
                }
            }
        }
        // The last line proves the file was written to the end.
        if (end != pages.size || pages.isEmpty()) return null
        if (pages.any { it.file.contains('/') || it.file.contains("..") }) return null
        return Draft(idCard, selected.coerceIn(0, pages.lastIndex), pages)
    }
}
