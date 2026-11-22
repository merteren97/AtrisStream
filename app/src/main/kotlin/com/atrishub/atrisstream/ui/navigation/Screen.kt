package com.atrishub.atrisstream.ui.navigation

import android.net.Uri
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.PlayerLaunchArgs

/**
 * Top-level and detail/player navigation routes in AtrisStream.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Movies : Screen("movies")
    data object Series : Screen("series")
    data object Anime : Screen("anime")
    data object Search : Screen("search")
    data object Watchlist : Screen("watchlist")
    data object Resources : Screen("resources")
    data object Settings : Screen("settings")

    data object Details : Screen("details/{sourceId}/{mediaId}/{type}") {
        fun createRoute(sourceId: String, mediaId: String, type: MediaType): String =
            "details/${Uri.encode(sourceId)}/${Uri.encode(mediaId)}/${type.name}"
    }

    data object Player : Screen(
        "player/{sourceId}/{mediaId}/{type}/{title}?posterUrl={posterUrl}&season={season}&episode={episode}&resumeMs={resumeMs}"
    ) {
        fun createRoute(args: PlayerLaunchArgs): String {
            val encodedPoster = Uri.encode(args.posterUrl.orEmpty())
            val season = args.seasonNumber ?: -1
            val episode = args.episodeNumber ?: -1
            return "player/${Uri.encode(args.sourceId)}/${Uri.encode(args.mediaId)}/${args.type.name}/${Uri.encode(args.title)}" +
                "?posterUrl=$encodedPoster&season=$season&episode=$episode&resumeMs=${args.resumePositionMs}"
        }
    }
}
