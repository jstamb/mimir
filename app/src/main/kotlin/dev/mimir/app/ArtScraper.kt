package dev.mimir.app

import dev.mimir.data.GameRepository
import dev.mimir.data.MediaEntity
import dev.mimir.scanner.PlatformDef
import dev.mimir.scraper.ArtMatcher
import dev.mimir.scraper.LibretroNames
import dev.mimir.scraper.ListingParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves boxart URLs for games that have none: one listing fetch per platform,
 * matched locally. Image bytes are never downloaded here — Coil does that lazily.
 */
class ArtScraper(
    private val repo: GameRepository,
    platforms: List<PlatformDef>,
    private val client: OkHttpClient = OkHttpClient(),
) {
    data class Progress(val done: Int, val total: Int)

    private val platformsById = platforms.associateBy { it.id }
    private val listingCache = mutableMapOf<String, List<String>>()

    /** Returns the number of games that got art. Throws nothing: per-platform failures skip quietly. */
    suspend fun scrapeMissing(onProgress: (Progress) -> Unit): Int = withContext(Dispatchers.IO) {
        val missing = repo.gamesWithoutArt()
        if (missing.isEmpty()) return@withContext 0
        var done = 0
        var resolved = 0
        onProgress(Progress(0, missing.size))
        for ((platformId, games) in missing.groupBy { it.platformId }) {
            val libretroName = platformsById[platformId]?.libretroName.orEmpty()
            val listing = if (libretroName.isBlank()) emptyList() else fetchListing(libretroName)
            if (listing.isNotEmpty()) {
                val matcher = ArtMatcher(listing)
                val found = games.mapNotNull { game ->
                    matcher.bestMatch(game.title)?.let { file ->
                        MediaEntity(game.uri, LibretroNames.imageUrl(libretroName, file), source = "libretro")
                    }
                }
                repo.saveArt(found)
                resolved += found.size
            }
            done += games.size
            onProgress(Progress(done, missing.size))
        }
        resolved
    }

    private fun fetchListing(libretroName: String): List<String> {
        listingCache[libretroName]?.let { return it }
        val listing = runCatching {
            client.newCall(Request.Builder().url(LibretroNames.listingUrl(libretroName)).build())
                .execute().use { response ->
                    if (!response.isSuccessful) emptyList()
                    else ListingParser.pngFiles(response.body?.string().orEmpty())
                }
        }.getOrDefault(emptyList())
        if (listing.isNotEmpty()) listingCache[libretroName] = listing
        return listing
    }
}
