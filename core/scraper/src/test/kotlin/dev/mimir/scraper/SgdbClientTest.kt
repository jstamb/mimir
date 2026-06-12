package dev.mimir.scraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SgdbClientTest {
    private val client = SgdbClient(apiKey = "k-test", fetcher = { url, headers ->
        assertEquals("Bearer k-test", headers["Authorization"])
        when {
            "search/autocomplete/Mario%20Kart%2064" in url ->
                """{"success":true,"data":[{"id":4263,"name":"Mario Kart 64"},{"id":99,"name":"Mario Kart 64 (Hack)"}]}"""
            "grids/game/4263" in url ->
                """{"success":true,"data":[{"id":1,"url":"https://cdn.sgdb/grid1.png"},{"id":2,"url":"https://cdn.sgdb/grid2.png"}]}"""
            "heroes/game/4263" in url ->
                """{"success":true,"data":[{"id":3,"url":"https://cdn.sgdb/hero1.png"}]}"""
            "logos/game/4263" in url ->
                """{"success":true,"data":[]}"""
            else -> null
        }
    })

    @Test
    fun `searches then fetches first asset per kind`() {
        val art = client.artFor("Mario Kart 64")
        assertEquals("https://cdn.sgdb/grid1.png", art?.gridUrl)
        assertEquals("https://cdn.sgdb/hero1.png", art?.heroUrl)
        assertNull(art?.logoUrl) // empty data array
    }

    @Test
    fun `no search hit returns null`() {
        assertNull(client.artFor("Zzz Nonexistent"))
    }

    @Test
    fun `fetch failure returns null not crash`() {
        val broken = SgdbClient("k", fetcher = { _, _ -> null })
        assertNull(broken.artFor("Mario Kart 64"))
    }

    @Test
    fun `malformed json returns null not crash`() {
        val garbage = SgdbClient("k", fetcher = { url, _ -> if ("search" in url) "<html>nope</html>" else null })
        assertNull(garbage.artFor("Mario Kart 64"))
    }

    @Test
    fun `prefers exact normalized name over first hit, then shortest startsWith`() {
        fun clientWithHits(hitsJson: String) = SgdbClient("k", fetcher = { url, _ ->
            when {
                "search/autocomplete/" in url -> """{"success":true,"data":$hitsJson}"""
                "grids/game/7" in url -> """{"success":true,"data":[{"id":1,"url":"https://cdn/right.png"}]}"""
                else -> """{"success":true,"data":[]}"""
            }
        })
        // exact normalized match wins over earlier hits
        val exact = clientWithHits("""[{"id":5,"name":"GoldenEye: Source"},{"id":7,"name":"GoldenEye 007"}]""")
        assertEquals("https://cdn/right.png", exact.artFor("GoldenEye 007")?.gridUrl)
        // no exact: shortest normalized startsWith wins ("GoldenEye" -> 007 beats Source)
        val starts = clientWithHits("""[{"id":5,"name":"GoldenEye: Source"},{"id":7,"name":"GoldenEye 007"}]""")
        assertEquals("https://cdn/right.png", starts.artFor("GoldenEye")?.gridUrl)
        // nothing related: falls back to first hit (id 5 -> no grids -> null gridUrl but non-null result)
        val fallback = clientWithHits("""[{"id":5,"name":"Something Else"}]""")
        assertEquals(null, fallback.artFor("GoldenEye")?.gridUrl)
    }
}
