package com.wizlabs.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Page-level edits of existing PDFs: reorder, rotate, delete, extract, split and merge. Pages keep
 * their original content (text stays text); only the page tree changes. Every result is written
 * to a ".part" file first and renamed into place, so a crash never leaves a broken document.
 */
object PdfOps {

    /** A problem worth showing to the user as is. */
    class OpError(msg: String) : Exception(msg)

    /** Take source page [index] and turn it a further [rotate] degrees clockwise. */
    data class PageOp(val index: Int, val rotate: Int = 0)

    /** One PDF going into a merge. */
    data class MergeItem(val ref: PdfRef, val name: String, val pages: Int, val password: String? = null)

    /** A file PDFBox can read: library documents in place, anything else as a private copy. */
    class Source(val file: File, private val temp: Boolean) {
        fun done() { if (temp) file.delete() }
    }

    fun toolsDir(ctx: Context) = File(ctx.cacheDir, "tools").apply { mkdirs() }

    /** Keeps big documents on disk: at most 24 MB of a file is held in memory at once. */
    fun memory(ctx: Context): MemoryUsageSetting = MemoryUsageSetting.setupMixed(24L shl 20).setTempDir(ctx.cacheDir)

    fun sourceFile(ctx: Context, ref: PdfRef): Source = when (ref) {
        is PdfRef.Local -> Source(Library.fileOf(ctx, ref.docId), false)
        is PdfRef.External -> {
            val f = File(toolsDir(ctx), "src-${System.nanoTime()}.pdf")
            try {
                (ctx.contentResolver.openInputStream(ref.uri) ?: throw OpError("This file is no longer available."))
                    .use { input -> f.outputStream().use { input.copyTo(it) } }
            } catch (e: SecurityException) {
                f.delete()
                throw OpError("Permission to read this file has expired. Open it again from your file manager.")
            } catch (e: java.io.IOException) {
                f.delete()
                throw OpError("This file is no longer available.")
            }
            Source(f, true)
        }
    }

    fun load(ctx: Context, file: File, password: String?, memory: MemoryUsageSetting = memory(ctx)): PDDocument = try {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx)
        PDDocument.load(file, password ?: "", memory)
    } catch (e: InvalidPasswordException) {
        throw PasswordRequired()
    } catch (e: OutOfMemoryError) {
        throw OpError("This PDF is too large for this phone's memory.")
    } catch (e: Exception) {
        throw OpError("This PDF couldn't be read. It may be damaged.")
    }

    /** Refuse files whose author switched off editing (unless opened with the owner password). */
    fun checkEditable(doc: PDDocument) {
        if (!doc.isEncrypted) return
        val p = doc.currentAccessPermission
        if (!p.isOwnerPermission && !p.canModify() && !p.canAssembleDocument())
            throw OpError("The author of this PDF has turned off editing.")
    }

    /** Keep the user's password on the result; drop owner-only protection that allows editing. */
    fun keepSecurity(doc: PDDocument, password: String?) {
        if (!doc.isEncrypted) return
        if (password.isNullOrEmpty()) {
            doc.isAllSecurityToBeRemoved = true
        } else {
            val perm = AccessPermission(doc.currentAccessPermission.permissionBytes)
            doc.protect(StandardProtectionPolicy(password, password, perm).apply {
                encryptionKeyLength = 128
                isPreferAES = true
            })
        }
    }

    /**
     * Rebuild the page tree so it holds exactly [ops], in that order. Runs in place: page content,
     * fonts and pictures are untouched. Pages that are left out are emptied so nothing that still
     * points at them (bookmarks, links, form fields) drags their content into the saved file.
     */
    fun arrange(doc: PDDocument, ops: List<PageOp>) {
        val all = doc.pages.toList()
        val keep = HashSet<Int>()
        for (op in ops) {
            if (op.index !in all.indices) throw OpError("Page ${op.index + 1} doesn't exist.")
            if (!keep.add(op.index)) throw OpError("Page ${op.index + 1} is listed twice.")
        }
        if (ops.isEmpty()) throw OpError("A PDF needs at least one page.")
        for (op in ops) {
            val page = all[op.index]
            // Size, rotation and resources can be inherited from parent nodes: pin them to the page
            // before it moves to a new parent.
            page.mediaBox = page.mediaBox
            page.cropBox = page.cropBox
            page.resources?.let { page.resources = it }
            page.rotation = (((page.rotation + op.rotate) % 360) + 360) % 360
        }
        val tree = doc.pages
        tree.cosObject.setItem(COSName.KIDS, COSArray())
        tree.cosObject.setInt(COSName.COUNT, 0)
        for (op in ops) tree.add(all[op.index])
        all.forEachIndexed { i, page -> if (i !in keep) page.cosObject.clear() }
    }

    private fun hasText(doc: PDDocument): Boolean = runCatching {
        val s = PDFTextStripper().apply { startPage = 1; endPage = minOf(3, doc.numberOfPages) }
        s.getText(doc).count { !it.isWhitespace() } > 20
    }.getOrDefault(false)

    private fun requireSpace(ctx: Context, needed: Long) {
        if (ctx.filesDir.usableSpace < needed + (8L shl 20)) throw OpError("Not enough free storage to save the PDF.")
    }

    internal fun moveInto(part: File, out: File) {
        if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
    }

    /** First-page thumbnail for the library list. Best effort. */
    fun makeThumb(ctx: Context, file: File, id: String, password: String? = null) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val renderer = if (!password.isNullOrEmpty() && Build.VERSION.SDK_INT >= 35) {
                    PdfRenderer(pfd, android.graphics.pdf.LoadParams.Builder().setPassword(password).build())
                } else PdfRenderer(pfd)
                renderer.use { r ->
                    r.openPage(0).use { p ->
                        val w = 420
                        val h = (w.toFloat() * p.height / p.width).toInt().coerceIn(1, 1200)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ScanEngine.saveJpeg(bmp, Library.thumbOf(ctx, id), 85)
                        bmp.recycle()
                    }
                }
            }
        }.onFailure { Library.thumbOf(ctx, id).delete() }
    }

    /** Remove half-written files left behind if the app was killed while saving. */
    fun sweep(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 3_600_000
        Library.docsDir(ctx).listFiles()?.filter { it.name.endsWith(".part") && it.lastModified() < cutoff }?.forEach { it.delete() }
        toolsDir(ctx).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /**
     * Write the pages in [ops] to the library. With [replaceDocId] the existing library document
     * is overwritten (same id, name and creation date); otherwise a new document called [name] is added.
     */
    suspend fun savePages(
        ctx: Context,
        ref: PdfRef,
        password: String?,
        ops: List<PageOp>,
        name: String,
        replaceDocId: String? = null
    ): Doc = withContext(Dispatchers.IO) {
        sweep(ctx)
        val src = sourceFile(ctx, ref)
        val old = replaceDocId?.let { Library.doc(it) }
        val id = old?.id ?: Library.newId()
        val out = Library.fileOf(ctx, id)
        val part = File(out.path + ".part")
        try {
            requireSpace(ctx, src.file.length())
            var searchable = false
            load(ctx, src.file, password).use { doc ->
                checkEditable(doc)
                arrange(doc, ops)
                searchable = hasText(doc)
                keepSecurity(doc, password)
                coroutineContext.ensureActive()
                doc.save(part)
            }
            // Only now, with the source closed, may the result replace it.
            moveInto(part, out)
            makeThumb(ctx, out, id, password)
            val now = System.currentTimeMillis()
            val d = Doc(id, old?.name ?: name, ops.size, old?.created ?: now, now, out.length(), searchable)
            Library.add(d)
            d
        } catch (e: java.io.IOException) {
            throw OpError("Couldn't save the PDF. Check free storage and try again.")
        } catch (e: OutOfMemoryError) {
            throw OpError("This PDF is too large for this phone's memory.")
        } finally {
            part.delete()
            src.done()
        }
    }

    /**
     * A password to put on a PDF. Without an [ownerPassword], a file with limits gets a random one:
     * the limits then hold for good, because nobody can open it "as owner" to lift them.
     */
    data class Protection(
        val password: String,
        val ownerPassword: String? = null,
        val allowPrint: Boolean = true,
        val allowCopy: Boolean = true,
        val allowEdit: Boolean = true
    ) {
        val limited get() = !(allowPrint && allowCopy && allowEdit)
        override fun toString() = "Protection(limited=$limited)"      // never log a password
    }

    /** Lock [doc] with [p] using AES with a key of [bits] (256 or 128). Takes effect when the document is saved. */
    fun applyProtection(doc: PDDocument, p: Protection, bits: Int) {
        val perm = AccessPermission().apply {
            setCanPrint(p.allowPrint)
            setCanPrintFaithful(p.allowPrint)
            setCanExtractContent(p.allowCopy)
            setCanExtractForAccessibility(true)
            setCanModify(p.allowEdit)
            setCanModifyAnnotations(p.allowEdit)
            setCanAssembleDocument(p.allowEdit)
            setCanFillInForm(true)
        }
        val owner = p.ownerPassword?.takeIf { it.isNotEmpty() }
            ?: if (p.limited) java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID() else p.password
        doc.isAllSecurityToBeRemoved = false
        doc.protect(StandardProtectionPolicy(owner, p.password, perm).apply {
            encryptionKeyLength = bits
            isPreferAES = true
        })
    }

    /** The result of [setPassword]: the saved document and the AES key size used (0 = password removed). */
    class Secured(val doc: Doc, val bits: Int)

    /**
     * Add a password ([protection]) or remove the current one ([protection] = null), saving the
     * result to the library as [name], or over the library document [replaceDocId]. AES-256 is
     * used when this phone can do it, otherwise AES-128.
     */
    suspend fun setPassword(
        ctx: Context,
        ref: PdfRef,
        currentPassword: String?,
        protection: Protection?,
        name: String,
        replaceDocId: String? = null
    ): Secured = withContext(Dispatchers.IO) {
        sweep(ctx)
        val src = sourceFile(ctx, ref)
        val old = replaceDocId?.let { Library.doc(it) }
        val id = old?.id ?: Library.newId()
        val out = Library.fileOf(ctx, id)
        val part = File(out.path + ".part")
        try {
            requireSpace(ctx, src.file.length())
            var pages = 0
            var searchable = false
            fun write(bits: Int) = load(ctx, src.file, currentPassword).use { doc ->
                if (doc.isEncrypted && !doc.currentAccessPermission.isOwnerPermission)
                    throw OpError("This PDF also has an owner password. Open it with that password to change its protection.")
                pages = doc.numberOfPages
                searchable = hasText(doc)
                if (protection == null) doc.isAllSecurityToBeRemoved = true else applyProtection(doc, protection, bits)
                doc.save(part)
            }
            var bits = if (protection == null) 0 else 256
            try {
                write(bits)
            } catch (e: OpError) {
                throw e
            } catch (e: PasswordRequired) {
                throw e
            } catch (e: Exception) {
                // No AES-256 on this phone (or this PDFBox build): AES-128 works everywhere.
                if (protection == null) throw e
                part.delete()
                bits = 128
                write(bits)
            }
            coroutineContext.ensureActive()
            moveInto(part, out)
            makeThumb(ctx, out, id, protection?.password)
            val now = System.currentTimeMillis()
            val d = Doc(id, old?.name ?: name, pages, old?.created ?: now, now, out.length(), searchable)
            Library.add(d)
            Secured(d, bits)
        } catch (e: java.io.IOException) {
            throw OpError("Couldn't save the PDF. Check free storage and try again.")
        } catch (e: OutOfMemoryError) {
            throw OpError("This PDF is too large for this phone's memory.")
        } finally {
            part.delete()
            src.done()
        }
    }

    /** One new library document per group of pages. [onProgress] gets (done, total). */
    suspend fun split(
        ctx: Context,
        ref: PdfRef,
        password: String?,
        groups: List<List<Int>>,
        baseName: String,
        onProgress: (Int, Int) -> Unit
    ): List<Doc> {
        val made = ArrayList<Doc>()
        groups.forEachIndexed { i, pages ->
            coroutineContext.ensureActive()
            onProgress(i, groups.size)
            val name = uniqueDocName(baseName, pagesLabel(pages), Library.docs.value.map { it.name }.toSet())
            made += savePages(ctx, ref, password, pages.map { PageOp(it) }, name)
        }
        onProgress(groups.size, groups.size)
        return made
    }

    /** Join [items] in order into one new library document. [onProgress] gets (done, total). */
    suspend fun merge(ctx: Context, items: List<MergeItem>, name: String, onProgress: (Int, Int) -> Unit): Doc =
        withContext(Dispatchers.IO) {
            sweep(ctx)
            val id = Library.newId()
            val out = Library.fileOf(ctx, id)
            val part = File(out.path + ".part")
            val sources = ArrayList<Source>()
            val open = ArrayList<PDDocument>()
            try {
                val merger = PDFMergerUtility()
                var pages = 0
                var searchable = false
                PDDocument(memory(ctx)).use { dest ->
                    items.forEachIndexed { i, item ->
                        coroutineContext.ensureActive()
                        onProgress(i, items.size)
                        val src = sourceFile(ctx, item.ref).also { sources += it }
                        if (i == 0) requireSpace(ctx, items.size * src.file.length())
                        // Sources stay on disk (no memory buffers) and open until the result is saved.
                        val doc = try {
                            load(ctx, src.file, item.password, MemoryUsageSetting.setupTempFileOnly().setTempDir(ctx.cacheDir))
                        } catch (e: PasswordRequired) {
                            throw OpError("“${item.name}” is password-protected. Remove it from the list or unlock it first.")
                        }
                        open += doc
                        if (doc.isEncrypted) {
                            val p = doc.currentAccessPermission
                            if (!p.isOwnerPermission && !p.canAssembleDocument() && !p.canModify() && !p.canExtractContent())
                                throw OpError("The author of “${item.name}” has turned off copying its pages.")
                            doc.isAllSecurityToBeRemoved = true
                        }
                        if (!searchable) searchable = hasText(doc)
                        merger.appendDocument(dest, doc)
                        pages += doc.numberOfPages
                    }
                    onProgress(items.size, items.size)
                    coroutineContext.ensureActive()
                    dest.save(part)
                }
                moveInto(part, out)
                makeThumb(ctx, out, id)
                val now = System.currentTimeMillis()
                Doc(id, name, pages, now, now, out.length(), searchable).also { Library.add(it) }
            } catch (e: java.io.IOException) {
                throw OpError("Couldn't merge these PDFs. Check free storage and try again.")
            } catch (e: OutOfMemoryError) {
                throw OpError("These PDFs are too large to merge on this phone.")
            } finally {
                open.forEach { runCatching { it.close() } }
                part.delete()
                sources.forEach { it.done() }
            }
        }
}
