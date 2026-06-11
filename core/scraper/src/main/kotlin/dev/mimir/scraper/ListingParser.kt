package dev.mimir.scraper

import java.net.URLDecoder

object ListingParser {
    // NOTE: a raw string can't END with a quote char ("""..."""" is a syntax error), so use an escaped string here.
    private val href = Regex("<a href=\"([^\"]+\\.png)\"", RegexOption.IGNORE_CASE)

    /** Extracts decoded .png filenames from a server directory-index page. */
    fun pngFiles(indexHtml: String): List<String> =
        href.findAll(indexHtml)
            .map { URLDecoder.decode(it.groupValues[1], Charsets.UTF_8) }
            .toList()
}
