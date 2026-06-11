package dev.mimir.scraper

import java.net.URLEncoder

object LibretroNames {
    private val forbidden = Regex("""[&*/:`<>?\\|"]""")

    /** libretro thumbnail filename rule: forbidden characters become underscores. */
    fun sanitize(title: String): String = forbidden.replace(title, "_")

    /** Percent-encodes one URL path segment (spaces as %20, not +). */
    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

    fun listingUrl(libretroName: String): String =
        "https://thumbnails.libretro.com/${encodeSegment(libretroName)}/Named_Boxarts/"

    fun imageUrl(libretroName: String, fileName: String): String =
        listingUrl(libretroName) + encodeSegment(fileName)
}
