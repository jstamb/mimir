package dev.mimir.app

import dev.mimir.launcher.PlayerDefs
import dev.mimir.scanner.PlatformDefs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Guards the join between the two community-editable registries. */
class RegistryConsistencyTest {
    @Test
    fun `every player platformId exists in the platform registry`() {
        val platformIds = PlatformDefs.load().map { it.id }.toSet()
        val orphans = PlayerDefs.load()
            .flatMap { player -> player.platformIds.map { player.id to it } }
            .filter { (_, platformId) -> platformId !in platformIds }
        assertTrue(orphans.isEmpty(), "players.json references unknown platform ids: $orphans")
    }
}
