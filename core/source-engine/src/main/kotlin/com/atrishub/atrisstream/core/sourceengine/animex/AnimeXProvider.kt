package com.atrishub.atrisstream.core.sourceengine.animex

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Built-in MediaProvider for `https://animex.one` (Anime).
 */
class AnimeXProvider(
    private val networkClient: AtrisNetworkClient,
    private val flixCloudExtractor: FlixCloudExtractor = FlixCloudExtractor(networkClient)
) : MediaProvider {

    override val manifest: ProviderManifest = ProviderManifest(
        id = SOURCE_ID,
        name = "AnimeX (Anime)",
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
        val body = networkClient.getText("$SITE_URL/home/__data.json", SITE_HEADERS)
        return parseHomeData(body)
    }

    override suspend fun browse(type: MediaType, page: Int): PagedResult<MediaCard> {
        val safePage = page.coerceAtLeast(1)
        val svelteResult = runCatching {
            val body = networkClient.getText("$SITE_URL/browse/__data.json?page=$safePage", SITE_HEADERS)
            parsePagedData(body, safePage)
        }.getOrNull()
        if (svelteResult != null && svelteResult.items.isNotEmpty()) {
            return svelteResult
        }
        return queryAniListCatalog(query = null, page = safePage)
    }

    override suspend fun search(query: String, page: Int): PagedResult<MediaCard> {
        val safePage = page.coerceAtLeast(1)
        val trimmed = query.trim()
        if (trimmed.isBlank()) return PagedResult(emptyList(), safePage, false)
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        val svelteResult = runCatching {
            val body = networkClient.getText("$SITE_URL/search/__data.json?q=$encoded&page=$safePage", SITE_HEADERS)
            parsePagedData(body, safePage)
        }.getOrNull()
        if (svelteResult != null && svelteResult.items.isNotEmpty()) {
            return svelteResult
        }
        return queryAniListCatalog(query = trimmed, page = safePage)
    }

    override suspend fun getDetails(id: String, type: MediaType): MediaDetails {
        val body = networkClient.getText("$SITE_URL/anime/$id/__data.json", SITE_HEADERS)
        return parseAnimeDetailsData(id, body)
    }

    override suspend fun getEpisodes(id: String, season: Int): List<Episode> {
        val details = getDetails(id, MediaType.ANIME)
        return details.seasons.firstOrNull()?.episodes.orEmpty()
    }

    override suspend fun getStreams(
        mediaId: String,
        type: MediaType,
        season: Int?,
        episode: Int?
    ): StreamResult {
        val epNum = episode ?: 1
        val watchUrl = "$SITE_URL/watch/$mediaId-episode-$epNum/__data.json?x-sveltekit-invalidated=01"
        val body = networkClient.getText(watchUrl, SITE_HEADERS)
        val playerUrl = parseWatchNdjson(body)
            ?: throw IllegalStateException("AnimeX missing FlixCloud player_url for $mediaId ep $epNum")

        return flixCloudExtractor.extract(
            embedUrl = playerUrl,
            serverName = "AnimeX (FlixCloud)",
            referer = "$SITE_URL/watch/$mediaId-episode-$epNum"
        )
    }

    internal fun parseHomeData(rawBody: String): List<HomeSection> {
        val merged = SvelteKitDevalueDecoder.decodeMergedNodes(rawBody)
        val sections = mutableListOf<HomeSection>()
        val candidates = listOf(
            "trending" to "Trending",
            "popular" to "Popular",
            "latest" to "Latest Episodes",
            "recent" to "Recently Updated",
            "topAiring" to "Top Airing",
            "upcoming" to "Upcoming"
        )
        for ((key, title) in candidates) {
            val arr = merged[key] as? JsonArray ?: continue
            val items = arr.mapNotNull { parseAnimeCard(it as? JsonObject) }
            if (items.isNotEmpty()) {
                sections.add(HomeSection(title = title, items = items))
            }
        }
        if (sections.isEmpty()) {
            val genericSections = merged["sections"] as? JsonArray
            genericSections?.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val title = obj.str("title") ?: obj.str("name") ?: return@forEach
                val items = (obj["items"] as? JsonArray ?: obj["anime"] as? JsonArray)
                    ?.mapNotNull { parseAnimeCard(it as? JsonObject) }
                    .orEmpty()
                if (items.isNotEmpty()) {
                    sections.add(HomeSection(title = title, items = items))
                }
            }
        }
        return sections
    }

    internal fun parsePagedData(rawBody: String, page: Int): PagedResult<MediaCard> {
        val merged = SvelteKitDevalueDecoder.decodeMergedNodes(rawBody)
        val arr = (merged["results"] as? JsonArray)
            ?: (merged["anime"] as? JsonArray)
            ?: (merged["items"] as? JsonArray)
            ?: (merged["media"] as? JsonArray)
            ?: JsonArray(emptyList())
        val items = arr.mapNotNull { parseAnimeCard(it as? JsonObject) }
        val hasNext = (merged["hasNextPage"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: (items.size >= 24)
        return PagedResult(items = items, page = page, hasNextPage = hasNext)
    }

    internal fun parseAnimeDetailsData(id: String, rawBody: String): MediaDetails {
        val merged = SvelteKitDevalueDecoder.decodeMergedNodes(rawBody)
        val animeObj = (merged["anime"] as? JsonObject)
            ?: (merged["media"] as? JsonObject)
            ?: merged

        val title = extractTitle(animeObj) ?: id
        val nextAiringEp = (animeObj["nextAiringEpisode"] as? JsonObject)?.int("episode")
            ?: (merged["nextAiringEpisode"] as? JsonObject)?.int("episode")

        val rawEpisodes = (animeObj["episodes"] as? JsonArray)
            ?: (merged["episodes"] as? JsonArray)
        val totalEpisodesCount = animeObj.int("episodes") ?: animeObj.int("totalEpisodes")

        val episodes = if (rawEpisodes != null && rawEpisodes.isNotEmpty()) {
            rawEpisodes.mapIndexedNotNull { idx, el ->
                val epObj = el as? JsonObject ?: return@mapIndexedNotNull null
                val epNum = epObj.int("number") ?: epObj.int("episode") ?: (idx + 1)
                val isReleased = if (nextAiringEp != null) epNum < nextAiringEp else true
                Episode(
                    id = epObj.str("id") ?: "$id-episode-$epNum",
                    seasonNumber = 1,
                    episodeNumber = epNum,
                    title = epObj.str("title")?.takeIf { it.isNotBlank() } ?: "Episode $epNum",
                    overview = epObj.str("description") ?: epObj.str("overview"),
                    thumbnailUrl = epObj.str("thumbnail") ?: epObj.str("image"),
                    runtimeMinutes = epObj.int("duration"),
                    isReleased = isReleased
                )
            }
        } else if (totalEpisodesCount != null && totalEpisodesCount > 0) {
            (1..totalEpisodesCount).map { epNum ->
                val isReleased = if (nextAiringEp != null) epNum < nextAiringEp else true
                Episode(
                    id = "$id-episode-$epNum",
                    seasonNumber = 1,
                    episodeNumber = epNum,
                    title = "Episode $epNum",
                    isReleased = isReleased
                )
            }
        } else {
            emptyList()
        }

        val genres = (animeObj["genres"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()

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
            id = animeObj.str("slug") ?: id,
            sourceId = SOURCE_ID,
            title = title,
            type = MediaType.ANIME,
            description = animeObj.str("description") ?: animeObj.str("synopsis"),
            posterUrl = animeObj.str("poster")
                ?: (animeObj["coverImage"] as? JsonObject)?.str("large")
                ?: animeObj.str("coverImage"),
            backdropUrl = animeObj.str("banner") ?: animeObj.str("bannerImage"),
            year = animeObj.int("seasonYear") ?: animeObj.int("year"),
            rating = animeObj.double("averageScore")?.let { if (it > 10.0) it / 10.0 else it }
                ?: animeObj.double("score"),
            genres = genres,
            runtimeMinutes = animeObj.int("duration"),
            seasons = seasons
        )
    }

    internal fun parseWatchNdjson(rawBody: String): String? {
        // First check for direct zen.data[0].player_url in NDJSON lines
        for (line in rawBody.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            val zenData = (obj["zen"] as? JsonObject)?.get("data") as? JsonArray
            val zenFirst = zenData?.firstOrNull() as? JsonObject
            zenFirst?.str("player_url")?.let { return it }

            val directData = obj["data"] as? JsonArray
            val directFirst = directData?.firstOrNull() as? JsonObject
            directFirst?.str("player_url")?.let { return it }
        }
        // Fallback: SvelteKit devalue decode
        val merged = SvelteKitDevalueDecoder.decodeMergedNodes(rawBody)
        val zenData = (merged["zen"] as? JsonObject)?.get("data") as? JsonArray
        val zenFirst = zenData?.firstOrNull() as? JsonObject
        return zenFirst?.str("player_url") ?: merged.str("player_url")
    }

    private suspend fun queryAniListCatalog(query: String?, page: Int): PagedResult<MediaCard> {
        val gql = if (query == null) {
            """{"query":"query(${"$"}page:Int){Page(page:${"$"}page,perPage:24){pageInfo{hasNextPage}media(type:ANIME,sort:POPULARITY_DESC){id title{romaji english}coverImage{large}bannerImage averageScore seasonYear}}}","variables":{"page":$page}}"""
        } else {
            val escaped = query.replace("\"", "\\\"")
            """{"query":"query(${"$"}page:Int,${"$"}search:String){Page(page:${"$"}page,perPage:24){pageInfo{hasNextPage}media(type:ANIME,search:${"$"}search){id title{romaji english}coverImage{large}bannerImage averageScore seasonYear}}}","variables":{"page":$page,"search":"$escaped"}}"""
        }
        val body = networkClient.postText(ANILIST_URL, gql)
        val root = json.parseToJsonElement(body).jsonObject
        val pageObj = root["data"]?.jsonObject?.get("Page")?.jsonObject
            ?: return PagedResult(emptyList(), page, false)
        val hasNext = pageObj["pageInfo"]?.jsonObject?.get("hasNextPage")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val items = (pageObj["media"] as? JsonArray)
            ?.mapNotNull { parseAnimeCard(it as? JsonObject) }
            .orEmpty()
        return PagedResult(items = items, page = page, hasNextPage = hasNext)
    }

    private fun parseAnimeCard(obj: JsonObject?): MediaCard? {
        obj ?: return null
        val slug = obj.str("slug") ?: obj.int("id")?.toString() ?: obj.str("id") ?: return null
        val title = extractTitle(obj) ?: return null
        val poster = obj.str("poster")
            ?: (obj["coverImage"] as? JsonObject)?.str("large")
            ?: obj.str("coverImage")
        return MediaCard(
            id = slug,
            sourceId = SOURCE_ID,
            title = title,
            posterUrl = poster,
            backdropUrl = obj.str("banner") ?: obj.str("bannerImage"),
            type = MediaType.ANIME,
            year = obj.int("seasonYear") ?: obj.int("year"),
            rating = obj.double("averageScore")?.let { if (it > 10.0) it / 10.0 else it }
                ?: obj.double("score")
        )
    }

    private fun extractTitle(obj: JsonObject): String? {
        obj.str("title")?.let { return it }
        val titleObj = obj["title"] as? JsonObject
        return titleObj?.str("english") ?: titleObj?.str("romaji") ?: titleObj?.str("native")
            ?: obj.str("name")
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    companion object {
        const val SOURCE_ID = "builtin_animex"
        const val SITE_URL = "https://animex.one"
        private const val ANILIST_URL = "https://graphql.anilist.co"
        private val SITE_HEADERS = mapOf(
            "Referer" to "$SITE_URL/",
            "Origin" to SITE_URL
        )
    }
}
