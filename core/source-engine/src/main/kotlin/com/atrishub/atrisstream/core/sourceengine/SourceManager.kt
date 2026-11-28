package com.atrishub.atrisstream.core.sourceengine

import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.model.MediaProvider
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.ProviderManifest
import com.atrishub.atrisstream.core.model.SourceDriverType
import com.atrishub.atrisstream.core.model.WatchlistTrackingPolicy
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import com.atrishub.atrisstream.core.sourceengine.animex.AnimeXProvider
import com.atrishub.atrisstream.core.sourceengine.driver.JsPluginProvider
import com.atrishub.atrisstream.core.sourceengine.driver.JsonManifestProvider
import com.atrishub.atrisstream.core.sourceengine.movy.MovyProvider
import com.atrishub.atrisstream.core.sourceengine.reanime.ReAnimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        val now = System.currentTimeMillis()
        val builtIns = listOf(
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
                addedAt = now
            ),
            InstalledSource(
                id = ReAnimeProvider.SOURCE_ID,
                name = "ReAnime (Anime)",
                version = "1.0.0",
                baseUrl = ReAnimeProvider.SITE_URL,
                driverType = SourceDriverType.BUILTIN,
                enabled = true,
                isBuiltIn = true,
                supportedTypes = listOf(MediaType.ANIME),
                languages = listOf("en"),
                addedAt = now
            ),
            InstalledSource(
                id = AnimeXProvider.SOURCE_ID,
                name = "AnimeX (Anime)",
                version = "1.0.0",
                baseUrl = AnimeXProvider.SITE_URL,
                driverType = SourceDriverType.BUILTIN,
                enabled = true,
                isBuiltIn = true,
                supportedTypes = listOf(MediaType.ANIME),
                languages = listOf("en"),
                addedAt = now
            )
        )
        for (builtIn in builtIns) {
            if (repository.getSourceById(builtIn.id) == null) {
                repository.upsertSource(builtIn)
            }
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
        val fallback = createBuiltInProvider(sourceId) ?: return null
        return providerCache.getOrPut(sourceId) {
            CachedProvider("builtin", fallback)
        }.provider
    }

    suspend fun refreshWatchlistNewEpisodes(trackSeries: Boolean): Int = coroutineScope {
        val items = repository.getAllWatchlistItems()
        val targets = items.filter { item ->
            item.type == MediaType.ANIME || (item.type == MediaType.TV && trackSeries)
        }
        if (targets.isEmpty()) return@coroutineScope 0

        val semaphore = Semaphore(3)
        val nowMs = System.currentTimeMillis()
        val results = targets.map { item ->
            async {
                semaphore.withPermit {
                    runCatching {
                        val provider = getProvider(item.sourceId) ?: return@withPermit false
                        val details = provider.getDetails(item.mediaId, item.type)
                        val episodes = if (details.seasons.isNotEmpty()) {
                            details.seasons.flatMap { season ->
                                season.episodes.ifEmpty {
                                    runCatching { provider.getEpisodes(item.mediaId, season.seasonNumber) }
                                        .getOrDefault(emptyList())
                                }
                            }
                        } else {
                            runCatching { provider.getEpisodes(item.mediaId, 1) }.getOrDefault(emptyList())
                        }
                        val progressList = repository.getAllProgressForMedia(item.sourceId, item.mediaId)
                        val updated = WatchlistTrackingPolicy.evaluateWatchlistUpdate(
                            item = item,
                            releasedEpisodes = episodes,
                            progressList = progressList,
                            nowMs = nowMs
                        )
                        if (updated != item) {
                            repository.upsertWatchlistItem(updated)
                        }
                        updated.hasNewEpisode && !item.hasNewEpisode
                    }.getOrDefault(false)
                }
            }
        }.awaitAll()

        results.count { it }
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
            SourceDriverType.BUILTIN -> createBuiltInProvider(source.id)
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

    private fun createBuiltInProvider(sourceId: String): MediaProvider? = when (sourceId) {
        MovyProvider.SOURCE_ID -> MovyProvider(networkClient)
        ReAnimeProvider.SOURCE_ID -> ReAnimeProvider(networkClient)
        AnimeXProvider.SOURCE_ID -> AnimeXProvider(networkClient)
        else -> null
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
