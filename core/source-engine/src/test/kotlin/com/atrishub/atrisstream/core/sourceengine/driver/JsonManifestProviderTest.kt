package com.atrishub.atrisstream.core.sourceengine.driver

import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.StreamFormat
import com.atrishub.atrisstream.core.model.SubtitleFormat
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonManifestProviderTest {

    private fun fakeFetcher(responses: Map<String, String>): suspend (String, Map<String, String>) -> String =
        { url, _ -> responses[url] ?: error("Unexpected URL: $url") }

    private val sampleConfig = JsonSourceManifestConfig(
        id = "example-json-source",
        name = "Example Stream Site",
        version = "1.0.0",
        baseUrl = "https://example.com",
        supportedTypes = listOf("MOVIE", "TV"),
        languages = listOf("tr", "en"),
        endpoints = mapOf(
            "home" to "/",
            "search" to "/search?q={query}&page={page}",
            "details" to "/title/{id}"
        ),
        selectors = mapOf(
            "homeSection" to "section.media-row",
            "homeSectionTitle" to "h2.row-title",
            "cardItem" to "article.media-card",
            "cardTitle" to "h3.title",
            "cardPoster" to "img.poster@data-src|src",
            "cardUrl" to "a.card-link@href",
            "cardYear" to "span.year",
            "cardRating" to "span.rating",
            "detailsTitle" to "h1.media-title",
            "detailsDescription" to "p.synopsis",
            "detailsBackdrop" to "div.hero@data-bg",
            "streamVideo" to "video source@src",
            "subtitleTrack" to "track[kind=subtitles]@src"
        )
    )

    @Test
    fun `getHome parses sections and cards from HTML`() = runTest {
        val homeHtml = """
            <html><body>
              <section class="media-row">
                <h2 class="row-title">Trending</h2>
                <article class="media-card">
                  <a class="card-link" href="/title/inception-2010">
                    <img class="poster" data-src="/img/inception.jpg" />
                    <h3 class="title">Inception</h3>
                  </a>
                  <span class="year">2010</span>
                  <span class="rating">8.8</span>
                </article>
              </section>
            </body></html>
        """.trimIndent()

        val provider = JsonManifestProvider(
            config = sampleConfig,
            fetchText = fakeFetcher(mapOf("https://example.com/" to homeHtml))
        )

        val sections = provider.getHome()
        assertEquals(1, sections.size)
        assertEquals("Trending", sections[0].title)
        assertEquals(1, sections[0].items.size)
        val card = sections[0].items[0]
        assertEquals("inception-2010", card.id)
        assertEquals("Inception", card.title)
        assertEquals("https://example.com/img/inception.jpg", card.posterUrl)
        assertEquals(2010, card.year)
        assertEquals(8.8, card.rating!!, 0.01)
    }

    @Test
    fun `getDetails and getStreams extract metadata and media tracks`() = runTest {
        val detailsHtml = """
            <html><body>
              <div class="hero" data-bg="/img/hero.jpg"></div>
              <h1 class="media-title">Inception</h1>
              <p class="synopsis">A mind-bending thriller.</p>
              <video>
                <source src="https://cdn.example.com/stream/master.m3u8" label="1080p" />
              </video>
              <track kind="subtitles" src="/subs/tr.srt" srclang="tr" label="Turkish" />
            </body></html>
        """.trimIndent()

        val provider = JsonManifestProvider(
            config = sampleConfig,
            fetchText = fakeFetcher(mapOf("https://example.com/title/inception-2010" to detailsHtml))
        )

        val details = provider.getDetails("inception-2010", MediaType.MOVIE)
        assertEquals("Inception", details.title)
        assertEquals("A mind-bending thriller.", details.description)
        assertEquals("https://example.com/img/hero.jpg", details.backdropUrl)

        val streams = provider.getStreams("inception-2010", MediaType.MOVIE)
        assertEquals(1, streams.streams.size)
        assertEquals("https://cdn.example.com/stream/master.m3u8", streams.streams[0].url)
        assertEquals(StreamFormat.HLS, streams.streams[0].format)
        assertEquals("1080p", streams.streams[0].quality)

        assertEquals(1, streams.subtitles.size)
        assertEquals("https://example.com/subs/tr.srt", streams.subtitles[0].url)
        assertEquals("tr", streams.subtitles[0].language)
        assertEquals(SubtitleFormat.SRT, streams.subtitles[0].format)
        assertTrue(streams.streams[0].headers.isEmpty())
    }
}
