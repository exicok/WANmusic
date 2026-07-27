package com.example.music

import android.content.Intent
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaCodecList
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.music.ui.theme.MusicTheme
import com.example.music.lyrics.EmbeddedLyricsReader
import com.google.common.util.concurrent.MoreExecutors
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce

// Config Persistence Helper
object ConfigManager {
    private const val FILE_NAME = "music_config.txt"

    fun saveDirectories(context: android.content.Context, dirs: List<Uri>) {
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(dirs.joinToString("\n") { it.toString() })
    }

    fun loadDirectories(context: android.content.Context): List<Uri> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map { it.toUri() }
    }
}

// 扫描记录持久化：保存上次扫描的歌曲列表（封面保存为独立 PNG 文件），
// 应用启动时直接加载，避免每次启动都重新扫描音乐库目录
// 歌曲列表滚动位置记忆：回到音乐页时恢复上次滑动位置
object ListScrollMemory {
    private const val PREFS = "music_prefs"
    private const val KEY_INDEX = "local_list_index"
    private const val KEY_OFFSET = "local_list_offset"

    fun load(context: android.content.Context): Pair<Int, Int> {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        return p.getInt(KEY_INDEX, 0) to p.getInt(KEY_OFFSET, 0)
    }

    fun save(context: android.content.Context, index: Int, offset: Int) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit {
            putInt(KEY_INDEX, index.coerceAtLeast(0))
            putInt(KEY_OFFSET, offset.coerceAtLeast(0))
        }
    }
}

object ScanRecordsManager {
    private const val FILE_NAME = "scan_records.json"
    private const val ARTWORK_DIR = "artwork"
    private const val KEY_VERSION = "v"
    private const val KEY_SONGS = "songs"
    private const val KEY_TITLE = "t"
    private const val KEY_ARTIST = "a"
    private const val KEY_DURATION = "d"   // Song 类的第三个字段实际存的是文件大小
    private const val KEY_URI = "u"
    private const val KEY_ARTWORK = "w"   // 封面文件 hash（用 URI 哈希做文件名）

    fun save(context: android.content.Context, songs: List<Song>) {
        try {
            val artworkDir = File(context.filesDir, ARTWORK_DIR).apply { mkdirs() }
            val root = org.json.JSONObject()
            root.put(KEY_VERSION, 1)
            val arr = org.json.JSONArray()
            songs.forEach { s ->
                val obj = org.json.JSONObject()
                obj.put(KEY_TITLE, s.title)
                obj.put(KEY_ARTIST, s.artist)
                obj.put(KEY_DURATION, s.duration)
                obj.put(KEY_URI, s.uri.toString())
                // 封面单独存为 PNG 文件，JSON 中只记录 hash
                s.artwork?.let { bitmap ->
                    val hash = s.uri.toString().hashCode().toString(16)
                    val file = File(artworkDir, "$hash.png")
                    try {
                        file.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        obj.put(KEY_ARTWORK, hash)
                    } catch (e: Exception) {
                        android.util.Log.w("ScanRecordsManager", "保存封面失败: ${e.message}")
                    }
                }
                arr.put(obj)
            }
            root.put(KEY_SONGS, arr)
            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            android.util.Log.w("ScanRecordsManager", "保存扫描记录失败: ${e.message}")
        }
    }

    fun load(context: android.content.Context): List<Song> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val root = org.json.JSONObject(file.readText())
            val arr = root.optJSONArray(KEY_SONGS) ?: return emptyList()
            // 关键优化：只读取元数据，封面置空
            // 封面会在列表显示时按需加载，避免启动时一次性解码几十张 Bitmap 卡死主线程
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        Song(
                            title = obj.optString(KEY_TITLE, "Unknown"),
                            artist = obj.optString(KEY_ARTIST, "本地音频"),
                            duration = obj.optString(KEY_DURATION, ""),
                            uri = obj.optString(KEY_URI).toUri(),
                            artwork = null  // 不在加载时解码，避免卡死
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ScanRecordsManager", "加载扫描记录失败: ${e.message}")
            emptyList()
        }
    }

    /** 异步加载封面：扫描单张 Bitmap 并赋值给 Song，返回更新后的 Song */
    fun loadArtworkAsync(context: android.content.Context, song: Song): Song? {
        if (song.artwork != null) return song
        val artFile = artworkFile(context, song.uri)
        if (!artFile.exists()) return null
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(artFile.absolutePath)
            if (bitmap != null) song.copy(artwork = bitmap) else null
        } catch (e: Exception) {
            null
        }
    }

    fun artworkFile(context: android.content.Context, uri: Uri): File {
        val hash = uri.toString().hashCode().toString(16)
        return File(File(context.filesDir, ARTWORK_DIR), "$hash.png")
    }

    /** 确保封面落盘，便于桌面部件读取。 */
    fun ensureArtworkCached(context: android.content.Context, song: Song?) {
        if (song == null) return
        val file = artworkFile(context, song.uri)
        if (file.exists()) return
        val bitmap = song.artwork ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        }
    }

    /** 读取封面 Bitmap，可选缩放到 maxPx，优先缓存文件，其次内存 bitmap。 */
    fun loadArtworkBitmap(
        context: android.content.Context,
        song: Song,
        maxPx: Int = 256
    ): Bitmap? {
        ensureArtworkCached(context, song)
        val file = artworkFile(context, song.uri)
        val raw = when {
            file.exists() -> runCatching {
                decodeSampledBitmap(file.absolutePath, maxPx)
            }.getOrNull()
            song.artwork != null -> song.artwork
            else -> null
        } ?: return null
        return if (raw.width <= maxPx && raw.height <= maxPx) {
            raw
        } else {
            val scale = maxPx.toFloat() / maxOf(raw.width, raw.height).toFloat()
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(raw, w, h, true)
        }
    }

    private fun decodeSampledBitmap(path: String, maxPx: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        while (largest / sample > maxPx * 2) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeFile(path, opts)
    }

    // 清理：删除所有已保存的扫描记录和封面文件
    fun clear(context: android.content.Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
            File(context.filesDir, ARTWORK_DIR).deleteRecursively()
        } catch (_: Exception) {}
    }
}

// 播放进度持久化
object LastPlaybackManager {
    private const val PREFS_NAME = "playback_prefs"
    private const val KEY_LAST_URI = "last_uri"
    private const val KEY_LAST_POSITION = "last_position"
    private const val KEY_LAST_DURATION = "last_duration"

    fun save(context: Context, uri: Uri?, position: Long, duration: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_URI, uri?.toString())
            putLong(KEY_LAST_POSITION, position)
            putLong(KEY_LAST_DURATION, duration)
        }
    }

    fun loadUri(context: Context): Uri? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_URI, null)?.toUri()
    }

    fun loadPosition(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_POSITION, 0L)
    }

    fun loadDuration(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_DURATION, 0L)
    }
}

// 数据管理：缓存清理、数据清除、配置导入导出
object DataManager {
    private const val TAG = "DataManager"
    private const val PREFS_NAME = "music_prefs"
    private const val DIRS_FILE = "directories.conf"
    private const val SCAN_FILE = "scan_records.json"
    private const val ARTWORK_DIR = "artwork"
    private const val FORMAT_VERSION = 1

    /** 清理封面缓存（下次扫描/播放时按需重新提取），返回释放的字节数 */
    fun clearCache(context: android.content.Context): Long {
        var bytes = 0L
        try {
            val artworkDir = File(context.filesDir, ARTWORK_DIR)
            if (artworkDir.exists()) {
                artworkDir.walkBottomUp().forEach { f ->
                    if (f.isFile) {
                        bytes += f.length()
                        f.delete()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "clearCache: ${e.message}")
        }
        return bytes
    }

    /** 清除全部数据：扫描记录 + 封面缓存 + 音乐库目录 + 个性化设置 */
    fun clearAllData(context: android.content.Context) {
        try {
            File(context.filesDir, SCAN_FILE).delete()
            clearCache(context)
            File(context.filesDir, DIRS_FILE).delete()
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "clearAllData: ${e.message}")
        }
    }

    /** 导出配置到输出流（包含：个性化设置 + 音乐库目录 + 扫描记录） */
    fun exportConfig(context: android.content.Context, outputStream: java.io.OutputStream) {
        val root = org.json.JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("exportTime", System.currentTimeMillis())

        // 导出 SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val prefsObj = org.json.JSONObject()
        prefs.all.forEach { (k, v) -> prefsObj.put(k, v) }
        root.put("preferences", prefsObj)

        // 导出音乐库目录
        val dirsFile = File(context.filesDir, DIRS_FILE)
        val dirsList = org.json.JSONArray()
        if (dirsFile.exists()) {
            dirsFile.readLines().filter { it.isNotBlank() }.forEach { dirsList.put(it) }
        }
        root.put("directories", dirsList)

        // 导出扫描记录（嵌套原 JSON）
        val scanFile = File(context.filesDir, SCAN_FILE)
        if (scanFile.exists()) {
            try {
                root.put("scan_records", org.json.JSONObject(scanFile.readText()))
            } catch (_: Exception) {}
        }

        outputStream.use { out ->
            out.write(root.toString(2).toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    /** 从输入流导入配置，返回是否成功 */
    fun importConfig(context: android.content.Context, inputStream: java.io.InputStream): Boolean {
        return try {
            val root = org.json.JSONObject(inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })

            // 导入 SharedPreferences
            root.optJSONObject("preferences")?.let { prefsObj ->
                val editor = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).edit()
                val keys = prefsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (editor == null) continue
                    val value = prefsObj.get(key)
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        else -> { /* 跳过不支持的类型 */ }
                    }
                }
                editor.apply()
            }

            // 导入音乐库目录
            root.optJSONArray("directories")?.let { arr ->
                val dirsFile = File(context.filesDir, DIRS_FILE)
                dirsFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    for (i in 0 until arr.length()) {
                        writer.write(arr.getString(i))
                        writer.newLine()
                    }
                }
            }

            // 导入扫描记录
            root.optJSONObject("scan_records")?.let { scanObj ->
                File(context.filesDir, SCAN_FILE).writeText(scanObj.toString())
            }

            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "importConfig: ${e.message}")
            false
        }
    }

    /** 格式化字节数为可读字符串 */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    }
}

object SmoothAnimationFrameRate {
    @Volatile
    var enabled: Boolean = false

    val frameDelayMillis: Long
        get() = if (enabled) 8L else 16L
}

/** 开启时优先 120Hz，关闭时锁定 60Hz。 */
private fun applySmoothAnimationMode(activity: android.app.Activity, enabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    SmoothAnimationFrameRate.enabled = enabled
    val display = activity.windowManager.defaultDisplay
    val currentMode = display.mode
    val sameResolutionModes = display.supportedModes.filter {
        it.physicalWidth == currentMode.physicalWidth &&
            it.physicalHeight == currentMode.physicalHeight
    }
    val targetMode = if (enabled) {
        sameResolutionModes
            .filter { kotlin.math.abs(it.refreshRate - 120f) < 1f }
            .maxByOrNull { it.refreshRate }
            ?: sameResolutionModes.maxByOrNull { it.refreshRate }
    } else {
        sameResolutionModes
            .filter { kotlin.math.abs(it.refreshRate - 60f) < 1f }
            .maxByOrNull { it.refreshRate }
            ?: sameResolutionModes.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
    }

    activity.window.attributes = activity.window.attributes.apply {
        preferredDisplayModeId = targetMode?.modeId ?: currentMode.modeId
        preferredRefreshRate = targetMode?.refreshRate ?: 60f
    }
}

class MainActivity : ComponentActivity() {
    private var player: Player? by mutableStateOf(null)
    private var initError: String? by mutableStateOf(null)
    private val prefs by lazy { getSharedPreferences("music_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val sessionToken = SessionToken(
                this,
                android.content.ComponentName(this, PlaybackService::class.java)
            )
            val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
            controllerFuture.addListener({
                try {
                    player = controllerFuture.get()
                } catch (e: Throwable) {
                    android.util.Log.e("MainActivity", "MediaController 连接失败", e)
                    initError = "无法连接播放服务：${e.message ?: e::class.java.simpleName}"
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "SessionToken 创建失败", e)
            initError = "播放服务初始化失败：${e.message ?: e::class.java.simpleName}"
        }

        val savedDirs = ConfigManager.loadDirectories(this)
        // 加载上次保存的扫描记录，应用启动时直接显示，无需重新扫描
        val savedSongs = ScanRecordsManager.load(this)
        val savedAmoledMode = prefs.getBoolean("is_amoled_mode", false)
        val savedDjMode = prefs.getBoolean("is_dj_mode", false)
        val savedPlayerLandscape = prefs.getBoolean("player_landscape", false)
        val savedSmoothAnimationMode = prefs.getBoolean("smooth_animation_mode", false)
        val lastUri = LastPlaybackManager.loadUri(this)
        val lastPosition = LastPlaybackManager.loadPosition(this)
        val lastDuration = LastPlaybackManager.loadDuration(this)

        applySmoothAnimationMode(this, savedSmoothAnimationMode)
        enableEdgeToEdge()
        setContent {
            var dockPosition by remember { mutableStateOf(prefs.getString("dock_position", "bottom") ?: "bottom") }
            var isAmoledMode by remember { mutableStateOf(savedAmoledMode) }
            var isDjMode by remember { mutableStateOf(savedDjMode) }
            var playerLandscape by remember { mutableStateOf(savedPlayerLandscape) }

            LaunchedEffect(dockPosition) { prefs.edit { putString("dock_position", dockPosition) } }
            LaunchedEffect(isAmoledMode) { prefs.edit { putBoolean("is_amoled_mode", isAmoledMode) } }
            LaunchedEffect(isDjMode) { prefs.edit { putBoolean("is_dj_mode", isDjMode) } }
            LaunchedEffect(playerLandscape) { prefs.edit { putBoolean("player_landscape", playerLandscape) } }

            MusicTheme(dynamicColor = false, amoledMode = isAmoledMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val err = initError
                    val currentPlayer = player
                    when {
                        err != null -> StartupErrorScreen(err)
                        currentPlayer != null -> MusicApp(
                            player = currentPlayer,
                            initialDirs = savedDirs,
                            initialSongs = savedSongs,
                            onDirsChanged = { dirs -> ConfigManager.saveDirectories(this, dirs) },
                            dockPosition = dockPosition,
                            onDockPositionChange = { dockPosition = it },
                            isAmoledMode = isAmoledMode,
                            onAmoledModeChange = { isAmoledMode = it },
                            isDjMode = isDjMode,
                            onDjModeChange = { isDjMode = it },
                            playerLandscape = playerLandscape,
                            onPlayerLandscapeChange = { playerLandscape = it },
                            initialLastUri = lastUri,
                            initialLastPosition = lastPosition,
                            initialLastDuration = lastDuration
                        )
                        else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applySmoothAnimationMode(
            this,
            prefs.getBoolean("smooth_animation_mode", false)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Controller is automatically released when activity is destroyed? 
        // No, we should release it if we were managing it manually, but buildAsync handles it mostly.
        // Actually, MediaController should be released.
    }
}

data class Song(
    val title: String, 
    val artist: String, 
    val duration: String, 
    val uri: Uri,
    val artwork: Bitmap? = null
)

@Composable
private fun StartupErrorScreen(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "启动失败",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "请检查：\n1. AndroidManifest 中是否声明了 PlaybackService\n2. 是否授予了通知与前台服务权限\n3. 查看 logcat 过滤 MainActivity 获取详情",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MusicApp(
    player: Player,
    initialDirs: List<Uri>,
    initialSongs: List<Song>,
    onDirsChanged: (List<Uri>) -> Unit,
    dockPosition: String,
    onDockPositionChange: (String) -> Unit,
    isAmoledMode: Boolean,
    onAmoledModeChange: (Boolean) -> Unit,
    isDjMode: Boolean,
    onDjModeChange: (Boolean) -> Unit,
    playerLandscape: Boolean,
    onPlayerLandscapeChange: (Boolean) -> Unit,
    initialLastUri: Uri?,
    initialLastPosition: Long,
    initialLastDuration: Long
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val appPreferences = remember {
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
    }
    val initialFullscreenPlayerMode = remember(initialLastUri, initialSongs) {
        appPreferences.getBoolean("fullscreen_player_mode", false) &&
            initialSongs.any { it.uri == initialLastUri }
    }
    LaunchedEffect(initialFullscreenPlayerMode) {
        if (!initialFullscreenPlayerMode &&
            appPreferences.getBoolean("fullscreen_player_mode", false)
        ) {
            appPreferences.edit { putBoolean("fullscreen_player_mode", false) }
        }
    }
    // 重启 Activity：用于清除全部数据 / 导入配置后重新加载所有状态
    val restartActivity: () -> Unit = { activity?.recreate() }
    val scope = rememberCoroutineScope()
    var fullscreenPlayerMode by remember { mutableStateOf(initialFullscreenPlayerMode) }
    var currentScreen by remember {
        mutableStateOf<Screen>(
            if (initialFullscreenPlayerMode) Screen.PlayerView else Screen.LocalMusic
        )
    }
    var previousScreen by remember { mutableStateOf<Screen>(Screen.LocalMusic) }
    LaunchedEffect(currentScreen, playerLandscape) {
        activity?.requestedOrientation = when {
            currentScreen != Screen.PlayerView -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            playerLandscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        activity?.window?.let { window ->
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (currentScreen == Screen.PlayerView) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                if (currentScreen == Screen.PlayerView) {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.statusBars())
                } else {
                    show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
    val openPlayer = {
        previousScreen = currentScreen
        currentScreen = Screen.PlayerView
    }
    val musicDirectories = remember { mutableStateListOf<Uri>().apply { addAll(initialDirs) } }
    // 初始歌曲列表：优先使用上次保存的扫描记录，启动时直接显示，无需扫描
    var songs by remember { mutableStateOf(initialSongs) }
    var isScanning by remember { mutableStateOf(false) }
    // 媒体项列表：初始为空，由后台协程构建
    var mediaItemsList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    // 是否已完成 mediaItemsList 的初始化构建
    var mediaItemsReady by remember { mutableStateOf(initialSongs.isEmpty()) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_ALL) }
    var shuffleMode by remember { mutableStateOf(false) }

    LaunchedEffect(repeatMode) { player.repeatMode = repeatMode }
    LaunchedEffect(shuffleMode) { player.shuffleModeEnabled = shuffleMode }

    var currentSong by remember { mutableStateOf<Song?>(initialSongs.find { it.uri == initialLastUri }) }
    var currentPosition by remember { mutableLongStateOf(initialLastPosition) }
    var isPlaying by remember { mutableStateOf(false) }
    var totalDuration by remember { mutableLongStateOf(initialLastDuration) }
    var wasPlayingBeforeScratch by remember { mutableStateOf(false) }

    // 启动时恢复上一次播放状态
    LaunchedEffect(Unit) {
        if (currentSong != null) {
            val item = buildMediaItem(currentSong!!)
            player.setMediaItem(item, initialLastPosition)
            player.prepare()
        }
    }

    // 每次歌曲或进度变动时保存状态（使用 apply 避免频繁 IO 阻塞）
    LaunchedEffect(currentSong, currentPosition, totalDuration) {
        if (currentSong != null) {
            LastPlaybackManager.save(context, currentSong?.uri, currentPosition, totalDuration)
        }
    }

    // 启动时在后台构建 mediaItemsList（不阻塞 UI）
    LaunchedEffect(initialSongs) {
        if (initialSongs.isNotEmpty()) {
            val items = withContext(Dispatchers.IO) {
                initialSongs.map { buildMediaItem(it) }
            }
            mediaItemsList = items
            mediaItemsReady = true
        }
    }

    // 手动扫描函数：抽出扫描逻辑供 LibrarySettingsScreen 的"立即扫描"按钮调用
    val triggerScan: () -> Unit = triggerScan@{
        if (musicDirectories.isNotEmpty() && !isScanning) {
            isScanning = true
            scope.launch {
                val scanResult = withContext(Dispatchers.IO) {
                    val foundSongs = mutableListOf<Song>()
                    val retriever = MediaMetadataRetriever()
                    musicDirectories.forEach { uri ->
                        val root = DocumentFile.fromTreeUri(context, uri)
                        if (root != null && root.isDirectory) {
                            root.listFiles().forEach { file ->
                                if (file.isFile && isMusicFile(file.name)) {
                                    try {
                                        retriever.setDataSource(context, file.uri)
                                        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.name ?: "Unknown"
                                        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "本地音频"
                                        val artBytes = retriever.embeddedPicture
                                        val bitmap = if (artBytes != null) BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) else null
                                        foundSongs.add(Song(title, artist, formatFileSize(file.length()), file.uri, bitmap))
                                    } catch (_: Exception) {
                                        foundSongs.add(Song(file.name ?: "Unknown", "本地音频", formatFileSize(file.length()), file.uri))
                                    }
                                }
                            }
                        }
                    }
                    retriever.release()
                    foundSongs
                }
                songs = scanResult
                // 直接构建 mediaItemsList，不依赖 LaunchedEffect
                mediaItemsList = withContext(Dispatchers.IO) {
                    scanResult.map { buildMediaItem(it) }
                }
                // 扫描完成后立即保存到磁盘，下次启动直接加载
                ScanRecordsManager.save(context, scanResult)
                isScanning = false
            }
        }
    }

    LaunchedEffect(musicDirectories.size) { onDirsChanged(musicDirectories.toList()) }

    // Lyricon Support
    val lyriconProvider = remember {
        try { LyriconFactory.createProvider(context) } catch (e: Exception) { null }
    }
    DisposableEffect(lyriconProvider) {
        lyriconProvider?.register()
        lyriconProvider?.player?.setPositionUpdateInterval(100)
        onDispose { lyriconProvider?.unregister() }
    }

    var currentSongLyrics by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    LaunchedEffect(currentSong) {
        val song = currentSong
        currentSongLyrics = emptyList()
        currentSongLyrics = if (song != null) {
            withContext(Dispatchers.IO) { parseLyrics(song, context, musicDirectories.toList()) }
        } else {
            emptyList()
        }
    }

    // 发送完整的结构化逐字歌词；Lyricon 会根据 LyricWord 的 begin/end 自动渲染逐字进度。
    LaunchedEffect(currentSong, currentSongLyrics, totalDuration) {
        val song = currentSong
        if (song == null) {
            lyriconProvider?.player?.setSong(null)
        } else {
            lyriconProvider?.player?.setSong(
                buildLyriconSong(song, currentSongLyrics, totalDuration)
            )
        }
    }

    LaunchedEffect(isPlaying) {
        lyriconProvider?.player?.setPlaybackState(isPlaying)
    }

    LaunchedEffect(isDjMode) {
        if (!isDjMode) player.playbackParameters = PlaybackParameters.DEFAULT
    }

    // 连续同步实际播放位置，让 Lyricon 在当前行内推进到对应的字。
    LaunchedEffect(currentPosition) {
        lyriconProvider?.player?.setPosition(currentPosition.coerceAtLeast(0L))
    }

    // 同步给桌面歌词部件与悬浮歌词
    LaunchedEffect(currentSong, currentSongLyrics, currentPosition, totalDuration, isPlaying) {
        LyricsStateHolder.publish(
            context = context,
            song = currentSong,
            lyrics = currentSongLyrics,
            position = currentPosition,
            duration = totalDuration,
            playing = isPlaying
        )
    }

    // 当前曲目变化时缓存封面并刷新桌面部件专辑图
    LaunchedEffect(currentSong?.uri) {
        val song = currentSong ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val withArt = if (song.artwork == null) {
                ScanRecordsManager.loadArtworkAsync(context, song) ?: song
            } else {
                song
            }
            ScanRecordsManager.ensureArtworkCached(context, withArt)
        }
        LyricsWidgetProvider.updateAllWidgets(context)
    }

    // 若用户此前开启了悬浮歌词，回到前台时自动恢复
    val hostActivity = context as? ComponentActivity
    DisposableEffect(hostActivity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                OverlayLyricsService.startIfEnabled(context)
            }
        }
        hostActivity?.lifecycle?.addObserver(observer)
        onDispose { hostActivity?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        OverlayLyricsService.startIfEnabled(context)
    }

    LaunchedEffect(isPlaying, currentSong) {
        if (isPlaying) {
            while (true) {
                currentPosition = player.currentPosition
                totalDuration = player.duration.coerceAtLeast(0L)
                // 播放时也记录进度
                LastPlaybackManager.save(context, currentSong?.uri, currentPosition, totalDuration)
                delay(100.milliseconds)
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 只有在非启动初始化的切换时，才从 player 获取位置（防止启动时的 0 覆盖恢复的位置）
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || 
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    currentPosition = player.currentPosition
                }
                totalDuration = player.duration.coerceAtLeast(0L)
                mediaItem?.let { item ->
                    val found = songs.find { it.uri == item.localConfiguration?.uri }
                    if (found != null) currentSong = found
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || 
                    reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    currentPosition = player.currentPosition
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 扫描控制：默认手动扫描（确保软件进入速度）
    // 不再自动扫描，用户在音乐库设置中点"立即扫描"按钮后才扫描
    LaunchedEffect(Unit) { /* 故意留空：禁用启动时及目录变化时的自动扫描 */ }

    val setFullscreenPlayerMode: (Boolean) -> Unit = { enabled ->
        if (!enabled || currentSong != null) {
            fullscreenPlayerMode = enabled
            appPreferences.edit { putBoolean("fullscreen_player_mode", enabled) }
            previousScreen = Screen.LocalMusic
            currentScreen = if (enabled) Screen.PlayerView else Screen.LocalMusic
        }
    }

    // 提取统一的返回逻辑
    val handleBack: () -> Unit = {
        if (fullscreenPlayerMode && currentScreen == Screen.PlayerView) {
            activity?.moveTaskToBack(true)
        } else {
            currentScreen = when (currentScreen) {
                Screen.Library, Screen.Data, Screen.Playback, Screen.AudioCodecs,
                Screen.AboutSupport -> Screen.Settings
                Screen.Equalizer -> Screen.LocalMusic
                Screen.WebDav -> Screen.Settings
                Screen.LocalMusic -> Screen.LocalMusic
                Screen.PlayerView -> previousScreen
                else -> Screen.LocalMusic
            }
        }
        Unit
    }

    val playSongFromQuickList: (Song) -> Unit = { selectedSong ->
        currentSong = selectedSong
        try {
            val index = songs.indexOf(selectedSong)
            if (!mediaItemsReady || mediaItemsList.size != songs.size) {
                mediaItemsList = songs.map { buildMediaItem(it) }
                mediaItemsReady = true
            }
            if (index >= 0 && mediaItemsList.size == songs.size) {
                player.setMediaItems(mediaItemsList, index, 0L)
            } else {
                player.setMediaItem(MediaItem.fromUri(selectedSong.uri))
            }
            player.prepare()
            player.play()
        } catch (e: Throwable) {
            android.util.Log.e("MusicApp", "快捷播放启动失败", e)
        }
    }

    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var predictiveBackDirection by remember { mutableFloatStateOf(1f) }
    // 音乐库根页面由系统执行返回桌面动画，其余页面统一使用预见式返回。
    PredictiveBackHandler(enabled = currentScreen != Screen.LocalMusic) { progress ->
        try {
            progress.collect { backEvent ->
                predictiveBackProgress = backEvent.progress.coerceIn(0f, 1f)
                predictiveBackDirection = if (
                    backEvent.swipeEdge == androidx.activity.BackEventCompat.EDGE_RIGHT
                ) {
                    -1f
                } else {
                    1f
                }
            }
            handleBack()
        } catch (_: java.util.concurrent.CancellationException) {
            // 用户取消返回手势时恢复当前页面。
        } finally {
            predictiveBackProgress = 0f
            predictiveBackDirection = 1f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = predictiveBackProgress.coerceIn(0f, 1f)
                translationX = size.width * 0.14f * progress * predictiveBackDirection
                scaleX = 1f - progress * 0.025f
                scaleY = 1f - progress * 0.025f
                alpha = 1f - progress * 0.12f
            }
    ) {
        val dockScreens = setOf(Screen.LocalMusic, Screen.Equalizer, Screen.Settings)
        val showDock = !fullscreenPlayerMode && currentScreen in dockScreens
        val isFloatDock = dockPosition.equals("float", ignoreCase = true)
        // 悬浮层仅用于音乐列表，避免遮挡设置/均衡器等其它页面
        val floatOverlayActive = isFloatDock && currentScreen == Screen.LocalMusic
        val showFixedBottomBar = showDock && (
            dockPosition == "bottom" ||
                (isFloatDock && currentScreen != Screen.LocalMusic)
        )
        Scaffold(
            containerColor = if (currentScreen == Screen.PlayerView) Color.Transparent
                else MaterialTheme.colorScheme.background,
            topBar = {
                if (showDock && dockPosition == "top") {
                    Column(Modifier.padding(top = 8.dp)) {
                        MusicDock(currentScreen = currentScreen, onNavigate = { currentScreen = it })
                        if (currentSong != null) MD3MiniPlayer(
                            song = currentSong!!,
                            isPlaying = isPlaying,
                            progress = if (totalDuration > 0) currentPosition.toFloat() / totalDuration else 0f,
                            onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                            onClick = openPlayer,
                            onNext = { player.seekToNext() },
                            onPrevious = { player.seekToPrevious() },
                            onSeek = { player.seekTo((it * totalDuration).toLong()) }
                        )
                    }
                }
            },
            bottomBar = {
                // 底部固定栏；悬浮偏好下除音乐列表外也走固定栏，避免干扰其它页面
                if (showFixedBottomBar) {
                    Column {
                        if (currentSong != null) {
                        MD3MiniPlayer(
                            song = currentSong!!,
                            isPlaying = isPlaying,
                            progress = if (totalDuration > 0) currentPosition.toFloat() / totalDuration else 0f,
                            onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                            onClick = openPlayer,
                            onNext = { player.seekToNext() },
                            onPrevious = { player.seekToPrevious() },
                            onSeek = { player.seekTo((it * totalDuration).toLong()) }
                        )
                        }
                        MusicDock(
                            currentScreen = currentScreen,
                            onNavigate = { currentScreen = it }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier.padding(
                    // 仅音乐列表悬浮层时内容铺满；其它页面使用 Scaffold 正常 padding
                    if (floatOverlayActive) PaddingValues(0.dp) else innerPadding
                )
            ) {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        if (targetState == Screen.PlayerView) {
                            (slideInVertically { it } + fadeIn()) togetherWith
                                (slideOutVertically { -it } + fadeOut())
                        } else if (initialState == Screen.PlayerView) {
                            (slideInVertically { -it } + fadeIn()) togetherWith
                                (slideOutVertically { it } + fadeOut())
                        } else {
                            fadeIn() togetherWith fadeOut()
                        }
                    },
                    label = "ScreenTransition"
                ) { screen ->
                    when (screen) {
                        Screen.PlayerView -> {
                            currentSong?.let { song ->
                                FullPlayerScreen(
                                    song = song,
                                    lyrics = currentSongLyrics,
                                    isPlaying = isPlaying,
                                    position = currentPosition,
                                    duration = totalDuration,
                                    onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                                    onSeek = {
                                        player.seekTo(it)
                                        currentPosition = it
                                        lyriconProvider?.player?.seekTo(it)
                                    },
                                    isDjMode = isDjMode,
                                    onScratchStart = {
                                        wasPlayingBeforeScratch = player.isPlaying
                                        if (!player.isPlaying) player.play()
                                    },
                                    onScratch = { targetPosition, speed ->
                                        player.playbackParameters = PlaybackParameters(speed, speed)
                                        player.seekTo(targetPosition)
                                        currentPosition = targetPosition
                                        lyriconProvider?.player?.setPosition(targetPosition)
                                    },
                                    onScratchEnd = {
                                        player.playbackParameters = PlaybackParameters.DEFAULT
                                        player.seekTo(it)
                                        if (!wasPlayingBeforeScratch) player.pause()
                                        currentPosition = it
                                        lyriconProvider?.player?.seekTo(it)
                                    },
                                    onNext = { player.seekToNext() },
                                    onPrevious = { player.seekToPrevious() },
                                    onBack = handleBack,
                                    onExitFullscreenMode = { setFullscreenPlayerMode(false) },
                                    fullscreenPlayerMode = fullscreenPlayerMode,
                                    context = context,
                                    musicDirectories = musicDirectories.toList(),
                                    songs = songs,
                                    onSelectSong = playSongFromQuickList,
                                    repeatMode = repeatMode,
                                    onRepeatModeChange = {
                                        repeatMode = when (repeatMode) {
                                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                                            else -> Player.REPEAT_MODE_ALL
                                        }
                                    },
                                    shuffleMode = shuffleMode,
                                    onShuffleModeChange = { shuffleMode = !shuffleMode },
                                    isLandscape = playerLandscape
                                )
                            }
                        }
                        Screen.LocalMusic -> LocalMusicScreen(
                            songs = songs,
                            isScanning = isScanning,
                            bottomContentPadding = if (floatOverlayActive) {
                                // 仅音乐列表悬浮层预留底部，避免最后几首被挡住
                                if (currentSong != null) 156.dp else 90.dp
                            } else {
                                0.dp
                            },
                            onSongClick = { song ->
                                currentSong = song
                                try {
                                    val index = songs.indexOf(song)
                                    // 如果 mediaItemsList 还没构建好，先同步构建
                                    if (!mediaItemsReady || mediaItemsList.size != songs.size) {
                                        mediaItemsList = songs.map { buildMediaItem(it) }
                                        mediaItemsReady = true
                                    }
                                    if (index >= 0 && mediaItemsList.size == songs.size) {
                                        player.setMediaItems(mediaItemsList, index, 0L)
                                    } else {
                                        player.setMediaItem(MediaItem.fromUri(song.uri))
                                    }
                                    player.prepare()
                                    player.play()
                                } catch (e: Throwable) {
                                    android.util.Log.e("MusicApp", "播放启动失败", e)
                                }
                                val openOnClick = context
                                    .getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
                                    .getBoolean("open_player_on_song_click", false)
                                if (fullscreenPlayerMode || openOnClick) openPlayer()
                            },
                            onReorder = { from, to ->
                                val newList = songs.toMutableList()
                                val item = newList.removeAt(from)
                                newList.add(to, item)
                                songs = newList
                                // 同步更新播放器的队列顺序
                                if (from != to) {
                                    try {
                                        player.moveMediaItem(from, to)
                                    } catch (_: Exception) {}
                                }
                                // 重新保存扫描记录以持久化顺序
                                ScanRecordsManager.save(context, newList)
                            }
                        )
                        Screen.Settings -> SettingsMenu(
                            dockPosition = dockPosition,
                            onDockPositionChange = onDockPositionChange,
                            playerLandscape = playerLandscape,
                            onPlayerLandscapeChange = onPlayerLandscapeChange,
                            isAmoledMode = isAmoledMode,
                            onAmoledModeChange = onAmoledModeChange,
                            onNavigate = { currentScreen = it }
                        )
                        Screen.Library -> LibrarySettingsScreen(
                            directories = musicDirectories,
                            isScanning = isScanning,
                            onScanNow = { triggerScan() }
                        ) { currentScreen = Screen.Settings }
                        Screen.WebDav -> WebDavSettingsScreen(
                            onBack = { currentScreen = Screen.Settings },
                            onSongsLoaded = { remoteSongs ->
                                songs = (songs.filterNot { it.artist == "WebDAV" } + remoteSongs)
                                mediaItemsList = songs.map { buildMediaItem(it) }
                                mediaItemsReady = true
                                ScanRecordsManager.save(context, songs)
                            }
                        )
                        Screen.Data -> DataSettingsScreen(
                            onBack = { currentScreen = Screen.Settings },
                            onRestart = restartActivity
                        )
                        Screen.Playback -> PlaybackSettingsScreen(
                            isDjMode = isDjMode,
                            onDjModeChange = onDjModeChange,
                            fullscreenPlayerMode = fullscreenPlayerMode,
                            hasCurrentSong = currentSong != null,
                            onFullscreenPlayerModeChange = setFullscreenPlayerMode,
                            onBack = { currentScreen = Screen.Settings }
                        )
                        Screen.AudioCodecs -> AudioCodecSupportScreen(
                            onBack = { currentScreen = Screen.Settings }
                        )
                        Screen.AboutSupport -> AboutSupportScreen(
                            onBack = { currentScreen = Screen.Settings }
                        )
                        Screen.Equalizer -> EqualizerSettingsScreen(
                            onBack = { currentScreen = Screen.LocalMusic }
                        )
                    }
                }
            }
        }

        // 悬浮底部 Dock：真正浮在内容之上（圆角卡片 + 边距 + 半透明）
        if (floatOverlayActive) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .zIndex(10f)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentSong != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 4.dp,
                        shadowElevation = 10.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
                    ) {
                        MD3MiniPlayer(
                            song = currentSong!!,
                            isPlaying = isPlaying,
                            progress = if (totalDuration > 0) currentPosition.toFloat() / totalDuration else 0f,
                            onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                            onClick = openPlayer,
                            onNext = { player.seekToNext() },
                            onPrevious = { player.seekToPrevious() },
                            onSeek = { player.seekTo((it * totalDuration).toLong()) }
                        )
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 14.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
                ) {
                    MusicDock(
                        currentScreen = currentScreen,
                        onNavigate = { currentScreen = it },
                        floating = true
                    )
                }
            }
        }
    }
}


/**
 * 本地音乐独立页：完整显示所有本地音乐
 * - 移除 TopAppBar，支持状态栏沉浸
 * - 右侧显示拖动图标，支持长按拖动排序
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
fun LocalMusicScreen(
    songs: List<Song>,
    isScanning: Boolean,
    onSongClick: (Song) -> Unit,
    onReorder: (Int, Int) -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val savedScroll = remember { ListScrollMemory.load(context) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScroll.first,
        initialFirstVisibleItemScrollOffset = savedScroll.second
    )
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var detailSong by remember { mutableStateOf<Song?>(null) }

    // 记录滑动位置，返回音乐页时恢复
    LaunchedEffect(listState, songs.size) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .debounce(250)
            .collectLatest { (index, offset) ->
                if (songs.isNotEmpty()) {
                    ListScrollMemory.save(context, index, offset)
                }
            }
    }

    // 列表变短时，避免恢复到越界位置
    LaunchedEffect(songs.size) {
        if (songs.isEmpty()) return@LaunchedEffect
        val maxIndex = songs.lastIndex
        if (listState.firstVisibleItemIndex > maxIndex) {
            listState.scrollToItem(maxIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.statusBarsPadding()
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (isScanning) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            } else if (songs.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MusicOff, null, Modifier.size(64.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("暂无本地音乐", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomContentPadding)
                ) {
                    itemsIndexed(songs, key = { _, song -> song.uri.toString() }) { index, song ->
                        val artwork = rememberArtwork(song)
                        val isDragging = index == draggingItemIndex
                        
                        ListItem(
                            modifier = Modifier
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffset else 0f
                                    alpha = if (isDragging) 0.8f else 1.0f
                                    scaleX = if (isDragging) 1.02f else 1.0f
                                    scaleY = if (isDragging) 1.02f else 1.0f
                                    shadowElevation = if (isDragging) 8f else 0f
                                }
                                .combinedClickable(
                                    enabled = draggingItemIndex == null,
                                    onClick = { onSongClick(song) },
                                    onLongClick = { detailSong = song }
                                ),
                            headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(song.artist) },
                            leadingContent = {
                                if (artwork != null) {
                                    androidx.compose.foundation.Image(
                                        artwork.asImageBitmap(),
                                        null,
                                        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else Icon(Icons.Default.MusicNote, null)
                            },
                            trailingContent = {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "拖动排序",
                                    modifier = Modifier
                                        .size(32.dp)
                                        .padding(4.dp)
                                        .pointerInput(Unit) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    draggingItemIndex = index
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffset += dragAmount.y
                                                    
                                                    // 简单的位移计算，触发位置交换
                                                    val threshold = 50f
                                                    if (dragOffset > threshold && index < songs.lastIndex) {
                                                        onReorder(index, index + 1)
                                                        draggingItemIndex = index + 1
                                                        dragOffset -= 64.dp.toPx() // 大致项高度
                                                    } else if (dragOffset < -threshold && index > 0) {
                                                        onReorder(index, index - 1)
                                                        draggingItemIndex = index - 1
                                                        dragOffset += 64.dp.toPx()
                                                    }
                                                },
                                                onDragEnd = {
                                                    draggingItemIndex = null
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggingItemIndex = null
                                                    dragOffset = 0f
                                                }
                                            )
                                        },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    detailSong?.let { selectedSong ->
        SongDetailsDialog(
            song = selectedSong,
            context = context,
            onDismiss = { detailSong = null }
        )
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMenu(
    dockPosition: String,
    onDockPositionChange: (String) -> Unit,
    playerLandscape: Boolean,
    onPlayerLandscapeChange: (Boolean) -> Unit,
    isAmoledMode: Boolean,
    onAmoledModeChange: (Boolean) -> Unit,
    onNavigate: (Screen) -> Unit
) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
    }
    var smoothAnimationMode by remember {
        mutableStateOf(preferences.getBoolean("smooth_animation_mode", false))
    }
    fun setSmoothAnimationMode(enabled: Boolean) {
        smoothAnimationMode = enabled
        preferences.edit { putBoolean("smooth_animation_mode", enabled) }
        (context as? android.app.Activity)?.let { activity ->
            applySmoothAnimationMode(activity, enabled)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Dock 位置") },
                supportingContent = {
                    Text(
                        when (dockPosition) {
                            "top" -> "顶部 Dock"
                            "float" -> "仅音乐列表悬浮；设置/均衡器等页面仍用固定底栏，不遮挡内容"
                            else -> "底部 Dock"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Dock, null) }
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                SegmentedButton(
                    selected = dockPosition == "top",
                    onClick = { onDockPositionChange("top") },
                    shape = SegmentedButtonDefaults.itemShape(0, 3)
                ) { Text("顶部") }
                SegmentedButton(
                    selected = dockPosition == "bottom",
                    onClick = { onDockPositionChange("bottom") },
                    shape = SegmentedButtonDefaults.itemShape(1, 3)
                ) { Text("底部") }
                SegmentedButton(
                    selected = dockPosition == "float",
                    onClick = { onDockPositionChange("float") },
                    shape = SegmentedButtonDefaults.itemShape(2, 3)
                ) { Text("悬浮") }
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("播放页方向") },
                supportingContent = { Text(if (playerLandscape) "固定横屏" else "固定竖屏") },
                leadingContent = { Icon(Icons.Default.ScreenRotation, null) },
                trailingContent = {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = !playerLandscape,
                            onClick = { onPlayerLandscapeChange(false) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("竖屏") }
                        SegmentedButton(
                            selected = playerLandscape,
                            onClick = { onPlayerLandscapeChange(true) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("横屏") }
                    }
                }
            )
            ListItem(
                headlineContent = { Text("AMOLED 模式") },
                supportingContent = { Text("深色模式下使用纯黑背景") },
                leadingContent = { Icon(Icons.Default.Brightness2, null) },
                trailingContent = { Switch(isAmoledMode, onAmoledModeChange) },
                modifier = Modifier.clickable { onAmoledModeChange(!isAmoledMode) }
            )
            ListItem(
                headlineContent = { Text("流畅动画模式") },
                supportingContent = {
                    Text(
                        if (smoothAnimationMode) {
                            "整个应用优先使用 120 FPS；不支持时自动使用设备最高刷新率"
                        } else {
                            "当前固定 60 FPS；开启后让全部页面和动画使用高刷新率"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Speed, null) },
                trailingContent = {
                    Switch(
                        checked = smoothAnimationMode,
                        onCheckedChange = { setSmoothAnimationMode(it) }
                    )
                },
                modifier = Modifier.clickable {
                    setSmoothAnimationMode(!smoothAnimationMode)
                }
            )
            HorizontalDivider()
            SettingsSectionHeader("界面与播放")
            SettingsItem("播放设置", Icons.Default.GraphicEq) { onNavigate(Screen.Playback) }
            SettingsItem("音频解码支持", Icons.Default.AudioFile) { onNavigate(Screen.AudioCodecs) }
            SettingsSectionHeader("音乐来源")
            SettingsItem("音乐库", Icons.Default.LibraryMusic) { onNavigate(Screen.Library) }
            SettingsItem("WebDAV 音乐源", Icons.Default.CloudQueue) { onNavigate(Screen.WebDav) }
            SettingsSectionHeader("数据管理")
            SettingsItem("数据", Icons.Default.Storage) { onNavigate(Screen.Data) }
            SettingsSectionHeader("关于")
            SettingsItem("关于与支持", Icons.Default.Info) { onNavigate(Screen.AboutSupport) }
        }
    }
}

@Composable
fun SettingsItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, modifier = Modifier.clickable { onClick() } )
}

@Composable
private fun SettingsSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 6.dp)
    )
}

private data class AudioDecoderInfo(
    val mimeType: String,
    val codecName: String,
    val implementation: String
)

private fun queryAudioDecoders(): List<AudioDecoderInfo> = runCatching {
    MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .asSequence()
        .filterNot { it.isEncoder }
        .flatMap { codec ->
            val implementation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when {
                    codec.isHardwareAccelerated -> "硬件"
                    codec.isSoftwareOnly -> "软件"
                    else -> "系统"
                }
            } else {
                if (codec.name.startsWith("OMX.google.", ignoreCase = true)) "软件" else "系统"
            }
            codec.supportedTypes.asSequence()
                .filter { it.startsWith("audio/", ignoreCase = true) }
                .map { AudioDecoderInfo(it.lowercase(), codec.name, implementation) }
        }
        .distinctBy { it.mimeType to it.codecName }
        .sortedWith(compareBy(AudioDecoderInfo::mimeType, AudioDecoderInfo::codecName))
        .toList()
}.getOrDefault(emptyList())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioCodecSupportScreen(onBack: () -> Unit) {
    val decoders = remember { queryAudioDecoders() }
    val grouped = remember(decoders) { decoders.groupBy { it.mimeType }.toSortedMap() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音频解码支持") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                ListItem(
                    headlineContent = { Text("本设备支持 ${grouped.size} 种音频格式") },
                    supportingContent = { Text("结果来自 Android 系统当前注册的媒体解码器") },
                    leadingContent = { Icon(Icons.Default.Memory, null) }
                )
                HorizontalDivider()
            }
            if (grouped.isEmpty()) {
                item {
                    Text(
                        "未读取到可用的音频解码器",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                grouped.forEach { (mimeType, codecs) ->
                    item { SettingsSectionHeader(mimeType) }
                    items(codecs) { codec ->
                        ListItem(
                            headlineContent = { Text(codec.codecName) },
                            supportingContent = { Text("${codec.implementation}解码") },
                            leadingContent = { Icon(Icons.Default.GraphicEq, null) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSettingsScreen(onBack: () -> Unit, onSongsLoaded: (List<Song>) -> Unit) {
    val context = LocalContext.current
    val saved = remember { WebDavRepository.load(context) }
    var url by remember { mutableStateOf(saved.url) }
    var username by remember { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    var status by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("WebDAV 音乐源") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("连接远程音乐目录", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("WebDAV 地址") }, placeholder = { Text("https://example.com/dav/music/") }, singleLine = true)
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Button(
                onClick = {
                    val config = WebDavConfig(url.trim(), username, password)
                    WebDavRepository.save(context, config)
                    context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_REFRESH_WEBDAV_AUTH))
                    scanning = true
                    status = "正在扫描…"
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { WebDavRepository.scan(config) } }
                            .onSuccess { songs -> onSongsLoaded(songs); status = "已找到 ${songs.size} 首音乐" }
                            .onFailure { status = "扫描失败：${it.message ?: "连接错误"}" }
                        scanning = false
                    }
                },
                enabled = !scanning && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (scanning) "扫描中…" else "保存并扫描") }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("支持 MP3、FLAC、WAV、M4A、OGG 等音频格式。播放远程歌曲时使用当前 WebDAV 账号。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

}

@Composable
private fun SongDetailsDialog(
    song: Song,
    context: Context,
    onDismiss: () -> Unit
) {
    var audioFormat by remember(song.uri) { mutableStateOf<AudioFormatInfo?>(null) }
    LaunchedEffect(song.uri) {
        audioFormat = withContext(Dispatchers.IO) { extractAudioFormat(context, song.uri) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌曲详细信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SongDetailRow("标题", song.title)
                SongDetailRow("歌手", song.artist)
                SongDetailRow("文件大小", song.duration)
                audioFormat?.let { info ->
                    if (info.mimeType.isNotBlank()) SongDetailRow("音频格式", info.mimeType.substringAfter('/').uppercase())
                    if (info.sampleRateHz > 0) SongDetailRow("采样率", formatSampleRate(info.sampleRateHz))
                    if (info.bitrateKbps > 0) SongDetailRow("比特率", formatBitrate(info.bitrateKbps))
                    audioQuality(info)?.let { SongDetailRow("音质", it) }
                }
                SongDetailRow("文件位置", song.uri.toString())
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun SongDetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MusicDock(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    floating: Boolean = false
) {
    val dockScreens = remember {
        listOf(Screen.LocalMusic, Screen.Equalizer, Screen.Settings)
    }
    val dragThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dockModifier = (if (floating) Modifier.height(68.dp) else Modifier)
        .graphicsLayer {
            translationX = dragOffset * 0.16f
        }
        .pointerInput(currentScreen, dragThreshold) {
            detectHorizontalDragGestures(
                onDragCancel = { dragOffset = 0f },
                onDragEnd = {
                    val currentIndex = dockScreens.indexOf(currentScreen)
                    if (currentIndex >= 0 && kotlin.math.abs(dragOffset) >= dragThreshold) {
                        val targetIndex = if (dragOffset < 0f) {
                            (currentIndex + 1).coerceAtMost(dockScreens.lastIndex)
                        } else {
                            (currentIndex - 1).coerceAtLeast(0)
                        }
                        if (targetIndex != currentIndex) {
                            onNavigate(dockScreens[targetIndex])
                        }
                    }
                    dragOffset = 0f
                }
            ) { change, dragAmount ->
                change.consume()
                dragOffset = (dragOffset + dragAmount)
                    .coerceIn(-dragThreshold * 1.5f, dragThreshold * 1.5f)
            }
        }

    NavigationBar(
        modifier = dockModifier,
        containerColor = if (floating) Color.Transparent else NavigationBarDefaults.containerColor,
        tonalElevation = 0.dp,
        windowInsets = if (floating) WindowInsets(0, 0, 0, 0) else NavigationBarDefaults.windowInsets
    ) {
        NavigationBarItem(
            selected = currentScreen == Screen.LocalMusic,
            onClick = { onNavigate(Screen.LocalMusic) },
            icon = { Icon(Icons.Default.LibraryMusic, "音乐") },
            label = { Text("音乐") }
        )
        NavigationBarItem(
            selected = currentScreen == Screen.Equalizer,
            onClick = { onNavigate(Screen.Equalizer) },
            icon = { Icon(Icons.Default.Equalizer, "均衡器") },
            label = { Text("均衡器") }
        )
        NavigationBarItem(
            selected = currentScreen == Screen.Settings,
            onClick = { onNavigate(Screen.Settings) },
            icon = { Icon(Icons.Default.Settings, "设置") },
            label = { Text("设置") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(
    isDjMode: Boolean,
    onDjModeChange: (Boolean) -> Unit,
    fullscreenPlayerMode: Boolean,
    hasCurrentSong: Boolean,
    onFullscreenPlayerModeChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE) }
    var overlayEnabled by remember {
        mutableStateOf(OverlayLyricsService.isEnabled(context) || OverlayLyricsService.isRunning())
    }
    var carLyricsEnabled by remember {
        mutableStateOf(preferences.getBoolean("car_lyrics_enabled", true))
    }
    var lyricsProviderEnabled by remember {
        mutableStateOf(preferences.getBoolean("lyrics_provider_enabled", true))
    }
    val canDrawOverlays = remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    val hostActivity = context as? ComponentActivity
    DisposableEffect(hostActivity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays.value = android.provider.Settings.canDrawOverlays(context)
                overlayEnabled = OverlayLyricsService.isEnabled(context) || OverlayLyricsService.isRunning()
                if (OverlayLyricsService.isEnabled(context) && canDrawOverlays.value) {
                    OverlayLyricsService.startIfEnabled(context)
                    overlayEnabled = true
                }
            }
        }
        hostActivity?.lifecycle?.addObserver(observer)
        onDispose { hostActivity?.lifecycle?.removeObserver(observer) }
    }
    fun setOverlayLyrics(enabled: Boolean) {
        if (enabled) {
            if (!android.provider.Settings.canDrawOverlays(context)) {
                OverlayLyricsService.setEnabled(context, true)
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
                overlayEnabled = false
                return
            }
            OverlayLyricsService.setEnabled(context, true)
            OverlayLyricsService.start(context)
            overlayEnabled = true
        } else {
            OverlayLyricsService.setEnabled(context, false)
            OverlayLyricsService.stop(context)
            overlayEnabled = false
        }
    }
    fun setLyricsProviderEnabled(enabled: Boolean) {
        lyricsProviderEnabled = enabled
        preferences.edit { putBoolean("lyrics_provider_enabled", enabled) }
        if (enabled) LyricsInteropPublisher.publish(context, force = true)
        context.contentResolver.notifyChange(LyricsProviderContract.STATE_URI, null)
        context.contentResolver.notifyChange(LyricsProviderContract.LYRICS_URI, null)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("播放设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ListItem(
                headlineContent = { Text("全屏播放模式") },
                supportingContent = {
                    Text(
                        when {
                            !hasCurrentSong -> "请先播放一首歌曲后再开启"
                            fullscreenPlayerMode -> "锁定播放页；左上角退出模式，下滑或系统返回直接回到桌面"
                            else -> "隐藏音乐库与 Dock，锁定显示全屏播放页"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Fullscreen, null) },
                trailingContent = {
                    Switch(
                        checked = fullscreenPlayerMode,
                        onCheckedChange = onFullscreenPlayerModeChange,
                        enabled = hasCurrentSong
                    )
                },
                modifier = if (hasCurrentSong) {
                    Modifier.clickable {
                        onFullscreenPlayerModeChange(!fullscreenPlayerMode)
                    }
                } else {
                    Modifier
                }
            )
            ListItem(
                headlineContent = { Text("打碟模式") },
                supportingContent = {
                    Text("拖动播放进度时启用实时定位、动态速度与唱片转盘效果")
                },
                leadingContent = { Icon(Icons.Default.Album, null) },
                trailingContent = {
                    Switch(checked = isDjMode, onCheckedChange = onDjModeChange)
                },
                modifier = Modifier.clickable { onDjModeChange(!isDjMode) }
            )
            ListItem(
                headlineContent = { Text("悬浮歌词") },
                supportingContent = {
                    Text(
                        if (canDrawOverlays.value) {
                            "在其它应用上方显示可拖动的当前歌词，并按单字高亮进度"
                        } else {
                            "需要授予「显示在其他应用上层」权限，点击开关将跳转系统设置"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Subtitles, null) },
                trailingContent = {
                    Switch(
                        checked = overlayEnabled,
                        onCheckedChange = { setOverlayLyrics(it) }
                    )
                },
                modifier = Modifier.clickable { setOverlayLyrics(!overlayEnabled) }
            )
            ListItem(
                headlineContent = { Text("车载歌词") },
                supportingContent = {
                    Text("在 Android Auto、车载媒体卡片和支持媒体元数据的蓝牙车机上显示当前歌词")
                },
                leadingContent = { Icon(Icons.Default.DirectionsCar, null) },
                trailingContent = {
                    Switch(
                        checked = carLyricsEnabled,
                        onCheckedChange = {
                            carLyricsEnabled = it
                            preferences.edit { putBoolean("car_lyrics_enabled", it) }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    carLyricsEnabled = !carLyricsEnabled
                    preferences.edit { putBoolean("car_lyrics_enabled", carLyricsEnabled) }
                }
            )
            ListItem(
                headlineContent = { Text("通用歌词提供服务") },
                supportingContent = {
                    Text("向歌词软件发送歌曲、播放位置、逐字进度，并提供完整歌词查询接口")
                },
                leadingContent = { Icon(Icons.Default.SyncAlt, null) },
                trailingContent = {
                    Switch(
                        checked = lyricsProviderEnabled,
                        onCheckedChange = ::setLyricsProviderEnabled
                    )
                },
                modifier = Modifier.clickable {
                    setLyricsProviderEnabled(!lyricsProviderEnabled)
                }
            )
            var artworkBeatEnabled by remember {
                mutableStateOf(preferences.getBoolean("artwork_beat_enabled", true))
            }
            ListItem(
                headlineContent = { Text("专辑节奏跳动") },
                supportingContent = {
                    Text("播放时专辑封面跟随低频节奏缩放跳动")
                },
                leadingContent = { Icon(Icons.Default.BubbleChart, null) },
                trailingContent = {
                    Switch(
                        checked = artworkBeatEnabled,
                        onCheckedChange = {
                            artworkBeatEnabled = it
                            preferences.edit { putBoolean("artwork_beat_enabled", it) }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    artworkBeatEnabled = !artworkBeatEnabled
                    preferences.edit { putBoolean("artwork_beat_enabled", artworkBeatEnabled) }
                }
            )
            var spectrumProgressEnabled by remember {
                mutableStateOf(preferences.getBoolean("spectrum_progress_enabled", true))
            }
            ListItem(
                headlineContent = { Text("频谱进度条") },
                supportingContent = {
                    Text("播放页进度条叠加实时频谱，可随时开关")
                },
                leadingContent = { Icon(Icons.Default.GraphicEq, null) },
                trailingContent = {
                    Switch(
                        checked = spectrumProgressEnabled,
                        onCheckedChange = {
                            spectrumProgressEnabled = it
                            preferences.edit { putBoolean("spectrum_progress_enabled", it) }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    spectrumProgressEnabled = !spectrumProgressEnabled
                    preferences.edit { putBoolean("spectrum_progress_enabled", spectrumProgressEnabled) }
                }
            )
            var openPlayerOnSongClick by remember {
                mutableStateOf(preferences.getBoolean("open_player_on_song_click", false))
            }
            ListItem(
                headlineContent = { Text("点击歌曲进入播放页") },
                supportingContent = {
                    Text(
                        if (openPlayerOnSongClick) {
                            "点击列表歌曲后会进入全屏播放页"
                        } else {
                            "点击列表歌曲只播放，不进入播放页（可点底部迷你播放器进入）"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.OpenInFull, null) },
                trailingContent = {
                    Switch(
                        checked = openPlayerOnSongClick,
                        onCheckedChange = {
                            openPlayerOnSongClick = it
                            preferences.edit { putBoolean("open_player_on_song_click", it) }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    openPlayerOnSongClick = !openPlayerOnSongClick
                    preferences.edit { putBoolean("open_player_on_song_click", openPlayerOnSongClick) }
                }
            )
            ListItem(
                headlineContent = { Text("桌面歌词部件") },
                supportingContent = {
                    Text("长按桌面空白处 → 微件/小部件 → 添加「桌面歌词」，可显示歌词并控制播放")
                },
                leadingContent = { Icon(Icons.Default.Widgets, null) }
            )
            val audioOutput = rememberAudioOutputSnapshot()
            ListItem(
                headlineContent = { Text("当前输出设备") },
                supportingContent = {
                    Text(
                        buildString {
                            append(audioOutput.summary)
                            append("\n")
                            append(audioOutput.latencyLabel)
                            val details = buildList {
                                audioOutput.systemLatencyMs?.let { add("系统 ${it}ms") }
                                audioOutput.bufferLatencyMs?.let { add("缓冲 ${it}ms") }
                                audioOutput.sampleRateHz?.let { add("${it}Hz") }
                            }
                            if (details.isNotEmpty()) {
                                append("（")
                                append(details.joinToString(" · "))
                                append("）")
                            }
                            if (audioOutput.allOutputs.size > 1) {
                                append("\n可用：")
                                append(audioOutput.allOutputs.joinToString("、"))
                            }
                        }
                    )
                },
                leadingContent = {
                    val icon = when {
                        audioOutput.typeLabel.contains("蓝牙") -> Icons.Default.Bluetooth
                        audioOutput.typeLabel.contains("USB") -> Icons.Default.Usb
                        audioOutput.typeLabel.contains("耳机") -> Icons.Default.Headset
                        else -> Icons.Default.Speaker
                    }
                    Icon(icon, contentDescription = null)
                }
            )
        }
    }
}

private data class EqualizerPreset(val name: String, val gains: IntArray)

private val equalizerFrequencies = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
private val builtInEqualizerPresets = listOf(
    EqualizerPreset("平直", intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
    EqualizerPreset("摇滚", intArrayOf(5, 4, 3, 1, -1, -1, 1, 3, 4, 5)),
    EqualizerPreset("古典", intArrayOf(4, 3, 2, 1, 0, 0, 1, 2, 3, 4)),
    EqualizerPreset("流行", intArrayOf(-1, 1, 3, 4, 3, 1, -1, -1, 1, 2)),
    EqualizerPreset("爵士", intArrayOf(3, 2, 1, 2, -1, -1, 1, 2, 3, 4)),
    EqualizerPreset("人声清晰", intArrayOf(-3, -2, -1, 1, 3, 4, 4, 2, 1, 0)),
    EqualizerPreset("原声", intArrayOf(2, 2, 1, 0, 1, 2, 3, 3, 2, 1)),
    EqualizerPreset("电子", intArrayOf(5, 4, 1, 0, -2, 1, 2, 3, 5, 4)),
    EqualizerPreset("嘻哈", intArrayOf(6, 5, 3, 1, -1, -1, 1, 2, 3, 2)),
    EqualizerPreset("金属", intArrayOf(5, 4, 2, 0, -2, -1, 2, 4, 5, 4)),
    EqualizerPreset("影院", intArrayOf(4, 3, 2, 1, 0, 2, 3, 4, 3, 2)),
    EqualizerPreset("深夜", intArrayOf(-3, -2, 0, 2, 3, 3, 2, 0, -2, -3)),
    EqualizerPreset("低音增强", intArrayOf(7, 6, 4, 2, 0, -1, -1, 0, 1, 2)),
    EqualizerPreset("高保真", intArrayOf(3, 2, 1, 0, 0, 0, 1, 2, 3, 4)),
    EqualizerPreset("播客", intArrayOf(-4, -3, -1, 2, 4, 5, 4, 2, 0, -2))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE) }
    var gains by remember { mutableStateOf(loadEqualizerGains(preferences)) }
    var eqEnabled by remember { mutableStateOf(preferences.getBoolean("equalizer_enabled", true)) }
    var preampDb by remember { mutableIntStateOf(preferences.getInt("equalizer_preamp", 0).coerceIn(-6, 6)) }
    var bassBoost by remember { mutableIntStateOf(preferences.getInt("bass_boost", 0).coerceIn(0, 1000)) }
    var virtualizer by remember { mutableIntStateOf(preferences.getInt("virtualizer", 0).coerceIn(0, 1000)) }
    var selectedPreset by remember { mutableStateOf(preferences.getString("equalizer_preset_name", "自定义") ?: "自定义") }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    val applyScope = rememberCoroutineScope()
    var pendingApplyJob by remember { mutableStateOf<Job?>(null) }

    fun persistAndApply(
        nextGains: IntArray = gains,
        enabled: Boolean = eqEnabled,
        preamp: Int = preampDb,
        bass: Int = bassBoost,
        virt: Int = virtualizer,
        presetName: String = selectedPreset
    ) {
        preferences.edit {
            putString("equalizer_gains", nextGains.joinToString(","))
            putBoolean("equalizer_enabled", enabled)
            putInt("equalizer_preamp", preamp)
            putInt("bass_boost", bass)
            putInt("virtualizer", virt)
            putString("equalizer_preset_name", presetName)
        }
        pendingApplyJob?.cancel()
        pendingApplyJob = applyScope.launch {
            delay(35)
            applyEqualizerSettings(
                context = context,
                gains = nextGains,
                enabled = enabled,
                preampDb = preamp,
                bassBoost = bass,
                virtualizer = virt
            )
        }
    }

    // 进入页面时重新下发一次，确保挂到当前播放会话
    LaunchedEffect(Unit) {
        applyEqualizerSettings(context, gains, eqEnabled, preampDb, bassBoost, virtualizer)
    }

    val personalPreset = remember {
        preferences.getString("equalizer_custom_gains", null)?.let(::parseEqualizerGains)
    }
    val presets = remember(personalPreset) {
        builtInEqualizerPresets + listOfNotNull(personalPreset?.let { EqualizerPreset("个人", it) })
    }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("专业均衡器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            gains = IntArray(equalizerFrequencies.size)
                            preampDb = 0
                            bassBoost = 0
                            virtualizer = 0
                            selectedPreset = "平直"
                            persistAndApply(
                                nextGains = gains,
                                preamp = 0,
                                bass = 0,
                                virt = 0,
                                presetName = "平直"
                            )
                        }
                    ) {
                        Icon(Icons.Default.RestartAlt, "重置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            ListItem(
                headlineContent = { Text("启用均衡器") },
                supportingContent = {
                    Text(if (eqEnabled) "频段、前置增益、低音与环绕均生效" else "已旁路，输出接近原始音质")
                },
                leadingContent = { Icon(Icons.Default.GraphicEq, null) },
                trailingContent = {
                    Switch(
                        checked = eqEnabled,
                        onCheckedChange = {
                            eqEnabled = it
                            persistAndApply(enabled = it)
                        }
                    )
                },
                modifier = Modifier.clickable {
                    eqEnabled = !eqEnabled
                    persistAndApply(enabled = eqEnabled)
                }
            )

            Text(
                "10 段参数均衡 + 前置增益 + 低音增强 + 环绕声场。调节后立即作用于当前播放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Text("频率响应", style = MaterialTheme.typography.titleMedium, color = primary)
            Spacer(Modifier.height(8.dp))
            // 专业响应曲线
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                val left = 8.dp.toPx()
                val right = size.width - 8.dp.toPx()
                val top = 12.dp.toPx()
                val bottom = size.height - 18.dp.toPx()
                val midY = (top + bottom) / 2f
                val width = (right - left).coerceAtLeast(1f)
                val height = (bottom - top).coerceAtLeast(1f)

                // 网格
                for (i in 0..4) {
                    val y = top + height * i / 4f
                    drawLine(outline, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                }
                for (i in 0 until equalizerFrequencies.size) {
                    val x = left + width * i / (equalizerFrequencies.size - 1).coerceAtLeast(1)
                    drawLine(outline.copy(alpha = 0.2f), Offset(x, top), Offset(x, bottom), 1f)
                }
                // 0dB 线
                drawLine(primary.copy(alpha = 0.35f), Offset(left, midY), Offset(right, midY), 2f)

                if (gains.isNotEmpty()) {
                    val maxDb = 15f
                    val pts = gains.mapIndexed { index, g ->
                        val x = left + width * index / (gains.size - 1).coerceAtLeast(1)
                        val total = (g + preampDb).coerceIn(-15, 15).toFloat()
                        val y = midY - (total / maxDb) * (height / 2f)
                        Offset(x, y)
                    }
                    // 填充
                    val fillPath = Path().apply {
                        moveTo(pts.first().x, midY)
                        pts.forEach { lineTo(it.x, it.y) }
                        lineTo(pts.last().x, midY)
                        close()
                    }
                    drawPath(
                        fillPath,
                        brush = Brush.verticalGradient(
                            listOf(primary.copy(alpha = 0.28f), secondary.copy(alpha = 0.08f))
                        )
                    )
                    // 曲线
                    for (i in 0 until pts.lastIndex) {
                        drawLine(
                            color = if (eqEnabled) primary else outline,
                            start = pts[i],
                            end = pts[i + 1],
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                    pts.forEach { p ->
                        drawCircle(
                            color = if (eqEnabled) secondary else outline,
                            radius = 3.5.dp.toPx(),
                            center = p
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(onClick = { presetMenuExpanded = true }, enabled = eqEnabled) {
                    Icon(Icons.Default.Tune, null)
                    Spacer(Modifier.width(8.dp))
                    Text("预设：$selectedPreset")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(
                    expanded = presetMenuExpanded,
                    onDismissRequest = { presetMenuExpanded = false }
                ) {
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                gains = preset.gains.copyOf()
                                selectedPreset = preset.name
                                presetMenuExpanded = false
                                persistAndApply(nextGains = gains, presetName = preset.name)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("前置增益", style = MaterialTheme.typography.titleSmall, color = primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preamp", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = preampDb.toFloat(),
                    onValueChange = {
                        preampDb = it.toInt()
                        selectedPreset = "自定义"
                        persistAndApply(preamp = preampDb, presetName = "自定义")
                    },
                    valueRange = -6f..6f,
                    steps = 11,
                    enabled = eqEnabled,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format(Locale.getDefault(), "%+d dB", preampDb),
                    modifier = Modifier.width(62.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Text("低音增强", style = MaterialTheme.typography.titleSmall, color = primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bass", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = bassBoost / 10f,
                    onValueChange = {
                        bassBoost = (it * 10f).toInt().coerceIn(0, 1000)
                        persistAndApply(bass = bassBoost)
                    },
                    valueRange = 0f..100f,
                    enabled = eqEnabled,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${bassBoost / 10}%",
                    modifier = Modifier.width(62.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Text("环绕声场", style = MaterialTheme.typography.titleSmall, color = primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Space", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = virtualizer / 10f,
                    onValueChange = {
                        virtualizer = (it * 10f).toInt().coerceIn(0, 1000)
                        persistAndApply(virt = virtualizer)
                    },
                    valueRange = 0f..100f,
                    enabled = eqEnabled,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${virtualizer / 10}%",
                    modifier = Modifier.width(62.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("十段参数均衡", style = MaterialTheme.typography.titleMedium, color = primary)
            Spacer(Modifier.height(4.dp))
            equalizerFrequencies.forEachIndexed { index, frequency ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatEqualizerFrequency(frequency),
                        modifier = Modifier.width(56.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = gains[index].toFloat(),
                        onValueChange = { value ->
                            gains = gains.copyOf().also { it[index] = value.toInt() }
                            selectedPreset = "自定义"
                            persistAndApply(nextGains = gains, presetName = "自定义")
                        },
                        valueRange = -15f..15f,
                        steps = 29,
                        enabled = eqEnabled,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%+d dB", gains[index]),
                        modifier = Modifier.width(62.dp),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            OutlinedButton(
                onClick = {
                    preferences.edit { putString("equalizer_custom_gains", gains.joinToString(",")) }
                    selectedPreset = "个人"
                    preferences.edit { putString("equalizer_preset_name", "个人") }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = eqEnabled
            ) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(6.dp))
                Text("保存为个人预设")
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}


private fun loadEqualizerGains(preferences: android.content.SharedPreferences): IntArray =
    preferences.getString("equalizer_gains", null)?.let(::parseEqualizerGains)
        ?: IntArray(equalizerFrequencies.size)

private fun parseEqualizerGains(value: String): IntArray {
    val values = value.split(',').mapNotNull { it.toIntOrNull() }
    return IntArray(equalizerFrequencies.size) { values.getOrElse(it) { 0 }.coerceIn(-15, 15) }
}

private fun applyEqualizerSettings(
    context: Context,
    gains: IntArray,
    enabled: Boolean = true,
    preampDb: Int = 0,
    bassBoost: Int = 0,
    virtualizer: Int = 0
) {
    context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        .edit {
            putString("equalizer_gains", gains.joinToString(","))
            putBoolean("equalizer_enabled", enabled)
            putInt("equalizer_preamp", preampDb)
            putInt("bass_boost", bassBoost)
            putInt("virtualizer", virtualizer)
        }
    val intent = Intent(context, PlaybackService::class.java)
        .setAction(PlaybackService.ACTION_APPLY_EQUALIZER)
        .putExtra(PlaybackService.EXTRA_GAINS, gains.copyOf())
        .putExtra(PlaybackService.EXTRA_EQ_ENABLED, enabled)
        .putExtra(PlaybackService.EXTRA_PREAMP, preampDb)
        .putExtra(PlaybackService.EXTRA_BASS_BOOST, bassBoost)
        .putExtra(PlaybackService.EXTRA_VIRTUALIZER, virtualizer)
    // 前台应用内启动：优先普通 startService；失败再尝试前台服务启动
    runCatching { context.startService(intent) }
        .recoverCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        .onFailure {
            android.util.Log.e("Equalizer", "无法将均衡器参数发送到播放服务: ${it.message}")
        }
}

private fun formatEqualizerFrequency(frequency: Int): String =
    if (frequency >= 1_000) "${frequency / 1_000}kHz" else "${frequency}Hz"



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    directories: MutableList<Uri>,
    isScanning: Boolean,
    onScanNow: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!directories.contains(it)) directories.add(it)
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("音乐库管理") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) },
        floatingActionButton = {
            // 立即扫描按钮：扫描中显示进度指示器
            FloatingActionButton(onClick = onScanNow) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Icon(Icons.Default.Refresh, null)
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            // 扫描控制区域
            item {
                Column(Modifier.padding(16.dp)) {
                    // 状态行
                    Text(
                        if (isScanning) "正在扫描…" else if (directories.isEmpty()) "尚未添加音乐库目录" else "共 ${directories.size} 个音乐库目录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isScanning) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(16.dp))
                                Text("正在扫描音乐库，由于需要读取元数据，界面出现卡顿为正常情况，请稍后...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // 醒目的"立即扫描"按钮
                    Button(
                        onClick = onScanNow,
                        enabled = !isScanning && directories.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isScanning) "扫描中…" else "立即扫描")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点击按钮扫描已添加的音乐库目录；为加快应用启动速度，应用不会自动扫描",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                }
            }
            // 已添加的目录列表
            items(directories) { uri ->
                ListItem(
                    headlineContent = { Text(uri.path ?: "Unknown") },
                    trailingContent = {
                        IconButton(onClick = { directories.remove(uri) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    }
                )
            }
            // 底部：添加目录按钮
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                    OutlinedButton(onClick = { launcher.launch(null) }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加音乐库目录")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(onBack: () -> Unit, onRestart: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }

    // 导出：使用 SAF CreateDocument 让用户选择保存位置
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            isWorking = true
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    DataManager.exportConfig(context, os)
                }
                scope.launch { snackbarHostState.showSnackbar("已导出配置") }
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("导出失败: ${e.message}") }
            } finally {
                isWorking = false
            }
        }
    }

    // 导入：使用 SAF OpenDocument 选择 JSON 文件
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isWorking = true
            val success = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    DataManager.importConfig(context, stream)
                } ?: false
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("导入失败: ${e.message}") }
                false
            }
            if (success) {
                scope.launch {
                    snackbarHostState.showSnackbar("导入成功，正在重启…")
                    kotlinx.coroutines.delay(600)
                    onRestart()
                }
            } else {
                isWorking = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ListItem(
                headlineContent = { Text("清理封面缓存") },
                supportingContent = { Text("删除已缓存的专辑封面（下次扫描时重新提取）") },
                leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                trailingContent = {
                    TextButton(
                        onClick = { showClearCacheDialog = true },
                        enabled = !isWorking
                    ) { Text("清理") }
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("清除全部数据") },
                supportingContent = { Text("删除扫描记录、封面缓存、音乐库目录与个性化设置（不可恢复）") },
                leadingContent = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                trailingContent = {
                    TextButton(
                        onClick = { showClearAllDialog = true },
                        enabled = !isWorking
                    ) { Text("清除", color = MaterialTheme.colorScheme.error) }
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("导出配置文件") },
                supportingContent = { Text("将所有设置、音乐库目录、扫描记录保存为 JSON 文件") },
                leadingContent = { Icon(Icons.Default.Upload, null) },
                trailingContent = {
                    TextButton(
                        onClick = { exportLauncher.launch("music_config_${System.currentTimeMillis()}.json") },
                        enabled = !isWorking
                    ) { Text("导出") }
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("导入配置文件") },
                supportingContent = { Text("从 JSON 文件恢复设置（导入后将自动重启应用）") },
                leadingContent = { Icon(Icons.Default.Download, null) },
                trailingContent = {
                    TextButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        enabled = !isWorking
                    ) { Text("导入") }
                }
            )
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("清理封面缓存？") },
            text = { Text("将删除所有已缓存的专辑封面图片；下次扫描音乐库时会自动重新提取。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    val freed = DataManager.clearCache(context)
                    scope.launch { snackbarHostState.showSnackbar("已清理 ${DataManager.formatSize(freed)}") }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") } }
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("清除全部数据？") },
            text = { Text("将删除所有扫描记录、封面缓存、音乐库目录、个性化设置。此操作不可恢复，建议先导出配置。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllDialog = false
                    DataManager.clearAllData(context)
                    scope.launch {
                        snackbarHostState.showSnackbar("已清除全部数据，正在重启…")
                        kotlinx.coroutines.delay(600)
                        onRestart()
                    }
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearAllDialog = false }) { Text("取消") } }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MD3MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlay: () -> Unit,
    onClick: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit
) {
    val artwork = rememberArtwork(song)
    var sliderPosition by remember(progress) { mutableFloatStateOf(progress) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clickable { onClick() }
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                if (artwork != null) {
                    androidx.compose.foundation.Image(
                        artwork.asImageBitmap(),
                        null,
                        Modifier.size(56.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer),
                        Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, null)
                    }
                    IconButton(
                        onClick = onTogglePlay,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(28.dp))
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, null)
                    }
                }
            }
            
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = { onSeek(sliderPosition) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            )
        }
    }
}

/**
 * 按需加载封面：优先使用 song.artwork；否则在后台解码 artwork/<hash>.png
 * - 单张图片解码，IO 阻塞很短
 * - 使用 key 标识，当 song 变化时重新加载
 */
@Composable
fun rememberArtwork(song: Song): Bitmap? {
    val context = LocalContext.current
    var loaded by remember(song.uri, song.artwork) { mutableStateOf(song.artwork) }
    LaunchedEffect(song.uri) {
        if (loaded == null) {
            val bitmap = withContext(Dispatchers.IO) {
                ScanRecordsManager.loadArtworkAsync(context, song)?.artwork
            }
            if (bitmap != null) loaded = bitmap
        }
    }
    return loaded
}

/** 音频格式信息：采样率 / 比特率 / 编码 / 容器 */
data class AudioFormatInfo(
    val sampleRateHz: Int,   // 例如 44100
    val bitrateKbps: Int,    // 例如 320 (kbps)
    val mimeType: String     // 例如 audio/mp4 / audio/flac
)

/** 解析音频格式字符串（如 "44100 Hz"、"320 kb/s"） */
private fun parseSampleRate(s: String?): Int? = s?.removeSuffix(" Hz")?.trim()?.toIntOrNull()
private fun parseBitrate(s: String?): Int? {
    if (s == null) return null
    val trimmed = s.removeSuffix(" kb/s").removeSuffix(" kbps").trim()
    val raw = trimmed.toIntOrNull() ?: return null
    return if (raw > 10_000) raw / 1_000 else raw
}

/** 从 URI 提取音频格式信息（在后台线程调用） */
fun extractAudioFormat(context: android.content.Context, uri: Uri): AudioFormatInfo? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val sr = parseSampleRate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)) ?: 0
        val br = parseBitrate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)) ?: 0
        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
        AudioFormatInfo(sr, br, mime)
    } catch (e: Exception) {
        null
    } finally {
        try { retriever.release() } catch (_: Exception) {}
    }
}

/** 音质标签：Lossless（FLAC/高码率）、Hi-Res（高采样率）、标准、未知 */
private fun audioQuality(info: AudioFormatInfo?): String? {
    if (info == null) return null
    val ext = info.mimeType.substringAfterLast("/").lowercase()
    val sr = info.sampleRateHz
    val br = info.bitrateKbps
    return when {
        ext in listOf("flac", "x-flac", "alac", "wav", "x-wav") -> "无损"
        sr >= 88200 -> "Hi-Res"
        br >= 320 && ext in listOf("mp3", "mpeg") -> "高清"
        else -> null
    }
}

/** 短标签：44.1kHz / 16-bit 等 */
private fun formatSampleRate(sr: Int): String = if (sr > 0) "%.1fkHz".format(sr / 1000.0) else ""
private fun formatBitrate(br: Int): String = if (br > 0) "${br} kbps" else ""

/**
 * 音频格式徽章：在播放页标题/作者下方显示音质 + 文件大小
 * 每个徽章包含图标 + 短文本
 */
@Composable
fun AudioFormatBadges(
    audioFormat: AudioFormatInfo?,
    fileSize: String,
    centered: Boolean = true,
    modifier: Modifier = Modifier
) {
    val quality = audioQuality(audioFormat)
    val srText = formatSampleRate(audioFormat?.sampleRateHz ?: 0)
    val brText = formatBitrate(audioFormat?.bitrateKbps ?: 0)
    val items = buildList {
        quality?.let { add(it) }
        if (srText.isNotEmpty()) add(srText)
        if (brText.isNotEmpty()) add(brText)
        if (fileSize.isNotBlank()) add(fileSize)
    }
    if (items.isEmpty()) return
    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .padding(top = 6.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start
    ) {
        items.forEachIndexed { i, text ->
            // 每个徽章：图标 + 文本
            val icon: androidx.compose.ui.graphics.vector.ImageVector = when {
                text == "无损" -> Icons.Default.HighQuality
                text == "Hi-Res" -> Icons.Default.GraphicEq
                text == "高清" -> Icons.Default.GraphicEq
                text.endsWith("kHz") -> Icons.Default.GraphicEq
                text.endsWith("kbps") -> Icons.Default.Speed
                else -> Icons.Default.Folder  // 文件大小
            }
            Row(
                modifier = Modifier
                    .padding(end = if (i == items.lastIndex) 0.dp else 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(containerColor)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, Modifier.size(14.dp), tint = contentColor)
                Spacer(Modifier.width(4.dp))
                Text(text, style = MaterialTheme.typography.labelSmall, color = contentColor)
            }
        }
    }
}

@Composable
fun AudioOutputBadge(
    output: AudioOutputSnapshot,
    centered: Boolean = true,
    modifier: Modifier = Modifier
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val icon = when {
        output.typeLabel.contains("蓝牙") -> Icons.Default.Bluetooth
        output.typeLabel.contains("USB") -> Icons.Default.Usb
        output.typeLabel.contains("耳机") -> Icons.Default.Headset
        output.typeLabel.contains("HDMI") -> Icons.Default.SettingsInputHdmi
        output.typeLabel.contains("扬声器") || output.typeLabel.contains("听筒") -> Icons.Default.Speaker
        else -> Icons.Default.SpeakerGroup
    }
    Row(
        modifier = modifier
            .padding(top = 6.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(containerColor)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = "输出设备", Modifier.size(14.dp), tint = contentColor)
            Spacer(Modifier.width(4.dp))
            Text(
                text = buildString {
                    append("输出 · ")
                    append(output.summary)
                    if (output.latencyMs != null) {
                        append(" · ")
                        append(output.latencyLabel)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AnimatedSongText(
    song: Song,
    songs: List<Song>,
    titleStyle: androidx.compose.ui.text.TextStyle,
    titleModifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = song.uri,
        modifier = titleModifier,
        transitionSpec = { songChangeTransition(songs, initialState, targetState) },
        label = "SongTitleTransition"
    ) { uri ->
        val targetSong = songs.firstOrNull { it.uri == uri } ?: song
        Text(
            targetSong.title,
            style = titleStyle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AnimatedArtistText(
    song: Song,
    songs: List<Song>,
    style: androidx.compose.ui.text.TextStyle
) {
    AnimatedContent(
        targetState = song.uri,
        transitionSpec = { songChangeTransition(songs, initialState, targetState) },
        label = "SongArtistTransition"
    ) { uri ->
        val targetSong = songs.firstOrNull { it.uri == uri } ?: song
        Text(
            targetSong.artist,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

private fun songChangeTransition(
    songs: List<Song>,
    initialUri: Uri,
    targetUri: Uri
): ContentTransform {
    val initialIndex = songs.indexOfFirst { it.uri == initialUri }
    val targetIndex = songs.indexOfFirst { it.uri == targetUri }
    val direction = if (targetIndex < 0 || initialIndex < 0 || targetIndex >= initialIndex) 1 else -1
    return (slideInHorizontally(tween(420)) { it * direction } + fadeIn(tween(280))) togetherWith
        (slideOutHorizontally(tween(420)) { -it * direction } + fadeOut(tween(220)))
}

@Composable
fun FullPlayerScreen(
    song: Song,
    lyrics: List<Pair<Long, String>>,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    isDjMode: Boolean,
    onScratchStart: () -> Unit,
    onScratch: (Long, Float) -> Unit,
    onScratchEnd: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onBack: () -> Unit,
    onExitFullscreenMode: () -> Unit,
    fullscreenPlayerMode: Boolean,
    context: android.content.Context,
    musicDirectories: List<Uri>,
    songs: List<Song>,
    onSelectSong: (Song) -> Unit,
    repeatMode: Int,
    onRepeatModeChange: () -> Unit,
    shuffleMode: Boolean,
    onShuffleModeChange: () -> Unit,
    isLandscape: Boolean
) {
    val configuration = LocalConfiguration.current
    val artworkSize = if (isLandscape) minOf(220, (configuration.screenHeightDp * 0.34f).toInt()).dp else 300.dp
    val pagePadding = if (isLandscape) 16.dp else 32.dp
    val sectionPadding = if (isLandscape) 4.dp else 16.dp
    var dismissDragOffset by remember { mutableFloatStateOf(0f) }
    var dismissGestureActive by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var quickListExpanded by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var turntableRotation by remember { mutableFloatStateOf(0f) }
    var scratchSpeed by remember { mutableFloatStateOf(1f) }
    var lastDragPosition by remember { mutableFloatStateOf(0f) }
    var lastDragTime by remember { mutableLongStateOf(0L) }
    var lastScratchDispatchTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(position, duration) { if (!isDragging) sliderPosition = if (duration > 0) position.toFloat() / duration else 0f }
    LaunchedEffect(isDjMode, isPlaying, isDragging) {
        while (isDjMode && isPlaying && !isDragging) {
            turntableRotation = (turntableRotation + 1.8f) % 360f
            delay(SmoothAnimationFrameRate.frameDelayMillis)
        }
    }

    // 按需加载封面（避免播放页首次显示时主线程解码卡顿）
    val playerArtwork = rememberArtwork(song)
    val playerPrefs = remember {
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
    }
    var artworkBeatEnabled by remember {
        mutableStateOf(playerPrefs.getBoolean("artwork_beat_enabled", true))
    }
    var spectrumProgressEnabled by remember {
        mutableStateOf(playerPrefs.getBoolean("spectrum_progress_enabled", true))
    }
    // 从设置返回时刷新开关
    LaunchedEffect(song.uri, isPlaying) {
        artworkBeatEnabled = playerPrefs.getBoolean("artwork_beat_enabled", true)
        spectrumProgressEnabled = playerPrefs.getBoolean("spectrum_progress_enabled", true)
    }
    val beatEnergy = rememberBeatEnergy(isPlaying = isPlaying, enabled = artworkBeatEnabled)
    val beatScale by animateFloatAsState(
        targetValue = beatScaleFromEnergy(beatEnergy),
        animationSpec = tween(durationMillis = 100),
        label = "ArtworkBeatScale"
    )
    // 按需加载音频格式信息（采样率/比特率），不阻塞主线程
    val audioFormat = remember(song.uri) {
        mutableStateOf<AudioFormatInfo?>(null)
    }
    val audioOutput = rememberAudioOutputSnapshot()
    LaunchedEffect(song.uri) {
        if (audioFormat.value == null) {
            val info = withContext(Dispatchers.IO) {
                extractAudioFormat(context, song.uri)
            }
            audioFormat.value = info
        }
    }

    // 检测是否支持并启用了模糊（API 31+ 且系统未禁用模糊）
    val isBlurSupported = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.isCrossWindowBlurEnabled
        } else {
            false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dismissDragOffset
                alpha = (1f - dismissDragOffset / (size.height.coerceAtLeast(1f) * 1.5f)).coerceIn(0.72f, 1f)
            }
            .pointerInput(onBack) {
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        dismissGestureActive = start.y <= size.height * 0.32f
                        dismissDragOffset = 0f
                    },
                    onVerticalDrag = { change, amount ->
                        if (dismissGestureActive) {
                            change.consume()
                            dismissDragOffset = (dismissDragOffset + amount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (dismissGestureActive && dismissDragOffset >= 120.dp.toPx()) onBack()
                        dismissDragOffset = 0f
                        dismissGestureActive = false
                    },
                    onDragCancel = {
                        dismissDragOffset = 0f
                        dismissGestureActive = false
                    }
                )
            }
    ) {
        // 背景层随歌曲封面交叉渐变，避免切歌时瞬间跳变。
        AnimatedContent(
            targetState = song.uri,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                fadeIn(tween(700)) togetherWith fadeOut(tween(700))
            },
            label = "PlayerBackgroundTransition"
        ) { targetUri ->
            val targetSong = songs.firstOrNull { it.uri == targetUri } ?: song
            val targetArtwork = rememberArtwork(targetSong)
            Box(Modifier.fillMaxSize()) {
                if (targetArtwork != null && isBlurSupported) {
                    androidx.compose.foundation.Image(
                        bitmap = targetArtwork.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().blur(60.dp),
                        contentScale = ContentScale.Crop
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                }
            }
        }
        if (isLandscape) {
            Column(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(0.42f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            IconButton(
                                onClick = if (fullscreenPlayerMode) onExitFullscreenMode else onBack
                            ) {
                                Icon(
                                    imageVector = if (fullscreenPlayerMode) {
                                        Icons.Default.FullscreenExit
                                    } else {
                                        Icons.Default.KeyboardArrowDown
                                    },
                                    contentDescription = if (fullscreenPlayerMode) "退出全屏模式" else "返回",
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AnimatedContent(
                                targetState = song.uri,
                                transitionSpec = { songChangeTransition(songs, initialState, targetState) },
                                label = "LandscapeArtworkTransition"
                            ) { targetUri ->
                                val targetSong = songs.firstOrNull { it.uri == targetUri } ?: song
                                val targetArtwork = rememberArtwork(targetSong)
                                if (targetArtwork != null) {
                                    androidx.compose.foundation.Image(
                                        targetArtwork.asImageBitmap(),
                                        null,
                                        Modifier
                                            .size(artworkSize)
                                            .graphicsLayer {
                                                scaleX = beatScale
                                                scaleY = beatScale
                                            }
                                            .clip(RoundedCornerShape(20.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Surface(
                                        Modifier
                                            .size(artworkSize)
                                            .graphicsLayer {
                                                scaleX = beatScale
                                                scaleY = beatScale
                                            }
                                            .clip(RoundedCornerShape(20.dp)),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.MusicNote, null, Modifier.size(64.dp))
                                        }
                                    }
                                }
                            }
                        }
                        AnimatedSongText(song, songs, MaterialTheme.typography.titleMedium)
                        AnimatedArtistText(song, songs, MaterialTheme.typography.bodyMedium)
                    }

                    Box(
                        modifier = Modifier.weight(0.58f).fillMaxHeight().padding(top = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LyricView(lyrics, position, duration, onSeek)
                    }
                }

                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    SpectrumProgressSlider(
                        value = sliderPosition,
                        onValueChange = {
                            isDragging = true
                            sliderPosition = it
                        },
                        onValueChangeFinished = {
                            onSeek((sliderPosition * duration).toLong())
                            isDragging = false
                        },
                        isPlaying = isPlaying,
                        spectrumEnabled = spectrumProgressEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        val shownPosition = if (isDragging) (sliderPosition * duration).toLong() else position
                        Text(formatTime(shownPosition), style = MaterialTheme.typography.bodySmall)
                        Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
                    }
                    ControllerRow(
                        onPrevious = onPrevious,
                        onTogglePlay = onTogglePlay,
                        onNext = onNext,
                        isPlaying = isPlaying,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                        repeatMode = repeatMode,
                        onRepeatModeChange = onRepeatModeChange,
                        shuffleMode = shuffleMode,
                        onShuffleModeChange = onShuffleModeChange
                    )
                }
            }
        } else {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(pagePadding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            // 顶部：仅返回按钮
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = if (fullscreenPlayerMode) onExitFullscreenMode else onBack
                ) {
                    Icon(
                        imageVector = if (fullscreenPlayerMode) {
                            Icons.Default.FullscreenExit
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (fullscreenPlayerMode) "退出全屏模式" else "返回",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            // 中部：歌词/专辑，带切换动画
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                AnimatedContent(
                    targetState = showLyrics to song.uri,
                    transitionSpec = {
                        if (initialState.first != targetState.first) {
                            (fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.92f)) togetherWith
                                fadeOut(animationSpec = tween(400))
                        } else {
                            val initialIndex = songs.indexOfFirst { it.uri == initialState.second }
                            val targetIndex = songs.indexOfFirst { it.uri == targetState.second }
                            val direction = if (targetIndex < 0 || initialIndex < 0 || targetIndex >= initialIndex) 1 else -1
                            (slideInHorizontally(tween(420)) { it * direction } + fadeIn(tween(280))) togetherWith
                                (slideOutHorizontally(tween(420)) { -it * direction } + fadeOut(tween(220)))
                        }
                    },
                    label = "SongArtworkTransition"
                ) { (targetShowLyrics, targetUri) ->
                    val targetSong = songs.firstOrNull { it.uri == targetUri } ?: song
                    val targetArtwork = rememberArtwork(targetSong)
                    if (targetShowLyrics) {
                        LyricView(lyrics, position, duration, onSeek)
                    } else {
                        if (isDjMode) {
                            DjTurntable(
                                artwork = targetArtwork,
                                size = artworkSize,
                                rotation = turntableRotation,
                                speed = scratchSpeed,
                                isScratching = isDragging,
                                beatScale = beatScale,
                                onClick = { showLyrics = true }
                            )
                        } else if (targetArtwork != null) {
                            androidx.compose.foundation.Image(
                                targetArtwork.asImageBitmap(),
                                null,
                                Modifier
                                    .size(artworkSize)
                                    .graphicsLayer {
                                        scaleX = beatScale
                                        scaleY = beatScale
                                    }
                                    .clip(RoundedCornerShape(if (isLandscape) 20.dp else 32.dp))
                                    .clickable { showLyrics = true },
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Surface(
                                Modifier
                                    .size(artworkSize)
                                    .graphicsLayer {
                                        scaleX = beatScale
                                        scaleY = beatScale
                                    }
                                    .clip(RoundedCornerShape(if (isLandscape) 20.dp else 32.dp))
                                    .clickable { showLyrics = true },
                                shadowElevation = 16.dp,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(120.dp), MaterialTheme.colorScheme.onPrimaryContainer) } }
                        }
                    }
                }
            }
            // 标题/作者区域：歌词模式下专辑图缩小并移至左侧
            if (showLyrics) {
                Row(Modifier.fillMaxWidth().padding(vertical = sectionPadding), verticalAlignment = Alignment.CenterVertically) {
                    // 小专辑封面：唯一可点击退出歌词模式的区域
                    if (playerArtwork != null) {
                        androidx.compose.foundation.Image(
                            playerArtwork.asImageBitmap(),
                            null,
                            Modifier
                                .size(56.dp)
                                .graphicsLayer {
                                    val s = 1f + (beatScale - 1f) * 0.7f
                                    scaleX = s
                                    scaleY = s
                                }
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showLyrics = false },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            Modifier
                                .size(56.dp)
                                .graphicsLayer {
                                    val s = 1f + (beatScale - 1f) * 0.7f
                                    scaleX = s
                                    scaleY = s
                                }
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showLyrics = false },
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(28.dp), MaterialTheme.colorScheme.onPrimaryContainer) } }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedSongText(song = song, songs = songs, titleStyle = MaterialTheme.typography.titleLarge, titleModifier = Modifier.weight(1f))
                            Box {
                                IconButton(onClick = { quickListExpanded = true }) {
                                    Icon(Icons.Default.QueueMusic, "快捷音乐列表")
                                }
                                DropdownMenu(
                                    expanded = quickListExpanded,
                                    onDismissRequest = { quickListExpanded = false }
                                ) {
                                    songs.forEach { item ->
                                        DropdownMenuItem(
                                            text = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = if (item.uri == song.uri) {
                                                { Icon(Icons.Default.PlayArrow, null) }
                                            } else null,
                                            onClick = {
                                                quickListExpanded = false
                                                onSelectSong(item)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        AnimatedArtistText(song = song, songs = songs, style = MaterialTheme.typography.bodyMedium)
                        AudioFormatBadges(audioFormat = audioFormat.value, fileSize = song.duration, centered = false)
                        AudioOutputBadge(output = audioOutput, centered = false)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(vertical = sectionPadding), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            AnimatedSongText(song = song, songs = songs, titleStyle = MaterialTheme.typography.headlineMedium)
                        }
                        Box {
                            IconButton(onClick = { quickListExpanded = true }) {
                                Icon(Icons.Default.QueueMusic, "快捷音乐列表")
                            }
                            DropdownMenu(
                                expanded = quickListExpanded,
                                onDismissRequest = { quickListExpanded = false }
                            ) {
                                songs.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = if (item.uri == song.uri) {
                                            { Icon(Icons.Default.PlayArrow, null) }
                                        } else null,
                                        onClick = {
                                            quickListExpanded = false
                                            onSelectSong(item)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    AnimatedArtistText(song = song, songs = songs, style = MaterialTheme.typography.titleMedium)
                    AudioFormatBadges(audioFormat = audioFormat.value, fileSize = song.duration, centered = true, modifier = Modifier.fillMaxWidth())
                    AudioOutputBadge(output = audioOutput, centered = true, modifier = Modifier.fillMaxWidth())
                }
            }
            Column(Modifier.fillMaxWidth()) {
                SpectrumProgressSlider(
                    value = sliderPosition,
                    onValueChange = { value ->
                        val now = android.os.SystemClock.uptimeMillis()
                        if (!isDragging) {
                            isDragging = true
                            lastDragPosition = sliderPosition
                            lastDragTime = now
                            lastScratchDispatchTime = 0L
                            if (isDjMode) onScratchStart()
                        }
                        val elapsedMs = (now - lastDragTime).coerceAtLeast(1L)
                        val sliderDelta = value - lastDragPosition
                        sliderPosition = value
                        if (isDjMode && duration > 0L) {
                            val mediaDeltaMs = kotlin.math.abs(sliderDelta) * duration
                            scratchSpeed = (mediaDeltaMs / elapsedMs.toFloat())
                                .coerceIn(0.25f, 2f)
                            turntableRotation = (turntableRotation + sliderDelta * 1_440f) % 360f
                            if (now - lastScratchDispatchTime >= 24L) {
                                onScratch((value * duration).toLong(), scratchSpeed)
                                lastScratchDispatchTime = now
                            }
                        }
                        lastDragPosition = value
                        lastDragTime = now
                    },
                    onValueChangeFinished = {
                        val targetPosition = (sliderPosition * duration).toLong()
                        if (isDjMode) onScratchEnd(targetPosition) else onSeek(targetPosition)
                        scratchSpeed = 1f
                        isDragging = false
                    },
                    isPlaying = isPlaying,
                    spectrumEnabled = spectrumProgressEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    val dPos = if (isDragging) (sliderPosition * duration).toLong() else position
                    Text(formatTime(dPos), style = MaterialTheme.typography.bodySmall); Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
                }
            }
            // 播放控制：始终在屏幕底部
            ControllerRow(
                onPrevious = onPrevious,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                isPlaying = isPlaying,
                compact = false,
                modifier = Modifier.fillMaxWidth().padding(vertical = sectionPadding),
                repeatMode = repeatMode,
                onRepeatModeChange = onRepeatModeChange,
                shuffleMode = shuffleMode,
                onShuffleModeChange = onShuffleModeChange
            )
        }
        }
    }
    // 已移除：全屏点击 Box —— 歌词模式下只有小专辑封面可以退出歌词模式
}

@Composable
fun DjTurntable(
    artwork: Bitmap?,
    size: androidx.compose.ui.unit.Dp = 300.dp,
    rotation: Float,
    speed: Float,
    isScratching: Boolean,
    beatScale: Float = 1f,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = beatScale
                scaleY = beatScale
            }
            .clip(CircleShape)
            .background(Color(0xFF101010))
            .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        listOf(size * 0.94f, size * 0.85f, size * 0.75f).forEach { diameter ->
            Box(
                Modifier
                    .size(diameter)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(size * 0.7f)
                .graphicsLayer { rotationZ = rotation }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (artwork != null) {
                androidx.compose.foundation.Image(
                    bitmap = artwork.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(88.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF101010))
                    .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            )
        }
        if (isScratching) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f)
            ) {
                Text(
                    text = String.format(Locale.getDefault(), "%.2fx", speed),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}

// 播放控件行：上一首 / 播放-暂停 / 下一首
// compact = true 用于顶部 Row（与返回按钮同行，控件较小）
// compact = false 用于底部独立一行（控件较大）
@Composable
fun ControllerRow(
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    isPlaying: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    repeatMode: Int = Player.REPEAT_MODE_OFF,
    onRepeatModeChange: () -> Unit = {},
    shuffleMode: Boolean = false,
    onShuffleModeChange: () -> Unit = {}
) {
    val prevSize = if (compact) 48.dp else 64.dp
    val prevIconSize = if (compact) 32.dp else 48.dp
    val playSize = if (compact) 56.dp else 80.dp
    val playIconSize = if (compact) 28.dp else 40.dp
    val secondaryIconSize = if (compact) 24.dp else 32.dp
    val arrangement = if (compact) Arrangement.spacedBy(4.dp) else Arrangement.SpaceEvenly
    Row(modifier = modifier, horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        if (!compact) {
            IconButton(onClick = onShuffleModeChange) {
                Icon(
                    if (shuffleMode) Icons.Default.Shuffle else Icons.Default.FormatListNumbered,
                    null,
                    Modifier.size(secondaryIconSize),
                    tint = if (shuffleMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onPrevious, Modifier.size(prevSize)) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(prevIconSize)) }
        Surface(onClick = onTogglePlay, shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(playSize)) {
            Box(contentAlignment = Alignment.Center) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(playIconSize)) }
        }
        IconButton(onClick = onNext, Modifier.size(prevSize)) { Icon(Icons.Default.SkipNext, null, Modifier.size(prevIconSize)) }
        if (!compact) {
            IconButton(onClick = onRepeatModeChange) {
                val icon = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                    Player.REPEAT_MODE_ALL -> Icons.Default.Repeat
                    else -> Icons.Default.TrendingFlat
                }
                Icon(
                    icon,
                    null,
                    Modifier.size(secondaryIconSize),
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LyricView(
    lyrics: List<Pair<Long, String>>,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit
) {
    if (lyrics.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("未找到歌词", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val lineIdx = lyrics.indexOfLast { it.first <= currentPosition }
    val listState = rememberLazyListState()
    LaunchedEffect(lineIdx) {
        if (lineIdx >= 0) listState.animateScrollToItem(
            index = lineIdx.coerceAtMost(lyrics.lastIndex),
            scrollOffset = -240
        )
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        contentPadding = PaddingValues(vertical = 220.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(lyrics) { index, entry ->
            val (timestamp, text) = entry
            val isCurrent = index == lineIdx
            val distanceFromCurrent = kotlin.math.abs(index - lineIdx)
            val nextTimestamp = if (isCurrent) {
                lyrics.asSequence()
                    .drop(index + 1)
                    .map { it.first }
                    .firstOrNull { it > timestamp }
            } else {
                null
            }
            val lineEnd = nextTimestamp
                ?: duration.takeIf { isCurrent && it > timestamp }
                ?: (timestamp + 5_000L)
            val lineProgress = if (isCurrent) {
                ((currentPosition - timestamp).toFloat() /
                        (lineEnd - timestamp).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val textAlpha by animateFloatAsState(
                targetValue = when {
                    isCurrent -> 1f
                    distanceFromCurrent == 1 -> 0.58f
                    distanceFromCurrent == 2 -> 0.38f
                    else -> 0.22f
                },
                animationSpec = tween(480),
                label = "lyricAlpha"
            )
            val lyricIndent by animateDpAsState(
                targetValue = if (isCurrent) 0.dp else 10.dp,
                animationSpec = tween(480),
                label = "lyricIndent"
            )
            val lyricBlur by animateDpAsState(
                targetValue = when {
                    isCurrent -> 0.dp
                    distanceFromCurrent == 1 -> 0.35.dp
                    else -> 0.8.dp
                },
                animationSpec = tween(480),
                label = "lyricBlur"
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp + lyricIndent, end = 24.dp)
                    .clickable { onSeek(timestamp) }
                    .blur(lyricBlur)
                    .graphicsLayer {
                        alpha = textAlpha
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                val textStyle = if (isCurrent) {
                    MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Start
                    )
                } else {
                    MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Start
                    )
                }
                if (isCurrent) {
                    val characterCount = text.codePointCount(0, text.length)
                    val playedCharacterCount = (characterCount * lineProgress)
                        .toInt()
                        .coerceIn(0, characterCount)
                    val playedTextEnd = text.offsetByCodePoints(0, playedCharacterCount)
                    val playedColor = MaterialTheme.colorScheme.onSurface
                    val pendingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    val progressText = buildAnnotatedString {
                        if (playedTextEnd > 0) {
                            pushStyle(SpanStyle(color = playedColor))
                            append(text.substring(0, playedTextEnd))
                            pop()
                        }
                        if (playedTextEnd < text.length) {
                            pushStyle(SpanStyle(color = pendingColor))
                            append(text.substring(playedTextEnd))
                            pop()
                        }
                    }
                    Text(
                        text = progressText,
                        style = textStyle
                    )
                } else {
                    Text(text = text, style = textStyle)
                }
            }
        }
    }
}

private fun buildLyriconSong(
    song: Song,
    lyrics: List<Pair<Long, String>>,
    duration: Long
): LyriconSong {
    val sortedLyrics = lyrics.sortedBy { it.first }
    val lastTimestamp = sortedLyrics.lastOrNull()?.first ?: 0L
    val songDuration = duration.takeIf { it > lastTimestamp }
        ?: (lastTimestamp + 5_000L)
    val lyricLines = sortedLyrics.mapIndexed { index, (begin, text) ->
        val nextTimestamp = sortedLyrics
            .asSequence()
            .drop(index + 1)
            .map { it.first }
            .firstOrNull { it > begin }
        val end = (nextTimestamp ?: songDuration).coerceAtLeast(begin + 1L)
        val codePoints = text.toCodePointStrings()
        val words = if (codePoints.isEmpty()) {
            emptyList()
        } else {
            codePoints.mapIndexed { wordIndex, word ->
                val wordBegin = begin + ((end - begin) * wordIndex / codePoints.size)
                val wordEnd = if (wordIndex == codePoints.lastIndex) {
                    end
                } else {
                    begin + ((end - begin) * (wordIndex + 1) / codePoints.size)
                }
                LyricWord(text = word, begin = wordBegin, end = wordEnd)
            }
        }
        RichLyricLine(
            begin = begin,
            end = end,
            duration = end - begin,
            text = text,
            words = words
        )
    }
    return LyriconSong(
        id = song.uri.toString(),
        name = song.title,
        artist = song.artist,
        duration = songDuration,
        lyrics = lyricLines
    )
}

private fun String.toCodePointStrings(): List<String> {
    if (isEmpty()) return emptyList()
    val result = ArrayList<String>()
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        val nextOffset = offset + Character.charCount(codePoint)
        result += substring(offset, nextOffset)
        offset = nextOffset
    }
    return result
}

private fun parseLyrics(song: Song, context: android.content.Context, musicDirectories: List<Uri>): List<Pair<Long, String>> {
    android.util.Log.d("MusicApp", "Extracting embedded lyrics for: ${song.title}")

    // 1) 优先：直读 ID3v2 USLT/SYLT、M4A ©lyr、FLAC/OGG Vorbis LYRICS
    runCatching { EmbeddedLyricsReader.read(context, song.uri) }.getOrNull()?.let { result ->
        if (result.synced.isNotEmpty()) {
            android.util.Log.d("MusicApp", "Found synced embedded lyrics (${result.synced.size} lines)")
            return result.synced.sortedBy { it.first }
        }
        val plain = result.plainText
        if (!plain.isNullOrBlank()) {
            val lrc = parseLrcContent(plain)
            if (lrc.isNotEmpty()) return lrc
        }
    }

    // 2) 兜底：MediaMetadataRetriever.METADATA_KEY_LYRICS
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, song.uri)
        val lyrics = retriever.extractMetadata(1000)
        if (!lyrics.isNullOrBlank()) {
            android.util.Log.d("MusicApp", "Lyrics via MediaMetadataRetriever")
            val parsed = parseLrcContent(lyrics)
            if (parsed.isNotEmpty()) return parsed
        }
    } catch (e: Exception) {
        android.util.Log.e("MusicApp", "MediaMetadataRetriever lyrics failed: ${e.message}")
    } finally {
        try { retriever.release() } catch (_: Exception) {}
    }

    // 3) 最后尝试：同名 .lrc 旁挂文件
    val sidecar = readSidecarLrc(song, context, musicDirectories)
    if (sidecar.isNotEmpty()) {
        android.util.Log.d("MusicApp", "Lyrics via sidecar .lrc")
        return sidecar
    }

    android.util.Log.d("MusicApp", "No embedded lyrics found in metadata")
    return listOf(0L to "--- 未发现内嵌歌词 ---")
}

private fun readSidecarLrc(song: Song, context: android.content.Context, musicDirectories: List<Uri>): List<Pair<Long, String>> {
    val rawName = song.uri.lastPathSegment ?: return emptyList()
    val baseName = rawName.substringBeforeLast('.', rawName)
    val lrcName = "$baseName.lrc"

    // 在歌曲所在目录的同层查找
    runCatching {
        val parentUri = song.uri.let { uri ->
            val doc = DocumentFile.fromSingleUri(context, uri)
            doc?.parentFile
        }
        parentUri?.findFile(lrcName)?.let { f ->
            context.contentResolver.openInputStream(f.uri)?.use { stream ->
                val text = stream.readBytes().toString(Charsets.UTF_8)
                val parsed = parseLrcContent(text)
                if (parsed.isNotEmpty()) return parsed
            }
        }
    }

    // 若 SAF 路径不支持遍历，再遍历所有已添加的目录
    musicDirectories.forEach { treeUri ->
        runCatching {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@forEach
            val found = findLrcRecursively(root, lrcName) ?: return@forEach
            context.contentResolver.openInputStream(found.uri)?.use { stream ->
                val text = stream.readBytes().toString(Charsets.UTF_8)
                val parsed = parseLrcContent(text)
                if (parsed.isNotEmpty()) return parsed
            }
        }
    }
    return emptyList()
}

private fun findLrcRecursively(dir: DocumentFile, lrcName: String): DocumentFile? {
    for (file in dir.listFiles()) {
        if (file.isFile && file.name == lrcName) return file
        if (file.isDirectory) {
            val found = findLrcRecursively(file, lrcName)
            if (found != null) return found
        }
    }
    return null
}

private fun parseLrcContent(content: String): List<Pair<Long, String>> {
    val lines = content.lines(); val parsed = mutableListOf<Pair<Long, String>>()
    val timeRegex = """\[(\d+):(\d+(?:\.\d+)?)]""".toRegex()
    lines.forEach { line ->
        val matches = timeRegex.findAll(line)
        if (matches.any()) {
            var text = line; matches.forEach { text = text.replace(it.value, "") }; text = text.trim()
            if (text.isEmpty()) return@forEach
            matches.forEach {
                val min = it.groupValues[1].toLong()
                val sec = it.groupValues[2].toFloat()
                val totalMs = (min * 60 * 1000 + (sec * 1000)).toLong()
                parsed.add(totalMs to text)
            }
        }
    }
    if (parsed.isEmpty() && content.isNotBlank()) {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, s -> (index * 5000L) to s }
    }
    return parsed.sortedBy { it.first }
}

fun formatTime(ms: Long): String { val totalSeconds = ms / 1000; val minutes = totalSeconds / 60; val seconds = totalSeconds % 60; return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds) }
fun isMusicFile(name: String?): Boolean { val lower = name?.lowercase() ?: return false; return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".ogg") }
fun formatFileSize(size: Long): String { if (size <= 0) return "0 B"; val units = arrayOf("B", "KB", "MB", "GB"); val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt(); return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups]) }

fun buildMediaItem(song: Song): MediaItem {
    val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist)
        .build()
    return MediaItem.Builder()
        .setUri(song.uri)
        .setMediaMetadata(mediaMetadata)
        .build()
}
