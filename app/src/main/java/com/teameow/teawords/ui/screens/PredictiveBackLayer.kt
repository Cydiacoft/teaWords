package com.teameow.teawords.ui.screens

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private enum class BackPhase { Entering, Idle, Gesture, Restoring, Exiting, Committed }

/** Keep the destination below this layer mounted until back is committed. */
@Composable
internal fun PredictiveBackLayer(
    onBack: () -> Unit,
    enabled: Boolean = true,
    content: @Composable (requestBack: () -> Unit) -> Unit
) {
    val motion = MaterialTheme.motionScheme
    val progress = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val latestBack by rememberUpdatedState(onBack)
    var phase by remember { mutableStateOf(BackPhase.Entering) }
    var gestureGeneration by remember { mutableIntStateOf(0) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val latestEnabled by rememberUpdatedState(enabled)
    val commitBack = {
        if (phase != BackPhase.Committed) {
            phase = BackPhase.Committed
            latestBack()
        }
    }
    LaunchedEffect(Unit) {
        if (phase == BackPhase.Entering) {
            animationJob = scope.launch {
                progress.animateTo(0f, motion.defaultSpatialSpec())
                phase = BackPhase.Idle
            }
        }
    }
    val requestBack: () -> Unit = {
        if (latestEnabled && phase != BackPhase.Exiting && phase != BackPhase.Committed &&
            phase != BackPhase.Gesture) {
            gestureGeneration++
            animationJob?.cancel()
            if (progress.value == 0f) edge = BackEventCompat.EDGE_LEFT
            phase = BackPhase.Exiting
            animationJob = scope.launch {
                progress.animateTo(1f, motion.defaultSpatialSpec())
                commitBack()
            }
        }
    }

    PredictiveBackHandler(enabled = enabled) { events ->
        // Finish consuming every flow, including callbacks dispatched just after disabling.
        if (!latestEnabled || phase == BackPhase.Exiting || phase == BackPhase.Committed) {
            events.collect { }
        } else {
            val generation = ++gestureGeneration
            animationJob?.cancel()
            phase = BackPhase.Gesture
            try {
                events.collect { event ->
                    if (generation == gestureGeneration) {
                        edge = event.swipeEdge
                        progress.snapTo(event.progress.coerceIn(0f, 1f) * 0.35f)
                    }
                }
                // Settle outside the gesture job: another back event cannot cancel a committed exit.
                if (generation == gestureGeneration) {
                    phase = BackPhase.Exiting
                    animationJob = scope.launch {
                        progress.animateTo(1f, motion.fastSpatialSpec())
                        commitBack()
                    }
                }
            } catch (cancelled: CancellationException) {
                // An older gesture's cancellation must not reset a newer gesture.
                if (generation == gestureGeneration) {
                    phase = BackPhase.Restoring
                    animationJob = scope.launch {
                        progress.animateTo(0f, motion.fastSpatialSpec())
                        phase = BackPhase.Idle
                    }
                }
                throw cancelled
            }
        }
    }

    val busy = phase != BackPhase.Idle
    // A fixed hit area prevents taps reaching the source through a moving layer.
    Box(Modifier.fillMaxSize().pointerInput(busy) {
        if (busy) awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }) {
        // Backdrop behind the moving layer. The enter/exit animation shrinks this layer to 94%, and
        // without this the letterbox gap exposes whatever is underneath — a white band that appears
        // to be a translucent overlay. The backdrop is never transformed, so the layer stays opaque.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val amount = progress.value.coerceIn(0f, 1f)
                translationX = size.width * amount * if (edge == BackEventCompat.EDGE_LEFT) 1f else -1f
                scaleX = 1f - 0.06f * amount
                scaleY = scaleX
                shape = RoundedCornerShape((24f * amount).dp)
                clip = true
            }.background(MaterialTheme.colorScheme.background)
                // Absorb taps on empty space without merging the entire page into one
                // accessibility node (clickable would swallow static text and scroll ancestry).
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        ) {
            content(requestBack)
        }
    }
}
