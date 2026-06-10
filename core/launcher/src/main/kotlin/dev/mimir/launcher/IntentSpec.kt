package dev.mimir.launcher

data class IntentSpec(
    val action: String,
    val packageName: String,
    val activityClass: String?,
    val dataUri: String,
    val extras: Map<String, String>,
    val flags: List<String>,
)

fun buildIntentSpec(player: PlayerDef, romUri: String, title: String): IntentSpec {
    fun substitute(template: String): String =
        template.replace("%ROM%", romUri).replace("%TITLE%", title)
    return IntentSpec(
        action = player.action,
        packageName = player.packageName,
        activityClass = player.activityClass,
        dataUri = substitute(player.dataTemplate),
        extras = player.extras.mapValues { (_, v) -> substitute(v) },
        flags = player.flags,
    )
}
