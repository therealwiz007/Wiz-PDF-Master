package com.wizlabs.pdfmaster

import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

/**
 * Small motion toolkit shared by the screens. All of it animates only graphicsLayer / draw-phase
 * values (no layout, no recomposition per frame) and switches itself off under Battery Saver or
 * when the system "Remove animations" setting is on.
 */
val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

fun motionAllowed(ctx: Context): Boolean =
    ValueAnimator.areAnimatorsEnabled() &&
        ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true

/** True when decorative motion should run. Re-checked every time the screen resumes. */
@Composable
fun rememberMotionOn(): Boolean {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var on by remember { mutableStateOf(motionAllowed(ctx)) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) on = motionAllowed(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return on
}

/**
 * One clock per screen for the staggered entrance. Each section reads it in its graphicsLayer, so
 * the whole intro is a single animation and items scrolled back into view never replay it.
 */
class Intro(internal val clock: Animatable<Float, AnimationVector1D>)

private const val INTRO_MS = 1500f

@Composable
fun rememberIntro(): Intro {
    val motion = rememberMotionOn()
    val clock = remember { Animatable(if (motion) 0f else INTRO_MS) }
    LaunchedEffect(Unit) { if (clock.value < INTRO_MS) clock.animateTo(INTRO_MS, tween(INTRO_MS.toInt(), easing = LinearEasing)) }
    return remember(clock) { Intro(clock) }
}

/** Fade + rise for the [index]-th section of a screen. */
fun Modifier.rise(intro: Intro, index: Int): Modifier = graphicsLayer {
    val start = 40f + index.coerceAtMost(10) * 65f
    val p = ((intro.clock.value - start) / 520f).coerceIn(0f, 1f)
    val e = Emphasized.transform(p)
    alpha = e
    translationY = (1f - e) * 26.dp.toPx()
}

/** Scale-down on press with a springy release. Doesn't consume touches, so clicks and scrolling still work. */
fun Modifier.pressScale(pressed: Float = 0.96f): Modifier = composed {
    val s = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            scope.launch { s.animateTo(pressed, tween(90)) }
            waitForUpOrCancellation(PointerEventPass.Final)
            scope.launch { s.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 520f)) }
        }
    }.graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/** Stronger haptic for primary actions (scan, capture, save). */
@Composable
fun rememberConfirm(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
}

/** Back-out easing (overshoots a little, then settles) for snap-in effects. */
fun backOut(x: Float): Float {
    val t = x.coerceIn(0f, 1f) - 1f
    val c1 = 1.9f
    return 1f + (c1 + 1f) * t * t * t + c1 * t * t
}

/** Touch counter fed by MainActivity.dispatchTouchEvent; decorative loops settle 9 s after the last touch. */
object MotionIdle {
    const val SETTLE_NS = 9_000_000_000L
    var touches by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private var lastBump = 0L
    fun onTouch() {
        val now = System.nanoTime()
        if (now - lastBump > 1_000_000_000L) { lastBump = now; touches++ }
    }
}
