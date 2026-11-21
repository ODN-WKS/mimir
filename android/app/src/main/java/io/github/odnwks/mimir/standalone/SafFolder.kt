package io.github.odnwks.mimir.standalone

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream

/**
 * The folder the owner chose with Android's folder picker (a "tree" address Android remembers for
 * the app). Files are read where they are: the model runner is given the open file's
 * `/proc/self/fd/<n>` path, so nothing is copied.
 */
class SafFolder(private val context: Context, private val tree: Uri) : OwnFolder {
    private val resolver get() = context.contentResolver

    private fun doc(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    override val name: String
        get() = runCatching {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "Your folder"

    override fun files(): List<OwnFile>? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<OwnFile>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR || !name.endsWith(".gguf", ignoreCase = true)) continue
                out += OwnFile(c.getString(0), name, c.getLong(2))
            }
        } ?: return null
        out
    } catch (_: Exception) {
        null  // permission taken back, or the folder is gone
    }

    override fun header(docId: String): InputStream? = runCatching { resolver.openInputStream(doc(docId)) }.getOrNull()

    override fun open(docId: String): OwnOpen? {
        val pfd = runCatching { resolver.openFileDescriptor(doc(docId), "r") }.getOrNull() ?: return null
        return object : OwnOpen {
            override val path = "/proc/self/fd/${pfd.fd}"
            override fun close() = runCatching { pfd.close() }.let { }
        }
    }

    override fun copy(docId: String, to: File, progress: (Long) -> Unit): Boolean = try {
        resolver.openInputStream(doc(docId))?.use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 32L shl 20) { progress(done); last = done }
                }
            }
            true
        } ?: false
    } catch (_: Exception) {
        to.delete()
        false
    }

    companion object {
        fun of(context: Context, uri: String): SafFolder? = runCatching {
            val u = Uri.parse(uri)
            // Only a folder the app was allowed to keep reading.
            if (context.contentResolver.persistedUriPermissions.none { it.uri == u && it.isReadPermission }) return null
            SafFolder(context, u)
        }.getOrNull()
    }
}
