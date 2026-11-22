package com.atrishub.atrisstream.core.sourceengine

import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.SourceDriverType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceManagerParsingTest {

    @Test
    fun `detectSourceKind identifies repo index, json manifest, and js plugin`() {
        val repoJson = """{"name":"Repo","sources":[{"id":"a","manifestUrl":"https://example.com/a.json"}]}"""
        val manifestJson = """{"id":"site","name":"Site","baseUrl":"https://site.com","endpoints":{"home":"/"},"selectors":{"cardItem":"div"}}"""
        val jsPlugin = """var manifest = { id: "js-1", name: "JS 1", baseUrl: "https://js.com" }; function getHome() { return "[]"; }"""

        assertEquals(SourceManager.DetectedSourceKind.REPO_INDEX, SourceManager.detectSourceKind("https://example.com/repo.json", repoJson))
        assertEquals(SourceManager.DetectedSourceKind.JSON_MANIFEST, SourceManager.detectSourceKind("https://example.com/site.json", manifestJson))
        assertEquals(SourceManager.DetectedSourceKind.JS_PLUGIN, SourceManager.detectSourceKind("https://example.com/plugin.js", jsPlugin))
    }

    @Test
    fun `parseSourcesFromContent installs all sources from repo index`() = runTest {
        val repoJson = """
            {
              "name": "Community Repo",
              "version": "1.0.0",
              "sources": [
                {
                  "id": "example-json",
                  "manifestUrl": "https://example.com/example.json"
                },
                {
                  "id": "example-js",
                  "manifestUrl": "https://example.com/example.js"
                }
              ]
            }
        """.trimIndent()

        val jsonManifest = """
            {
              "id": "example-json",
              "name": "Example JSON",
              "version": "1.2.0",
              "baseUrl": "https://example.com",
              "driverType": "JSON_MANIFEST",
              "supportedTypes": ["MOVIE", "TV"],
              "languages": ["tr"],
              "endpoints": { "home": "/" },
              "selectors": { "cardItem": "article" }
            }
        """.trimIndent()

        val jsPlugin = """
            var manifest = {
              id: "example-js",
              name: "Example JS",
              version: "2.0.0",
              baseUrl: "https://js.example.com",
              supportedTypes: ["ANIME"],
              languages: ["en", "tr"]
            };
            function getHome() { return "[]"; }
        """.trimIndent()

        val children = mapOf(
            "https://example.com/example.json" to jsonManifest,
            "https://example.com/example.js" to jsPlugin
        )

        val installed = SourceManager.parseSourcesFromContent(
            url = "https://example.com/repo.json",
            content = repoJson,
            fetchChild = { url -> children[url] ?: error("Missing $url") },
            nowMs = 1000L
        )

        assertEquals(2, installed.size)

        val first = installed[0]
        assertEquals("example-json", first.id)
        assertEquals(SourceDriverType.JSON_MANIFEST, first.driverType)
        assertEquals(listOf(MediaType.MOVIE, MediaType.TV), first.supportedTypes)
        assertEquals("https://example.com/example.json", first.manifestUrl)

        val second = installed[1]
        assertEquals("example-js", second.id)
        assertEquals("Example JS", second.name)
        assertEquals("2.0.0", second.version)
        assertEquals(SourceDriverType.JS_PLUGIN, second.driverType)
        assertEquals(listOf(MediaType.ANIME), second.supportedTypes)
        assertTrue(second.languages.contains("tr"))
    }
}
