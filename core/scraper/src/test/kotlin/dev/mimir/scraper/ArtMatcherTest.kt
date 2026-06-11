package dev.mimir.scraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtMatcherTest {
    private val listing = listOf(
        "GoldenEye 007 (USA).png",
        "Legend of Zelda, The - The Wind Waker (USA).png",
        "Mario Kart 64 (USA).png",
        "Mario Kart 64 (Europe) (En,Fr,De).png",
        "Mario Kart DS (USA) (En,Fr,De,Es,It).png",
        "Super Mario 64 (USA).png",
    )
    private val matcher = ArtMatcher(listing)

    @Test
    fun `exact normalized match wins and prefers USA region`() {
        assertEquals("Mario Kart 64 (USA).png", matcher.bestMatch("Mario Kart 64"))
    }

    @Test
    fun `startsWith match finds longer official titles`() {
        assertEquals("GoldenEye 007 (USA).png", matcher.bestMatch("GoldenEye"))
        assertEquals("Mario Kart DS (USA) (En,Fr,De,Es,It).png", matcher.bestMatch("Mario Kart DS"))
    }

    @Test
    fun `contains match finds The-suffixed and subtitled names`() {
        assertEquals("Legend of Zelda, The - The Wind Waker (USA).png", matcher.bestMatch("Wind Waker"))
    }

    @Test
    fun `no match returns null and short titles do not contains-match wildly`() {
        assertNull(matcher.bestMatch("Banjo-Kazooie"))
    }

    @Test
    fun `exact beats startsWith - Mario Kart 64 does not steal Super Mario 64`() {
        assertEquals("Super Mario 64 (USA).png", matcher.bestMatch("Super Mario 64"))
    }
}
