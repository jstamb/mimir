package dev.mimir.scanner

/** Abstraction over a walkable document tree; Android implements this with DocumentsContract. */
interface TreeSource {
    data class Node(
        val name: String,
        /** Opaque launchable/queryable identifier (SAF document URI on Android). */
        val uri: String,
        val isDirectory: Boolean,
        val lastModified: Long,
    )

    /** Lists the direct children of a directory. Throws [TreeAccessException] when the tree can't be read. */
    fun children(dirUri: String): List<Node>
}

class TreeAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
