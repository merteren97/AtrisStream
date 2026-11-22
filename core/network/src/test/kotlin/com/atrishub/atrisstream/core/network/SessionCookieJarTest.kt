package com.atrishub.atrisstream.core.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SessionCookieJarTest {

    @Test
    fun `saves and loads cookies per host`() {
        val jar = SessionCookieJar()
        val movyUrl = "https://www.movy.sx/tr".toHttpUrl()
        val otherUrl = "https://example.com/".toHttpUrl()

        val ddgCookie = Cookie.Builder()
            .name("__ddg1_")
            .value("token-123")
            .domain("www.movy.sx")
            .path("/")
            .build()

        jar.saveFromResponse(movyUrl, listOf(ddgCookie))

        val loadedMovy = jar.loadForRequest(movyUrl)
        assertEquals(1, loadedMovy.size)
        assertEquals("__ddg1_", loadedMovy.first().name)
        assertEquals("token-123", loadedMovy.first().value)

        val loadedOther = jar.loadForRequest(otherUrl)
        assertTrue(loadedOther.isEmpty())
    }

    @Test
    fun `updates existing cookie with same name domain and path`() {
        val jar = SessionCookieJar()
        val url = "https://www.movy.sx/tr".toHttpUrl()

        val first = Cookie.Builder()
            .name("session")
            .value("old")
            .domain("www.movy.sx")
            .path("/")
            .build()
        val second = Cookie.Builder()
            .name("session")
            .value("new")
            .domain("www.movy.sx")
            .path("/")
            .build()

        jar.saveFromResponse(url, listOf(first))
        jar.saveFromResponse(url, listOf(second))

        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("new", loaded.first().value)
    }

    @Test
    fun `expired cookies are pruned on load`() {
        val jar = SessionCookieJar()
        val url = "https://www.movy.sx/tr".toHttpUrl()

        val expired = Cookie.Builder()
            .name("expired_token")
            .value("gone")
            .domain("www.movy.sx")
            .path("/")
            .expiresAt(System.currentTimeMillis() - 10_000L)
            .build()

        jar.saveFromResponse(url, listOf(expired))

        val loaded = jar.loadForRequest(url)
        assertTrue(loaded.isEmpty())
        assertTrue(jar.getCookiesForHost("www.movy.sx").isEmpty())
    }

    @Test
    fun `concurrent saves and loads are thread safe`() {
        val jar = SessionCookieJar()
        val url = "https://www.movy.sx/tr".toHttpUrl()
        val threads = 8
        val iterations = 50
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)

        for (t in 0 until threads) {
            executor.submit {
                try {
                    for (i in 0 until iterations) {
                        val cookie = Cookie.Builder()
                            .name("c_$t")
                            .value("v_$i")
                            .domain("www.movy.sx")
                            .path("/")
                            .build()
                        jar.saveFromResponse(url, listOf(cookie))
                        jar.loadForRequest(url)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val finalCookies = jar.loadForRequest(url)
        assertEquals(threads, finalCookies.size)
    }
}
