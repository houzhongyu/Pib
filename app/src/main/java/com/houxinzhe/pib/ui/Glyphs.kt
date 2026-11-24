package com.houxinzhe.pib.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.houxinzhe.pib.R

/**
 * Tab-bar glyphs drawn from the same vector paths as the iOS DailyTabItem
 * (28×24 pt canvas): arched door for 首页, PIB bird-in-fish for the companion tab.
 */
object Glyphs {
    private fun stroke(w: Float) = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

    private val doorFrame = Path().apply {
        moveTo(6f, 20f); lineTo(6f, 11f)
        quadraticTo(6f, 3f, 14f, 3f)
        quadraticTo(22f, 3f, 22f, 11f)
        lineTo(22f, 20f)
        moveTo(4.5f, 20f); lineTo(23.5f, 20f)
    }
    private val doorPanel = Path().apply {
        moveTo(9.75f, 20f); lineTo(9.75f, 9.55f); lineTo(17.9f, 6.6f); lineTo(17.9f, 17.35f); close()
    }
    private val doorHandle = Path().apply { addOval(Rect(14.5f, 12.25f, 14.5f + 1.65f, 12.25f + 1.65f)) }

    private val fish = Path().apply {
        moveTo(2.3f, 12.4f)
        cubicTo(2.3f, 7.45f, 7f, 3.8f, 12.5f, 4.75f)
        cubicTo(16.25f, 5.4f, 19.25f, 9.25f, 21.5f, 8.6f)
        cubicTo(23.6f, 8.6f, 24.55f, 7.55f, 24.25f, 6.4f)
        cubicTo(23.95f, 5.2f, 21.45f, 5.05f, 20.25f, 6.45f)
        quadraticTo(20.55f, 4.9f, 22.15f, 4.35f)
        quadraticTo(20.35f, 3.45f, 19.35f, 4.75f)
        cubicTo(19.7f, 2.65f, 21.85f, 2f, 23.4f, 3.2f)
        cubicTo(26.5f, 4.8f, 27f, 10f, 26.1f, 13.75f)
        cubicTo(25f, 18.8f, 19.25f, 21.9f, 14.2f, 21.55f)
        cubicTo(8.15f, 21.4f, 2.3f, 17.65f, 2.3f, 12.4f)
        close()
    }
    private val bird = Path().apply {
        moveTo(6.55f, 11.7f)
        cubicTo(8.1f, 9.25f, 10.7f, 8.5f, 12.8f, 9.7f)
        cubicTo(15.8f, 11.45f, 17.4f, 12.5f, 21.45f, 10.9f)
        quadraticTo(20.35f, 12.7f, 17.85f, 13.8f)
        quadraticTo(19.25f, 13.95f, 21.2f, 12.95f)
        cubicTo(19.45f, 15.6f, 16.25f, 17.25f, 13.35f, 17.15f)
        cubicTo(10.85f, 17.05f, 9.7f, 15.8f, 9.25f, 13.65f)
        quadraticTo(8.75f, 11.1f, 6.55f, 11.7f)
        close()
    }
    private val birdEye = Path().apply { addOval(Rect(9.9f, 10.4f, 9.9f + 1.5f, 10.4f + 1.5f)) }

    private fun DrawScope.inGlyphSpace(block: DrawScope.() -> Unit) =
        scale(size.width / 28f, size.height / 24f, pivot = Offset.Zero) { block() }

    fun DrawScope.door(color: Color, selected: Boolean) = inGlyphSpace {
        drawPath(doorFrame, color, style = stroke(1.55f))
        if (selected) {
            val p = Path().apply { addPath(doorPanel); addPath(doorHandle); fillType = PathFillType.EvenOdd }
            drawPath(p, color)
        } else {
            drawPath(doorPanel, color, style = stroke(1.55f))
            drawPath(doorHandle, color)
        }
    }

    fun DrawScope.birdFish(color: Color, selected: Boolean) = inGlyphSpace {
        drawPath(fish, color, style = stroke(1.05f))
        if (selected) {
            val p = Path().apply { addPath(bird); addPath(birdEye); fillType = PathFillType.EvenOdd }
            drawPath(p, color)
        } else {
            drawPath(bird, color, style = stroke(1.2f))
            drawPath(birdEye, color)
        }
    }

    /** 2×2 circle grid, like SF Symbol circle.grid.2x2. */
    fun DrawScope.grid(color: Color, selected: Boolean) {
        val r = size.minDimension * 0.19f
        val gap = size.minDimension * 0.07f
        val cx = size.width / 2f; val cy = size.height / 2f
        listOf(-1, 1).forEach { dx -> listOf(-1, 1).forEach { dy ->
            val c = Offset(cx + dx * (r + gap / 2f + r / 10f), cy + dy * (r + gap / 2f + r / 10f))
            if (selected) drawCircle(color, r, c) else drawCircle(color, r - 0.8f, c, style = Stroke(width = size.minDimension * 0.075f))
        } }
    }
}

@Composable
fun DoorGlyph(color: Color, selected: Boolean, modifier: Modifier = Modifier) =
    Canvas(modifier.size(28.dp, 24.dp)) { with(Glyphs) { door(color, selected) } }

@Composable
fun BirdFishGlyph(color: Color, selected: Boolean, modifier: Modifier = Modifier) =
    Canvas(modifier.size(28.dp, 24.dp)) { with(Glyphs) { birdFish(color, selected) } }

@Composable
fun GridGlyph(color: Color, selected: Boolean, modifier: Modifier = Modifier) =
    Canvas(modifier.size(24.dp, 24.dp)) { with(Glyphs) { grid(color, selected) } }

/** Pib's avatar: the PIB bird-and-fish artwork, round. */
@Composable
fun PibAvatar(size: Dp = 34.dp, modifier: Modifier = Modifier) =
    Image(
        painter = painterResource(R.drawable.pib_avatar),
        contentDescription = "Pib",
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
    )

/** Subtle floating + breathing avatar for the Companion empty state. */
@Composable
fun BreathingPibAvatar(size: Dp = 88.dp) {
    val inf = rememberInfiniteTransition(label = "pibBreath")
    val dy by inf.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dy",
    )
    val sc by inf.animateFloat(
        initialValue = 0.985f, targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sc",
    )
    PibAvatar(size = size, modifier = Modifier.offset(y = dy.dp).scale(sc))
}

/** SF Symbol `brain`-style two-hemisphere vector glyph (matches iOS AssistantModelButton). */
@Composable
fun BrainGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(23.dp, 20.dp)) {
        scale(size.width / 24f, size.height / 20f, pivot = Offset.Zero) {
            val s = Stroke(width = 1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val left = Path().apply {
                moveTo(11.6f, 4.2f)
                cubicTo(11.6f, 2.3f, 8.2f, 1.8f, 6.8f, 3.6f)
                cubicTo(4.4f, 3.4f, 2.7f, 5.4f, 3.3f, 7.6f)
                cubicTo(1.6f, 8.9f, 1.7f, 11.9f, 3.6f, 13.1f)
                cubicTo(3.2f, 15.5f, 5.4f, 17.6f, 7.8f, 17.1f)
                cubicTo(9.4f, 18.4f, 11.6f, 17.6f, 11.6f, 15.4f)
                lineTo(11.6f, 4.2f)
            }
            val right = Path().apply {
                moveTo(12.4f, 4.2f)
                cubicTo(12.4f, 2.3f, 15.8f, 1.8f, 17.2f, 3.6f)
                cubicTo(19.6f, 3.4f, 21.3f, 5.4f, 20.7f, 7.6f)
                cubicTo(22.4f, 8.9f, 22.3f, 11.9f, 20.4f, 13.1f)
                cubicTo(20.8f, 15.5f, 18.6f, 17.6f, 16.2f, 17.1f)
                cubicTo(14.6f, 18.4f, 12.4f, 17.6f, 12.4f, 15.4f)
                lineTo(12.4f, 4.2f)
            }
            val folds = Path().apply {
                moveTo(6.2f, 7.8f); quadraticTo(8.5f, 6.8f, 9.8f, 8.6f)
                moveTo(5.6f, 12.2f); quadraticTo(8.1f, 13.3f, 9.9f, 11.8f)
                moveTo(17.8f, 7.8f); quadraticTo(15.5f, 6.8f, 14.2f, 8.6f)
                moveTo(18.4f, 12.2f); quadraticTo(15.9f, 13.3f, 14.1f, 11.8f)
            }
            drawPath(left, color, style = s)
            drawPath(right, color, style = s)
            drawPath(folds, color, style = Stroke(width = 1.3f, cap = StrokeCap.Round))
        }
    }
}

/** Animated 4-bar waveform shown while holding/recording voice input, matching iOS `waveform`. */
@Composable
fun WaveformGlyph(color: Color, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "wave")
    val p1 by inf.animateFloat(0.35f, 0.95f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "w1")
    val p2 by inf.animateFloat(0.90f, 0.30f, infiniteRepeatable(tween(360), RepeatMode.Reverse), label = "w2")
    Canvas(modifier.size(22.dp, 20.dp)) {
        val bars = listOf(p1, p2, p1 * 0.85f + 0.1f, p2 * 0.8f + 0.15f)
        val stroke = 2.4.dp.toPx()
        bars.forEachIndexed { i, h ->
            val x = size.width * (0.18f + i * 0.21f)
            val half = size.height * 0.44f * h
            drawLine(color, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
