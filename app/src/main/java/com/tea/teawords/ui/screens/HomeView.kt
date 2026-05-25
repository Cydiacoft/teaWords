package com.tea.teawords.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tea.teawords.data.HistoryItem

@Composable
fun HomeView(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    suggestions: List<String>,
    isInputFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    historyList: List<HistoryItem>,
    homeTitle: String,
    homeSubtitle: String,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onMenuClick: () -> Unit,
    onPersonClick: () -> Unit,
    wallpaperUrl: String? = null
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val animatedScale by animateFloatAsState(
        targetValue = if (isInputFocused) 1.01f else 1f,
        animationSpec = tween(200)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (wallpaperUrl != null) {
            AsyncImage(
                model = wallpaperUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            )
        }

        val primaryTextColor = if (wallpaperUrl != null) Color.White else MaterialTheme.colorScheme.primary
        val secondaryTextColor = if (wallpaperUrl != null) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .heightIn(min = 56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = if (wallpaperUrl != null) Color.White else MaterialTheme.colorScheme.outline)
                }
                Text(
                    text = "teawords",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        letterSpacing = 0.05.sp
                    ),
                    color = if (wallpaperUrl != null) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
                IconButton(onClick = onPersonClick) {
                    Icon(Icons.Default.Person, contentDescription = "Profile", tint = if (wallpaperUrl != null) Color.White else MaterialTheme.colorScheme.outline)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val contentSpacing by animateDpAsState(
                    targetValue = if (isInputFocused) 32.dp else 80.dp,
                    animationSpec = tween(400)
                )
                Spacer(modifier = Modifier.height(contentSpacing))

                AnimatedVisibility(
                    visible = !isInputFocused || searchQuery.isEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = homeTitle,
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 36.sp
                            ),
                            color = primaryTextColor,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = homeSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryTextColor,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(48.dp))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(scaleX = animatedScale, scaleY = animatedScale)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                        .border(
                            1.dp,
                            if (isInputFocused) MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            MaterialTheme.shapes.extraLarge
                        )
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .animateContentSize()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isInputFocused) {
                            IconButton(onClick = {
                                onQueryChange("")
                                focusManager.clearFocus()
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Exit Search",
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        } else {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextField(
                            value = searchQuery,
                            onValueChange = onQueryChange,
                            placeholder = {
                                Text(
                                    "键入单词或长句...",
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.primary,
                                unfocusedTextColor = MaterialTheme.colorScheme.primary
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                onSearch()
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { onFocusChange(it.isFocused) }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = suggestions.isNotEmpty() && isInputFocused,
                    enter = fadeIn(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)) + expandVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)),
                    exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(animationSpec = tween(200))
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                    MaterialTheme.shapes.large
                                )
                        ) {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                suggestions.forEach { word ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onQueryChange(word)
                                                onSearch()
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = word,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = historyList.isNotEmpty() && (suggestions.isEmpty() || !isInputFocused),
                    enter = fadeIn(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)) + expandVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)),
                    exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(animationSpec = tween(200))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(48.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最近查阅",
                                style = MaterialTheme.typography.labelMedium,
                                color = secondaryTextColor
                            )
                            Text(
                                text = "清空",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                                color = secondaryTextColor,
                                modifier = Modifier.clickable { onClearHistory() }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            historyList.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onHistoryClick(item.word) }
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = item.word,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                            color = primaryTextColor
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.translation,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = secondaryTextColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Go",
                                        tint = if (wallpaperUrl != null) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                                HorizontalDivider(color = if (wallpaperUrl != null) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
