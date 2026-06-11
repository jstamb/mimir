package dev.mimir.launcher

data class PlayerPrefs(
    val platformDefaults: Map<String, String> = emptyMap(), // platformId -> playerId
    val gameOverrides: Map<String, String> = emptyMap(),    // game uri -> playerId
)

/**
 * Resolution order: per-game override -> per-platform default -> first INSTALLED
 * claimant in registry order -> first claimant (launch will show a guided
 * not-installed failure). Explicit prefs win even when uninstalled, on purpose.
 */
class PlayerResolver(
    private val players: List<PlayerDef>,
    private val installedPackages: Set<String>,
    private val prefs: PlayerPrefs,
) {
    private val byId = players.associateBy { it.id }

    fun claimants(platformId: String): List<PlayerDef> =
        players.filter { platformId in it.platformIds }

    fun isInstalled(player: PlayerDef): Boolean = player.packageName in installedPackages

    fun resolve(gameUri: String, platformId: String): PlayerDef? =
        prefs.gameOverrides[gameUri]?.let(byId::get)
            ?: prefs.platformDefaults[platformId]?.let(byId::get)
            ?: claimants(platformId).firstOrNull { isInstalled(it) }
            ?: claimants(platformId).firstOrNull()
}
