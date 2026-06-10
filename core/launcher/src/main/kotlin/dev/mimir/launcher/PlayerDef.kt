package dev.mimir.launcher

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlayerDef(
    val id: String,
    val name: String,
    val packageName: String,
    val activityClass: String? = null,
    val action: String = "android.intent.action.VIEW",
    val dataTemplate: String = "%ROM%",
    val extras: Map<String, String> = emptyMap(),
    val flags: List<String> = listOf("GRANT_READ_URI_PERMISSION"),
    val platformIds: List<String> = emptyList(),
)

object PlayerDefs {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<PlayerDef> {
        val text = requireNotNull(
            PlayerDefs::class.java.getResourceAsStream("/players.json")
        ) { "players.json missing from resources" }.bufferedReader().readText()
        return json.decodeFromString(text)
    }
}

fun defaultPlayerFor(players: List<PlayerDef>, platformId: String): PlayerDef? =
    players.firstOrNull { platformId in it.platformIds }
