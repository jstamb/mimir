package dev.mimir.scanner

data class ScannedFile(
    /** Path relative to the library root, '/'-separated, e.g. "GameCube/Wind Waker.rvz". */
    val relativePath: String,
    /** Opaque launchable identifier (SAF content URI on Android; anything in tests). */
    val uri: String,
    val lastModified: Long = 0L,
)

data class Game(
    val title: String,
    val uri: String,
    val platformId: String,
    val relativePath: String = "",
    val lastModified: Long = 0L,
)

data class SkippedFile(val relativePath: String, val reason: String)

data class ScanResult(val games: List<Game>, val skipped: List<SkippedFile>)

class LibraryMatcher(private val platforms: List<PlatformDef>) {
    private val aliasToPlatform: Map<String, PlatformDef> =
        platforms.flatMap { p -> p.folderAliases.map { it.lowercase() to p } }.toMap()
    private val extensionToPlatforms: Map<String, List<PlatformDef>> =
        platforms.flatMap { p -> p.extensions.map { it.lowercase().trim() to p } }
            .groupBy({ it.first }, { it.second })
    private val platformIdToExtensions: Map<String, Set<String>> =
        platforms.associate { p -> p.id to p.extensions.map { it.lowercase().trim() }.toSet() }

    fun match(files: List<ScannedFile>): ScanResult {
        val games = mutableListOf<Game>()
        val skipped = mutableListOf<SkippedFile>()
        for (file in files) {
            val segments = file.relativePath.split('/')
            val name = segments.last()
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val title = name.substringBeforeLast('.')
            val folderPlatform = segments.dropLast(1)
                .lastOrNull { aliasToPlatform.containsKey(it.lowercase().trim()) }
                ?.let { aliasToPlatform.getValue(it.lowercase().trim()) }

            when {
                folderPlatform != null && ext in platformIdToExtensions.getValue(folderPlatform.id) ->
                    games += Game(title, file.uri, folderPlatform.id, file.relativePath, file.lastModified)
                folderPlatform != null ->
                    skipped += SkippedFile(
                        file.relativePath,
                        "extension .$ext is not registered for ${folderPlatform.name} " +
                            "(expects: ${folderPlatform.extensions.joinToString { ".$it" }})",
                    )
                else -> {
                    val byExt = extensionToPlatforms[ext].orEmpty()
                    when (byExt.size) {
                        1 -> games += Game(title, file.uri, byExt.single().id, file.relativePath, file.lastModified)
                        0 -> skipped += SkippedFile(
                            file.relativePath,
                            "no platform registered for extension .$ext and no platform folder in path",
                        )
                        else -> skipped += SkippedFile(
                            file.relativePath,
                            "extension .$ext is ambiguous (${byExt.joinToString { it.name }}); " +
                                "place the file in a platform-named folder",
                        )
                    }
                }
            }
        }
        return ScanResult(games, skipped)
    }
}
