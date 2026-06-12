package dev.mimir.app

import dev.mimir.data.GameRepository
import dev.mimir.theme.ThemeConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a mimir-backup.zip: art rows, prefs, play state, custom players, theme config. */
object BackupWriter {
    @Serializable data class MediaRow(val gameUri: String, val url: String, val kind: String, val source: String)
    @Serializable data class PrefRow(val key: String, val value: String)
    @Serializable data class PlayRow(val gameUri: String, val lastPlayedAt: Long)
    @Serializable data class CustomPlayerRow(val id: String, val name: String, val packageName: String, val activityClass: String?, val action: String, val platformIds: String)

    private val json = Json { prettyPrint = true }

    suspend fun write(out: OutputStream, repo: GameRepository, theme: ThemeConfig) {
        val media = repo.mediaSnapshot().map { MediaRow(it.gameUri, it.boxartUrl, it.kind, it.source) }
        val platformPrefs = repo.platformPrefsSnapshot().map { PrefRow(it.platformId, it.playerId) }
        val gamePrefs = repo.gamePrefsSnapshot().map { PrefRow(it.gameUri, it.playerId) }
        val plays = repo.playStatesSnapshot().map { PlayRow(it.gameUri, it.lastPlayedAt) }
        val customs = repo.customPlayersSnapshot().map {
            CustomPlayerRow(it.id, it.name, it.packageName, it.activityClass, it.action, it.platformIds)
        }
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            entry("media.json", json.encodeToString(ListSerializer(MediaRow.serializer()), media))
            entry("platform_prefs.json", json.encodeToString(ListSerializer(PrefRow.serializer()), platformPrefs))
            entry("game_prefs.json", json.encodeToString(ListSerializer(PrefRow.serializer()), gamePrefs))
            entry("play_state.json", json.encodeToString(ListSerializer(PlayRow.serializer()), plays))
            entry("custom_players.json", json.encodeToString(ListSerializer(CustomPlayerRow.serializer()), customs))
            entry("theme.json", json.encodeToString(ThemeConfig.serializer(), theme))
        }
    }
}
