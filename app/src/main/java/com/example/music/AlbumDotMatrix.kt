package com.example.music

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
    var dotShape by mutableStateOf("circle")
        private set
    var rotationEnabled by mutableStateOf(true)
        private set
    var rotationSpeed by mutableFloatStateOf(0.45f)
        private set
    var depth by mutableFloatStateOf(0.55f)
        private set
    var visualizerDepthEnabled by mutableStateOf(true)
        private set
    var jumpRange by mutableFloatStateOf(0.5f)
        private set
    var manualYaw by mutableFloatStateOf(10f)
        private set
    var manualPitch by mutableFloatStateOf(12f)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("album_dot_matrix_enabled", false)
        columns = prefs.getInt("album_dot_matrix_columns", 36).coerceIn(12, 80)
        dotScale = prefs.getFloat("album_dot_matrix_scale", 0.82f).coerceIn(0.35f, 1f)
        dotShape = prefs.getString("album_dot_matrix_shape", "circle")
            ?.takeIf { it in setOf("circle", "square", "diamond") }
            ?: "circle"
        rotationEnabled = prefs.getBoolean("album_dot_matrix_rotation", true)
        rotationSpeed = prefs.getFloat("album_dot_matrix_rotation_speed", 0.45f).coerceIn(0.1f, 1.5f)
        depth = prefs.getFloat("album_dot_matrix_depth", 0.55f).coerceIn(0f, 1f)
        visualizerDepthEnabled = prefs.getBoolean("album_dot_matrix_visualizer_depth", true)
        jumpRange = prefs.getFloat("album_dot_matrix_jump_range", 0.5f).coerceIn(0f, 1f)
        manualYaw = prefs.getFloat("album_dot_matrix_manual_yaw", 10f)
        manualPitch = prefs.getFloat("album_dot_matrix_manual_pitch", 12f).coerceIn(-62f, 62f)
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

    fun setJumpRange(context: Context, value: Float) {
        jumpRange = value.coerceIn(0f, 1f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putFloat("album_dot_matrix_jump_range", jumpRange)
        }
    }

    fun setDotShape(context: Context, value: String) {
        dotShape = value.takeIf { it in setOf("circle", "square", "diamond") } ?: "circle"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("album_dot_matrix_shape", dotShape)
        }
    }

    fun setManualRotation(context: Context, yaw: Float, pitch: Float) {
        manualYaw = ((yaw % 360f) + 360f) % 360f
        manualPitch = pitch.coerceIn(-62f, 62f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putFloat("album_dot_matrix_manual_yaw", manualYaw)
            putFloat("album_dot_matrix_manual_pitch", manualPitch)
        }
    }
}

@Composable
fun DotMatrixArtwork(
    bitmap: Bitmap,
    visualizerEnergy: Float = 0f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var manualYaw by remember { mutableFloatStateOf(AlbumDotMatrixSettings.manualYaw) }
    var manualPitch by remember { mutableFloatStateOf(AlbumDotMatrixSettings.manualPitch) }
    val requestedColumns = AlbumDotMatrixSettings.columns
    val requestedDotScale = AlbumDotMatrixSettings.dotScale
    val requestedDotShape = AlbumDotMatrixSettings.dotShape
    val randomDots = remember(bitmap, requestedColumns) { buildRandomArtworkDots(bitmap, requestedColumns) }
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
    val depthAmount = AlbumDotMatrixSettings.depth
    val jumpRange = AlbumDotMatrixSettings.jumpRange
    val energy = if (AlbumDotMatrixSettings.visualizerDepthEnabled) visualizerEnergy.coerceIn(0f, 1f) else 0f

    Canvas(
        modifier = modifier.albumDotMatrixDrag(
            context = context,
            onRotate = { dx, dy ->
                manualYaw = (manualYaw + dx * 0.55f) % 360f
                manualPitch = (manualPitch - dy * 0.42f).coerceIn(-62f, 62f)
            },
            onCommit = {
                AlbumDotMatrixSettings.setManualRotation(context, manualYaw, manualPitch)
            }
        )
    ) {
        drawRandomArtworkDots(
            randomDots = randomDots,
            requestedColumns = requestedColumns,
            requestedDotScale = requestedDotScale,
            requestedDotShape = requestedDotShape,
            rotationDegrees = autoYaw + manualYaw,
            tiltDegrees = manualPitch,
            depthAmount = depthAmount,
            jumpRange = jumpRange,
            energy = energy
        )
    }
}

private fun Modifier.albumDotMatrixDrag(
    context: Context,
    onRotate: (Float, Float) -> Unit,
    onCommit: () -> Unit
): Modifier = pointerInput(context) {
    detectDragGestures(
        onDragEnd = onCommit,
        onDragCancel = onCommit
    ) { _, dragAmount ->
        onRotate(dragAmount.x, dragAmount.y)
    }
}

private fun buildRandomArtworkDots(bitmap: Bitmap, requestedColumns: Int): List<RandomArtworkDot> {
    val random = Random(bitmap.generationId * 31 + requestedColumns)
    val dotCount = requestedColumns * requestedColumns
    return List(dotCount) {
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

private fun DrawScope.drawRandomArtworkDots(
    randomDots: List<RandomArtworkDot>,
    requestedColumns: Int,
    requestedDotScale: Float,
    requestedDotShape: String,
    rotationDegrees: Float,
    tiltDegrees: Float,
    depthAmount: Float,
    jumpRange: Float,
    energy: Float
) {
    val radius = size.minDimension / requestedColumns.coerceAtLeast(1) * requestedDotScale * 0.5f
    val rotationRadians = (rotationDegrees / 180f * PI).toFloat()
    val tiltRadians = (tiltDegrees / 180f * PI).toFloat()

    randomDots.forEach { dot ->
        val nx = dot.x
        val ny = dot.y
        val beatJump = sin(dot.jumpPhase + energy * PI.toFloat()) * energy
        val sourceZ = dot.baseDepth * depthAmount + beatJump * jumpRange
        val rotatedX = nx * cos(rotationRadians) + sourceZ * sin(rotationRadians)
        val rotatedZ = -nx * sin(rotationRadians) + sourceZ * cos(rotationRadians)
        val tiltedY = ny * cos(tiltRadians) - rotatedZ * sin(tiltRadians)
        val tiltedZ = ny * sin(tiltRadians) + rotatedZ * cos(tiltRadians)
        val perspective = (1f / (1f + tiltedZ * 0.24f)).coerceIn(0.72f, 1.32f)
        val center = Offset(
            x = size.width * 0.5f + rotatedX * size.width * 0.39f * perspective,
            y = size.height * 0.5f + tiltedY * size.height * 0.39f * perspective
        )
        val dotRadius = radius * dot.sizeJitter * perspective
        when (requestedDotShape) {
            "square" -> drawRect(
                color = dot.color,
                topLeft = Offset(center.x - dotRadius, center.y - dotRadius),
                size = Size(dotRadius * 2f, dotRadius * 2f)
            )
            "diamond" -> drawPath(
                path = Path().apply {
                    moveTo(center.x, center.y - dotRadius)
                    lineTo(center.x + dotRadius, center.y)
                    lineTo(center.x, center.y + dotRadius)
                    lineTo(center.x - dotRadius, center.y)
                    close()
                },
                color = dot.color
            )
            else -> drawCircle(color = dot.color, radius = dotRadius, center = center)
        }
    }
}
