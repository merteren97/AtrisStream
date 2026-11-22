package com.atrishub.atrisstream.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.core.model.MediaCard

/**
 * D-pad focusable poster card with 2dp cyan focus ring, scale animation,
 * gradient overlay, optional rating/year badge, and optional watch progress bar.
 */
@Composable
fun TvPosterCard(
    item: MediaCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 148.dp,
    progressFraction: Float? = null,
    badgeText: String? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "posterScale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) AtrisColors.AccentCyan else AtrisColors.CardBorder.copy(alpha = 0.45f),
        animationSpec = tween(durationMillis = 150),
        label = "posterBorder"
    )

    Column(
        modifier = modifier
            .width(width)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(AtrisColors.ElevatedCard)
                .border(
                    width = if (isFocused) 2.5.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = AtrisColors.TextSecondary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // Bottom gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                        )
                    )
            )

            // Top-right rating or custom badge
            val topBadge = badgeText ?: item.rating?.let { "★ ${String.format("%.1f", it)}" }
            if (!topBadge.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = topBadge,
                        color = AtrisColors.AccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Bottom watch progress bar
            if (progressFraction != null && progressFraction > 0f) {
                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter),
                    color = AtrisColors.AccentCyan,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) AtrisColors.AccentCyan else AtrisColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val meta = listOfNotNull(
            item.year?.toString(),
            item.subtitle ?: item.type.name
        ).joinToString(" • ")

        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * D-pad first action button with crisp 2dp cyan focus border and scale animation.
 */
@Composable
fun TvFocusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPrimary: Boolean = false,
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(130),
        label = "btnScale"
    )

    val containerColor = when {
        !enabled -> AtrisColors.ElevatedCard.copy(alpha = 0.5f)
        isFocused && isPrimary -> AtrisColors.AccentCyan
        isFocused -> AtrisColors.ElevatedCard
        isPrimary -> AtrisColors.AccentCyan.copy(alpha = 0.88f)
        else -> AtrisColors.Surface
    }

    val contentColor = when {
        !enabled -> AtrisColors.TextSecondary
        isPrimary -> AtrisColors.Background
        isFocused -> AtrisColors.AccentCyan
        else -> AtrisColors.TextPrimary
    }

    val borderColor = if (isFocused) AtrisColors.AccentCyan else AtrisColors.CardBorder

    Surface(
        modifier = modifier
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(if (isFocused) 2.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = text,
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * D-pad focusable pill chip for source selectors, season tabs, and settings options.
 */
@Composable
fun TvFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(120),
        label = "chipScale"
    )

    val bg = when {
        selected -> AtrisColors.AccentCyan.copy(alpha = 0.2f)
        isFocused -> AtrisColors.ElevatedCard
        else -> AtrisColors.Surface
    }
    val border = when {
        isFocused -> AtrisColors.AccentCyan
        selected -> AtrisColors.SecondaryIndigo
        else -> AtrisColors.CardBorder
    }
    val textColor = when {
        isFocused -> AtrisColors.AccentCyan
        selected -> AtrisColors.TextPrimary
        else -> AtrisColors.TextSecondary
    }

    Surface(
        modifier = modifier
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = bg,
        border = BorderStroke(if (isFocused) 2.dp else 1.dp, border)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines = 1
        )
    }
}

/**
 * Horizontal titled section row of [TvPosterCard]s.
 */
@Composable
fun TvSectionRow(
    title: String,
    items: List<MediaCard>,
    onItemClick: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
    progressProvider: ((MediaCard) -> Float?)? = null
) {
    if (items.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items, key = { "${it.sourceId}_${it.id}" }) { card ->
                TvPosterCard(
                    item = card,
                    onClick = { onItemClick(card) },
                    progressFraction = progressProvider?.invoke(card)
                )
            }
        }
    }
}

/**
 * Shimmer-like loading skeleton row for TV home & browse screens.
 */
@Composable
fun TvLoadingSkeletonRow(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AtrisColors.ElevatedCard)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(6) {
                Box(
                    modifier = Modifier
                        .width(148.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AtrisColors.ElevatedCard)
                )
            }
        }
    }
}

/**
 * Centered error banner with D-pad focusable Retry button.
 */
@Composable
fun TvErrorBanner(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AtrisColors.Surface)
            .border(1.dp, AtrisColors.Error.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = AtrisColors.TextPrimary
        )
        if (onRetry != null) {
            TvFocusButton(
                text = stringResource(R.string.action_retry),
                icon = Icons.Default.Refresh,
                isPrimary = true,
                onClick = onRetry
            )
        }
    }
}
