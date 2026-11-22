package com.atrishub.atrisstream.core.sourceengine.driver

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Serializable
data class JsonSourceManifestConfig(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val baseUrl: String,
    val driverType: String = "JSON_MANIFEST",
    val supportedTypes: List<String> = listOf("MOVIE", "TV"),
    val languages: List<String> = listOf("en"),
    val headers: Map<String, String> = emptyMap(),
    val endpoints: Map<String, String> = emptyMap(),
    val selectors: Map<String, String> = emptyMap()
)

/**
 * Declarative HTML/CSS-selector MediaProvider driven by a JSON manifest (`sample-json-source.json`).
 */
class JsonManifestProvider(
    private val config: JsonSourceManifestConfig,
    private val fetchText: suspend (url: String, headers: Map<String, String>) -> String
) : MediaProvider {

    constructor(
        config: JsonSourceManifestConfig,
        networkClient: AtrisNetworkClient
    ) : this(config, networkClient::getText)

    override val manifest: ProviderManifest = ProviderManifest(
        id = config.id,
        name = config.name,
        version = config.version,
        baseUrl = config.baseUrl,
        supportedTypes = config.supportedTypes.mapNotNull { runCatching { MediaType.valueOf(it.uppercase()) }.getOrNull() }
            .ifEmpty { listOf(MediaType.MOVIE, MediaType.TV) },
        languages = config.languages.ifEmpty { listOf("en") }
    )

    private val selectors = config.selectors
    private val endpoints = config.endpoints
    private val headers = config.headers

    override suspend fun getHome(): List<HomeSection> {
        val homePath = endpoints["home"] ?: "/"
        val url = resolveUrl(homePath)
        val html = fetchText(url, headers)
        val doc = Jsoup.parse(html, url)

        val sectionSelector = selectors["homeSection"]
        if (sectionSelector.isNullOrBlank()) {
            val cards = parseCards(doc, defaultType = manifest.supportedTypes.firstOrNull() ?: MediaType.MOVIE)
            return if (cards.isNotEmpty()) listOf(HomeSection(title = config.name, items = cards)) else emptyList()
        }

        val sections = mutableListOf<HomeSection>()
        val sectionTitleSel = selectors["homeSectionTitle"]
        for (sectionEl in doc.select(sectionSelector)) {
            val title = if (!sectionTitleSel.isNullOrBlank()) {
                extractValue(sectionEl, sectionTitleSel) ?: config.name
            } else {
                config.name
            }
            val cards = parseCards(sectionEl, defaultType = manifest.supportedTypes.firstOrNull() ?: MediaType.MOVIE)
            if (cards.isNotEmpty()) {
                sections.add(HomeSection(title = title, items = cards))
            }
        }
        return sections
    }

    override suspend fun browse(type: MediaType, page: Int): PagedResult<MediaCard> {
        val endpointKey = when (type) {
            MediaType.MOVIE -> "browseMovie"
            MediaType.TV -> "browseTv"
            MediaType.ANIME -> "browseAnime"
            MediaType.LIVE -> "browseLive"
        }
        val template = endpoints[endpointKey] ?: endpoints["browse"] ?: endpoints["home"] ?: "/"
        val path = template.replace("{page}", page.toString())
        val url = resolveUrl(path)
        val html = fetchText(url, headers)
        val doc = Jsoup.parse(html, url)

        val cards = parseCards(doc, defaultType = type)
        val nextSel = selectors["nextPage"]
        val hasNext = if (!nextSel.isNullOrBlank()) {
            doc.selectFirst(nextSel.substringBefore("@")) != null
        } else {
            cards.isNotEmpty()
        }
        return PagedResult(items = cards, page = page, hasNextPage = hasNext)
    }

    override suspend fun search(query: String, page: Int): PagedResult<MediaCard> {
        val template = endpoints["search"] ?: return PagedResult(emptyList(), page, false)
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val path = template
            .replace("{query}", encoded)
            .replace("{page}", page.toString())
        val url = resolveUrl(path)
        val html = fetchText(url, headers)
        val doc = Jsoup.parse(html, url)

        val cards = parseCards(doc, defaultType = manifest.supportedTypes.firstOrNull() ?: MediaType.MOVIE)
        val nextSel = selectors["nextPage"]
        val hasNext = if (!nextSel.isNullOrBlank()) {
            doc.selectFirst(nextSel.substringBefore("@")) != null
        } else {
            false
        }
        return PagedResult(items = cards, page = page, hasNextPage = hasNext)
    }

    override suspend fun getDetails(id: String, type: MediaType): MediaDetails {
        val url = buildDetailsUrl(id)
        val html = fetchText(url, headers)
        val doc = Jsoup.parse(html, url)

        val title = selectors["detailsTitle"]?.let { extractValue(doc, it) } ?: id
        val description = selectors["detailsDescription"]?.let { extractValue(doc, it) }
        val poster = selectors["detailsPoster"]?.let { extractValue(doc, it) }?.let(::resolveUrl)
        val backdrop = selectors["detailsBackdrop"]?.let { extractValue(doc, it) }?.let(::resolveUrl)
        val genres = selectors["detailsGenres"]?.let { sel ->
            doc.select(sel.substringBefore("@")).mapNotNull { el ->
                el.text().trim().takeIf { it.isNotEmpty() }
            }
        }.orEmpty()
        val year = selectors["detailsYear"]?.let { extractValue(doc, it)?.let(::extractYear) }
        val rating = selectors["detailsRating"]?.let { extractValue(doc, it)?.toDoubleOrNull() }

        val episodes = parseEpisodes(doc, season = 1)
        val seasons = if (episodes.isNotEmpty()) {
            listOf(Season(seasonNumber = 1, name = "Episodes", episodeCount = episodes.size, episodes = episodes))
        } else {
            emptyList()
        }

        return MediaDetails(
            id = id,
            sourceId = config.id,
            title = title,
            type = type,
            description = description,
            posterUrl = poster,
            backdropUrl = backdrop,
            year = year,
            rating = rating,
            genres = genres,
            seasons = seasons
        )
    }

    override suspend fun getEpisodes(id: String, season: Int): List<Episode> {
        val template = endpoints["episodes"]
        val url = if (!template.isNullOrBlank()) {
            resolveUrl(template.replace("{id}", id).replace("{season}", season.toString()))
        } else {
            buildDetailsUrl(id)
        }
        val html = fetchText(url, headers)
        val doc = Jsoup.parse(html, url)
        return parseEpisodes(doc, season)
    }

    override suspend fun getStreams(
        mediaId: String,
        type: MediaType,
        season: Int?,
        episode: Int?
    ): StreamResult {
        val url = buildDetailsUrl(mediaId)
        val html = fetchText(url, headers)
        val doc = Jsoup.parse(html, url)

        val streams = mutableListOf<StreamSource>()
        val videoSel = selectors["streamVideo"]
        if (!videoSel.isNullOrBlank()) {
            for (el in doc.select(videoSel.substringBefore("@"))) {
                val rawUrl = extractFromElement(el, videoSel) ?: continue
                val resolved = resolveUrl(rawUrl)
                streams.add(
                    StreamSource(
                        url = resolved,
                        quality = el.attr("label").ifBlank { el.attr("data-quality") }.ifBlank { "Auto" },
                        format = inferStreamFormat(resolved),
                        serverName = config.name,
                        headers = headers
                    )
                )
            }
        }

        val subtitles = mutableListOf<SubtitleTrack>()
        val subSel = selectors["subtitleTrack"]
        if (!subSel.isNullOrBlank()) {
            for (el in doc.select(subSel.substringBefore("@"))) {
                val rawSub = extractFromElement(el, subSel) ?: continue
                val resolved = resolveUrl(rawSub)
                val lang = el.attr("srclang").ifBlank { el.attr("lang") }.ifBlank { "en" }
                val label = el.attr("label").ifBlank { lang.uppercase() }
                val fmt = when {
                    resolved.contains(".srt", ignoreCase = true) -> SubtitleFormat.SRT
                    resolved.contains(".ass", ignoreCase = true) -> SubtitleFormat.ASS
                    else -> SubtitleFormat.VTT
                }
                subtitles.add(
                    SubtitleTrack(
                        url = resolved,
                        language = lang,
                        label = label,
                        format = fmt,
                        headers = headers
                    )
                )
            }
        }

        return StreamResult(streams = streams.distinctBy { it.url }, subtitles = subtitles.distinctBy { it.url })
    }

    private fun parseCards(root: Element, defaultType: MediaType): List<MediaCard> {
        val itemSel = selectors["cardItem"] ?: return emptyList()
        val titleSel = selectors["cardTitle"]
        val posterSel = selectors["cardPoster"]
        val urlSel = selectors["cardUrl"]
        val yearSel = selectors["cardYear"]
        val ratingSel = selectors["cardRating"]

        return root.select(itemSel).mapNotNull { cardEl ->
            val title = titleSel?.let { extractValue(cardEl, it) }
                ?: cardEl.text().trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val rawUrl = urlSel?.let { extractValue(cardEl, it) } ?: return@mapNotNull null
            val id = extractIdFromUrl(rawUrl)
            val poster = posterSel?.let { extractValue(cardEl, it) }?.let(::resolveUrl)
            val year = yearSel?.let { extractValue(cardEl, it) }?.let(::extractYear)
            val rating = ratingSel?.let { extractValue(cardEl, it) }?.toDoubleOrNull()

            MediaCard(
                id = id,
                sourceId = config.id,
                title = title,
                posterUrl = poster,
                type = defaultType,
                year = year,
                rating = rating
            )
        }
    }

    private fun parseEpisodes(root: Element, season: Int): List<Episode> {
        val epSel = selectors["episodeItem"] ?: return emptyList()
        val epTitleSel = selectors["episodeTitle"]
        val epUrlSel = selectors["episodeUrl"]
        return root.select(epSel).mapIndexedNotNull { idx, el ->
            val title = epTitleSel?.let { extractValue(el, it) } ?: el.text().trim().ifBlank { "Episode ${idx + 1}" }
            val rawUrl = epUrlSel?.let { extractValue(el, it) }
            val id = rawUrl?.let(::extractIdFromUrl) ?: "ep_${season}_${idx + 1}"
            Episode(
                id = id,
                seasonNumber = season,
                episodeNumber = idx + 1,
                title = title
            )
        }
    }

    private fun buildDetailsUrl(id: String): String {
        if (id.startsWith("http://") || id.startsWith("https://")) return id
        val template = endpoints["details"] ?: "/title/{id}"
        return resolveUrl(template.replace("{id}", id))
    }

    private fun extractIdFromUrl(rawUrl: String): String {
        val clean = rawUrl.trim().trimEnd('/')
        return clean.substringAfterLast('/').ifBlank { clean }
    }

    private fun resolveUrl(pathOrUrl: String): String {
        val trimmed = pathOrUrl.trim()
        val base = config.baseUrl.trimEnd('/')
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$base$trimmed"
            else -> "$base/$trimmed"
        }
    }

    private fun extractValue(root: Element, selectorExpr: String): String? {
        val css = selectorExpr.substringBefore("@").trim()
        val el = if (css.isEmpty()) root else root.selectFirst(css) ?: return null
        return extractFromElement(el, selectorExpr)
    }

    private fun extractFromElement(el: Element, selectorExpr: String): String? {
        val attrPart = selectorExpr.substringAfter("@", missingDelimiterValue = "").trim()
        if (attrPart.isEmpty()) {
            return el.text().trim().takeIf { it.isNotEmpty() }
        }
        for (attr in attrPart.split("|")) {
            val trimmedAttr = attr.trim()
            val value = if (trimmedAttr == "text") el.text().trim() else el.attr(trimmedAttr).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun extractYear(text: String): Int? =
        Regex("\\d{4}").find(text)?.value?.toIntOrNull()

    private fun inferStreamFormat(url: String): StreamFormat {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> StreamFormat.HLS
            lower.contains(".mpd") -> StreamFormat.DASH
            lower.contains(".mp4") -> StreamFormat.MP4
            else -> StreamFormat.UNKNOWN
        }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun parseConfig(rawJson: String): JsonSourceManifestConfig =
            json.decodeFromString(JsonSourceManifestConfig.serializer(), rawJson)
    }
}
