package com.atrishub.atrisstream.core.sourceengine.movy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovyProviderParsingTest {

    @Test
    fun `normalizeAnimeSlug strips leading slashes, locale, anime prefix, and trailing segments`() {
        assertEquals("one-piece-odmau", normalizeAnimeSlug("/anime/one-piece-odmau"))
        assertEquals("solo-leveling-3eukp", normalizeAnimeSlug("/tr/anime/solo-leveling-3eukp"))
        assertEquals("dandadan-lzcmw", normalizeAnimeSlug("dandadan-lzcmw/ep-1"))
        assertEquals("anime-gataris", normalizeAnimeSlug("anime-gataris/ep-1"))
        assertNull(normalizeAnimeSlug(""))
        assertNull(normalizeAnimeSlug(null))
    }

    @Test
    fun `extractNextDataJson extracts payload from valid Next_js HTML`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><script src="/app.js"></script></head>
            <body>
                <div id="__next"></div>
                <script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"ok":true}}}</script>
            </body>
            </html>
        """.trimIndent()

        assertEquals("""{"props":{"pageProps":{"ok":true}}}""", extractNextDataJson(html))
    }

    @Test
    fun `extractNextDataJson supports single-quoted id and attributes before or after id`() {
        val singleQuoted = """<html><body><script id='__NEXT_DATA__'>{"a":1}</script></body></html>"""
        assertEquals("""{"a":1}""", extractNextDataJson(singleQuoted))

        val reorderedAttrs = """<html><body><script type="application/json" id="__NEXT_DATA__" crossorigin="anonymous">{"b":2}</script></body></html>"""
        assertEquals("""{"b":2}""", extractNextDataJson(reorderedAttrs))
    }

    @Test
    fun `extractNextDataJson returns null when script tag is missing`() {
        val html = """<html><body><script id="other">{"x":1}</script></body></html>"""
        assertNull(extractNextDataJson(html))
    }
}
