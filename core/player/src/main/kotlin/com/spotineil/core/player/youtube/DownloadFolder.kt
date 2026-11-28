package com.spotineil.core.player.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream

/**
 * A downloads folder the person picked with Android's folder picker (Settings → Downloads → Save to).
 * Files go in through the storage-access framework, which also makes the phone's music library
 * pick them up.
 */
object DownloadFolder {

    /** Keeps the access to a picked folder after the phone restarts. */
    fun keepAccess(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** Gives the access back when another folder (or the default one) is chosen. */
    fun releaseAccess(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** "Music/Mine" for a folder on the phone's storage ("SD card: Music" elsewhere), to show and to find its songs. */
    fun displayPath(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        return if (volume == "primary") path.ifBlank { "/" } else "$volume: $path"
    }

    /** The folder's path relative to its storage ("Music/Mine/"), as the music library stores it. */
    fun relativePath(tree: Uri): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val path = id.substringAfter(':', "").trim('/')
        return if (path.isEmpty()) null else "$path/"
    }

    /**
     * Writes a file called [name] into [tree] (inside its [subfolder] when given, made if needed) and
     * returns the name it got. With [replace] an existing file of that name is overwritten; otherwise
     * the folder gives the new one a free name ("… (1)").
     */
    fun write(
        context: Context,
        tree: Uri,
        subfolder: String?,
        name: String,
        replace: Boolean = false,
        body: (OutputStream) -> Unit,
    ): String {
        val resolver = context.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val dir = if (subfolder == null) root else {
            child(context, tree, root, subfolder)
                ?: DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, subfolder)
                ?: throw IOException("Could not make the folder")
        }
        val existing = if (replace) child(context, tree, dir, name) else null
        // "application/octet-stream" keeps the name exactly as given (no extension added).
        val doc = existing
            ?: DocumentsContract.createDocument(resolver, dir, "application/octet-stream", name)
            ?: throw IOException("Could not create the file")
        try {
            (resolver.openOutputStream(doc, "wt") ?: throw IOException("Could not write the file")).use(body)
        } catch (t: Throwable) {
            if (existing == null) runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw t
        }
        return resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: name
    }

    private fun child(context: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }
}
