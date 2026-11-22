package com.atrishub.atrisstream.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AtrisNetworkClientTest {

    @Test
    fun `clientWithHeaders merges headers without overriding existing custom headers`() {
        val networkClient = AtrisNetworkClient()
        var capturedRequest: Request? = null

        val mergedClient = networkClient.clientWithHeaders(
            mapOf(
                "Referer" to "https://www.movy.sx/",
                "Origin" to "https://www.movy.sx",
                "X-Default" to "from-client"
            )
        ).newBuilder()
            .addInterceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val request = Request.Builder()
            .url("https://cdn.example.com/playlist.m3u8")
            .header("Referer", "https://custom-override.example/")
            .build()

        mergedClient.newCall(request).execute().close()

        val actual = requireNotNull(capturedRequest)
        assertEquals("https://custom-override.example/", actual.header("Referer"))
        assertEquals("https://www.movy.sx", actual.header("Origin"))
        assertEquals("from-client", actual.header("X-Default"))
        assertSame(networkClient.cookieJar, mergedClient.cookieJar)
    }

    @Test
    fun `baseClient configures timeouts redirects and shared cookie jar`() {
        val cookieJar = SessionCookieJar()
        val networkClient = AtrisNetworkClient(cookieJar = cookieJar)

        assertSame(cookieJar, networkClient.baseClient.cookieJar)
        assertEquals(20_000, networkClient.baseClient.connectTimeoutMillis)
        assertEquals(20_000, networkClient.baseClient.readTimeoutMillis)
        assertEquals(true, networkClient.baseClient.followRedirects)
    }
}
