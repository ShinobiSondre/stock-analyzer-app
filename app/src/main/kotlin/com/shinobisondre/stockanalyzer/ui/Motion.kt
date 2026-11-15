package com.shinobisondre.stockanalyzer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinobisondre.stockanalyzer.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * Cards fade and slide up into place, one after another (a short cascade).
 * Plays once per item — scrolling back doesn't replay it.
 */
fun Modifier.appear(index: Int = 0): Modifier = composed {
    var shown by rememberSaveable { mutableStateOf(false) }
    val a = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!shown) {
            delay(index.coerceIn(0, 10) * 45L)
            a.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
            shown = true
        }
    }
    graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 28.dp.toPx()
        val s = 0.97f + 0.03f * a.value
        scaleX = s; scaleY = s
    }
}

/** Shrinks a touch while pressed, springs back on release. Pass the same interaction source to clickable(). */
fun Modifier.pressScale(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.965f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Gentle breathing pulse (for "live" things like a quick win). */
fun Modifier.pulse(enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 1.14f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse-s")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** A number that counts up to its new value. */
@Composable
fun AnimatedNumber(value: Double, format: (Double) -> String, style: TextStyle = TextStyle.Default, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Palette.text) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) { anim.animateTo(value.toFloat(), tween(850, easing = FastOutSlowInEasing)) }
    Text(format(if (anim.isRunning) anim.value.toDouble() else value), style = style, modifier = modifier, color = color)
}

/** App start: a line chart draws itself, the name fades in, then everything lifts away. */
@Composable
fun IntroSplash(onDone: () -> Unit) {
    val draw = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        draw.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
        delay(180)
        out.animateTo(0f, tween(320, easing = LinearEasing))
        onDone()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = out.value }.background(Palette.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer { val s = 1f + (1f - out.value) * 0.08f; scaleX = s; scaleY = s; translationY = -(1f - out.value) * 40f }) {
            Canvas(Modifier.size(140.dp, 80.dp)) {
                val pts = listOf(0f to 0.78f, 0.18f to 0.62f, 0.32f to 0.70f, 0.5f to 0.42f, 0.64f to 0.50f, 0.82f to 0.22f, 1f to 0.08f)
                val p = Path().apply {
                    pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
                }
                val m = PathMeasure().apply { setPath(p, false) }
                val part = Path()
                m.getSegment(0f, m.length * draw.value, part, true)
                drawPath(part, Palette.accent, style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round))
                if (draw.value > 0.98f) {
                    val (x, y) = pts.last()
                    drawCircle(Palette.accent, 8.dp.toPx(), Offset(x * size.width, y * size.height))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("StockAnalyzer", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.graphicsLayer { alpha = draw.value })
            Text("odds, not guesses", fontSize = 13.sp, color = Palette.muted, modifier = Modifier.graphicsLayer { alpha = (draw.value - 0.4f).coerceAtLeast(0f) / 0.6f })
        }
    }
}
