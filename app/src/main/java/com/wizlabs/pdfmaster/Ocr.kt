package com.wizlabs.pdfmaster

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A recognised word and where it sits in the source bitmap. */
data class OcrWord(val text: String, val box: Rect)

data class OcrResult(val text: String, val words: List<OcrWord>, val width: Int, val height: Int, val lang: OcrLang = OcrLang.LATIN)

/**
 * On-device text recognition. The Latin model ships inside the APK; the other scripts are read by
 * components of Google Play services on the phone, and only when the user chose them.
 */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val extras = HashMap<OcrLang, TextRecognizer>()

    /** The reader for [lang]. Creating one never downloads anything by itself. */
    @Synchronized
    fun client(lang: OcrLang): TextRecognizer = if (lang == OcrLang.LATIN) recognizer else extras.getOrPut(lang) {
        TextRecognition.getClient(
            when (lang) {
                OcrLang.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                OcrLang.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                OcrLang.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                OcrLang.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
                OcrLang.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
            }
        )
    }

    private fun scoreOf(text: Text): Float = OcrLangs.score(
        text.textBlocks.flatMap { it.lines }.map { line ->
            // Readers that report no confidence (0) are taken at a middling value.
            line.text.count { it.isLetterOrDigit() } to (runCatching { line.confidence }.getOrDefault(0f).takeIf { it > 0f } ?: 0.6f)
        }
    )

    /**
     * Read the text on [bmp]. With [flatten] the recogniser is given a gray copy with the shadows
     * taken out, which reads better than either the raw photo or a hard black & white page.
     * Word boxes always come back in [bmp]'s own pixels.
     *
     * Latin is always read; each further script in [langs] is read as well and the reading with
     * the most confidently recognised text wins. A script whose component is not on the phone is
     * skipped quietly.
     */
    suspend fun recognize(bmp: Bitmap, flatten: Boolean = false, langs: Set<OcrLang> = setOf(OcrLang.LATIN)): OcrResult {
        // ML Kit is happiest around 2000px; bigger only costs time.
        val (w, h) = fitWithin(bmp.width, bmp.height, 2400)
        val input = when {
            flatten -> runCatching { ScanEngine.ocrImage(bmp, 2400) }.getOrNull()
            else -> null
        } ?: if (w == bmp.width) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        try {
            val image = InputImage.fromBitmap(input, 0)
            var text = recognizer.process(image).await()
            var lang = OcrLang.LATIN
            val more = OcrLangs.normalize(langs) - OcrLang.LATIN
            if (more.isNotEmpty()) {
                val readings = arrayListOf(lang to text)
                for (l in more) {
                    try {
                        // A component that is still downloading can leave the task waiting forever.
                        kotlinx.coroutines.withTimeoutOrNull(20_000) { client(l).process(image).await() }
                            ?.let { readings += l to it }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Component missing or still downloading: carry on with what there is.
                    }
                }
                val best = OcrLangs.best(readings.map { scoreOf(it.second) })
                lang = readings[best].first; text = readings[best].second
            }
            val sx = bmp.width.toFloat() / input.width
            val sy = bmp.height.toFloat() / input.height
            val words = ArrayList<OcrWord>()
            for (block in text.textBlocks) for (line in block.lines) for (el in line.elements) {
                val b = el.boundingBox ?: continue
                words += OcrWord(
                    el.text,
                    Rect((b.left * sx).toInt(), (b.top * sy).toInt(), (b.right * sx).toInt(), (b.bottom * sy).toInt())
                )
            }
            val full = text.textBlocks.joinToString("\n\n") { blk -> blk.lines.joinToString("\n") { it.text } }
            return OcrResult(full, words, bmp.width, bmp.height, lang)
        } finally {
            if (input !== bmp) input.recycle()
        }
    }

    /**
     * Which way up a page is and how tilted its text is, from one quick pass over a small copy
     * (about 1000 px). Null when the page has too little text to tell, or recognition fails.
     */
    suspend fun pageAngle(page: Bitmap): PageAngle.Fix? {
        val input = ScanEngine.ocrImage(page, 1000)
        try {
            val text = recognizer.process(InputImage.fromBitmap(input, 0)).await()
            val angles = ArrayList<Float>(); val weights = ArrayList<Float>()
            for (block in text.textBlocks) for (line in block.lines) {
                val n = line.text.count { it.isLetterOrDigit() }
                if (n < 3) continue
                angles += line.angle
                weights += n.toFloat()
            }
            return PageAngle.fix(angles.toFloatArray(), weights.toFloatArray())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        } finally {
            input.recycle()
        }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
