package dev.mimir.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateListOf
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
    customPlayers: List<PlayerDef>,
    launchableApps: () -> List<Pair<String, String>>,
    onAddCustom: (name: String, packageName: String, platformIds: List<String>) -> Unit,
    onDeleteCustom: (String) -> Unit,
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
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Text("Custom emulators", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Register any installed app as an emulator — no whitelist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(customPlayers, key = { it.id }) { player ->
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(player.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${player.packageName} — ${player.platformIds.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onDeleteCustom(player.id) }) { Text("Remove") }
                    }
                }
            }
            item {
                var showAdd by remember { mutableStateOf(false) }
                Button(onClick = { showAdd = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Add custom emulator")
                }
                if (showAdd) {
                    AddCustomPlayerDialog(
                        platforms = platforms,
                        launchableApps = launchableApps,
                        onAdd = { name, pkg, ids -> onAddCustom(name, pkg, ids); showAdd = false },
                        onDismiss = { showAdd = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddCustomPlayerDialog(
    platforms: List<Pair<String, String>>,
    launchableApps: () -> List<Pair<String, String>>,
    onAdd: (name: String, packageName: String, platformIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val apps = remember { launchableApps() }
    var selectedApp by remember { mutableStateOf<Pair<String, String>?>(null) }
    val selectedPlatforms = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selectedApp == null) "Pick the app" else "Pick its systems") },
        text = {
            if (selectedApp == null) {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(apps, key = { it.second }) { app ->
                        ListItem(
                            headlineContent = { Text(app.first) },
                            supportingContent = { Text(app.second, style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.clickable { selectedApp = app },
                        )
                    }
                }
            } else {
                Column {
                    Text(selectedApp!!.first, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(platforms, key = { it.first }) { (id, name) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (id in selectedPlatforms) selectedPlatforms.remove(id)
                                    else selectedPlatforms.add(id)
                                },
                            ) {
                                Checkbox(
                                    checked = id in selectedPlatforms,
                                    onCheckedChange = {
                                        if (id in selectedPlatforms) selectedPlatforms.remove(id)
                                        else selectedPlatforms.add(id)
                                    },
                                )
                                Text(name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedApp != null && selectedPlatforms.isNotEmpty(),
                onClick = { onAdd(selectedApp!!.first, selectedApp!!.second, selectedPlatforms.toList()) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
