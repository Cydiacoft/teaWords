package com.tea.teawords.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tea.teawords.ui.helper.playAudio

private data class Phoneme(
    val symbol: String,
    val example: String
)

private val vowelList = listOf(
    Phoneme("i:", "see"), Phoneme("ɪ", "sit"), Phoneme("e", "bed"), Phoneme("æ", "cat"),
    Phoneme("ɜ:", "her"), Phoneme("ə", "about"), Phoneme("ʌ", "cup"), Phoneme("u:", "blue"),
    Phoneme("ʊ", "book"), Phoneme("ɔ:", "door"), Phoneme("ɒ", "hot"), Phoneme("ɑ:", "car")
)

private val diphthongList = listOf(
    Phoneme("eɪ", "day"), Phoneme("aɪ", "time"), Phoneme("ɔɪ", "boy"), Phoneme("əʊ", "go"),
    Phoneme("aʊ", "now"), Phoneme("ɪə", "near"), Phoneme("eə", "care"), Phoneme("ʊə", "tour")
)

private val consonantList = listOf(
    Phoneme("p", "pen"), Phoneme("b", "big"), Phoneme("t", "tea"), Phoneme("d", "day"),
    Phoneme("k", "key"), Phoneme("g", "go"), Phoneme("f", "fee"), Phoneme("v", "vase"),
    Phoneme("θ", "think"), Phoneme("ð", "this"), Phoneme("s", "see"), Phoneme("z", "zoo"),
    Phoneme("ʃ", "she"), Phoneme("ʒ", "vision"), Phoneme("h", "hat"), Phoneme("m", "man"),
    Phoneme("n", "no"), Phoneme("ŋ", "sing"), Phoneme("l", "light"), Phoneme("r", "red"),
    Phoneme("j", "yes"), Phoneme("w", "we"), Phoneme("tʃ", "chair"), Phoneme("dʒ", "jump")
)

private data class PhonemeGroup(
    val title: String,
    val icon: ImageVector,
    val count: Int,
    val phonemes: List<Phoneme>
)

private val groups = listOf(
    PhonemeGroup("单元音", Icons.Default.RecordVoiceOver, vowelList.size, vowelList),
    PhonemeGroup("双元音", Icons.Default.CompareArrows, diphthongList.size, diphthongList),
    PhonemeGroup("辅音", Icons.Default.TextFields, consonantList.size, consonantList)
)

@Composable
fun PhoneticsChartView(onBack: () -> Unit) {
    val context = LocalContext.current
    var playingSymbol by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playingSymbol) {
        if (playingSymbol != null) {
            kotlinx.coroutines.delay(800)
            playingSymbol = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text("音标练习", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            groups.forEach { group ->
                item {
                    PhonemeGroupCard(
                        group = group,
                        playingSymbol = playingSymbol,
                        onPlay = { symbol ->
                            playingSymbol = symbol
                            val url = getIpaAudioUrl(symbol)
                            if (url != null) playAudio(context, url)
                        }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun PhonemeGroupCard(
    group: PhonemeGroup,
    playingSymbol: String?,
    onPlay: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        group.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    group.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "共 ${group.count} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val rows = group.phonemes.chunked(4)
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { phoneme ->
                        PhonemeCard(
                            phoneme = phoneme,
                            isPlaying = playingSymbol == phoneme.symbol,
                            onClick = { onPlay(phoneme.symbol) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(4 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PhonemeCard(
    phoneme: Phoneme,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh),
        label = "pressScale"
    )

    val bgColor = when {
        isPlaying -> MaterialTheme.colorScheme.secondaryContainer
        isPressed -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    val textColor = when {
        isPlaying -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val subColor = when {
        isPlaying -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }

    Column(
        modifier = modifier
            .scale(pressScale)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            phoneme.symbol,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp
            ),
            color = textColor,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                phoneme.example,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = subColor,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.width(3.dp))
            Icon(
                Icons.Default.VolumeUp,
                contentDescription = null,
                tint = if (isPlaying) MaterialTheme.colorScheme.primary else subColor,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

private fun getIpaAudioUrl(symbol: String): String? {
    val mapping = mapOf(
        "i:" to "i:", "ɪ" to "ɪ", "e" to "e", "æ" to "æ",
        "ɜ:" to "ɜ:", "ə" to "ə", "ʌ" to "ʌ", "u:" to "u:",
        "ʊ" to "ʊ", "ɔ:" to "ɔ:", "ɒ" to "ɒ", "ɑ:" to "ɑ:",
        "eɪ" to "eɪ", "aɪ" to "aɪ", "ɔɪ" to "ɔɪ", "əʊ" to "əʊ",
        "aʊ" to "aʊ", "ɪə" to "ɪə", "eə" to "eə", "ʊə" to "ʊə",
        "p" to "p", "b" to "b", "t" to "t", "d" to "d",
        "k" to "k", "g" to "g", "f" to "f", "v" to "v",
        "θ" to "θ", "ð" to "ð", "s" to "s", "z" to "z",
        "ʃ" to "ʃ", "ʒ" to "ʒ", "h" to "h", "m" to "m",
        "n" to "n", "ŋ" to "ŋ", "l" to "l", "r" to "r",
        "j" to "j", "w" to "w", "tʃ" to "tʃ", "dʒ" to "dʒ"
    )
    val param = java.net.URLEncoder.encode(mapping[symbol] ?: symbol, "UTF-8")
    return "https://dict.youdao.com/dictvoice?audio=$param&type=1"
}
