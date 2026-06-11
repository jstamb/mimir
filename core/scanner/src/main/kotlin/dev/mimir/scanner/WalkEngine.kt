package dev.mimir.scanner

object WalkEngine {
    /** Depth-first walk producing every file with a '/'-joined path relative to the root. */
    fun walk(source: TreeSource, rootUri: String): List<ScannedFile> {
        val out = mutableListOf<ScannedFile>()
        fun recurse(dirUri: String, prefix: String) {
            for (node in source.children(dirUri)) {
                val path = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
                if (node.isDirectory) recurse(node.uri, path)
                else out += ScannedFile(relativePath = path, uri = node.uri, lastModified = node.lastModified)
            }
        }
        recurse(rootUri, "")
        return out
    }
}
