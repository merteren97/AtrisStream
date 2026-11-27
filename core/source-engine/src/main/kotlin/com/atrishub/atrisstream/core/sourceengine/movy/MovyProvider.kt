package com.atrishub.atrisstream.core.sourceengine.movy

import com.atrishub.atrisstream.core.model.Episode
import com.atrishub.atrisstream.core.model.HomeSection
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.model.MediaDetails
import com.atrishub.atrisstream.core.model.MediaProvider
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.PagedResult
import com.atrishub.atrisstream.core.model.ProviderManifest
import com.atrishub.atrisstream.core.model.Season
import com.atrishub.atrisstream.core.model.StreamFormat
import com.atrishub.atrisstream.core.model.StreamResult
import com.atrishub.atrisstream.core.model.StreamSource
import com.atrishub.atrisstream.core.model.SubtitleFormat
import com.atrishub.atrisstream.core.model.SubtitleTrack
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Built-in MediaProvider for `https://www.movy.sx/tr` (Movies, TV Series, and Anime).
 */
class MovyProvider(
    private val networkClient: AtrisNetworkClient
) : MediaProvider {

    override val manifest: ProviderManifest = ProviderManifest(
        id = SOURCE_ID,
        name = "Movy.sx (TR)",
        version = "1.0.0",
        baseUrl = SITE_URL,
        supportedTypes = listOf(MediaType.MOVIE, MediaType.TV, MediaType.ANIME),
        languages = listOf("tr", "en")
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private data class CachedSeed(val seed: String, val expiresAtMs: Long)

    private val seedCache = ConcurrentHashMap<String, CachedSeed>()
    private val seedMutex = Mutex()

    override suspend fun getHome(): List<HomeSection> {
        val html = networkClient.getText(SITE_URL, SITE_HEADERS)
        val nextData = extractNextData(html) ?: return emptyList()
        val pageProps = nextData["props"]?.jsonObject?.get("pageProps")?.jsonObject ?: return emptyList()

        val sections = mutableListOf<HomeSection>()

        val sectionKeys = listOf("defaultSections", "trendingSections", "streamingSections")
        for (key in sectionKeys) {
            val arr = pageProps[key] as? JsonArray ?: continue
            for (el in arr) {
                val obj = el as? JsonObject ?: continue
                val title = obj.str("name") ?: continue
                val items = (obj["movies"] as? JsonArray)
                    ?.mapNotNull { parseHomeMediaItem(it as? JsonObject) }
                    .orEmpty()
                if (items.isNotEmpty()) {
                    sections.add(HomeSection(title = title, items = items))
                }
            }
        }

        (pageProps["recent4k"] as? JsonArray)
            ?.mapNotNull { parseHomeMediaItem(it as? JsonObject) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { sections.add(HomeSection(title = "4K Filmler", items = it)) }

        (pageProps["upcomingTv"] as? JsonArray)
            ?.mapNotNull { parseHomeMediaItem(it as? JsonObject) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { sections.add(HomeSection(title = "Yaklaşan Diziler", items = it)) }

        return sections
    }

    override suspend fun browse(type: MediaType, page: Int): PagedResult<MediaCard> {
        return when (type) {
            MediaType.ANIME -> browseAnime(page)
            MediaType.TV, MediaType.MOVIE, MediaType.LIVE -> browseTmdb(type, page)
        }
    }

    private suspend fun browseTmdb(type: MediaType, page: Int): PagedResult<MediaCard> {
        val mediaSeg = if (type == MediaType.TV) "tv" else "movie"
        val url = "$TMDB_PROXY/discover/$mediaSeg?page=$page&language=tr-TR" +
            "&include_adult=false&include_video=false&sort_by=popularity.desc"
        val body = networkClient.getText(url, SITE_HEADERS)
        val root = json.parseToJsonElement(body).jsonObject
        val totalPages = root.int("total_pages") ?: 1
        val items = (root["results"] as? JsonArray)
            ?.mapNotNull { parseTmdbCard(it as? JsonObject, type) }
            .orEmpty()
        return PagedResult(items = items, page = page, hasNextPage = page < totalPages)
    }

    private suspend fun browseAnime(page: Int): PagedResult<MediaCard> {
        if (page == 1) {
            runCatching {
                val html = networkClient.getText("$SITE_URL/browse/anime", SITE_HEADERS)
                val nextData = extractNextData(html)
                val grid = nextData?.get("props")?.jsonObject
                    ?.get("pageProps")?.jsonObject
                    ?.get("initialGrid") as? JsonArray
                val items = grid?.mapNotNull { parseAnimeCard(it as? JsonObject) }.orEmpty()
                if (items.isNotEmpty()) {
                    return PagedResult(items = items, page = 1, hasNextPage = true)
                }
            }
        }

        val url = "$ANIME_API/genre/action?page=$page"
        val body = networkClient.getText(url, SITE_HEADERS)
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonObject ?: return PagedResult(emptyList(), page, false)
        val totalPages = results.int("totalPages") ?: 1
        val items = (results["data"] as? JsonArray)
            ?.mapNotNull { parseAnimeCard(it as? JsonObject) }
            .orEmpty()
        return PagedResult(items = items, page = page, hasNextPage = page < totalPages)
    }

    override suspend fun search(query: String, page: Int): PagedResult<MediaCard> = coroutineScope {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        if (encoded.isBlank()) {
            return@coroutineScope PagedResult(emptyList(), page, false)
        }

        val tmdbDeferred = async {
            runCatching {
                val url = "$TMDB_PROXY/search/multi?query=$encoded&language=tr-TR&page=$page"
                val root = json.parseToJsonElement(networkClient.getText(url, SITE_HEADERS)).jsonObject
                val totalPages = root.int("total_pages") ?: 1
                val cards = (root["results"] as? JsonArray)?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val mediaTypeStr = obj.str("media_type")
                    if (mediaTypeStr == "person" || obj.str("poster_path").isNullOrBlank()) {
                        return@mapNotNull null
                    }
                    val type = if (mediaTypeStr == "tv") MediaType.TV else MediaType.MOVIE
                    parseTmdbCard(obj, type)
                }.orEmpty()
                cards to (page < totalPages)
            }.getOrDefault(emptyList<MediaCard>() to false)
        }

        val animeDeferred = async {
            runCatching {
                val url = "$ANIME_API/search?keyword=$encoded&page=$page"
                val root = json.parseToJsonElement(networkClient.getText(url, SITE_HEADERS)).jsonObject
                val results = root["results"]?.jsonObject
                val totalPages = results?.int("totalPages") ?: 1
                val cards = (results?.get("data") as? JsonArray)
                    ?.mapNotNull { parseAnimeCard(it as? JsonObject) }
                    .orEmpty()
                cards to (page < totalPages)
            }.getOrDefault(emptyList<MediaCard>() to false)
        }

        val (tmdbItems, tmdbHasNext) = tmdbDeferred.await()
        val (animeItems, animeHasNext) = animeDeferred.await()

        PagedResult(
            items = tmdbItems + animeItems,
            page = page,
            hasNextPage = tmdbHasNext || animeHasNext
        )
    }

    override suspend fun getDetails(id: String, type: MediaType): MediaDetails {
        return when (type) {
            MediaType.ANIME -> getAnimeDetails(id)
            MediaType.MOVIE, MediaType.TV, MediaType.LIVE -> getTmdbDetails(id, type)
        }
    }

    private suspend fun getTmdbDetails(id: String, type: MediaType): MediaDetails {
        val seg = if (type == MediaType.TV) "tv" else "movie"
        return runCatching {
            val url = "$TMDB_PROXY/$seg/$id?language=tr-TR&append_to_response=external_ids,credits"
            val root = json.parseToJsonElement(networkClient.getText(url, SITE_HEADERS)).jsonObject
            val title = root.str("title") ?: root.str("name") ?: id
            val date = root.str("release_date") ?: root.str("first_air_date")
            val genres = (root["genres"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.str("name") }
                .orEmpty()
            val cast = (root["credits"]?.jsonObject?.get("cast") as? JsonArray)
                ?.take(15)
                ?.mapNotNull { (it as? JsonObject)?.str("name") }
                .orEmpty()
            val runtime = root.int("runtime")
                ?: (root["episode_run_time"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull
            val seasons = (root["seasons"] as? JsonArray)?.mapNotNull { el ->
                val s = el as? JsonObject ?: return@mapNotNull null
                val sNum = s.int("season_number") ?: return@mapNotNull null
                if (sNum <= 0) return@mapNotNull null
                Season(
                    seasonNumber = sNum,
                    name = s.str("name") ?: "Sezon $sNum",
                    episodeCount = s.int("episode_count"),
                    posterUrl = s.str("poster_path")?.let(::tmdbPosterUrl)
                )
            }.orEmpty()

            MediaDetails(
                id = id,
                sourceId = SOURCE_ID,
                title = title,
                type = type,
                description = root.str("overview"),
                posterUrl = root.str("poster_path")?.let(::tmdbPosterUrl),
                backdropUrl = root.str("backdrop_path")?.let(::tmdbBackdropUrl),
                year = date?.take(4)?.toIntOrNull(),
                rating = root.double("vote_average"),
                genres = genres,
                cast = cast,
                runtimeMinutes = runtime,
                seasons = seasons
            )
        }.getOrElse {
            // Fallback to site __NEXT_DATA__
            val html = networkClient.getText("$SITE_URL/$seg/$id", SITE_HEADERS)
            val nextData = extractNextData(html) ?: throw it
            val details = nextData["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("details")?.jsonObject ?: throw it
            val seasons = (details["seasons"] as? JsonArray)?.mapNotNull { el ->
                val s = el as? JsonObject ?: return@mapNotNull null
                val sNum = s.int("season_number") ?: return@mapNotNull null
                if (sNum <= 0) return@mapNotNull null
                Season(
                    seasonNumber = sNum,
                    name = s.str("name") ?: "Sezon $sNum",
                    episodeCount = s.int("episode_count"),
                    posterUrl = s.str("poster_path")?.let(::tmdbPosterUrl)
                )
            }.orEmpty()

            MediaDetails(
                id = id,
                sourceId = SOURCE_ID,
                title = details.str("title") ?: details.str("titleOriginal") ?: id,
                type = type,
                description = details.str("desc"),
                posterUrl = normalizeImageUrl(details.str("poster")),
                backdropUrl = normalizeImageUrl(details.str("background")),
                year = details.int("year"),
                rating = details.double("score"),
                genres = (details["categories"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty(),
                runtimeMinutes = details.int("duration"),
                seasons = seasons
            )
        }
    }

    private suspend fun getAnimeDetails(id: String): MediaDetails = coroutineScope {
        val infoDeferred = async {
            val body = networkClient.getText("$ANIME_API/info?id=$id", SITE_HEADERS)
            json.parseToJsonElement(body).jsonObject["results"]?.jsonObject
        }
        val episodesDeferred = async {
            runCatching { getEpisodes(id, 1) }.getOrDefault(emptyList())
        }

        val info = infoDeferred.await()
        val episodes = episodesDeferred.await()

        val genres = (info?.get("genres") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val rating = info?.str("malScore")?.toDoubleOrNull() ?: info?.double("malScore")

        MediaDetails(
            id = id,
            sourceId = SOURCE_ID,
            title = info?.str("title") ?: id,
            type = MediaType.ANIME,
            description = info?.str("synopsis"),
            posterUrl = normalizeImageUrl(info?.str("poster")),
            backdropUrl = normalizeImageUrl(info?.str("backgroundImage")),
            year = info?.str("aired")?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() },
            rating = rating,
            genres = genres,
            seasons = if (episodes.isNotEmpty()) {
                listOf(
                    Season(
                        seasonNumber = 1,
                        name = "Bölümler",
                        episodeCount = episodes.size,
                        episodes = episodes
                    )
                )
            } else {
                emptyList()
            }
        )
    }

    override suspend fun getEpisodes(id: String, season: Int): List<Episode> {
        // Check if id is numeric TMDB id or anime slug
        if (id.toIntOrNull() == null) {
            val body = networkClient.getText("$ANIME_API/episodes/$id", SITE_HEADERS)
            val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject
            val arr = results?.get("episodes") as? JsonArray ?: return emptyList()
            return arr.mapIndexedNotNull { idx, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                val epNo = obj.int("episode_no") ?: (idx + 1)
                val epId = obj.str("id") ?: epNo.toString()
                Episode(
                    id = epId,
                    seasonNumber = 1,
                    episodeNumber = epNo,
                    title = obj.str("title")?.takeIf { it.isNotBlank() } ?: "Bölüm $epNo"
                )
            }
        }

        val url = "$TMDB_PROXY/tv/$id/season/$season?language=tr-TR"
        val body = networkClient.getText(url, SITE_HEADERS)
        val root = json.parseToJsonElement(body).jsonObject
        val arr = root["episodes"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val epNum = obj.int("episode_number") ?: return@mapNotNull null
            Episode(
                id = "${id}_s${season}_e$epNum",
                seasonNumber = season,
                episodeNumber = epNum,
                title = obj.str("name")?.takeIf { it.isNotBlank() } ?: "Bölüm $epNum",
                overview = obj.str("overview"),
                thumbnailUrl = obj.str("still_path")?.let(::tmdbBackdropUrl),
                runtimeMinutes = obj.int("runtime")
            )
        }
    }

    override suspend fun getStreams(
        mediaId: String,
        type: MediaType,
        season: Int?,
        episode: Int?
    ): StreamResult {
        return when (type) {
            MediaType.ANIME -> getAnimeStreams(mediaId, episode ?: 1)
            MediaType.MOVIE, MediaType.TV, MediaType.LIVE -> getMovieOrTvStreams(mediaId, type, season, episode)
        }
    }

    private suspend fun getAnimeStreams(mediaId: String, episodeNo: Int): StreamResult = coroutineScope {
        val infoDeferred = async {
            runCatching {
                val body = networkClient.getText("$ANIME_API/info?id=$mediaId", SITE_HEADERS)
                json.parseToJsonElement(body).jsonObject["results"]?.jsonObject?.str("animeId")
            }.getOrNull()
        }
        val epDeferred = async {
            runCatching {
                val eps = getEpisodes(mediaId, 1)
                eps.firstOrNull { it.episodeNumber == episodeNo }?.id ?: eps.firstOrNull()?.id
            }.getOrNull()
        }

        val animeId = infoDeferred.await() ?: mediaId
        val episodeId = epDeferred.await() ?: episodeNo.toString()

        val subAndDub = listOf(false, true).map { dub ->
            async {
                runCatching {
                    val url = "$STREAM_API/anikoto/sources-id" +
                        "?providerId=${urlEncode(animeId)}&episodeId=${urlEncode(episodeId)}&dub=$dub"
                    val body = networkClient.getText(url, MOVY_STREAM_HEADERS)
                    val root = json.parseToJsonElement(body).jsonObject
                    val mediaSources = root["mediaSources"]?.jsonObject ?: root
                    val tag = if (dub) "Dub" else "Sub"
                    val streams = parseSourcesArray(mediaSources["sources"] as? JsonArray, "Anikoto ($tag)", tag.lowercase())
                    val subs = parseSubtitlesArray(mediaSources["subtitles"] as? JsonArray, isExternal = false)
                    streams to subs
                }.getOrDefault(emptyList<StreamSource>() to emptyList<SubtitleTrack>())
            }
        }.awaitAll()

        val allStreams = subAndDub.flatMap { it.first }
        val allSubs = sortSubtitles(subAndDub.flatMap { it.second }.distinctBy { it.url })
        StreamResult(streams = allStreams, subtitles = allSubs)
    }

    private suspend fun getMovieOrTvStreams(
        mediaId: String,
        type: MediaType,
        season: Int?,
        episode: Int?
    ): StreamResult = coroutineScope {
        val tmdbId = mediaId.toIntOrNull()
            ?: throw IllegalArgumentException("MovyProvider requires numeric TMDB id, got: $mediaId")

        val seedDeferred = async { getSeed(mediaId, forceRefresh = false) }
        val metaDeferred = async { fetchStreamMetadata(mediaId, type) }
        val extSubsDeferred = async { fetchExternalSubtitles(mediaId, type, season, episode) }

        val seed = seedDeferred.await()
        val meta = metaDeferred.await()

        val providerSemaphore = Semaphore(3)
        val providerResults = STREAM_PROVIDERS.map { provider ->
            async {
                providerSemaphore.withPermit {
                    fetchProviderStreams(
                        provider = provider,
                        tmdbId = tmdbId,
                        mediaId = mediaId,
                        type = type,
                        title = meta.title,
                        year = meta.year,
                        imdbId = meta.imdbId,
                        totalSeasons = meta.totalSeasons,
                        season = season ?: 1,
                        episode = episode ?: 1,
                        seed = seed
                    )
                }
            }
        }.awaitAll()

        val allStreams = providerResults.flatMap { it.first }.distinctBy { it.url }
        val providerSubs = providerResults.flatMap { it.second }
        val extSubs = extSubsDeferred.await()
        val mergedSubs = sortSubtitles((providerSubs + extSubs).distinctBy { it.url })

        StreamResult(streams = allStreams, subtitles = mergedSubs)
    }

    private suspend fun fetchProviderStreams(
        provider: String,
        tmdbId: Int,
        mediaId: String,
        type: MediaType,
        title: String,
        year: String,
        imdbId: String,
        totalSeasons: Int,
        season: Int,
        episode: Int,
        seed: String
    ): Pair<List<StreamSource>, List<SubtitleTrack>> {
        var currentSeed = seed
        for (attempt in 0..1) {
            val mediaTypeStr = if (type == MediaType.TV) "tv" else "movie"
            val params = buildString {
                append("title=").append(urlEncode(title))
                append("&mediaType=").append(mediaTypeStr)
                append("&year=").append(urlEncode(year))
                append("&tmdbId=").append(mediaId)
                append("&imdbId=").append(urlEncode(imdbId))
                append("&enc=2")
                append("&seed=").append(urlEncode(currentSeed))
                if (type == MediaType.TV) {
                    append("&totalSeasons=").append(totalSeasons)
                    append("&seasonId=").append(season)
                    append("&episodeId=").append(episode)
                }
            }
            val url = "$STREAM_API/$provider/sources?$params"
            val result = runCatching {
                val encoded = networkClient.getText(url, MOVY_STREAM_HEADERS)
                val decodedJson = MovyCipher.decode(encoded, currentSeed, tmdbId)
                val root = json.parseToJsonElement(decodedJson).jsonObject
                val streams = parseSourcesArray(
                    root["sources"] as? JsonArray,
                    serverName = provider.replaceFirstChar { it.uppercase() }
                )
                val subs = parseSubtitlesArray(root["subtitles"] as? JsonArray, isExternal = false)
                streams to subs
            }
            if (result.isSuccess) {
                return result.getOrThrow()
            }
            val errMsg = result.exceptionOrNull()?.message.orEmpty()
            if (attempt == 0 && errMsg.contains("401")) {
                currentSeed = getSeed(mediaId, forceRefresh = true)
            } else {
                break
            }
        }
        return emptyList<StreamSource>() to emptyList()
    }

    fun trimMemory() {
        seedCache.clear()
    }

    private suspend fun getSeed(mediaId: String, forceRefresh: Boolean): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            seedCache[mediaId]?.let { cached ->
                if (now < cached.expiresAtMs) return cached.seed
            }
        }
        return seedMutex.withLock {
            val againNow = System.currentTimeMillis()
            seedCache.entries.removeIf { it.value.expiresAtMs <= againNow }
            if (!forceRefresh) {
                seedCache[mediaId]?.let { cached ->
                    if (againNow < cached.expiresAtMs) return@withLock cached.seed
                }
            }
            val body = networkClient.getText("$STREAM_API/seed?mediaId=$mediaId", MOVY_STREAM_HEADERS)
            val obj = json.parseToJsonElement(body).jsonObject
            val seed = obj.str("seed") ?: throw IllegalStateException("Missing seed in response")
            val ttlMs = obj.long("ttlMs") ?: 60_000L
            val safeTtl = (ttlMs - 5_000L).coerceAtLeast(5_000L)
            seedCache[mediaId] = CachedSeed(seed = seed, expiresAtMs = againNow + safeTtl)
            seed
        }
    }

    private data class StreamMetadata(
        val title: String,
        val year: String,
        val imdbId: String,
        val totalSeasons: Int
    )

    private suspend fun fetchStreamMetadata(mediaId: String, type: MediaType): StreamMetadata {
        val seg = if (type == MediaType.TV) "tv" else "movie"
        return runCatching {
            val url = "$TMDB_PROXY/$seg/$mediaId?language=tr-TR&append_to_response=external_ids"
            val root = json.parseToJsonElement(networkClient.getText(url, SITE_HEADERS)).jsonObject
            val title = root.str("original_title")
                ?: root.str("original_name")
                ?: root.str("title")
                ?: root.str("name")
                ?: mediaId
            val date = root.str("release_date") ?: root.str("first_air_date") ?: ""
            val year = date.take(4)
            val imdbId = root["external_ids"]?.jsonObject?.str("imdb_id") ?: ""
            val totalSeasons = root.int("number_of_seasons") ?: 1
            StreamMetadata(title = title, year = year, imdbId = imdbId, totalSeasons = totalSeasons)
        }.getOrDefault(StreamMetadata(title = mediaId, year = "", imdbId = "", totalSeasons = 1))
    }

    private suspend fun fetchExternalSubtitles(
        mediaId: String,
        type: MediaType,
        season: Int?,
        episode: Int?
    ): List<SubtitleTrack> = runCatching {
        val url = buildString {
            append("$SUBTITLES_API?id=$mediaId")
            if (type == MediaType.TV && season != null && episode != null) {
                append("&season=$season&episode=$episode")
            }
        }
        val body = networkClient.getText(url, MOVY_STREAM_HEADERS)
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val subUrl = obj.str("url") ?: return@mapNotNull null
            val lang = obj.str("language") ?: "en"
            val label = obj.str("display") ?: lang.uppercase()
            val fmt = when (obj.str("format")?.lowercase()) {
                "srt" -> SubtitleFormat.SRT
                "ass" -> SubtitleFormat.ASS
                else -> SubtitleFormat.VTT
            }
            SubtitleTrack(
                url = subUrl,
                language = lang,
                label = label,
                format = fmt,
                headers = MOVY_STREAM_HEADERS,
                isExternal = true
            )
        }
    }.getOrDefault(emptyList())

    private fun parseSourcesArray(
        arr: JsonArray?,
        serverName: String,
        audioLang: String? = null
    ): List<StreamSource> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val url = obj.str("url") ?: return@mapNotNull null
            val quality = obj.str("quality") ?: "Auto"
            StreamSource(
                url = url,
                quality = quality,
                format = inferStreamFormat(url),
                serverName = serverName,
                audioLang = audioLang,
                headers = MOVY_STREAM_HEADERS
            )
        }
    }

    private fun parseSubtitlesArray(arr: JsonArray?, isExternal: Boolean): List<SubtitleTrack> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val url = obj.str("url") ?: return@mapNotNull null
            val lang = obj.str("lang") ?: obj.str("language") ?: "en"
            val label = obj.str("language") ?: obj.str("label") ?: lang.uppercase()
            val fmt = when {
                url.contains(".srt", ignoreCase = true) -> SubtitleFormat.SRT
                url.contains(".ass", ignoreCase = true) -> SubtitleFormat.ASS
                else -> SubtitleFormat.VTT
            }
            SubtitleTrack(
                url = url,
                language = lang,
                label = label,
                format = fmt,
                headers = MOVY_STREAM_HEADERS,
                isExternal = isExternal
            )
        }
    }

    private fun sortSubtitles(tracks: List<SubtitleTrack>): List<SubtitleTrack> {
        return tracks.sortedBy { track ->
            val lang = track.language.lowercase()
            val label = track.label.lowercase()
            when {
                lang.startsWith("tr") || label.contains("turk") || label.contains("türk") -> 0
                lang.startsWith("en") || label.contains("eng") -> 1
                else -> 2
            }
        }
    }

    private fun parseHomeMediaItem(obj: JsonObject?): MediaCard? {
        if (obj == null) return null
        val slug = obj.str("slug").orEmpty()
        val rawMediaType = obj.str("mediaType")?.lowercase()
        val type = when {
            rawMediaType == "tv" || slug.startsWith("/tv/") -> MediaType.TV
            rawMediaType == "anime" || slug.startsWith("/anime/") || slug.startsWith("/tr/anime/") -> MediaType.ANIME
            else -> MediaType.MOVIE
        }
        val rawId = obj.str("id")
        val id = if (type == MediaType.ANIME) {
            normalizeAnimeSlug(slug) ?: rawId ?: return null
        } else {
            rawId ?: slug.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        }
        val title = obj.str("title") ?: return null
        return MediaCard(
            id = id,
            sourceId = SOURCE_ID,
            title = title,
            posterUrl = normalizeImageUrl(obj.str("poster") ?: obj.str("image")),
            backdropUrl = normalizeImageUrl(obj.str("background")),
            type = type,
            year = obj.str("release_date")?.take(4)?.toIntOrNull(),
            rating = obj.double("rating"),
            subtitle = obj.str("episodeCode")
        )
    }

    private fun parseTmdbCard(obj: JsonObject?, type: MediaType): MediaCard? {
        if (obj == null) return null
        val id = obj.str("id") ?: return null
        val title = obj.str("title") ?: obj.str("name") ?: return null
        val date = obj.str("release_date") ?: obj.str("first_air_date")
        return MediaCard(
            id = id,
            sourceId = SOURCE_ID,
            title = title,
            posterUrl = obj.str("poster_path")?.let(::tmdbPosterUrl),
            backdropUrl = obj.str("backdrop_path")?.let(::tmdbBackdropUrl),
            type = type,
            year = date?.take(4)?.toIntOrNull(),
            rating = obj.double("vote_average")
        )
    }

    private fun parseAnimeCard(obj: JsonObject?): MediaCard? {
        if (obj == null) return null
        val slug = obj.str("slug")
        val id = normalizeAnimeSlug(slug) ?: obj.str("id") ?: obj.str("animeId") ?: return null
        val title = obj.str("title") ?: return null
        return MediaCard(
            id = id,
            sourceId = SOURCE_ID,
            title = title,
            posterUrl = normalizeImageUrl(obj.str("poster")),
            type = MediaType.ANIME,
            rating = obj.double("rating") ?: obj.str("rating")?.toDoubleOrNull()
        )
    }

    private fun extractNextData(html: String): JsonObject? {
        val raw = extractNextDataJson(html) ?: return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun inferStreamFormat(url: String): StreamFormat {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> StreamFormat.HLS
            lower.contains(".mpd") -> StreamFormat.DASH
            lower.contains(".mp4") -> StreamFormat.MP4
            else -> StreamFormat.UNKNOWN
        }
    }

    private fun normalizeImageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$SITE_ORIGIN$raw"
            else -> "$SITE_ORIGIN/$raw"
        }
    }

    private fun tmdbPosterUrl(path: String): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "$SITE_ORIGIN/img/w342$normalized/q50.webp"
    }

    private fun tmdbBackdropUrl(path: String): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "$SITE_ORIGIN/img/w780$normalized/q65.webp"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull

    companion object {
        const val SOURCE_ID = "builtin_movy_sx"
        const val SITE_ORIGIN = "https://www.movy.sx"
        const val SITE_URL = "https://www.movy.sx/tr"
        const val TMDB_PROXY = "https://db.wecollege.net/3"
        const val STREAM_API = "https://api.wecollege.net"
        const val ANIME_API = "https://anime.vidy.st/api"
        const val SUBTITLES_API = "https://subtitles.vidy.st/search"

        val SITE_HEADERS = mapOf(
            "User-Agent" to AtrisNetworkClient.DEFAULT_USER_AGENT,
            "Referer" to "$SITE_ORIGIN/"
        )

        val MOVY_STREAM_HEADERS = mapOf(
            "Origin" to SITE_ORIGIN,
            "Referer" to "$SITE_ORIGIN/",
            "User-Agent" to AtrisNetworkClient.DEFAULT_USER_AGENT
        )

        val STREAM_PROVIDERS = listOf(
            "miami",
            "boise",
            "seattle",
            "atlanta",
            "phoenix",
            "berlin",
            "paris"
        )
    }
}

internal fun normalizeAnimeSlug(rawSlug: String?): String? {
    val cleaned = rawSlug
        ?.trim()
        ?.trimStart('/')
        ?.removePrefix("tr/")
        ?.removePrefix("anime/")
        ?.substringBefore('/')
        ?.trim()
    return cleaned?.takeIf { it.isNotEmpty() }
}

internal fun extractNextDataJson(html: String): String? {
    var searchFrom = 0
    while (true) {
        val scriptStart = html.indexOf("<script", searchFrom, ignoreCase = true)
        if (scriptStart == -1) return null
        val tagEnd = html.indexOf('>', scriptStart)
        if (tagEnd == -1) return null
        val openTag = html.substring(scriptStart, tagEnd + 1)
        if (openTag.contains("id=\"__NEXT_DATA__\"") || openTag.contains("id='__NEXT_DATA__'")) {
            val contentStart = tagEnd + 1
            val scriptEnd = html.indexOf("</script>", contentStart, ignoreCase = true)
            if (scriptEnd == -1) return null
            return html.substring(contentStart, scriptEnd)
        }
        searchFrom = tagEnd + 1
    }
}
