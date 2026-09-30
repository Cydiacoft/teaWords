package com.teameow.teawords.ui.screens

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
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

internal val LocalPredictiveBackEnabled = staticCompositionLocalOf { false }
internal val LocalPageBackHandled = staticCompositionLocalOf { false }
private val PageEnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val PageExitEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/** Keep the destination below this layer mounted until back is committed. */
@Composable
internal fun PredictiveBackLayer(
    onBack: () -> Unit,
    enabled: Boolean = true,
    topLevel: Boolean = false,
    content: @Composable (requestBack: () -> Unit) -> Unit
) {
    val predictive = LocalPredictiveBackEnabled.current
    val visibility = remember { Animatable(0f) }
    val preview = remember { Animatable(0f) }
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
                visibility.animateTo(1f, tween(300, easing = PageEnterEasing))
                phase = BackPhase.Idle
            }
        }
    }
    val requestBack: () -> Unit = {
        if (latestEnabled && phase != BackPhase.Exiting && phase != BackPhase.Committed &&
            phase != BackPhase.Gesture) {
            gestureGeneration++
            animationJob?.cancel()
            phase = BackPhase.Exiting
            animationJob = scope.launch {
                visibility.animateTo(0f, tween(200, easing = PageExitEasing))
                commitBack()
            }
        }
    }

    // Keep a disabled page's back event from falling through to an underlying page or the activity.
    BackHandler(enabled = !predictive || !enabled) { if (latestEnabled) requestBack() }
    PredictiveBackHandler(enabled = enabled && predictive) { events ->
        // Finish consuming every flow, including callbacks dispatched just after disabling.
        if (!latestEnabled || phase == BackPhase.Exiting || phase == BackPhase.Committed) {
            events.collect { }
        } else {
            val generation = ++gestureGeneration
            animationJob?.cancel()
            phase = BackPhase.Gesture
            try {
                visibility.snapTo(1f)
                events.collect { event ->
                    if (generation == gestureGeneration) {
                        edge = event.swipeEdge
                        preview.snapTo(event.progress.coerceIn(0f, 1f))
                    }
                }
                // Settle outside the gesture job: another back event cannot cancel a committed exit.
                if (generation == gestureGeneration) {
                    phase = BackPhase.Exiting
                    animationJob = scope.launch {
                        visibility.animateTo(0f, tween(200, easing = PageExitEasing))
                        commitBack()
                    }
                }
            } catch (cancelled: CancellationException) {
                // An older gesture's cancellation must not reset a newer gesture.
                if (generation == gestureGeneration) {
                    phase = BackPhase.Restoring
                    animationJob = scope.launch {
                        preview.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f))
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
        // The retained source page remains visible behind this surface during transitions/previews.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val shown = visibility.value.coerceIn(0f, 1f)
                val gesture = preview.value.coerceIn(0f, 1f)
                alpha = shown
                translationX = if (topLevel) 0f else 32.dp.toPx() * (1f - shown)
                translationX += 24.dp.toPx() * gesture * if (edge == BackEventCompat.EDGE_LEFT) 1f else -1f
                translationY = if (topLevel) 12.dp.toPx() * (1f - shown) else 0f
                scaleX = (1f - 0.015f * (1f - shown)) * (1f - 0.1f * gesture)
                scaleY = scaleX
                shape = RoundedCornerShape((28f * gesture).dp)
                clip = true
            }.background(MaterialTheme.colorScheme.background)
                // Absorb taps on empty space without merging the entire page into one
                // accessibility node (clickable would swallow static text and scroll ancestry).
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        ) {
            CompositionLocalProvider(LocalPageBackHandled provides true) { content(requestBack) }
        }
    }
}
