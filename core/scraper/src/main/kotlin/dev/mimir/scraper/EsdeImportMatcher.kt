package dev.mimir.scraper

import dev.mimir.scanner.Game
import dev.mimir.scanner.PlatformDef
import dev.mimir.scanner.ScannedFile

/**
 * Matches an ES-DE downloaded_media tree against the library.
 * Path shape: <system dir>/<mediatype>/<game filename minus extension>.<ext>.
 * System dirs resolve through the platforms' folderAliases (ES-DE short names
 * are already aliases). Covers match by exact title, then normalized title.
 */
object EsdeImportMatcher {
    data class Result(
        /** game uri -> cover image SAF uri */
        val covers: Map<String, String>,
        val videoCount: Int,
    )

    fun match(mediaFiles: List<ScannedFile>, games: List<Game>, platforms: List<PlatformDef>): Result {
        val aliasToPlatformId = platforms
            .flatMap { p -> p.folderAliases.map { it.lowercase().trim() to p.id } }
            .toMap()
        val gamesByPlatform = games.groupBy { it.platformId }

        val covers = mutableMapOf<String, String>()
        var videoCount = 0
        for (file in mediaFiles) {
            val segments = file.relativePath.split('/')
            if (segments.size < 3) continue
            val platformId = aliasToPlatformId[segments[0].lowercase().trim()] ?: continue
            val mediaType = segments[1].lowercase()
            val title = segments.last().substringBeforeLast('.')
            when (mediaType) {
                "videos" -> if (gamesByPlatform[platformId]?.any { titlesMatch(it.title, title) } == true) videoCount++
                "covers" -> {
                    val game = gamesByPlatform[platformId]?.firstOrNull { it.title == title }
                        ?: gamesByPlatform[platformId]?.firstOrNull { titlesMatch(it.title, title) }
                    if (game != null && game.uri !in covers) covers[game.uri] = file.uri
                }
            }
        }
        return Result(covers, videoCount)
    }

    private fun titlesMatch(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
}
