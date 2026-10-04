package com.teameow.teawords.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay

/**
 * Shared Material 3 building blocks. Every page composes these so that spacing, radii,
 * colour roles and type scale stay identical across the app.
 */

/** Page scaffold: one TopAppBar + one lazy column, with consistent insets and spacing. */
/**
 * Bottom space the host has already reserved for this page.
 *
 * The host is the only component that knows whether the floating dock is composed right now, so it
 * also owns the single reservation for it (see MainScreen). Pages must not reserve the same space
 * again: doing that counted the system gesture inset twice and cut the content off one gesture bar
 * too early. `null` means "no host reservation" — used when a page is rendered outside the app's
 * bottom-bar layout and must fall back to its own system inset.
 */
internal val LocalReservedBottom = staticCompositionLocalOf<Dp?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeaListPage(
    title: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    navigationContent: (@Composable () -> Unit)? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    loading: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val reservedBottom = LocalReservedBottom.current
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            // Fast local reads should not flash a loading bar on every tab switch.
            delay(250)
            showLoading = true
        } else showLoading = false
    }
    Column(
        modifier.fillMaxSize()
            .then(
                // Host absent: this page is the bottom-most surface and has to clear the gesture area
                // itself. Host present: reserve exactly what the host measured for the floating dock
                // and nothing else. `navigationBars` must not be added here — the host's reservation
                // already includes the gesture bar, and stacking the two shorted the 学习 list by one
                // gesture bar, which is what left a blank strip between the content and the dock.
                if (reservedBottom == null) Modifier.navigationBarsPadding()
                else Modifier.padding(bottom = reservedBottom)
            )
            .background(containerColor)
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            },
            // The icon is never dropped while a page is busy, only disabled: removing it makes
            // TopAppBar re-centre the title, which shifts the whole bar on every tap.
            navigationIcon = {
                if (navigationContent != null) navigationContent()
                else if (onBack != null) IconButton(onClick = onBack, enabled = backEnabled) {
                    Icon(AppSymbols.ArrowBack, contentDescription = "返回")
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize()
                // The hosted dock already includes the keyboard height in reservedBottom.
                .then(if (reservedBottom == null) Modifier.imePadding() else Modifier),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (floatingActionButton != null) 88.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
        // Overlay the indicator in the existing top gap so loading never moves list items.
        if (showLoading) LinearProgressIndicator(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(4.dp).testTag("page-loading")
        )
        if (floatingActionButton != null) Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { floatingActionButton() }
        }
    }
}

/** Neutral surface card: the default container for a block of related content. */
@Composable
fun TeaCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        // Content colour is set explicitly: relying on the inherited value left cards rendering
        // their container while the text inside was drawn in a colour indistinguishable from it.
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Same container as [TeaCard] but with a title/subtitle header. */
@Composable
fun LearningCard(
    title: String,
    subtitle: String,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    TeaCard(containerColor = containerColor) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = titleStyle, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotBlank()) Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}

/** Indeterminate work indicator. Thin and inset so it never stretches edge to edge. */
@Composable
fun TeaProgressBar(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    )
}

/** Section label that separates major regions inside a page. */
@Composable
fun TeaSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

/** One number with a caption. Used in aligned metric rows. */
@Composable
fun TeaMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = valueColor)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Equal-width metric row: avoids the ragged alignment of hand-placed Columns. */
@Composable
fun TeaMetricRow(vararg metrics: Pair<String, String>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.forEach { (value, label) -> TeaMetric(value, label, Modifier.weight(1f)) }
    }
}

/** Thin progress line with full colour control, independent of the Material indicator API. */
@Composable
fun TeaProgressLine(fraction: Float, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

/** Labelled progress line used by the knowledge profile. */
@Composable
fun TeaProgressRow(
    label: String,
    count: Int,
    total: Int,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$count",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        TeaProgressLine(if (total <= 0) 0f else count.toFloat() / total, accent)
    }
}

/** Small rounded descriptor chip. */
@Composable
fun TeaChip(text: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Surface(
        modifier,
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.10f),
        contentColor = accent
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** Caption line for caveats and evidence disclaimers. */
@Composable
fun TeaCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Progress line for the screening queue, with the remaining count on the right. */
@Composable
fun TeaScreenProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已排查 $done", style = MaterialTheme.typography.labelLarge)
            Text(
                "剩余 ${(total - done).coerceAtLeast(0)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The counts change on every tap; single-digit steps must not shift the page around.
        TeaProgressLine(fraction = if (total <= 0) 1f else done.toFloat() / total, color = MaterialTheme.colorScheme.primary)
    }
}

/** Centred empty state with an optional icon-free headline. */
@Composable
fun TeaEmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
