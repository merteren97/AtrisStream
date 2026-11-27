package com.atrishub.atrisstream.core.sourceengine

import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.model.MediaProvider
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.ProviderManifest
import com.atrishub.atrisstream.core.model.SourceDriverType
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import com.atrishub.atrisstream.core.sourceengine.driver.JsPluginProvider
import com.atrishub.atrisstream.core.sourceengine.driver.JsonManifestProvider
import com.atrishub.atrisstream.core.sourceengine.movy.MovyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

data class SourceHealthResult(
    val ok: Boolean,
    val latencyMs: Long,
    val itemCount: Int,
    val error: String? = null
)

class SourceManager(
    private val networkClient: AtrisNetworkClient,
    private val repository: AtrisRepository
) {
    private data class CachedProvider(
        val configKey: String,
        val provider: MediaProvider
    )

    private val providerCache = ConcurrentHashMap<String, CachedProvider>()

    suspend fun initialize() {
        val existing = repository.getSourceById(MovyProvider.SOURCE_ID)
        if (existing == null) {
            repository.upsertSource(
                InstalledSource(
                    id = MovyProvider.SOURCE_ID,
                    name = "Movy.sx (TR)",
                    version = "1.0.0",
                    baseUrl = MovyProvider.SITE_URL,
                    driverType = SourceDriverType.BUILTIN,
                    enabled = true,
                    isBuiltIn = true,
                    supportedTypes = listOf(MediaType.MOVIE, MediaType.TV, MediaType.ANIME),
                    languages = listOf("tr", "en"),
                    addedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun observeSources(): Flow<List<InstalledSource>> = repository.observeAllSources()

    fun observeEnabledProviders(): Flow<List<MediaProvider>> =
        repository.observeEnabledSources().map { sources ->
            sources.mapNotNull { buildProvider(it) }
        }

    suspend fun getProvider(sourceId: String): MediaProvider? {
        val source = repository.getSourceById(sourceId)
        if (source != null) {
            return buildProvider(source)
        }
        if (sourceId == MovyProvider.SOURCE_ID) {
            return providerCache.getOrPut(sourceId) {
                CachedProvider("builtin", MovyProvider(networkClient))
            }.provider
        }
        return null
    }

    suspend fun addSourceFromUrl(url: String): Result<List<InstalledSource>> = runCatching {
        val trimmedUrl = url.trim()
        require(trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
            "Invalid source URL: $url"
        }
        val content = networkClient.getText(trimmedUrl)
        val parsed = parseSourcesFromContent(
            url = trimmedUrl,
            content = content,
            fetchChild = { childUrl -> networkClient.getText(childUrl) }
        )
        require(parsed.isNotEmpty()) { "No valid sources found at $trimmedUrl" }
        for (source in parsed) {
            repository.upsertSource(source)
        }
        parsed
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        repository.setSourceEnabled(id, enabled)
        if (!enabled) {
            providerCache.remove(id)?.let { cached ->
                runCatching { (cached.provider as? Closeable)?.close() }
            }
        }
    }

    fun trimMemory() {
        val iterator = providerCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val provider = entry.value.provider
            if (provider is MovyProvider) {
                provider.trimMemory()
            } else if (provider is Closeable) {
                runCatching { provider.close() }
                iterator.remove()
            }
        }
    }

    fun close() {
        val iterator = providerCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            runCatching { (entry.value.provider as? Closeable)?.close() }
            iterator.remove()
        }
    }

    suspend fun removeSource(id: String): Boolean {
        val existing = repository.getSourceById(id) ?: return false
        if (existing.isBuiltIn) return false
        repository.deleteSource(id)
        providerCache.remove(id)?.let { cached ->
            (cached.provider as? Closeable)?.close()
        }
        return true
    }

    suspend fun testSource(id: String): SourceHealthResult {
        val provider = getProvider(id)
            ?: return SourceHealthResult(ok = false, latencyMs = 0L, itemCount = 0, error = "Source not found: $id")
        val startMs = System.currentTimeMillis()
        return try {
            val sections = provider.getHome()
            val elapsed = System.currentTimeMillis() - startMs
            val count = sections.sumOf { it.items.size }
            SourceHealthResult(ok = true, latencyMs = elapsed, itemCount = count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val elapsed = System.currentTimeMillis() - startMs
            SourceHealthResult(
                ok = false,
                latencyMs = elapsed,
                itemCount = 0,
                error = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }

    private fun buildProvider(source: InstalledSource): MediaProvider? {
        val key = "${source.driverType}:${source.version}:${source.rawConfig.orEmpty()}"
        providerCache[source.id]?.let { cached ->
            if (cached.configKey == key) return cached.provider
            (cached.provider as? Closeable)?.close()
        }

        val created: MediaProvider? = when (source.driverType) {
            SourceDriverType.BUILTIN -> {
                if (source.id == MovyProvider.SOURCE_ID) MovyProvider(networkClient) else null
            }
            SourceDriverType.JSON_MANIFEST -> {
                val raw = source.rawConfig ?: return null
                runCatching {
                    JsonManifestProvider(JsonManifestProvider.parseConfig(raw), networkClient)
                }.getOrNull()
            }
            SourceDriverType.JS_PLUGIN -> {
                val raw = source.rawConfig ?: return null
                val manifest = ProviderManifest(
                    id = source.id,
                    name = source.name,
                    version = source.version,
                    baseUrl = source.baseUrl,
                    supportedTypes = source.supportedTypes,
                    languages = source.languages
                )
                JsPluginProvider(manifest = manifest, script = raw, networkClient = networkClient)
            }
        }

        if (created != null) {
            providerCache[source.id] = CachedProvider(key, created)
        }
        return created
    }

    @Serializable
    internal data class RepoIndexDto(
        val name: String? = null,
        val version: String? = null,
        val sources: List<RepoEntryDto> = emptyList()
    )

    @Serializable
    internal data class RepoEntryDto(
        val id: String? = null,
        val name: String? = null,
        val version: String? = null,
        val driverType: String? = null,
        val manifestUrl: String? = null,
        val scriptUrl: String? = null,
        val url: String? = null,
        val supportedTypes: List<String>? = null,
        val languages: List<String>? = null
    )

    internal enum class DetectedSourceKind {
        REPO_INDEX,
        JSON_MANIFEST,
        JS_PLUGIN
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun detectSourceKind(url: String, content: String): DetectedSourceKind {
            val trimmed = content.trim()
            if (trimmed.startsWith("{")) {
                val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                if (obj != null) {
                    if (obj.containsKey("sources")) {
                        return DetectedSourceKind.REPO_INDEX
                    }
                    val driverType = obj["driverType"]?.jsonPrimitive?.contentOrNull?.uppercase()
                    if (driverType == "JS_PLUGIN" || obj.containsKey("scriptUrl")) {
                        return DetectedSourceKind.JS_PLUGIN
                    }
                    if (driverType == "JSON_MANIFEST" || obj.containsKey("selectors") || obj.containsKey("endpoints")) {
                        return DetectedSourceKind.JSON_MANIFEST
                    }
                }
            }
            if (url.substringBefore('?').endsWith(".js", ignoreCase = true) ||
                trimmed.contains("function getHome") ||
                trimmed.contains("var manifest")
            ) {
                return DetectedSourceKind.JS_PLUGIN
            }
            return DetectedSourceKind.JSON_MANIFEST
        }

        internal suspend fun parseSourcesFromContent(
            url: String,
            content: String,
            fetchChild: suspend (String) -> String,
            nowMs: Long = System.currentTimeMillis()
        ): List<InstalledSource> {
            return when (detectSourceKind(url, content)) {
                DetectedSourceKind.REPO_INDEX -> {
                    val repo = json.decodeFromString(RepoIndexDto.serializer(), content)
                    val installed = mutableListOf<InstalledSource>()
                    for (entry in repo.sources) {
                        val childUrl = entry.manifestUrl ?: entry.scriptUrl ?: entry.url ?: continue
                        val childContent = fetchChild(childUrl)
                        installed += parseSourcesFromContent(childUrl, childContent, fetchChild, nowMs)
                    }
                    installed
                }
                DetectedSourceKind.JSON_MANIFEST -> {
                    val config = JsonManifestProvider.parseConfig(content)
                    listOf(
                        InstalledSource(
                            id = config.id,
                            name = config.name,
                            version = config.version,
                            baseUrl = config.baseUrl,
                            manifestUrl = url,
                            driverType = SourceDriverType.JSON_MANIFEST,
                            enabled = true,
                            isBuiltIn = false,
                            supportedTypes = config.supportedTypes.mapNotNull {
                                runCatching { MediaType.valueOf(it.uppercase()) }.getOrNull()
                            }.ifEmpty { listOf(MediaType.MOVIE, MediaType.TV) },
                            languages = config.languages.ifEmpty { listOf("en") },
                            rawConfig = content,
                            addedAt = nowMs
                        )
                    )
                }
                DetectedSourceKind.JS_PLUGIN -> {
                    val trimmed = content.trim()
                    val (scriptUrl, scriptContent) = if (trimmed.startsWith("{")) {
                        val obj = json.parseToJsonElement(trimmed) as JsonObject
                        val sUrl = obj["scriptUrl"]?.jsonPrimitive?.contentOrNull
                            ?: obj["manifestUrl"]?.jsonPrimitive?.contentOrNull
                            ?: throw IllegalArgumentException("JS plugin manifest missing scriptUrl")
                        sUrl to fetchChild(sUrl)
                    } else {
                        url to content
                    }
                    val manifest = JsPluginProvider.extractManifest(scriptContent, fallbackUrl = scriptUrl)
                    listOf(
                        InstalledSource(
                            id = manifest.id,
                            name = manifest.name,
                            version = manifest.version,
                            baseUrl = manifest.baseUrl,
                            manifestUrl = scriptUrl,
                            driverType = SourceDriverType.JS_PLUGIN,
                            enabled = true,
                            isBuiltIn = false,
                            supportedTypes = manifest.supportedTypes,
                            languages = manifest.languages,
                            rawConfig = scriptContent,
                            addedAt = nowMs
                        )
                    )
                }
            }
        }
    }
}
