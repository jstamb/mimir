package dev.mimir.scanner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlatformDef(
    val id: String,
    val name: String,
    val folderAliases: List<String>,
    val extensions: List<String>,
)

object PlatformDefs {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<PlatformDef> {
        val text = requireNotNull(
            PlatformDefs::class.java.getResourceAsStream("/platforms.json")
        ) { "platforms.json missing from resources" }.bufferedReader().readText()
        return json.decodeFromString<List<PlatformDef>>(text).map {
            it.copy(
                folderAliases = it.folderAliases.map { a -> a.lowercase().trim() },
                extensions = it.extensions.map { e -> e.lowercase().trim() },
            )
        }
    }
}
