package com.wizlabs.pdfmaster

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** "Add to home screen": a launcher icon that opens one PDF directly. */
object HomeShortcuts {
    const val ACTION_OPEN = "com.wizlabs.pdfmaster.OPEN_PDF"
    private const val EXTRA_DOC_ID = "docId"
    private const val EXTRA_URI = "uri"
    private const val EXTRA_TITLE = "title"

    fun supported(ctx: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)

    private fun idFor(ref: PdfRef) = when (ref) {
        is PdfRef.Local -> "doc_${ref.docId}"
        is PdfRef.External -> "uri_${ref.uri.toString().hashCode().toUInt()}"
    }

    /** Ask the launcher to pin a shortcut. Android shows its own "Add to home screen" confirmation. */
    fun pin(ctx: Context, ref: PdfRef, firstPage: Bitmap?): Boolean {
        if (!supported(ctx)) return false
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = ACTION_OPEN
            putExtra(EXTRA_TITLE, ref.title)
            when (ref) {
                is PdfRef.Local -> putExtra(EXTRA_DOC_ID, ref.docId)
                is PdfRef.External -> putExtra(EXTRA_URI, ref.uri.toString())
            }
        }
        val info = ShortcutInfoCompat.Builder(ctx, idFor(ref))
            .setShortLabel(ref.title.take(24).ifBlank { "PDF" })
            .setLongLabel(ref.title.take(60).ifBlank { "PDF" })
            .setIcon(IconCompat.createWithAdaptiveBitmap(iconBitmap(firstPage)))
            .setIntent(intent)
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(ctx, info, null) }.getOrDefault(false)
    }

    /** Grey out a pinned shortcut whose document was deleted (launcher shows the message on tap). */
    fun disableFor(ctx: Context, docId: String) {
        runCatching {
            ShortcutManagerCompat.disableShortcuts(ctx, listOf("doc_$docId"), "This document was deleted")
        }
    }

    /** Turn a shortcut launch into the PDF to open, or null if it isn't one of ours. */
    fun refFrom(intent: Intent?): PdfRef? {
        if (intent?.action != ACTION_OPEN) return null
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "PDF"
        intent.getStringExtra(EXTRA_DOC_ID)?.let { return PdfRef.Local(it, title) }
        intent.getStringExtra(EXTRA_URI)?.let { return PdfRef.External(Uri.parse(it), title) }
        return null
    }

    /**
     * Adaptive icon (108dp canvas at 4x): Wiz yellow background with the document's first page
     * centred inside the 66dp safe zone, so every launcher mask (circle, squircle...) shows it whole.
     */
    private fun iconBitmap(page: Bitmap?): Bitmap {
        val size = 432
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(0xFF, 0xC8, 0x00))
        val safe = 264f                                  // 66dp of 108dp, at 4x
        val aspect = if (page != null && page.height > 0) page.width.toFloat() / page.height else 0.707f
        val h = safe * 0.92f
        val w = (h * aspect).coerceAtMost(safe)
        val r = RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        c.drawRoundRect(RectF(r.left + 6, r.top + 8, r.right + 6, r.bottom + 8), 14f, 14f, shadow)
        c.drawRoundRect(r, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        if (page != null) {
            c.save()
            val clip = android.graphics.Path().apply { addRoundRect(r, 14f, 14f, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            c.drawBitmap(page, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()
        }
        // Small black "PDF" tab so it reads as a document at a glance.
        val tab = RectF(r.right - 92f, r.bottom - 52f, r.right + 14f, r.bottom + 10f)
        c.drawRoundRect(tab, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x11, 0x11, 0x14) })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xFF, 0xC8, 0x00); textSize = 34f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        c.drawText("PDF", tab.centerX(), tab.centerY() + 12f, text)
        return out
    }
}
