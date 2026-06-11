package dev.mimir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.launcher.PlayerDef
import dev.mimir.launcher.PlayerPrefs

@Composable
fun EmulatorSettingsScreen(
    platforms: List<Pair<String, String>>, // id to display name
    claimants: Map<String, List<PlayerDef>>,
    prefs: PlayerPrefs,
    isInstalled: (PlayerDef) -> Boolean,
    onSetDefault: (platformId: String, playerId: String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Emulators", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(
            "Default emulator per system. Per-game overrides: long-press any game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(platforms, key = { it.first }) { (platformId, platformName) ->
                PlatformDefaultRow(
                    platformName = platformName,
                    claimants = claimants[platformId].orEmpty(),
                    selectedId = prefs.platformDefaults[platformId],
                    isInstalled = isInstalled,
                    onSelect = { onSetDefault(platformId, it) },
                )
            }
        }
    }
}

@Composable
private fun PlatformDefaultRow(
    platformName: String,
    claimants: List<PlayerDef>,
    selectedId: String?,
    isInstalled: (PlayerDef) -> Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val effective = claimants.firstOrNull { it.id == selectedId }
        ?: claimants.firstOrNull { isInstalled(it) }
        ?: claimants.firstOrNull()
    Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(platformName, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        effective == null -> "No emulators registered"
                        selectedId != null -> "Default: ${effective.name}"
                        else -> "Auto: ${effective.name}"
                    } + if (effective != null && !isInstalled(effective)) " (not installed)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (effective != null && !isInstalled(effective))
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { expanded = true }, enabled = claimants.isNotEmpty()) { Text("Change") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    claimants.forEach { player ->
                        DropdownMenuItem(
                            text = {
                                Text(player.name + if (!isInstalled(player)) "  (not installed)" else "")
                            },
                            onClick = { expanded = false; onSelect(player.id) },
                        )
                    }
                }
            }
        }
    }
}
