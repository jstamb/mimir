package dev.mimir.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.mimir.scanner.TreeAccessException
import dev.mimir.scanner.TreeSource

/**
 * Bulk SAF tree source: one ContentResolver child-query per directory
 * (vs DocumentFile's one IPC round-trip per entry).
 */
class DocumentsTreeSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : TreeSource {
    val rootUri: String = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    ).toString()

    override fun children(dirUri: String): List<TreeSource.Node> {
        val docId = DocumentsContract.getDocumentId(Uri.parse(dirUri))
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null)
        } catch (e: SecurityException) {
            throw TreeAccessException("folder permission revoked", e)
        } ?: throw TreeAccessException("document provider returned no result for $dirUri")

        val out = mutableListOf<TreeSource.Node>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += TreeSource.Node(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)).toString(),
                    isDirectory = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = c.getLong(3),
                )
            }
        }
        return out
    }
}
