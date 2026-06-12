package dev.mimir.app

/** Pure helpers behind the browse screen: alphabet rail indexing and search filtering. */
object BrowseLogic {
    val RAIL: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

    /** Maps each rail letter to the first index in [sortedTitles] starting with it (digits/symbols -> "#"). */
    fun alphaSections(sortedTitles: List<String>): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        sortedTitles.forEachIndexed { index, title ->
            val first = title.firstOrNull()?.uppercaseChar()
            val key = if (first != null && first in 'A'..'Z') first.toString() else "#"
            out.putIfAbsent(key, index)
        }
        return out
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z0-9]+"""), "")

    /** Case/punctuation-insensitive contains filter; blank query returns everything. */
    fun filter(titles: List<String>, query: String): List<String> {
        if (query.isBlank()) return titles
        val q = norm(query)
        return titles.filter { q in norm(it) }
    }
}
