package dev.mimir.data

import dev.mimir.scanner.Game

data class ChangeSet(
    val toUpsert: List<GameEntity>,
    val toDeleteUris: List<String>,
)

fun Game.toEntity() = GameEntity(
    uri = uri,
    title = title,
    platformId = platformId,
    relativePath = relativePath,
    lastModified = lastModified,
)

object DiffEngine {
    /** Computes the minimal set of writes to make the stored library match a scan result. */
    fun diff(existing: List<GameEntity>, scanned: List<Game>): ChangeSet {
        val existingByUri = existing.associateBy { it.uri }
        val scannedUris = scanned.mapTo(mutableSetOf()) { it.uri }
        val toUpsert = scanned
            .filter { game -> existingByUri[game.uri] != game.toEntity() }
            .map { it.toEntity() }
        val toDelete = existing.filter { it.uri !in scannedUris }.map { it.uri }
        return ChangeSet(toUpsert, toDelete)
    }
}
