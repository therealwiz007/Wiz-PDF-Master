package com.wizlabs.pdfmaster

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A recognised word and where it sits in the source bitmap. */
data class OcrWord(val text: String, val box: Rect)

data class OcrResult(val text: String, val words: List<OcrWord>, val width: Int, val height: Int)

/** On-device text recognition. The Latin model ships inside the APK; nothing is downloaded. */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bmp: Bitmap): OcrResult {
        // ML Kit is happiest around 2000px; bigger only costs time.
        val (w, h) = fitWithin(bmp.width, bmp.height, 2400)
        val input = if (w == bmp.width) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        try {
            val text = recognizer.process(InputImage.fromBitmap(input, 0)).await()
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
            return OcrResult(full, words, bmp.width, bmp.height)
        } finally {
            if (input !== bmp) input.recycle()
        }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
