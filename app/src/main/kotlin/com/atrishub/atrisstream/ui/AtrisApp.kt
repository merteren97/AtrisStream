package com.atrishub.atrisstream.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.PlayerLaunchArgs
import com.atrishub.atrisstream.di.AppContainer
import com.atrishub.atrisstream.ui.navigation.Screen
import com.atrishub.atrisstream.ui.navigation.TvNavigationRail
import com.atrishub.atrisstream.ui.screens.browse.BrowseScreen
import com.atrishub.atrisstream.ui.screens.browse.BrowseViewModel
import com.atrishub.atrisstream.ui.screens.details.DetailsScreen
import com.atrishub.atrisstream.ui.screens.details.DetailsViewModel
import com.atrishub.atrisstream.ui.screens.home.HomeScreen
import com.atrishub.atrisstream.ui.screens.home.HomeViewModel
import com.atrishub.atrisstream.ui.screens.player.PlayerRoute
import com.atrishub.atrisstream.ui.screens.player.PlayerViewModel
import com.atrishub.atrisstream.ui.screens.resources.ResourcesScreen
import com.atrishub.atrisstream.ui.screens.resources.ResourcesViewModel
import com.atrishub.atrisstream.ui.screens.search.SearchScreen
import com.atrishub.atrisstream.ui.screens.search.SearchViewModel
import com.atrishub.atrisstream.ui.screens.settings.SettingsScreen
import com.atrishub.atrisstream.ui.screens.settings.SettingsViewModel
import com.atrishub.atrisstream.ui.screens.watchlist.WatchlistScreen
import com.atrishub.atrisstream.ui.screens.watchlist.WatchlistViewModel
import com.atrishub.atrisstream.ui.theme.AtrisColors

@Composable
fun AtrisApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isPlayerRoute = currentRoute?.startsWith("player/") == true

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AtrisColors.Background)
    ) {
        if (!isPlayerRoute) {
            TvNavigationRail(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    if (currentRoute != route) {
                        navController.navigate(route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route
            ) {
                composable(Screen.Home.route) {
                    val vm: HomeViewModel = viewModel(
                        factory = HomeViewModel.Factory(container.sourceManager, container.repository)
                    )
                    HomeScreen(
                        viewModel = vm,
                        onOpenDetails = { sourceId, mediaId, type ->
                            navController.navigate(Screen.Details.createRoute(sourceId, mediaId, type))
                        },
                        onPlayDirect = { args ->
                            navController.navigate(Screen.Player.createRoute(args))
                        }
                    )
                }

                composable(Screen.Movies.route) {
                    val vm: BrowseViewModel = viewModel(
                        key = "browse_movie",
                        factory = BrowseViewModel.Factory(MediaType.MOVIE, container.sourceManager)
                    )
                    BrowseScreen(
                        viewModel = vm,
                        onOpenDetails = { sourceId, mediaId, type ->
                            navController.navigate(Screen.Details.createRoute(sourceId, mediaId, type))
                        }
                    )
                }

                composable(Screen.Series.route) {
                    val vm: BrowseViewModel = viewModel(
                        key = "browse_tv",
                        factory = BrowseViewModel.Factory(MediaType.TV, container.sourceManager)
                    )
                    BrowseScreen(
                        viewModel = vm,
                        onOpenDetails = { sourceId, mediaId, type ->
                            navController.navigate(Screen.Details.createRoute(sourceId, mediaId, type))
                        }
                    )
                }

                composable(Screen.Anime.route) {
                    val vm: BrowseViewModel = viewModel(
                        key = "browse_anime",
                        factory = BrowseViewModel.Factory(MediaType.ANIME, container.sourceManager)
                    )
                    BrowseScreen(
                        viewModel = vm,
                        onOpenDetails = { sourceId, mediaId, type ->
                            navController.navigate(Screen.Details.createRoute(sourceId, mediaId, type))
                        }
                    )
                }

                composable(Screen.Search.route) {
                    val vm: SearchViewModel = viewModel(
                        factory = SearchViewModel.Factory(container.sourceManager)
                    )
                    SearchScreen(
                        viewModel = vm,
                        onOpenDetails = { sourceId, mediaId, type ->
                            navController.navigate(Screen.Details.createRoute(sourceId, mediaId, type))
                        }
                    )
                }

                composable(Screen.Watchlist.route) {
                    val vm: WatchlistViewModel = viewModel(
                        factory = WatchlistViewModel.Factory(container.repository)
                    )
                    WatchlistScreen(
                        viewModel = vm,
                        onOpenDetails = { sourceId, mediaId, type ->
                            navController.navigate(Screen.Details.createRoute(sourceId, mediaId, type))
                        }
                    )
                }

                composable(Screen.Resources.route) {
                    val vm: ResourcesViewModel = viewModel(
                        factory = ResourcesViewModel.Factory(container.sourceManager, container.lanPasteServer)
                    )
                    ResourcesScreen(viewModel = vm)
                }

                composable(Screen.Settings.route) {
                    val vm: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.Factory(container.preferencesRepository)
                    )
                    SettingsScreen(viewModel = vm)
                }

                composable(
                    route = Screen.Details.route,
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("mediaId") { type = NavType.StringType },
                        navArgument("type") { type = NavType.StringType }
                    )
                ) { entry ->
                    val sourceId = Uri.decode(entry.arguments?.getString("sourceId").orEmpty())
                    val mediaId = Uri.decode(entry.arguments?.getString("mediaId").orEmpty())
                    val type = runCatching {
                        MediaType.valueOf(entry.arguments?.getString("type") ?: MediaType.MOVIE.name)
                    }.getOrDefault(MediaType.MOVIE)

                    val vm: DetailsViewModel = viewModel(
                        key = "details_${sourceId}_$mediaId",
                        factory = DetailsViewModel.Factory(
                            sourceId = sourceId,
                            mediaId = mediaId,
                            mediaType = type,
                            sourceManager = container.sourceManager,
                            repository = container.repository
                        )
                    )
                    DetailsScreen(
                        viewModel = vm,
                        onPlay = { args -> navController.navigate(Screen.Player.createRoute(args)) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Screen.Player.route,
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("mediaId") { type = NavType.StringType },
                        navArgument("type") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                        navArgument("posterUrl") { type = NavType.StringType; defaultValue = "" },
                        navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                        navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                        navArgument("resumeMs") { type = NavType.LongType; defaultValue = 0L }
                    )
                ) { entry ->
                    val args = entry.arguments!!
                    val sourceId = Uri.decode(args.getString("sourceId").orEmpty())
                    val mediaId = Uri.decode(args.getString("mediaId").orEmpty())
                    val type = runCatching {
                        MediaType.valueOf(args.getString("type") ?: MediaType.MOVIE.name)
                    }.getOrDefault(MediaType.MOVIE)
                    val title = Uri.decode(args.getString("title").orEmpty())
                    val posterUrl = Uri.decode(args.getString("posterUrl").orEmpty()).ifBlank { null }
                    val season = args.getInt("season").takeIf { it > 0 }
                    val episode = args.getInt("episode").takeIf { it > 0 }
                    val resumeMs = args.getLong("resumeMs", 0L)

                    val launchArgs = PlayerLaunchArgs(
                        sourceId = sourceId,
                        mediaId = mediaId,
                        type = type,
                        title = title,
                        posterUrl = posterUrl,
                        seasonNumber = season,
                        episodeNumber = episode,
                        resumePositionMs = resumeMs
                    )

                    val vm: PlayerViewModel = viewModel(
                        key = "player_${sourceId}_${mediaId}_${season}_${episode}",
                        factory = PlayerViewModel.Factory(
                            initialArgs = launchArgs,
                            sourceManager = container.sourceManager,
                            repository = container.repository,
                            preferencesRepository = container.preferencesRepository
                        )
                    )
                    PlayerRoute(
                        viewModel = vm,
                        okHttpClient = container.networkClient.baseClient,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
