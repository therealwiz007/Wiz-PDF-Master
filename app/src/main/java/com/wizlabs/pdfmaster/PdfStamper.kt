package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stamps signature images onto an existing PDF. The pages keep their original (vector) content;
 * the signature is appended as a PNG image with transparency. The result is always a new library
 * document: the original file is never modified.
 */
object PdfStamper {

    sealed class Check {
        data object Ok : Check()
        data class Warn(val certSigned: Boolean) : Check()
        data class Blocked(val why: String) : Check()
    }

    class SignError(msg: String) : Exception(msg)

    data class Result(val doc: Doc, val encrypted: Boolean)

    private fun load(ctx: Context, file: File, password: String?): PDDocument = try {
        PDDocument.load(file, password ?: "", MemoryUsageSetting.setupMixed(32L shl 20).setTempDir(ctx.cacheDir))
    } catch (e: InvalidPasswordException) {
        throw SignError("Couldn't unlock this PDF to sign it.")
    } catch (e: Exception) {
        throw SignError("This PDF can't be signed. It may be damaged.")
    }

    private fun check(doc: PDDocument): Check {
        if (doc.isEncrypted) {
            val p = doc.currentAccessPermission
            if (!p.isOwnerPermission && !p.canModify() && !p.canModifyAnnotations())
                return Check.Blocked("The author of this PDF has turned off editing, so it can't be signed.")
        }
        val signed = runCatching { doc.signatureDictionaries.isNotEmpty() }.getOrDefault(false)
        return if (signed) Check.Warn(certSigned = true) else Check.Ok
    }

    /** Copies the source to a private file PDFBox can read (local docs are used in place). */
    private fun sourceFile(ctx: Context, src: PdfSource): Pair<File, Boolean> = when (val r = src.ref) {
        is PdfRef.Local -> Library.fileOf(ctx, r.docId) to false
        is PdfRef.External -> {
            val f = File(ctx.cacheDir, "sign/src.pdf").apply { parentFile?.mkdirs() }
            try {
                src.openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            } catch (e: SecurityException) {
                throw SignError("Permission to read this file has expired. Open it again from your file manager.")
            }
            f to true
        }
    }

    /** Opens the PDF just to see whether it can be signed. */
    suspend fun precheck(ctx: Context, src: PdfSource): Check = withContext(Dispatchers.IO) {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx)
        val (file, temp) = sourceFile(ctx, src)
        try { load(ctx, file, src.password).use { check(it) } } finally { if (temp) file.delete() }
    }

    suspend fun stamp(
        ctx: Context,
        src: PdfSource,
        items: List<PlacementData>,
        onProgress: (Int, Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx)
        sweepParts(ctx)
        val (file, temp) = sourceFile(ctx, src)
        val id = Library.newId()
        val out = Library.fileOf(ctx, id)
        val part = File(out.path + ".part")
        val images = HashMap<Pair<String, Int>, Bitmap>()
        try {
            if (ctx.filesDir.usableSpace < file.length() * 2 + (8L shl 20)) throw SignError("Not enough free storage to save the signed copy.")
            val encrypted: Boolean
            load(ctx, file, src.password).use { doc ->
                (check(doc) as? Check.Blocked)?.let { throw SignError(it.why) }
                encrypted = doc.isEncrypted
                val xobjects = HashMap<Pair<String, Int>, PDImageXObject>()
                val byPage = items.groupBy { it.page }.toSortedMap()
                var done = 0
                for ((pageIndex, list) in byPage) {
                    if (pageIndex !in 0 until doc.numberOfPages) continue
                    val page = doc.getPage(pageIndex)
                    val box = page.cropBox
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        for (p in list) {
                            val key = p.sigId to p.ink
                            val img = xobjects.getOrPut(key) {
                                val bmp = images.getOrPut(key) { tintedFull(ctx, p.sigId, p.ink) }
                                LosslessFactory.createFromImage(doc, bmp)
                            }
                            val m = SignGeometry.placementMatrix(p, page.rotation, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
                            cs.saveGraphicsState()
                            cs.transform(Matrix(m.a, m.b, m.c, m.d, m.e, m.f))
                            cs.drawImage(img, 0f, 0f, 1f, 1f)
                            cs.restoreGraphicsState()
                        }
                    }
                    done++
                    onProgress(done, byPage.size)
                }
                if (encrypted) {
                    val pw = src.password
                    if (pw.isNullOrEmpty()) {
                        // Owner-only protection that allows editing: save without it.
                        doc.isAllSecurityToBeRemoved = true
                    } else {
                        val perm = AccessPermission(doc.currentAccessPermission.permissionBytes)
                        doc.protect(StandardProtectionPolicy(pw, pw, perm).apply {
                            encryptionKeyLength = 128
                            isPreferAES = true
                        })
                    }
                }
                doc.save(part)
            }
            if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }

            // Thumbnail: the original first page with any signatures placed on it.
            runCatching { thumbnail(ctx, src, items.filter { it.page == 0 }, images)?.let { ScanEngine.saveJpeg(it, Library.thumbOf(ctx, id), 85) } }

            val base = src.ref.title.removeSuffix(".pdf").removeSuffix(".PDF")
            val name = SignGeometry.uniqueName(base, Library.docs.value.map { it.name }.toSet())
            val searchable = when (val r = src.ref) {
                is PdfRef.Local -> Library.doc(r.docId)?.searchable ?: false
                is PdfRef.External -> src.isIndexed && !src.looksScanned
            }
            val now = System.currentTimeMillis()
            val d = Doc(id, name, src.pageCount, now, now, out.length(), searchable)
            Library.add(d)
            SignatureStore.touch(items.map { it.sigId }.toSet())
            Result(d, encrypted && !src.password.isNullOrEmpty())
        } catch (e: SignError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SignError("Couldn't save the signed copy. Check free storage and try again.")
        } finally {
            part.delete()
            if (temp) file.delete()
            images.values.forEach { it.recycle() }
        }
    }

    private fun tintedFull(ctx: Context, sigId: String, ink: Int): Bitmap {
        val raw = SignatureStore.decode(ctx, sigId) ?: throw SignError("A signature file is missing.")
        val t = SignatureEngine.tinted(raw, ink)
        if (t !== raw) raw.recycle()
        return t
    }

    private suspend fun thumbnail(ctx: Context, src: PdfSource, onFirst: List<PlacementData>, images: Map<Pair<String, Int>, Bitmap>): Bitmap? {
        val page = src.renderFresh(0, 420) ?: return null
        if (onFirst.isEmpty()) return page
        val c = Canvas(page)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val W = page.width.toFloat(); val H = page.height.toFloat()
        for (p in onFirst) {
            val bmp = images[p.sigId to p.ink] ?: continue
            val w = p.w * W; val h = w / p.aspect
            c.save()
            c.translate(p.cx * W, p.cy * H)
            c.rotate(p.rot)
            c.drawBitmap(bmp, null, android.graphics.RectF(-w / 2, -h / 2, w / 2, h / 2), paint)
            c.restore()
        }
        return page
    }

    /** Remove half-written files left behind if the app was killed while saving. */
    private fun sweepParts(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 3_600_000
        Library.docsDir(ctx).listFiles()?.filter { it.name.endsWith(".part") && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
