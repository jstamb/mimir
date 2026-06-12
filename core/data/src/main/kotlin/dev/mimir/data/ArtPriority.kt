package dev.mimir.data

object ArtPriority {
    private val rank = mapOf("folder" to 4, "esde" to 3, "sgdb" to 2, "libretro" to 1)
    /** True when [incoming] may overwrite [existing] (>= so a source can refresh itself). */
    fun canReplace(existing: String, incoming: String): Boolean =
        (rank[incoming] ?: 0) >= (rank[existing] ?: 0)
}
