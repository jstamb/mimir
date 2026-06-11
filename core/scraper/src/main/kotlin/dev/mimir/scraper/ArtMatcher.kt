package dev.mimir.scraper

/**
 * Matches a scanned game title against a platform's thumbnail listing.
 * Tiers: exact normalized match, then startsWith, then contains.
 * Within a tier: non-demo releases first (Demo/Kiosk/Beta/Proto/Sample penalized), then files containing "(USA)", then shortest name.
 */
class ArtMatcher(listing: List<String>) {
    private data class Candidate(val fileName: String, val normalized: String)

    private val candidates = listing.map { Candidate(it, normalize(it.removeSuffix(".png"))) }

    fun bestMatch(title: String): String? {
        val wanted = normalize(title)
        if (wanted.length < 4) return null // too short for fuzzy tiers to be trustworthy
        val exact = candidates.filter { it.normalized == wanted }
        val starts = candidates.filter { it.normalized.startsWith(wanted) }
        val contains = candidates.filter { wanted in it.normalized }
        val tier = listOf(exact, starts, contains).firstOrNull { it.isNotEmpty() } ?: return null
        val demoTags = listOf("(demo", "(kiosk", "(beta", "(proto", "(sample")
        return tier.sortedWith(
            compareBy<Candidate> { c -> demoTags.any { it in c.fileName.lowercase() } }
                .thenByDescending { "(usa)" in it.fileName.lowercase() }
                .thenBy { it.fileName.length }
        ).first().fileName
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ") // drop (USA), [!], (En,Fr,...) groups
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
}
