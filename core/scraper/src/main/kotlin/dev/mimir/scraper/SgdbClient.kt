package dev.mimir.scraper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/** SteamGridDB v2 client. Execution is injected so the logic stays pure-JVM testable. */
class SgdbClient(
    private val apiKey: String,
    private val fetcher: (url: String, headers: Map<String, String>) -> String?,
) {
    @Serializable private data class SearchHit(val id: Long, val name: String)
    @Serializable private data class SearchResponse(val success: Boolean, val data: List<SearchHit> = emptyList())
    @Serializable private data class Asset(val id: Long, val url: String)
    @Serializable private data class AssetResponse(val success: Boolean, val data: List<Asset> = emptyList())

    data class GameArt(val gridUrl: String?, val heroUrl: String?, val logoUrl: String?)

    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://www.steamgriddb.com/api/v2"
    private fun headers() = mapOf("Authorization" to "Bearer $apiKey")

    fun artFor(title: String): GameArt? {
        val term = URLEncoder.encode(title, Charsets.UTF_8).replace("+", "%20")
        val search = get<SearchResponse>("$base/search/autocomplete/$term") ?: return null
        val gameId = search.data.firstOrNull()?.id ?: return null
        return GameArt(
            gridUrl = firstAsset("$base/grids/game/$gameId?dimensions=600x900"),
            heroUrl = firstAsset("$base/heroes/game/$gameId"),
            logoUrl = firstAsset("$base/logos/game/$gameId"),
        )
    }

    private fun firstAsset(url: String): String? = get<AssetResponse>(url)?.data?.firstOrNull()?.url

    private inline fun <reified T> get(url: String): T? =
        fetcher(url, headers())?.let { body -> runCatching { json.decodeFromString<T>(body) }.getOrNull() }
}
