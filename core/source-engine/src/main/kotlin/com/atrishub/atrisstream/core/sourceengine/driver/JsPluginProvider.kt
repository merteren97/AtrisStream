package com.atrishub.atrisstream.core.sourceengine.driver

import app.cash.quickjs.QuickJs
import com.atrishub.atrisstream.core.model.Episode
import com.atrishub.atrisstream.core.model.HomeSection
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.model.MediaDetails
import com.atrishub.atrisstream.core.model.MediaProvider
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.PagedResult
import com.atrishub.atrisstream.core.model.ProviderManifest
import com.atrishub.atrisstream.core.model.StreamResult
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Host HTTP bridge exposed to sandboxed QuickJS plugins as `atrisHttp`.
 */
interface AtrisHttpBridge {
    fun get(url: String, headersJson: String?): String
    fun post(url: String, body: String, contentType: String?, headersJson: String?): String
}

/**
 * Sandboxed QuickJS-backed MediaProvider (`sample-js-plugin.js` schema).
 * All QuickJS evaluations execute on a dedicated single-threaded dispatcher.
 */
class JsPluginProvider(
    override val manifest: ProviderManifest,
    private val script: String,
    private val networkClient: AtrisNetworkClient
) : MediaProvider, Closeable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QuickJs-${manifest.id}").apply { isDaemon = true }
    }
    private val jsDispatcher = executor.asCoroutineDispatcher()

    private var quickJs: QuickJs? = null

    private val bridge = object : AtrisHttpBridge {
        override fun get(url: String, headersJson: String?): String = runBlocking {
            networkClient.getText(url, parseHeaders(headersJson))
        }

        override fun post(
            url: String,
            body: String,
            contentType: String?,
            headersJson: String?
        ): String = runBlocking {
            networkClient.postText(
                url = url,
                body = body,
                contentType = contentType?.takeIf { it.isNotBlank() } ?: "application/json",
                headers = parseHeaders(headersJson)
            )
        }
    }

    override suspend fun getHome(): List<HomeSection> {
        val raw = evalString("(function(){ var r = getHome(); return typeof r === 'string' ? r : JSON.stringify(r); })()")
        return json.decodeFromString(raw)
    }

    override suspend fun browse(type: MediaType, page: Int): PagedResult<MediaCard> {
        val raw = evalString(
            "(function(){ var r = browse(${quoteJs(type.name)}, $page); return typeof r === 'string' ? r : JSON.stringify(r); })()"
        )
        return json.decodeFromString(raw)
    }

    override suspend fun search(query: String, page: Int): PagedResult<MediaCard> {
        val raw = evalString(
            "(function(){ var r = search(${quoteJs(query)}, $page); return typeof r === 'string' ? r : JSON.stringify(r); })()"
        )
        return json.decodeFromString(raw)
    }

    override suspend fun getDetails(id: String, type: MediaType): MediaDetails {
        val raw = evalString(
            "(function(){ var r = getDetails(${quoteJs(id)}, ${quoteJs(type.name)}); return typeof r === 'string' ? r : JSON.stringify(r); })()"
        )
        return json.decodeFromString(raw)
    }

    override suspend fun getEpisodes(id: String, season: Int): List<Episode> {
        val raw = evalString(
            "(function(){ var r = getEpisodes(${quoteJs(id)}, $season); return typeof r === 'string' ? r : JSON.stringify(r); })()"
        )
        return json.decodeFromString(raw)
    }

    override suspend fun getStreams(
        mediaId: String,
        type: MediaType,
        season: Int?,
        episode: Int?
    ): StreamResult {
        val seasonArg = season?.toString() ?: "null"
        val episodeArg = episode?.toString() ?: "null"
        val raw = evalString(
            "(function(){ var r = getStreams(${quoteJs(mediaId)}, ${quoteJs(type.name)}, $seasonArg, $episodeArg); return typeof r === 'string' ? r : JSON.stringify(r); })()"
        )
        return json.decodeFromString(raw)
    }

    private suspend fun evalString(expression: String): String = withContext(jsDispatcher) {
        val runtime = ensureRuntime()
        val result = runtime.evaluate(expression)
        result?.toString() ?: throw IllegalStateException("QuickJS returned null for $expression")
    }

    private fun ensureRuntime(): QuickJs {
        quickJs?.let { return it }
        val instance = QuickJs.create()
        instance.set("atrisHttp", AtrisHttpBridge::class.java, bridge)
        instance.evaluate(HTTP_SHIM)
        instance.evaluate(script)
        quickJs = instance
        return instance
    }

    override fun close() {
        executor.execute {
            runCatching { quickJs?.close() }
            quickJs = null
        }
        executor.shutdown()
    }

    private fun parseHeaders(headersJson: String?): Map<String, String> {
        if (headersJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(headersJson) as? JsonObject ?: return emptyMap()
            obj.entries.mapNotNull { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { k to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun quoteJs(value: String): String = json.encodeToString(kotlinx.serialization.serializer<String>(), value)

    @Serializable
    private data class JsManifestDto(
        val id: String? = null,
        val name: String? = null,
        val version: String = "1.0.0",
        val baseUrl: String = "",
        val supportedTypes: List<String> = listOf("MOVIE", "TV"),
        val languages: List<String> = listOf("en")
    )

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        private val HTTP_SHIM = """
            function httpGet(url, headersJson) {
                return atrisHttp.get(url, headersJson || "{}");
            }
            function httpPost(url, body, headersJson) {
                return atrisHttp.post(url, body, "application/json", headersJson || "{}");
            }
        """.trimIndent()

        private val MANIFEST_REGEX = Regex(
            """(?:var|let|const)\s+manifest\s*=\s*(\{[\s\S]*?\})\s*;""",
            setOf(RegexOption.MULTILINE)
        )

        /**
         * Extracts `manifest` metadata from a JS plugin source without requiring native QuickJS runtime.
         */
        fun extractManifest(script: String, fallbackUrl: String = ""): ProviderManifest {
            val match = MANIFEST_REGEX.find(script)
            if (match != null) {
                val rawObj = match.groupValues[1]
                    .replace(Regex("""(?m)(^|[{,])\s*(\w+)\s*:"""), "$1\"$2\":")
                    .replace("'", "\"")
                val parsed = runCatching { json.decodeFromString(JsManifestDto.serializer(), rawObj) }.getOrNull()
                if (parsed != null && !parsed.id.isNullOrBlank()) {
                    return ProviderManifest(
                        id = parsed.id,
                        name = parsed.name ?: parsed.id,
                        version = parsed.version,
                        baseUrl = parsed.baseUrl.ifBlank { fallbackUrl },
                        supportedTypes = parsed.supportedTypes.mapNotNull {
                            runCatching { MediaType.valueOf(it.uppercase()) }.getOrNull()
                        }.ifEmpty { listOf(MediaType.MOVIE, MediaType.TV) },
                        languages = parsed.languages.ifEmpty { listOf("en") }
                    )
                }
            }

            val fallbackId = fallbackUrl.substringAfterLast('/')
                .substringBefore('?')
                .removeSuffix(".js")
                .ifBlank { "js_plugin_${script.hashCode().toUInt()}" }
            return ProviderManifest(
                id = fallbackId,
                name = fallbackId,
                version = "1.0.0",
                baseUrl = fallbackUrl
            )
        }
    }
}
