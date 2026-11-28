package com.atrishub.atrisstream.core.sourceengine.reanime

import com.atrishub.atrisstream.core.model.Episode
import com.atrishub.atrisstream.core.model.HomeSection
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.model.MediaDetails
import com.atrishub.atrisstream.core.model.MediaProvider
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.PagedResult
import com.atrishub.atrisstream.core.model.ProviderManifest
import com.atrishub.atrisstream.core.model.Season
import com.atrishub.atrisstream.core.model.StreamResult
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import com.atrishub.atrisstream.core.sourceengine.flixcloud.FlixCloudExtractor
import com.atrishub.atrisstream.core.sourceengine.flixcloud.SvelteKitDevalueDecoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Built-in MediaProvider for `https://reanime.cz` (Anime).
 */
class ReAnimeProvider(
    private val networkClient: AtrisNetworkClient,
    private val flixCloudExtractor: FlixCloudExtractor = FlixCloudExtractor(networkClient)
) : MediaProvider {

    override val manifest: ProviderManifest = ProviderManifest(
        id = SOURCE_ID,
        name = "ReAnime (Anime)",
        version = "1.0.0",
        baseUrl = SITE_URL,
        supportedTypes = listOf(MediaType.ANIME),
        languages = listOf("en")
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun getHome(): List<HomeSection> {
        val body = networkClient.getText("$SITE_URL/api/v1/home", SITE_HEADERS)
        return parseHomeResponse(body)
    }

    override suspend fun browse(type: MediaType, page: Int): PagedResult<MediaCard> {
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * PAGE_SIZE
        val url = "$SITE_URL/api/v1/search?limit=$PAGE_SIZE&offset=$offset"
        val body = networkClient.getText(url, SITE_HEADERS)
        return parseSearchResponse(body, safePage)
    }

    override suspend fun search(query: String, page: Int): PagedResult<MediaCard> {
        val safePage = page.coerceAtLeast(1)
        val trimmed = query.trim()
        if (trimmed.isBlank()) return PagedResult(emptyList(), safePage, false)
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        val offset = (safePage - 1) * PAGE_SIZE
        val url = "$SITE_URL/api/v1/search?q=$encoded&limit=$PAGE_SIZE&offset=$offset"
        val body = networkClient.getText(url, SITE_HEADERS)
        return parseSearchResponse(body, safePage)
    }

    override suspend fun getDetails(id: String, type: MediaType): MediaDetails = coroutineScope {
        val apiDeferred = async {
            networkClient.getText("$SITE_URL/api/v1/anime/$id", SITE_HEADERS)
        }
        val watchDeferred = async {
            runCatching {
                networkClient.getText("$SITE_URL/watch/$id/__data.json?ep=1", SITE_HEADERS)
            }.getOrNull()
        }
        parseDetailsAndEpisodes(
            id = id,
            animeApiBody = apiDeferred.await(),
            watchDataBody = watchDeferred.await()
        )
    }

    override suspend fun getEpisodes(id: String, season: Int): List<Episode> {
        val watchDataBody = networkClient.getText("$SITE_URL/watch/$id/__data.json?ep=1", SITE_HEADERS)
        return parseEpisodesFromWatchData(watchDataBody)
    }

    override suspend fun getStreams(
        mediaId: String,
        type: MediaType,
        season: Int?,
        episode: Int?
    ): StreamResult {
        val epNum = episode ?: 1
        val animeBody = networkClient.getText("$SITE_URL/api/v1/anime/$mediaId", SITE_HEADERS)
        val animeRoot = json.parseToJsonElement(animeBody).jsonObject
        val animeObj = animeRoot["anime"] as? JsonObject ?: animeRoot["data"] as? JsonObject ?: animeRoot

        val anilistId = animeObj.int("anilistId")
            ?: animeObj.int("anilist_id")
            ?: animeObj.int("id")
            ?: throw IllegalStateException("ReAnime missing anilistId for $mediaId")
        val malId = animeObj.int("malId") ?: animeObj.int("mal_id") ?: 0

        val flixUrl = "$SITE_URL/api/flix/$anilistId/$epNum?mal=$malId"
        val flixBody = networkClient.getText(flixUrl, SITE_HEADERS)
        val embedUrl = extractFlixCloudUrl(flixBody)
            ?: throw IllegalStateException("ReAnime missing FlixCloud embed URL for $mediaId ep $epNum")

        return flixCloudExtractor.extract(
            embedUrl = embedUrl,
            serverName = "ReAnime (FlixCloud)",
            referer = "$SITE_URL/watch/$mediaId?ep=$epNum"
        )
    }

    internal fun parseHomeResponse(body: String): List<HomeSection> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"] as? JsonObject ?: root
        val mappings = listOf(
            "latest_aired" to "Latest Aired",
            "new_on_site" to "New on Site",
            "trending" to "Trending"
        )
        return mappings.mapNotNull { (key, title) ->
            val items = (data[key] as? JsonArray)
                ?.mapNotNull { parseAnimeCard(it as? JsonObject) }
                .orEmpty()
            if (items.isNotEmpty()) HomeSection(title = title, items = items) else null
        }
    }

    internal fun parseSearchResponse(body: String, page: Int): PagedResult<MediaCard> {
        val root = json.parseToJsonElement(body).jsonObject
        val arr = (root["results"] as? JsonArray)
            ?: (root["data"] as? JsonArray)
            ?: (root["anime"] as? JsonArray)
            ?: JsonArray(emptyList())
        val items = arr.mapNotNull { parseAnimeCard(it as? JsonObject) }
        val total = root.int("total") ?: (root["pagination"] as? JsonObject)?.int("total")
        val hasNext = if (total != null) {
            page * PAGE_SIZE < total
        } else {
            items.size >= PAGE_SIZE
        }
        return PagedResult(items = items, page = page, hasNextPage = hasNext)
    }

    internal fun parseDetailsAndEpisodes(
        id: String,
        animeApiBody: String,
        watchDataBody: String?
    ): MediaDetails {
        val root = json.parseToJsonElement(animeApiBody).jsonObject
        val obj = root["anime"] as? JsonObject ?: root["data"] as? JsonObject ?: root

        val title = extractTitle(obj) ?: id
        val genres = (obj["genres"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val episodes = watchDataBody?.let(::parseEpisodesFromWatchData).orEmpty()
            .ifEmpty {
                (obj["episodes"] as? JsonArray)?.mapIndexedNotNull { idx, el ->
                    parseEpisodeObj(el as? JsonObject, idx + 1)
                }.orEmpty()
            }

        val seasons = if (episodes.isNotEmpty()) {
            listOf(
                Season(
                    seasonNumber = 1,
                    name = "Episodes",
                    episodeCount = episodes.size,
                    episodes = episodes
                )
            )
        } else {
            emptyList()
        }

        return MediaDetails(
            id = obj.str("slug") ?: id,
            sourceId = SOURCE_ID,
            title = title,
            type = MediaType.ANIME,
            description = obj.str("description") ?: obj.str("synopsis"),
            posterUrl = obj.str("poster") ?: obj.str("coverImage") ?: obj.str("poster_url"),
            backdropUrl = obj.str("banner") ?: obj.str("bannerImage") ?: obj.str("backdrop_url"),
            year = obj.int("year") ?: obj.int("seasonYear"),
            rating = obj.double("score") ?: obj.double("rating") ?: obj.double("averageScore"),
            genres = genres,
            runtimeMinutes = obj.int("duration"),
            seasons = seasons
        )
    }

    internal fun parseEpisodesFromWatchData(watchDataBody: String): List<Episode> {
        val merged = SvelteKitDevalueDecoder.decodeMergedNodes(watchDataBody)
        val arr = (merged["episodes"] as? JsonArray)
            ?: (merged["anime"] as? JsonObject)?.get("episodes") as? JsonArray
            ?: return emptyList()
        return arr.mapIndexedNotNull { idx, el ->
            parseEpisodeObj(el as? JsonObject, idx + 1)
        }
    }

    internal fun extractFlixCloudUrl(flixBody: String): String? {
        val trimmed = flixBody.trim()
        if (trimmed.startsWith("http")) return trimmed
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        return root.str("url")
            ?: root.str("player_url")
            ?: root.str("embedUrl")
            ?: root.str("iframe")
            ?: (root["data"] as? JsonObject)?.let { d ->
                d.str("url") ?: d.str("player_url") ?: d.str("embedUrl")
            }
    }

    private fun parseAnimeCard(obj: JsonObject?): MediaCard? {
        obj ?: return null
        val slug = obj.str("slug") ?: obj.str("id") ?: return null
        val title = extractTitle(obj) ?: return null
        return MediaCard(
            id = slug,
            sourceId = SOURCE_ID,
            title = title,
            posterUrl = obj.str("poster") ?: obj.str("coverImage") ?: obj.str("poster_url"),
            backdropUrl = obj.str("banner") ?: obj.str("bannerImage"),
            type = MediaType.ANIME,
            year = obj.int("year") ?: obj.int("seasonYear"),
            rating = obj.double("score") ?: obj.double("rating") ?: obj.double("averageScore")
        )
    }

    private fun parseEpisodeObj(obj: JsonObject?, fallbackNum: Int): Episode? {
        obj ?: return null
        val epNum = obj.int("number") ?: obj.int("episode") ?: obj.int("ep") ?: fallbackNum
        val playable = obj.bool("playable") ?: true
        val title = obj.str("title")?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
        return Episode(
            id = obj.str("id") ?: epNum.toString(),
            seasonNumber = 1,
            episodeNumber = epNum,
            title = title,
            overview = obj.str("description") ?: obj.str("overview"),
            thumbnailUrl = obj.str("thumbnail") ?: obj.str("image"),
            runtimeMinutes = obj.int("duration"),
            isReleased = playable
        )
    }

    private fun extractTitle(obj: JsonObject): String? {
        obj.str("title")?.let { return it }
        val titleObj = obj["title"] as? JsonObject
        return titleObj?.str("english") ?: titleObj?.str("romaji") ?: titleObj?.str("native")
            ?: obj.str("name")
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull

    companion object {
        const val SOURCE_ID = "builtin_reanime"
        const val SITE_URL = "https://reanime.cz"
        private const val PAGE_SIZE = 24
        private val SITE_HEADERS = mapOf(
            "Referer" to "$SITE_URL/",
            "Origin" to SITE_URL
        )
    }
}
