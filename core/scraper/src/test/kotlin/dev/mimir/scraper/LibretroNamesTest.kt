package dev.mimir.scraper

import kotlin.test.Test
import kotlin.test.assertEquals

class LibretroNamesTest {
    @Test
    fun `replaces forbidden characters with underscore`() {
        assertEquals("Q_bert's Qubes", LibretroNames.sanitize("Q*bert's Qubes"))
        assertEquals("Mario _ Luigi_ Superstar Saga", LibretroNames.sanitize("Mario & Luigi: Superstar Saga"))
        assertEquals("WarioWare_ Inc.", LibretroNames.sanitize("WarioWare? Inc."))
    }

    @Test
    fun `plain names pass through`() {
        assertEquals("Mario Kart 64", LibretroNames.sanitize("Mario Kart 64"))
    }

    @Test
    fun `builds an encoded boxart listing url`() {
        assertEquals(
            "https://thumbnails.libretro.com/Nintendo%20-%20Nintendo%2064/Named_Boxarts/",
            LibretroNames.listingUrl("Nintendo - Nintendo 64"),
        )
    }
}
