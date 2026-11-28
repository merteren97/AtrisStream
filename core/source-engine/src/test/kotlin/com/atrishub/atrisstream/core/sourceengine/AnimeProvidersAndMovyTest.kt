package com.atrishub.atrisstream.core.sourceengine

import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import com.atrishub.atrisstream.core.sourceengine.animex.AnimeXProvider
import com.atrishub.atrisstream.core.sourceengine.movy.MovyProvider
import com.atrishub.atrisstream.core.sourceengine.reanime.ReAnimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeProvidersAndMovyTest {

    private val client = AtrisNetworkClient()

    @Test
    fun `ReAnimeProvider parses home and playable episode flags`() {
        val provider = ReAnimeProvider(client)
        val homeJson = """
            {
              "latest_aired": [{"slug": "dandadan", "title": "Dan Da Dan", "year": 2024}],
              "trending": [{"slug": "frieren", "title": "Frieren", "score": 9.1}]
            }
        """.trimIndent()
        val sections = provider.parseHomeResponse(homeJson)
        assertEquals(2, sections.size)
        assertEquals("Latest Aired", sections[0].title)
        assertEquals("dandadan", sections[0].items[0].id)

        val watchDataJson = """
            {
              "type": "data",
              "nodes": [{
                "type": "data",
                "data": [
                  {"episodes": 1},
                  [2, 3],
                  {"id": 4, "number": 5, "title": 6, "playable": 7},
                  {"id": 8, "number": 9, "title": 10, "playable": 11},
                  "ep1", 1, "First Episode", true,
                  "ep2", 2, "Upcoming Episode", false
                ]
              }]
            }
        """.trimIndent()
        val episodes = provider.parseEpisodesFromWatchData(watchDataJson)
        assertEquals(2, episodes.size)
        assertTrue(episodes[0].isReleased)
        assertFalse(episodes[1].isReleased)
    }

    @Test
    fun `AnimeXProvider parses nextAiringEpisode release status and NDJSON watch player_url`() {
        val provider = AnimeXProvider(client)
        val detailsData = """
            {
              "type": "data",
              "nodes": [{
                "type": "data",
                "data": [
                  {"anime": 1},
                  {"slug": 2, "title": 3, "totalEpisodes": 5, "nextAiringEpisode": 4},
                  "solo-leveling",
                  "Solo Leveling",
                  {"episode": 5},
                  3
                ]
              }]
            }
        """.trimIndent()
        val details = provider.parseAnimeDetailsData("solo-leveling", detailsData)
        val eps = details.seasons.first().episodes
        assertEquals(3, eps.size)
        assertTrue(eps[0].isReleased)
        assertTrue(eps[1].isReleased)
        assertFalse(eps[2].isReleased)

        val ndjson = """
            {"type":"chunk","zen":{"data":[{"player_url":"https://flixcloud.cc/e/abc123?v=2"}]}}
        """.trimIndent()
        assertEquals("https://flixcloud.cc/e/abc123?v=2", provider.parseWatchNdjson(ndjson))
    }

    @Test
    fun `MovyProvider filters TMDB air_date against today`() {
        val movy = MovyProvider(client)
        assertTrue(movy.isTmdbAirDateReleased("2025-01-01", todayUtc = "2026-11-28"))
        assertTrue(movy.isTmdbAirDateReleased("2026-11-28", todayUtc = "2026-11-28"))
        assertFalse(movy.isTmdbAirDateReleased("2026-12-01", todayUtc = "2026-11-28"))
        assertTrue(movy.isTmdbAirDateReleased(null, todayUtc = "2026-11-28"))
    }
}
