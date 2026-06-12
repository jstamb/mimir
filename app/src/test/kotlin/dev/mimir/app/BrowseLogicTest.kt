package dev.mimir.app

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseLogicTest {
    @Test
    fun `alpha sections map first letters to first index, digits and symbols bucket to hash`() {
        val titles = listOf("1080 Snowboarding", "Banjo", "banjo 2", "GoldenEye", "Zelda")
        val sections = BrowseLogic.alphaSections(titles)
        assertEquals(0, sections["#"])
        assertEquals(1, sections["B"])
        assertEquals(3, sections["G"])
        assertEquals(4, sections["Z"])
        assertEquals(null, sections["A"])
    }

    @Test
    fun `search filter is case and punctuation insensitive`() {
        val titles = listOf("GoldenEye 007", "Mario Kart 64", "Banjo-Kazooie")
        assertEquals(listOf("Banjo-Kazooie"), BrowseLogic.filter(titles, "banjo kaz"))
        assertEquals(listOf("GoldenEye 007"), BrowseLogic.filter(titles, "golden eye").ifEmpty { BrowseLogic.filter(titles, "goldeneye") })
        assertEquals(titles, BrowseLogic.filter(titles, ""))
    }

    @Test
    fun `rail letters are the fixed a-z plus hash`() {
        assertEquals(27, BrowseLogic.RAIL.size)
        assertEquals("#", BrowseLogic.RAIL.first())
        assertEquals("Z", BrowseLogic.RAIL.last())
    }
}
