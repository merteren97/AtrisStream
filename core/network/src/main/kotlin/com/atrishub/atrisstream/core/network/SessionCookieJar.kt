package com.atrishub.atrisstream.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.Locale

/**
 * Thread-safe in-memory [CookieJar] storing cookies per host.
 *
 * Designed for multi-step streaming flows (such as DDoS-Guard `__ddg*` cookies
 * and session tokens) shared across [AtrisNetworkClient] and Media3's OkHttpDataSource.
 */
class SessionCookieJar : CookieJar {

    private val lock = Any()
    private val cookieStore = HashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val hostKey = url.host.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val hostCookies = cookieStore.getOrPut(hostKey) { mutableListOf() }
            hostCookies.removeAll { it.expiresAt < now }

            for (incoming in cookies) {
                hostCookies.removeAll { existing ->
                    existing.name == incoming.name &&
                        existing.domain == incoming.domain &&
                        existing.path == incoming.path
                }
                if (incoming.expiresAt >= now) {
                    hostCookies.add(incoming)
                }
            }

            if (hostCookies.isEmpty()) {
                cookieStore.remove(hostKey)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val matched = mutableListOf<Cookie>()

        synchronized(lock) {
            val iterator = cookieStore.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val hostCookies = entry.value
                hostCookies.removeAll { it.expiresAt < now }
                if (hostCookies.isEmpty()) {
                    iterator.remove()
                    continue
                }
                for (cookie in hostCookies) {
                    if (cookie.matches(url)) {
                        matched.add(cookie)
                    }
                }
            }
        }

        return matched
    }

    fun getCookiesForHost(host: String): List<Cookie> {
        val hostKey = host.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val hostCookies = cookieStore[hostKey] ?: return emptyList()
            hostCookies.removeAll { it.expiresAt < now }
            if (hostCookies.isEmpty()) {
                cookieStore.remove(hostKey)
                return emptyList()
            }
            return hostCookies.toList()
        }
    }

    fun clearHost(host: String) {
        val hostKey = host.lowercase(Locale.ROOT)
        synchronized(lock) {
            cookieStore.remove(hostKey)
        }
    }

    fun clear() {
        synchronized(lock) {
            cookieStore.clear()
        }
    }
}
