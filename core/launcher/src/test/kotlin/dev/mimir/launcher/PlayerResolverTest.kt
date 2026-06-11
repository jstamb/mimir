package dev.mimir.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerResolverTest {
    private val real = PlayerDef("melonds", "melonDS", "me.magnum.melonds", platformIds = listOf("nds"))
    private val other = PlayerDef("drastic", "DraStic", "com.dsemu.drastic", platformIds = listOf("nds"))
    private val fake = PlayerDef(
        "fake-emulator", "Fake", "dev.mimir.fakeemulator",
        activityClass = "dev.mimir.fakeemulator.CatchActivity",
        platformIds = listOf("nds", "n64"),
    )
    private val players = listOf(real, other, fake) // fake LAST, like the registry

    private fun resolver(
        installed: Set<String> = emptySet(),
        prefs: PlayerPrefs = PlayerPrefs(),
    ) = PlayerResolver(players, installed, prefs)

    @Test
    fun `per-game override wins over everything`() {
        val r = resolver(
            installed = setOf("me.magnum.melonds"),
            prefs = PlayerPrefs(
                platformDefaults = mapOf("nds" to "melonds"),
                gameOverrides = mapOf("uri-x" to "drastic"),
            ),
        )
        assertEquals("drastic", r.resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `platform default beats installed-order`() {
        val r = resolver(
            installed = setOf("me.magnum.melonds", "com.dsemu.drastic"),
            prefs = PlayerPrefs(platformDefaults = mapOf("nds" to "drastic")),
        )
        assertEquals("drastic", r.resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `first installed claimant wins with no prefs - fake does not shadow real`() {
        val r = resolver(installed = setOf("com.dsemu.drastic", "dev.mimir.fakeemulator"))
        assertEquals("drastic", r.resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `fake wins only when it is the only installed claimant`() {
        val r = resolver(installed = setOf("dev.mimir.fakeemulator"))
        assertEquals("fake-emulator", r.resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `nothing installed falls back to first claimant for guided failure`() {
        assertEquals("melonds", resolver().resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `unknown platform resolves to null`() {
        assertNull(resolver().resolve("uri-x", "gba"))
    }

    @Test
    fun `claimants lists registry order and isInstalled reflects the set`() {
        val r = resolver(installed = setOf("com.dsemu.drastic"))
        assertEquals(listOf("melonds", "drastic", "fake-emulator"), r.claimants("nds").map { it.id })
        assertTrue(r.isInstalled(other))
        assertTrue(!r.isInstalled(real))
    }
}
