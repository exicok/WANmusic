package com.example.music

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

private data class RandomArtworkDot(
    val x: Float,
    val y: Float,
    val baseDepth: Float,
    val jumpPhase: Float,
    val sizeJitter: Float,
    val color: Color
)

object AlbumDotMatrixSettings {
    private const val PREFS = "music_prefs"
    var enabled by mutableStateOf(false)
        private set
    var columns by mutableIntStateOf(36)
        private set
    var dotScale by mutableFloatStateOf(0.82f)
        private set
    var rotationEnabled by mutableStateOf(true)
        private set
    var rotationSpeed by mutableFloatStateOf(0.45f)
        private set
    var depth by mutableFloatStateOf(0.55f)
        private set
    var visualizerDepthEnabled by mutableStateOf(true)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("album_dot_matrix_enabled", false)
        columns = prefs.getInt("album_dot_matrix_columns", 36).coerceIn(12, 80)
        dotScale = prefs.getFloat("album_dot_matrix_scale", 0.82f).coerceIn(0.35f, 1f)
        rotationEnabled = prefs.getBoolean("album_dot_matrix_rotation", true)
        rotationSpeed = prefs.getFloat("album_dot_matrix_rotation_speed", 0.45f).coerceIn(0.1f, 1.5f)
        depth = prefs.getFloat("album_dot_matrix_depth", 0.55f).coerceIn(0f, 1f)
        visualizerDepthEnabled = prefs.getBoolean("album_dot_matrix_visualizer_depth", true)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("album_dot_matrix_enabled", value)
        }
    }

    fun setColumns(context: Context, value: Int) {
        columns = value.coerceIn(12, 80)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt("album_dot_matrix_columns", columns)
        }
    }

    fun setDotScale(context: Context, value: Float) {
        dotScale = value.coerceIn(0.35f, 1f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putFloat("album_dot_matrix_scale", dotScale)
        }
    }

    fun setRotationEnabled(context: Context, value: Boolean) {
        rotationEnabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("album_dot_matrix_rotation", value) }
    }

    fun setRotationSpeed(context: Context, value: Float) {
        rotationSpeed = value.coerceIn(0.1f, 1.5f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putFloat("album_dot_matrix_rotation_speed", rotationSpeed) }
    }

    fun setDepth(context: Context, value: Float) {
        depth = value.coerceIn(0f, 1f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putFloat("album_dot_matrix_depth", depth) }
    }

    fun setVisualizerDepthEnabled(context: Context, value: Boolean) {
        visualizerDepthEnabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("album_dot_matrix_visualizer_depth", value) }
    }
}

@Composable
fun DotMatrixArtwork(
    bitmap: Bitmap,
    visualizerEnergy: Float = 0f,
    modifier: Modifier = Modifier
) {
    var manualYaw by remember { mutableFloatStateOf(-45f) }
    var manualPitch by remember { mutableFloatStateOf(12f) }
    val requestedColumns = AlbumDotMatrixSettings.columns
    val requestedDotScale = AlbumDotMatrixSettings.dotScale
    val randomDots = remember(bitmap, requestedColumns) {
        val random = Random(bitmap.generationId * 31 + requestedColumns)
        val dotCount = requestedColumns * requestedColumns
        List(dotCount) {
            val x = random.nextFloat()
            val y = random.nextFloat()
            val bitmapX = (x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val bitmapY = (y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            RandomArtworkDot(
                x = x * 2f - 1f,
                y = y * 2f - 1f,
                baseDepth = random.nextFloat() * 0.20f - 0.10f,
                jumpPhase = random.nextFloat() * (PI * 2f).toFloat(),
                sizeJitter = 0.68f + random.nextFloat() * 0.64f,
                color = Color(bitmap.getPixel(bitmapX, bitmapY))
            )
        }
    }
    val rotationLoop = rememberInfiniteTransition(label = "DotMatrixRotation")
    val rotationPhase by rotationLoop.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween((24_000 / AlbumDotMatrixSettings.rotationSpeed).toInt()),
            repeatMode = RepeatMode.Restart
        ),
        label = "DotMatrixRotationPhase"
    )
    val autoYaw = if (AlbumDotMatrixSettings.rotationEnabled) rotationPhase else 0f
    val rotationRadians = ((autoYaw + manualYaw) / 180f * PI).toFloat()
    val tiltRadians = (manualPitch / 180f * PI).toFloat()
    val depthAmount = AlbumDotMatrixSettings.depth
    val energy = if (AlbumDotMatrixSettings.visualizerDepthEnabled) visualizerEnergy.coerceIn(0f, 1f) else 0f
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                manualYaw = (manualYaw + dragAmount.x * 0.55f) % 360f
                manualPitch = (manualPitch - dragAmount.y * 0.42f).coerceIn(-62f, 62f)
            }
        }
    ) {
        val radius = size.minDimension / requestedColumns.coerceAtLeast(1) * requestedDotScale * 0.5f

        randomDots.forEach { dot ->
                val nx = dot.x
                val ny = dot.y
                val lyricJump = sin(dot.jumpPhase + energy * PI.toFloat()) * energy
                val sourceZ = dot.baseDepth * depthAmount + lyricJump * depthAmount * 0.46f
                val rotatedX = nx * cos(rotationRadians) + sourceZ * sin(rotationRadians)
                val rotatedZ = -nx * sin(rotationRadians) + sourceZ * cos(rotationRadians)
                val tiltedY = ny * cos(tiltRadians) - rotatedZ * sin(tiltRadians)
                val tiltedZ = ny * sin(tiltRadians) + rotatedZ * cos(tiltRadians)
                val perspective = (1f / (1f + tiltedZ * 0.28f)).coerceIn(0.68f, 1.42f)
                drawCircle(
                    color = dot.color,
                    radius = radius * dot.sizeJitter * perspective,
                    center = Offset(
                        x = size.width * 0.5f + rotatedX * size.width * 0.47f * perspective,
                        y = size.height * 0.5f + tiltedY * size.height * 0.47f * perspective
                    )
                )
        }
    }
}
