package com.atrishub.atrisstream.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP client and suspend helpers for AtrisStream source providers and Media3 playback.
 */
open class AtrisNetworkClient(
    val cookieJar: SessionCookieJar = SessionCookieJar(),
    enableLogging: Boolean = false
) {

    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val updated = if (request.header("User-Agent") == null) {
                    request.newBuilder()
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .build()
                } else {
                    request
                }
                chain.proceed(updated)
            }
        )
        .apply {
            if (enableLogging) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    }
                )
            }
        }
        .build()

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers).get().build()
        baseClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for GET $url")
            }
            response.body?.string().orEmpty()
        }
    }

    suspend fun postText(
        url: String,
        body: String,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val requestBody = body.toRequestBody(contentType.toMediaType())
        val request = buildRequest(url, headers).post(requestBody).build()
        baseClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for POST $url")
            }
            response.body?.string().orEmpty()
        }
    }

    suspend fun getDocument(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): Document {
        val html = getText(url, headers)
        return Jsoup.parse(html, url)
    }

    suspend fun headStatus(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): Int = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers).head().build()
        baseClient.newCall(request).execute().use { response ->
            response.code
        }
    }

    /**
     * Returns an [OkHttpClient] sharing [cookieJar] and base configuration,
     * merging [headers] onto each request without overriding already-set custom headers.
     */
    fun clientWithHeaders(headers: Map<String, String>): OkHttpClient {
        if (headers.isEmpty()) {
            return baseClient
        }
        return baseClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                for ((key, value) in headers) {
                    if (original.header(key) == null) {
                        builder.header(key, value)
                    }
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>
    ): Request.Builder {
        val builder = Request.Builder().url(url)
        for ((key, value) in headers) {
            builder.header(key, value)
        }
        return builder
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 20L
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
