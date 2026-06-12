package dev.mimir.scraper

import dev.mimir.scanner.Game
import dev.mimir.scanner.ScannedFile

/** Picks up user-supplied art living next to the ROMs. Sibling images beat art-subdir images. */
object FolderArtMatcher {
    private val imageExts = setOf("png", "jpg", "jpeg", "webp")
    private val artDirs = setOf("covers", "images", "boxart", "media")

    fun match(files: List<ScannedFile>, games: List<Game>): Map<String, String> {
        data class Candidate(val dir: String, val stem: String, val uri: String, val sibling: Boolean)

        val candidates = files.mapNotNull { file ->
            val segments = file.relativePath.split('/')
            val name = segments.last()
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in imageExts) return@mapNotNull null
            val stem = name.substringBeforeLast('.')
            val parent = segments.dropLast(1)
            when {
                parent.isNotEmpty() && parent.last().lowercase() in artDirs ->
                    Candidate(parent.dropLast(1).joinToString("/"), stem, file.uri, sibling = false)
                else -> Candidate(parent.joinToString("/"), stem, file.uri, sibling = true)
            }
        }

        val out = mutableMapOf<String, String>()
        for (game in games) {
            val gameDir = game.relativePath.split('/').dropLast(1).joinToString("/")
            val inDir = candidates.filter { it.dir == gameDir }
            val match = inDir.filter { it.stem == game.title }.maxByOrNull { it.sibling }
                ?: inDir.filter { normalize(it.stem) == normalize(game.title) }.maxByOrNull { it.sibling }
            if (match != null) out[game.uri] = match.uri
        }
        return out
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
}
