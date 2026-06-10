package dev.mimir.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.mimir.scanner.ScannedFile

object SafTreeWalker {
    /** Walks a persisted SAF tree and returns every file as a ScannedFile with a path relative to the root. */
    fun walk(context: Context, treeUri: Uri): List<ScannedFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<ScannedFile>()
        fun recurse(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) recurse(child, path)
                else out += ScannedFile(relativePath = path, uri = child.uri.toString())
            }
        }
        recurse(root, "")
        return out
    }
}
