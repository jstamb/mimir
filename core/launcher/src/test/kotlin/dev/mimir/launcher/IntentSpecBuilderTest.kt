package dev.mimir.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentSpecBuilderTest {
    private val melonds = PlayerDef(
        id = "melonds",
        name = "melonDS",
        packageName = "me.magnum.melonds",
        activityClass = null,
        action = "android.intent.action.VIEW",
        platformIds = listOf("nds"),
    )

    @Test
    fun `substitutes ROM into data uri`() {
        val spec = buildIntentSpec(melonds, romUri = "content://tree/doc%2Fmario.nds", title = "Mario")
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("me.magnum.melonds", spec.packageName)
        assertEquals("content://tree/doc%2Fmario.nds", spec.dataUri)
        assertTrue("GRANT_READ_URI_PERMISSION" in spec.flags)
    }

    @Test
    fun `substitutes variables inside extras`() {
        val player = melonds.copy(extras = mapOf("ROM" to "%ROM%", "GAME_TITLE" to "%TITLE%"))
        val spec = buildIntentSpec(player, romUri = "content://x/y.nds", title = "Zelda")
        assertEquals("content://x/y.nds", spec.extras["ROM"])
        assertEquals("Zelda", spec.extras["GAME_TITLE"])
    }

    @Test
    fun `bundled players load and cover the fake emulator`() {
        val players = PlayerDefs.load()
        val fake = players.first { it.id == "fake-emulator" }
        assertEquals("dev.mimir.fakeemulator", fake.packageName)
        assertTrue(fake.platformIds.containsAll(listOf("nes", "n64", "nds")))
    }

    @Test
    fun `defaultPlayerFor returns first player claiming the platform`() {
        val players = PlayerDefs.load()
        val player = defaultPlayerFor(players, platformId = "nds")
        assertTrue(player != null && "nds" in player.platformIds)
    }
}
