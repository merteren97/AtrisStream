package com.atrishub.atrisstream.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.ui.theme.AtrisColors

data class NavDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
)

val topLevelDestinations = listOf(
    NavDestination(Screen.Home.route, R.string.nav_home, Icons.Default.Home),
    NavDestination(Screen.Movies.route, R.string.nav_movies, Icons.Default.Movie),
    NavDestination(Screen.Series.route, R.string.nav_series, Icons.Default.Tv),
    NavDestination(Screen.Anime.route, R.string.nav_anime, Icons.Default.AutoAwesome),
    NavDestination(Screen.Search.route, R.string.nav_search, Icons.Default.Search),
    NavDestination(Screen.Watchlist.route, R.string.nav_watchlist, Icons.Default.Bookmark),
    NavDestination(Screen.Resources.route, R.string.nav_resources, Icons.Default.Extension),
    NavDestination(Screen.Settings.route, R.string.nav_settings, Icons.Default.Settings)
)

/**
 * Collapsible left-edge TV Navigation Rail. Expands smoothly when any rail item receives D-pad focus.
 */
@Composable
fun TvNavigationRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isRailFocused by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(
        targetValue = if (isRailFocused) 216.dp else 72.dp,
        animationSpec = tween(durationMillis = 180),
        label = "railWidth"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(railWidth)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(AtrisColors.Surface, AtrisColors.Background.copy(alpha = 0.96f))
                )
            )
            .onFocusChanged { isRailFocused = it.hasFocus }
            .padding(vertical = 20.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Brand emblem
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(AtrisColors.AccentCyan, AtrisColors.SecondaryIndigo)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = AtrisColors.Background,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (isRailFocused) {
                Text(
                    text = "AtrisStream",
                    color = AtrisColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        topLevelDestinations.forEach { dest ->
            val selected = currentRoute == dest.route
            RailItem(
                destination = dest,
                selected = selected,
                expanded = isRailFocused,
                onClick = { onNavigate(dest.route) }
            )
        }
    }
}

@Composable
private fun RailItem(
    destination: NavDestination,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    var itemFocused by remember { mutableStateOf(false) }

    val bgColor by animateColorAsState(
        targetValue = when {
            itemFocused -> AtrisColors.AccentCyan.copy(alpha = 0.22f)
            selected -> AtrisColors.ElevatedCard
            else -> Color.Transparent
        },
        label = "railItemBg"
    )

    val contentColor = when {
        itemFocused -> AtrisColors.AccentCyan
        selected -> AtrisColors.TextPrimary
        else -> AtrisColors.TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .then(
                if (itemFocused) {
                    Modifier.border(1.5.dp, AtrisColors.AccentCyan, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            )
            .onFocusChanged { itemFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = stringResource(destination.labelRes),
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        if (expanded) {
            Text(
                text = stringResource(destination.labelRes),
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = if (selected || itemFocused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
