package dev.mimir.scraper

import kotlin.test.Test
import kotlin.test.assertEquals

class ListingParserTest {
    @Test
    fun `extracts png filenames from an index page`() {
        val html = """
            <html><body><h1>Index of /Nintendo - Nintendo 64/Named_Boxarts/</h1>
            <a href="../">../</a>
            <a href="GoldenEye%20007%20%28USA%29.png">GoldenEye 007 (USA).png</a>
            <a href="Mario%20Kart%2064%20%28USA%29.png">Mario Kart 64 (USA).png</a>
            <a href="Mario%20Kart%2064%20%28Europe%29%20%28En%2CFr%2CDe%29.png">Mario Kart 64 (Europe) (En,Fr,De).png</a>
            <a href="somefile.txt">somefile.txt</a>
            </body></html>
        """.trimIndent()
        val files = ListingParser.pngFiles(html)
        assertEquals(
            listOf(
                "GoldenEye 007 (USA).png",
                "Mario Kart 64 (USA).png",
                "Mario Kart 64 (Europe) (En,Fr,De).png",
            ),
            files,
        )
    }

    @Test
    fun `empty page yields empty list`() {
        assertEquals(emptyList(), ListingParser.pngFiles("<html><body></body></html>"))
    }
}
