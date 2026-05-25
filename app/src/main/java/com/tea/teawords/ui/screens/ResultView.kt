package com.tea.teawords.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tea.teawords.data.*
import com.tea.teawords.ui.helper.playAudio

@Composable
fun ResultView(
    query: String,
    isSearching: Boolean,
    result: WordResponse?,
    translation: String?,
    levels: List<String>,
    pronunciationDialect: PronunciationDialect,
    isStarred: Boolean,
    onStarToggle: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.weight(1f)
            ) {
                levels.forEach { lvl ->
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = lvl,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.secondary,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onStarToggle) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Save word",
                        tint = if (isStarred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
                val queryFontSize = when {
                    query.length > 100 -> 16.sp
                    query.length > 20 -> 20.sp
                    else -> 32.sp
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            MaterialTheme.shapes.large
                        )
                ) {
                    Column {
                        // Original Content
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = query,
                                fontSize = queryFontSize,
                                lineHeight = queryFontSize * 1.4f,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val matchedPhonetic = selectPhoneticByDialect(result?.phonetics, pronunciationDialect)
                            val phoneticsText = matchedPhonetic?.text ?: result?.phonetic
                            if (!phoneticsText.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = phoneticsText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    val audioUrl = matchedPhonetic?.audio?.takeIf { it.isNotBlank() }
                                    if (!audioUrl.isNullOrEmpty()) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { playAudio(context, audioUrl) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = pronunciationDialect.label,
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Translation Content (Integrated into the same Card)
                        if (translation != null && translation.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFE3F2FD)) // Light blue tint background
                                    .padding(16.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "中文翻译",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF1976D2) // Darker blue for label
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = translation,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (result != null && result.meanings != null) {
                items(result.meanings) { meaning ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = meaning.partOfSpeech?.uppercase() ?: "",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    letterSpacing = 0.1.sp
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        meaning.definitions?.forEach { def ->
                            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                Text(
                                    text = def.definition ?: "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (!def.example.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "\u201c${def.example}\u201d",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }
}

private fun selectPhoneticByDialect(
    phonetics: List<Phonetic>?,
    dialect: PronunciationDialect
): Phonetic? {
    if (phonetics.isNullOrEmpty()) return null

    val urlMarkers = when (dialect) {
        PronunciationDialect.US -> listOf("us", "en-us", "american")
        PronunciationDialect.UK -> listOf("uk", "gb", "en-gb", "british")
    }

    val textMarkers = when (dialect) {
        PronunciationDialect.US -> listOf("/oʊ", "/o", "/ɑ")
        PronunciationDialect.UK -> listOf("/əʊ", "/ɒ")
    }

    // Pass 1: match by audio URL
    phonetics.firstOrNull { p ->
        val url = p.audio?.lowercase() ?: ""
        urlMarkers.any { url.contains(it) }
    }?.let { return it }

    // Pass 2: match by phonetic text pattern
    phonetics.firstOrNull { p ->
        val text = p.text ?: ""
        textMarkers.any { text.contains(it) }
    }?.let { return it }

    // Fallback: first entry with text, then first with audio
    return phonetics.firstOrNull { !it.text.isNullOrEmpty() }
        ?: phonetics.firstOrNull { !it.audio.isNullOrBlank() }
}
