package com.example.music

import android.content.Intent
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.SurfaceView
import android.view.PixelCopy
import android.view.Choreographer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay

import com.example.music.ui.theme.MusicTheme
import com.example.music.lyrics.EmbeddedLyricsReader
import com.google.common.util.concurrent.MoreExecutors
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.FutureTask
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first

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

// 扫描记录持久化：保存上次扫描的歌曲列表（封面保存为独立压缩文件），
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
    private const val KEY_VIDEO = "mv"
    private const val DEFAULT_ARTWORK_SIZE = 768
    private val artworkSizeBuckets = intArrayOf(96, 160, 320, DEFAULT_ARTWORK_SIZE)
    private val missingArtwork = ConcurrentHashMap.newKeySet<String>()
    private val inFlightLoads = ConcurrentHashMap<String, FutureTask<Bitmap?>>()
    private val memoryCache = object : LruCache<String, Bitmap>(artworkCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    @Synchronized
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
                s.videoUri?.let { obj.put(KEY_VIDEO, it.toString()) }
                val hash = s.uri.toString().hashCode().toString(16)
                val artworkFile = File(artworkDir, "$hash.png")
                if (artworkFile.exists()) {
                    obj.put(KEY_ARTWORK, hash)
                } else {
                    s.artwork?.let { bitmap ->
                        if (writeArtworkFile(artworkFile, bitmap)) {
                            obj.put(KEY_ARTWORK, hash)
                        }
                    }
                }
                arr.put(obj)
            }
            root.put(KEY_SONGS, arr)
            val recordsFile = File(context.filesDir, FILE_NAME)
            val temporaryFile = File(context.filesDir, "$FILE_NAME.tmp")
            temporaryFile.writeText(root.toString())
            if (!temporaryFile.renameTo(recordsFile)) {
                recordsFile.writeText(root.toString())
                temporaryFile.delete()
            }
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
                            artwork = null,  // 不在加载时解码，避免卡死
                            videoUri = obj.optString(KEY_VIDEO).takeIf { it.isNotEmpty() }?.toUri()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ScanRecordsManager", "加载扫描记录失败: ${e.message}")
            emptyList()
        }
    }

    fun peekArtwork(song: Song, maxPx: Int = DEFAULT_ARTWORK_SIZE): Bitmap? {
        val targetSize = artworkSizeBucket(maxPx)
        val key = artworkCacheKey(song.uri, targetSize)
        memoryCache.get(key)?.let { return it }
        val artwork = song.artwork ?: return null
        if (maxOf(artwork.width, artwork.height) > targetSize) return null
        memoryCache.put(key, artwork)
        return artwork
    }

    /** 后台加载封面并复用同一 URI、同一尺寸的并发请求。 */
    fun loadArtworkAsync(
        context: android.content.Context,
        song: Song,
        maxPx: Int = DEFAULT_ARTWORK_SIZE
    ): Song? {
        return loadArtworkBitmap(context, song, maxPx)?.let { song.copy(artwork = it) }
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
        writeArtworkFile(file, bitmap)
    }

    fun cacheEmbeddedArtwork(context: Context, uri: Uri, bytes: ByteArray) {
        val file = artworkFile(context, uri)
        if (file.exists()) return
        val bitmap = decodeSampledBitmap(bytes, DEFAULT_ARTWORK_SIZE) ?: return
        if (writeArtworkFile(file, bitmap)) {
            missingArtwork.remove(uri.toString())
            memoryCache.put(artworkCacheKey(uri, DEFAULT_ARTWORK_SIZE), bitmap)
        }
    }

    /** 读取封面 Bitmap，可选缩放到 maxPx，优先缓存文件，其次内存 bitmap。 */
    fun loadArtworkBitmap(
        context: android.content.Context,
        song: Song,
        maxPx: Int = 256
    ): Bitmap? {
        val targetSize = artworkSizeBucket(maxPx)
        val cacheKey = artworkCacheKey(song.uri, targetSize)
        memoryCache.get(cacheKey)?.let { return it }
        peekArtwork(song, targetSize)?.let { return it }

        val file = artworkFile(context, song.uri)
        val uriKey = song.uri.toString()
        if (uriKey in missingArtwork && song.artwork == null) return null
        if (!file.exists()) {
            missingArtwork.add(uriKey)
            val scaled = song.artwork?.let { scaleBitmap(it, targetSize) } ?: return null
            memoryCache.put(cacheKey, scaled)
            return scaled
        }
        if (uriKey in missingArtwork) missingArtwork.remove(uriKey)

        val newTask = FutureTask<Bitmap?> {
            decodeSampledBitmap(file.absolutePath, targetSize)?.let { decoded ->
                val scaled = scaleBitmap(decoded, targetSize)
                if (scaled !== decoded) decoded.recycle()
                scaled
            }
        }
        val task = inFlightLoads.putIfAbsent(cacheKey, newTask) ?: newTask.also { it.run() }
        return try {
            task.get()?.also { memoryCache.put(cacheKey, it) }
        } catch (_: Exception) {
            null
        } finally {
            if (task === newTask) inFlightLoads.remove(cacheKey, newTask)
        }
    }

    private fun decodeSampledBitmap(path: String, maxPx: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        while (largest / sample > maxPx * 2) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (maxPx <= 160) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        return android.graphics.BitmapFactory.decodeFile(path, opts)
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        while (largest / sample > maxPx * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (maxPx <= 160) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { decoded ->
            val scaled = scaleBitmap(decoded, maxPx)
            if (scaled !== decoded) decoded.recycle()
            scaled
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxPx: Int): Bitmap {
        if (bitmap.width <= maxPx && bitmap.height <= maxPx) return bitmap
        val scale = maxPx.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun writeArtworkFile(file: File, bitmap: Bitmap): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val temporaryFile = File(file.parentFile, "${file.name}.tmp")
        temporaryFile.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.WEBP, 88, output))
        }
        if (!temporaryFile.renameTo(file)) {
            temporaryFile.copyTo(file, overwrite = true)
            temporaryFile.delete()
        }
        true
    }.getOrElse {
        android.util.Log.w("ScanRecordsManager", "保存封面失败: ${it.message}")
        false
    }

    private fun artworkSizeBucket(maxPx: Int): Int {
        val requested = maxPx.coerceAtLeast(1)
        return artworkSizeBuckets.firstOrNull { it >= requested } ?: requested
    }

    private fun artworkCacheKey(uri: Uri, size: Int): String = "${uri}#$size"

    private fun artworkCacheSizeKb(): Int {
        val runtimeLimitKb = Runtime.getRuntime().maxMemory() / 1024L / 8L
        return runtimeLimitKb.coerceIn(8L * 1024L, 64L * 1024L).toInt()
    }

    // 清理：删除所有已保存的扫描记录和封面文件
    fun clear(context: android.content.Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
            File(context.filesDir, ARTWORK_DIR).deleteRecursively()
            memoryCache.evictAll()
            missingArtwork.clear()
            inFlightLoads.clear()
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

class MainActivity : ComponentActivity() {
    private var player: Player? by mutableStateOf(null)
    private var initError: String? by mutableStateOf(null)
    private val prefs by lazy { getSharedPreferences("music_prefs", MODE_PRIVATE) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageSettings.wrapContext(newBase))
    }

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
        AppAnimationSettings.enabled = prefs.getBoolean("app_animations_enabled", true)
        val lastUri = LastPlaybackManager.loadUri(this)
        val lastPosition = LastPlaybackManager.loadPosition(this)
        val lastDuration = LastPlaybackManager.loadDuration(this)

        enableEdgeToEdge()
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 8 }
            window.setBackgroundBlurRadius(8)
        }
        setContent {
            remember(this) {
                AlbumDotMatrixSettings.load(this)
                true
            }
            var isAmoledMode by remember { mutableStateOf(savedAmoledMode) }
            var isDjMode by remember { mutableStateOf(savedDjMode) }
            var playerLandscape by remember { mutableStateOf(savedPlayerLandscape) }

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

}

data class Song(
    val title: String, 
    val artist: String, 
    val duration: String, 
    val uri: Uri,
    val artwork: Bitmap? = null,
    val videoUri: Uri? = null
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
    // 重启 Activity：用于清除全部数据 / 导入配置后重新加载所有状态
    val restartActivity: () -> Unit = { activity?.recreate() }
    val scope = rememberCoroutineScope()
    var scanRecordsSaveJob by remember { mutableStateOf<Job?>(null) }
    val scheduleScanRecordsSave: (List<Song>) -> Unit = { value ->
        val snapshot = value.toList()
        scanRecordsSaveJob?.cancel()
        scanRecordsSaveJob = scope.launch {
            delay(250L)
            withContext(Dispatchers.IO) {
                ScanRecordsManager.save(context, snapshot)
            }
        }
    }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.LocalMusic) }
    LaunchedEffect(currentScreen, playerLandscape) {
        activity?.requestedOrientation = when {
            currentScreen != Screen.PlayerView -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            playerLandscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        activity?.window?.let { window ->
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }
    val openPlayer = {
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

    // 启动时恢复完整播放队列、上次歌曲和进度。不能只恢复单个 MediaItem，
    // 否则播放器队列长度为 1，列表循环也会表现成单曲循环。
    LaunchedEffect(initialSongs, initialLastUri) {
        if (initialSongs.isNotEmpty()) {
            val restoredItems = withContext(Dispatchers.IO) {
                initialSongs.map { buildMediaItem(it) }
            }
            mediaItemsList = restoredItems
            mediaItemsReady = true
            val restoredIndex = initialSongs.indexOfFirst { it.uri == initialLastUri }
                .coerceAtLeast(0)
            if (currentSong == null) currentSong = initialSongs[restoredIndex]
            player.setMediaItems(restoredItems, restoredIndex, initialLastPosition.coerceAtLeast(0L))
            player.prepare()
        }
    }

    // 每次歌曲或进度变动时保存状态（使用 apply 避免频繁 IO 阻塞）
    LaunchedEffect(currentSong, currentPosition, totalDuration) {
        if (currentSong != null) {
            LastPlaybackManager.save(context, currentSong?.uri, currentPosition, totalDuration)
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
                            val children = root.listFiles()
                            // 同名 MV 与音乐合并为同一条目（不单独入库）
                            val videosByBaseName = children
                                .filter { it.isFile && isVideoFile(it.name) }
                                .associateBy(
                                    { it.name?.substringBeforeLast('.')?.trim()?.lowercase().orEmpty() }
                                ) { it.uri }
                            // 同名多个音频文件合并为一个条目：无损优先，其次大文件
                            fun qualityRank(name: String?): Int = when (
                                name?.substringAfterLast('.', "")?.lowercase()
                            ) {
                                "flac", "wav", "ape", "dsf", "dff" -> 2
                                else -> 1
                            }
                            children
                                .filter { it.isFile && isMusicFile(it.name) }
                                .groupBy {
                                    it.name?.substringBeforeLast('.')?.trim()?.lowercase().orEmpty()
                                }
                                .values
                                .mapNotNull { group ->
                                    group.maxWithOrNull(
                                        compareBy({ qualityRank(it.name) }, { it.length() ?: 0L })
                                    )
                                }
                                .forEach { file ->
                                    val mvUri = file.name
                                        ?.substringBeforeLast('.')
                                        ?.trim()?.lowercase()
                                        ?.let { videosByBaseName[it] }
                                    try {
                                        retriever.setDataSource(context, file.uri)
                                        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.name ?: "Unknown"
                                        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "本地音频"
                                        val artBytes = retriever.embeddedPicture
                                        if (artBytes != null) {
                                            ScanRecordsManager.cacheEmbeddedArtwork(context, file.uri, artBytes)
                                        }
                                        foundSongs.add(Song(title, artist, formatFileSize(file.length()), file.uri, videoUri = mvUri))
                                    } catch (_: Exception) {
                                        foundSongs.add(Song(file.name ?: "Unknown", "本地音频", formatFileSize(file.length()), file.uri, videoUri = mvUri))
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
                withContext(Dispatchers.IO) {
                    ScanRecordsManager.save(context, scanResult)
                }
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

    // MV 模式提升到主界面：切换到其他页面时 MV 继续播放（音频+进度不断）
    var mvVideoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    // 按曲目记忆音乐/MV 模式；mvModeEnabled 直接由当前曲目推导（无异步竞态，切页不会被重置）
    val mvModeBySong = remember { mutableStateMapOf<String, Boolean>() }
    val mvModeEnabled = currentSong?.let { song ->
        song.videoUri != null && mvModeBySong[song.uri.toString()] == true
    } == true
    // MV 模式全程静音主播放器（音乐只是后台时钟），退出 MV 模式后恢复外放
    LaunchedEffect(mvModeEnabled) {
        runCatching { (player as? MediaController)?.setVolume(if (mvModeEnabled) 0f else 1f) }
    }
    LaunchedEffect(mvModeEnabled, currentSong?.videoUri) {
        val uri = if (mvModeEnabled) currentSong?.videoUri else null
        val existing = mvVideoPlayer
        if (uri == null) {
            runCatching { existing?.release() }
            mvVideoPlayer = null
        } else if (existing == null || existing.currentMediaItem?.localConfiguration?.uri != uri) {
            runCatching { existing?.release() }
            // 不用 apply 块：外层 repeatMode/isPlaying 状态与播放器属性同名，直接显式赋值
            val built = ExoPlayer.Builder(context).build()
            // 不申请音频焦点，否则会打断服务侧主播放器导致 MV 没声音没画面
            built.setAudioAttributes(AudioAttributes.DEFAULT, false)
            built.setMediaItem(MediaItem.fromUri(uri))
            built.volume = 1f
            built.repeatMode = Player.REPEAT_MODE_ONE
            built.seekTo(player.currentPosition.coerceAtLeast(0L))
            built.playWhenReady = player.isPlaying
            built.prepare()
            mvVideoPlayer = built
        }
    }
    LaunchedEffect(isPlaying, mvVideoPlayer) {
        mvVideoPlayer?.playWhenReady = isPlaying
    }
    LaunchedEffect(currentPosition) {
        val videoPlayer = mvVideoPlayer ?: return@LaunchedEffect
        // MV 进度为准：静音的主播放器偏差过大时追赶到视频位置
        if (kotlin.math.abs(player.currentPosition - videoPlayer.currentPosition) > 800) {
            runCatching { player.seekTo(videoPlayer.currentPosition.coerceAtLeast(0L)) }
        }
    }

    // 发送完整的结构化逐字歌词；Lyricon 会根据 LyricWord 的 begin/end 自动渲染逐字进度。
    // MV 模式下不向歌词模块发送数据。
    LaunchedEffect(currentSong, currentSongLyrics, totalDuration, mvModeEnabled) {
        val song = currentSong
        if (song == null || mvModeEnabled) {
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

    // 同步给桌面歌词部件与悬浮歌词；MV 模式下不发送歌词数据
    LaunchedEffect(currentSong, currentSongLyrics, currentPosition, totalDuration, isPlaying, mvModeEnabled) {
        LyricsStateHolder.publish(
            context = context,
            song = currentSong,
            lyrics = if (mvModeEnabled) emptyList() else currentSongLyrics,
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

    LaunchedEffect(isPlaying, currentSong, mvModeEnabled, mvVideoPlayer) {
        if (isPlaying) {
            while (true) {
                val videoClock = if (mvModeEnabled) mvVideoPlayer else null
                if (videoClock != null) {
                    // MV 模式进度以视频为准
                    currentPosition = videoClock.currentPosition.coerceAtLeast(0L)
                    totalDuration = videoClock.duration.coerceAtLeast(0L)
                } else {
                    currentPosition = player.currentPosition
                    totalDuration = player.duration.coerceAtLeast(0L)
                }
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

    // 提取统一的返回逻辑
    val handleBack: () -> Unit = {
        currentScreen.backDestination()?.let { currentScreen = it }
        Unit
    }

    val startSongPlayback: (Song) -> Unit = { selectedSong ->
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
    val floatingDockArtwork = currentSong?.let { rememberArtwork(it) }
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
        val hideDockForLandscapePlayer = currentScreen == Screen.PlayerView && playerLandscape
        val floatOverlayActive = !hideDockForLandscapePlayer
        val floatDockHasMiniPlayer = currentSong != null && currentScreen != Screen.PlayerView
        // 覆盖迷你播放器/导航栏、手势导航区及外边距，确保任何页面内容都位于 Dock 上方。
        val floatingDockSpace = 98.dp
        val dockBackdrop = rememberLayerBackdrop()
        Scaffold(
            modifier = Modifier.layerBackdrop(dockBackdrop),
            containerColor = if (currentScreen == Screen.PlayerView) Color.Transparent
                else MaterialTheme.colorScheme.background,
            // 所有页面背景完整绘制到状态栏和导航栏后方；交互内容由各页面自行避让系统栏。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {},
            bottomBar = {}
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                AnimatedContent(
                    targetState = currentScreen,
                    modifier = Modifier
                        .fillMaxSize()
                        // 播放页自身绘制全屏背景；其他页面继续使用 Scaffold 内容安全区。
                        .then(
                            if (currentScreen == Screen.PlayerView) {
                                Modifier
                            } else {
                                Modifier.padding(innerPadding)
                            }
                        ),
                    transitionSpec = {
                        if (!AppAnimationSettings.enabled) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else if (targetState == Screen.PlayerView) {
                            // 进入播放页：从 Dock 位置向上滑动展开
                            (
                                slideInVertically(
                                    animationSpec = tween(durationMillis = 380),
                                    initialOffsetY = { fullHeight -> fullHeight }
                                ) + fadeIn(animationSpec = tween(200), initialAlpha = 0.35f)
                            ) togetherWith (
                                fadeOut(animationSpec = tween(160)) + scaleOut(
                                    targetScale = 0.94f,
                                    transformOrigin = TransformOrigin(0.5f, 1f),
                                    animationSpec = tween(durationMillis = 380)
                                )
                            )
                        } else if (initialState == Screen.PlayerView) {
                            // 切离播放页：播放器向下滑回 Dock 位置
                            (
                                fadeIn(animationSpec = tween(220), initialAlpha = 0.35f) + slideInVertically(
                                    animationSpec = tween(durationMillis = 380),
                                    initialOffsetY = { fullHeight -> (fullHeight * 0.08f).toInt() }
                                )
                            ) togetherWith (
                                slideOutVertically(
                                    animationSpec = tween(durationMillis = 380),
                                    targetOffsetY = { fullHeight -> fullHeight }
                                ) + fadeOut(animationSpec = tween(200))
                            )
                        } else {
                            val duration = 320
                            (
                                slideInVertically(
                                    animationSpec = tween(durationMillis = duration),
                                    initialOffsetY = { fullHeight -> (fullHeight * 0.10f).toInt() }
                                ) + fadeIn(animationSpec = tween(180), initialAlpha = 0.18f) + scaleIn(
                                    initialScale = 0.92f,
                                    transformOrigin = TransformOrigin(0.5f, 1f),
                                    animationSpec = tween(durationMillis = duration)
                                )
                            ) togetherWith (
                                slideOutVertically(
                                    animationSpec = tween(durationMillis = duration),
                                    targetOffsetY = { fullHeight -> (fullHeight * 0.08f).toInt() }
                                ) + fadeOut(animationSpec = tween(140), targetAlpha = 0.0f) + scaleOut(
                                    targetScale = 0.96f,
                                    transformOrigin = TransformOrigin(0.5f, 1f),
                                    animationSpec = tween(durationMillis = duration)
                                )
                            )
                        }
                    },
                    label = "ScreenTransition"
                ) { screen ->
                    CompositionLocalProvider(
                        LocalDockContentPadding provides if (floatOverlayActive) floatingDockSpace else 0.dp
                    ) {
                    when (screen) {
                        Screen.PlayerView -> {
                            val song = currentSong
                            if (song != null) {
                                FullPlayerScreen(
                                    song = song,
                                    lyrics = currentSongLyrics,
                                    isPlaying = isPlaying,
                                    position = currentPosition,
                                    duration = totalDuration,
                                    onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                                    onSeek = {
                                        if (mvModeEnabled) runCatching { mvVideoPlayer?.seekTo(it) }
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
                                    context = context,
                                    musicDirectories = musicDirectories.toList(),
                                    songs = songs,
                                    onSelectSong = startSongPlayback,
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
                                    onBack = handleBack,
                                    isLandscape = playerLandscape,
                                    floatingDockVisible = floatOverlayActive,
                                    fixedDockBottomPadding = 0.dp,
                                    mvModeEnabled = mvModeEnabled,
                                    onMvModeEnabledChange = { enabled ->
                                        currentSong?.let { mvModeBySong[it.uri.toString()] = enabled }
                                    },
                                    mvVideoPlayer = mvVideoPlayer
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.MusicOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            appText("暂无正在播放的歌曲", "Nothing is playing"),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        Screen.LocalMusic -> LocalMusicScreen(
                            songs = songs,
                            isScanning = isScanning,
                            backgroundArtwork = floatingDockArtwork,
                            bottomContentPadding = if (floatOverlayActive) floatingDockSpace else 0.dp,
                            protectStatusBarContent = true,
                            onSongClick = { song ->
                                startSongPlayback(song)
                                val openOnClick = context
                                    .getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
                                    .getBoolean("open_player_on_song_click", false)
                                if (openOnClick) openPlayer()
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
                                scheduleScanRecordsSave(newList)
                            }
                        )
                        Screen.Settings -> SettingsMenu(
                            playerLandscape = playerLandscape,
                            onPlayerLandscapeChange = onPlayerLandscapeChange,
                            isAmoledMode = isAmoledMode,
                            onAmoledModeChange = onAmoledModeChange,
                            onNavigate = { currentScreen = it }
                        )
                        Screen.Personalization -> PersonalizationSettingsScreen(
                            playerLandscape = playerLandscape,
                            onPlayerLandscapeChange = onPlayerLandscapeChange,
                            isAmoledMode = isAmoledMode,
                            onAmoledModeChange = onAmoledModeChange,
                            onBack = handleBack
                        )
                        Screen.Library -> LibrarySettingsScreen(
                            directories = musicDirectories,
                            isScanning = isScanning,
                            onScanNow = { triggerScan() }
                        ) { handleBack() }
                        Screen.Data -> DataSettingsScreen(
                            onBack = handleBack,
                            onRestart = restartActivity
                        )
                        Screen.Playback -> PlaybackSettingsScreen(
                            isDjMode = isDjMode,
                            onDjModeChange = onDjModeChange,
                            onBack = handleBack
                        )
                        Screen.AudioOutput -> AudioOutputSettingsScreen(
                            onBack = handleBack
                        )
                        Screen.LyricsAndDevices -> LyricsAndDevicesSettingsScreen(
                            onBack = handleBack
                        )
                        Screen.LyricsSettings -> LyricsSettingsScreen(
                            onBack = handleBack
                        )
                        Screen.AudioCodecs -> AudioCodecSupportScreen(
                            onBack = handleBack
                        )
                        Screen.AboutSupport -> AboutSupportScreen(
                            onBack = handleBack,
                            onNavigate = { currentScreen = it }
                        )
                        Screen.OpenSourceLibraries -> OpenSourceLibrariesScreen(
                            onBack = handleBack
                        )
                    }
                    }
                }
            }
        }

        // 悬浮 Dock 随页面切换滑动：普通页滑入展开，播放页滑出收起
        AnimatedVisibility(
            visible = floatOverlayActive,
            enter = if (AppAnimationSettings.enabled) {
                slideInVertically(tween(360)) { it } + fadeIn(tween(220))
            } else {
                EnterTransition.None
            },
            exit = if (AppAnimationSettings.enabled) {
                slideOutVertically(tween(360)) { it } + fadeOut(tween(220))
            } else {
                ExitTransition.None
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(10f),
            label = "FloatingDockVisibility"
        ) {
            FloatingDockWindow(
                backdrop = dockBackdrop,
                modifier = Modifier.fillMaxWidth(),
                miniPlayer = if (floatDockHasMiniPlayer) {
                    {
                        MD3MiniPlayer(
                            song = currentSong!!,
                            isPlaying = isPlaying,
                            progress = if (totalDuration > 0) currentPosition.toFloat() / totalDuration else 0f,
                            onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                            onClick = openPlayer,
                            onNext = { player.seekToNext() },
                            onPrevious = { player.seekToPrevious() },
                            onSeek = { player.seekTo((it * totalDuration).toLong()) },
                            backdrop = dockBackdrop,
                            compactFloating = true
                        )
                    }
                } else {
                    null
                }
            ) {
                MusicDock(
                    currentScreen = currentScreen,
                    onNavigate = { currentScreen = it },
                    backdrop = dockBackdrop,
                    floating = true
                )
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
    backgroundArtwork: Bitmap?,
    onSongClick: (Song) -> Unit,
    onReorder: (Int, Int) -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    protectStatusBarContent: Boolean = true
) {
    val context = LocalContext.current
    val savedScroll = remember { ListScrollMemory.load(context) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScroll.first,
        initialFirstVisibleItemScrollOffset = savedScroll.second
    )
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (backgroundArtwork != null) {
            androidx.compose.foundation.Image(
                bitmap = backgroundArtwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.16f
                        scaleY = 1.16f
                        alpha = 0.42f
                    }
                    .blur(52.dp)
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.62f))
            )
        }
        // 顶部间距由外层 Scaffold 的 innerPadding 提供，这里不再重复叠加状态栏内边距
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (protectStatusBarContent) Modifier.statusBarsPadding() else Modifier
                )
        ) {
            if (isScanning) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            } else if (songs.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MusicOff, null, Modifier.size(64.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(appText("暂无本地音乐", "No local music"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                TraditionalMusicList(
                    songs = songs,
                    listState = listState,
                    bottomContentPadding = bottomContentPadding,
                    onSongClick = onSongClick,
                    onSongLongClick = { detailSong = it },
                    onReorder = onReorder
                )
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
}

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun TraditionalMusicList(
    songs: List<Song>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onReorder: (Int, Int) -> Unit
) {
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        itemsIndexed(songs, key = { _, song -> song.uri.toString() }) { index, song ->
            val artwork = rememberArtwork(song, maxPx = 96)
            val isDragging = draggingItemIndex == index
            ListItem(
                modifier = Modifier
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset else 0f
                        alpha = if (isDragging) 0.82f else 1f
                        scaleX = if (isDragging) 1.02f else 1f
                        scaleY = if (isDragging) 1.02f else 1f
                        shadowElevation = if (isDragging) 8f else 0f
                    }
                    .combinedClickable(
                        enabled = draggingItemIndex == null,
                        onClick = { onSongClick(song) },
                        onLongClick = { onSongLongClick(song) }
                    ),
                headlineContent = {
                    Text(
                        text = song.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                supportingContent = {
                    Text(
                        text = song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = {
                    if (artwork != null) {
                        androidx.compose.foundation.Image(
                            bitmap = artwork.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MusicNote, contentDescription = null)
                            }
                        }
                    }
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = appText("拖动排序", "Drag to reorder"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .size(32.dp)
                            .padding(4.dp)
                            .pointerInput(song.uri) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingItemIndex = index
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffset += dragAmount.y
                                        val threshold = 50f
                                        if (dragOffset > threshold && index < songs.lastIndex) {
                                            onReorder(index, index + 1)
                                            draggingItemIndex = index + 1
                                            dragOffset -= 72.dp.toPx()
                                        } else if (dragOffset < -threshold && index > 0) {
                                            onReorder(index, index - 1)
                                            draggingItemIndex = index - 1
                                            dragOffset += 72.dp.toPx()
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
                            }
                    )
                }
            )
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMenu(
    playerLandscape: Boolean,
    onPlayerLandscapeChange: (Boolean) -> Unit,
    isAmoledMode: Boolean,
    onAmoledModeChange: (Boolean) -> Unit,
    onNavigate: (Screen) -> Unit
) {
    val dockContentPadding = LocalDockContentPadding.current
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text(appText("设置", "Settings")) }) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp + dockContentPadding)
        ) {
            SettingsSectionHeader(appText("个性化", "Personalization"))
            SettingsItem(appText("个性化设置", "Personalization settings"), Icons.Default.Palette) {
                onNavigate(Screen.Personalization)
            }
            SettingsSectionHeader(appText("播放与声音", "Playback and audio"))
            SettingsItem(
                appText("播放设置", "Playback settings"),
                Icons.Default.GraphicEq,
                appText("控制播放行为、切歌逻辑和基础播放体验。", "Controls playback behavior, track switching, and the basic playback experience.")
            ) { onNavigate(Screen.Playback) }
            SettingsItem(appText("音频输出与增益", "Audio output and gain"), Icons.Default.Speaker) {
                onNavigate(Screen.AudioOutput)
            }
            SettingsItem(
                appText("音频解码支持", "Audio codec support"),
                Icons.Default.AudioFile,
                appText("查看并管理当前设备支持的音频解码器。", "View and manage the audio decoders supported by this device.")
            ) { onNavigate(Screen.AudioCodecs) }
            SettingsSectionHeader(appText("歌词与连接", "Lyrics and connections"))
            SettingsItem(
                appText("歌词设置", "Lyrics settings"),
                Icons.Default.Lyrics,
                appText("配置歌词来源、显示方式和逐字效果。", "Configure lyric sources, presentation, and per-word effects.")
            ) {
                onNavigate(Screen.LyricsSettings)
            }
            SettingsItem(
                appText("外部设备与服务", "External devices and services"),
                Icons.Default.Subtitles,
                appText("连接外部播放器、桌面歌词和系统集成能力。", "Connect external players, desktop lyrics, and system integrations.")
            ) {
                onNavigate(Screen.LyricsAndDevices)
            }
            SettingsSectionHeader(appText("音乐来源", "Music sources"))
            SettingsItem(
                appText("音乐库", "Music library"),
                Icons.Default.LibraryMusic,
                appText("管理本地音乐目录、扫描结果和曲库内容。", "Manage local music folders, scan results, and the library contents.")
            ) { onNavigate(Screen.Library) }
            SettingsSectionHeader(appText("数据与支持", "Data and support"))
            SettingsItem(
                appText("数据", "Data"),
                Icons.Default.Storage,
                appText("导入、导出、清理和重置应用数据。", "Import, export, clear, and reset app data.")
            ) { onNavigate(Screen.Data) }
            SettingsItem(
                appText("关于与支持", "About and support"),
                Icons.Default.Info,
                appText("查看版本信息、项目说明和支持入口。", "View version info, project notes, and support links.")
            ) { onNavigate(Screen.AboutSupport) }
        }
    }
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
    val dockContentPadding = LocalDockContentPadding.current
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
            contentPadding = PaddingValues(bottom = 24.dp + dockContentPadding)
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
private fun FloatingDockWindow(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    miniPlayer: (@Composable () -> Unit)? = null,
    dock: @Composable () -> Unit
) {
    val dockShape = RoundedCornerShape(32.dp)
    val dockSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val dockOutlineColor = MaterialTheme.colorScheme.outlineVariant
    val navigationBarHeight = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    Box(
        modifier = modifier.height(navigationBarHeight + 90.dp)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .height(62.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { dockShape },
                        effects = {
                            vibrancy()
                            backdropBlur(8.dp.toPx())
                            lens(18.dp.toPx(), 28.dp.toPx(), chromaticAberration = true)
                        },
                        onDrawSurface = {
                            drawRect(
                                color = dockSurfaceColor.copy(alpha = 0.34f)
                            )
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = dockOutlineColor.copy(alpha = 0.58f),
                        shape = dockShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    dock()
                }
            }
            if (miniPlayer != null) {
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .fillMaxHeight()
                ) {
                    miniPlayer()
                }
            }
        }
    }

}

/**
 * MV 视频画面：播放器由主界面持有（切换到其他页面时音频继续、进度不断），
 * 这里只负责挂载/卸载渲染表面并保持原始比例。
 */
@Composable
private fun MvVideoSurface(
    videoPlayer: ExoPlayer,
    videoUri: Uri,
    surfaceState: MutableState<SurfaceView?>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val surfaceView = remember { SurfaceView(context) }
    // 交给播放页背景层做低分辨率取帧
    DisposableEffect(surfaceView) {
        surfaceState.value = surfaceView
        onDispose {
            if (surfaceState.value === surfaceView) surfaceState.value = null
        }
    }
    var videoAspectRatio by remember(videoUri) { mutableFloatStateOf(0f) }
    LaunchedEffect(videoUri) {
        videoAspectRatio = withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, videoUri)
                val width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                retriever.release()
                if (width > 0 && height > 0) {
                    if (rotation == 90 || rotation == 270) height.toFloat() / width
                    else width.toFloat() / height
                } else 0f
            } catch (_: Exception) {
                0f
            }
        }
    }
    DisposableEffect(videoPlayer, surfaceView) {
        runCatching { videoPlayer.setVideoSurfaceView(surfaceView) }
        onDispose { runCatching { videoPlayer.clearVideoSurfaceView(surfaceView) } }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { surfaceView },
            modifier = if (videoAspectRatio > 0f) {
                Modifier.aspectRatio(videoAspectRatio)
            } else {
                Modifier.fillMaxSize()
            }
        )
    }
}

/** MV 画面（模式切换只通过头部按钮）。 */
@Composable
private fun MvVideoArea(
    videoPlayer: ExoPlayer,
    videoUri: Uri,
    surfaceState: MutableState<SurfaceView?>,
    modifier: Modifier = Modifier
) {
    MvVideoSurface(
        videoPlayer = videoPlayer,
        videoUri = videoUri,
        surfaceState = surfaceState,
        modifier = modifier
    )
}

/** 取帧下限间隔：优先用解码器上报的帧率，取不到时按 30fps。 */
private fun mvFrameIntervalMs(videoPlayer: ExoPlayer?): Long {
    val frameRate = runCatching { videoPlayer?.videoFormat?.frameRate }.getOrNull() ?: 0f
    return if (frameRate.isFinite() && frameRate in 1f..240f) {
        (1000L / frameRate.toLong()).coerceIn(8L, 1000L)
    } else 33L
}

/** 挂起直到下一个显示 vsync，返回该帧时间戳（纳秒）。 */
private suspend fun awaitVsync(): Long = suspendCancellableCoroutine { cont ->
    Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
        if (cont.isActive) cont.resume(frameTimeNanos)
    }
}

/**
 * 从 MV 渲染表面取帧，供播放页背景层放大模糊成动态底色。
 * 取帧跟随视频逐帧渲染回调：渲染线程每出一帧才抓一张，并等下一个 vsync 让这一帧
 * 完成合成，背景与画面的延迟因此控制在 1~2 帧内；解码器不回调时退化为按帧率轮询。
 * 像素没变化就不发布新位图，暂停或熄屏时不会空转重组。
 */
@Composable
private fun rememberMvBackdropFrame(
    videoPlayer: ExoPlayer?,
    surfaceState: MutableState<SurfaceView?>,
    enabled: Boolean
): Bitmap? {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    // 渲染线程只写这两个计数，采样循环读它们判断有没有新画面
    val frameGeneration = remember { AtomicLong(0L) }
    val frameSignalNanos = remember { AtomicLong(0L) }
    DisposableEffect(videoPlayer) {
        val player = videoPlayer
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : VideoFrameMetadataListener {
                override fun onVideoFrameAboutToBeRendered(
                    presentationTimeUs: Long,
                    releaseTimeNs: Long,
                    format: Format,
                    outputImage: MediaFormat?
                ) {
                    frameSignalNanos.set(System.nanoTime())
                    frameGeneration.incrementAndGet()
                }
            }
            // 注册失败（部分解码路径不支持）时由采样循环的轮询分支兜底
            runCatching { player.setVideoFrameMetadataListener(listener) }
            onDispose { runCatching { player.clearVideoFrameMetadataListener(listener) } }
        }
    }
    LaunchedEffect(enabled, surfaceState.value, videoPlayer) {
        if (!enabled) return@LaunchedEffect
        var lastPixels: IntArray? = null
        var lastCopiedGeneration = -1L
        var lastCopyNanos = 0L
        while (isActive) {
            // 对齐显示 vsync：既做节拍，也给刚渲染的帧留出合成时间
            val frameTimeNanos = awaitVsync()
            val generation = frameGeneration.get()
            val callbackAlive = generation > 0L &&
                frameTimeNanos - frameSignalNanos.get() < 1_500_000_000L
            val minIntervalNs = mvFrameIntervalMs(videoPlayer) * 1_000_000L
            val idle = !callbackAlive && videoPlayer?.isPlaying != true
            val due = when {
                callbackAlive -> generation != lastCopiedGeneration
                idle -> frameTimeNanos - lastCopyNanos > 200_000_000L
                else -> frameTimeNanos - lastCopyNanos >= minIntervalNs
            }
            if (!due) continue
            lastCopiedGeneration = generation
            lastCopyNanos = frameTimeNanos
            val surfaceView = surfaceState.value
            val sourceWidth = surfaceView?.width ?: 0
            val sourceHeight = surfaceView?.height ?: 0
            if (surfaceView == null || sourceWidth <= 0 || sourceHeight <= 0) {
                delay(200L)
                continue
            }
            val target = Bitmap.createBitmap(
                64,
                (64 * sourceHeight / sourceWidth).coerceIn(16, 128),
                Bitmap.Config.ARGB_8888
            )
            val copied = suspendCancellableCoroutine { cont ->
                // 失败（无新帧/超时）时丢弃这张，正在显示的位图绝不回收
                val requested = runCatching {
                    PixelCopy.request(surfaceView, target, { result ->
                        if (result != PixelCopy.SUCCESS) target.recycle()
                        if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
                    }, handler)
                }.isSuccess
                if (!requested) {
                    target.recycle()
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!copied) continue
            // 拷贝期间又渲染了若干帧，视为已全部消化，避免连续追赶取帧
            lastCopiedGeneration = frameGeneration.get()
            val pixels = IntArray(target.width * target.height)
            target.getPixels(pixels, 0, target.width, 0, 0, target.width, target.height)
            val previous = lastPixels
            if (previous == null || previous.size != pixels.size || !previous.contentEquals(pixels)) {
                lastPixels = pixels
                // 旧帧交给 GC，主动回收可能撞上正在绘制的那一帧
                frame = target
            } else {
                lastPixels = previous
                target.recycle()
            }
        }
    }
    return frame
}

private fun dockRootScreen(screen: Screen): Screen = when (screen) {
    Screen.LocalMusic -> Screen.LocalMusic
    Screen.PlayerView -> Screen.PlayerView
    else -> Screen.Settings
}

@Composable
private fun MusicDock(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    backdrop: Backdrop? = null,
    floating: Boolean = false
) {
    val dockScreens = remember {
        listOf(Screen.LocalMusic, Screen.PlayerView, Screen.Settings)
    }
    val selectedScreen = dockRootScreen(currentScreen)
    val md3ItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
    )
    val dockModifier = Modifier

    if (floating) {
        val destinations = listOf(
            Triple(Screen.LocalMusic, Icons.Default.LibraryMusic, appText("音乐库", "Library")),
            Triple(Screen.PlayerView, Icons.Default.PlayCircle, appText("播放", "Player")),
            Triple(Screen.Settings, Icons.Default.Settings, appText("设置", "Settings"))
        )
        val dockItemAnimation = if (AppAnimationSettings.enabled) {
            spring<Float>(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        } else {
            snap<Float>()
        }
        val selectedIndex = dockScreens.indexOf(selectedScreen).coerceAtLeast(0)
        val settledDropPosition by animateFloatAsState(
            targetValue = selectedIndex.toFloat(),
            animationSpec = dockItemAnimation,
            label = "FloatingDockDropPosition"
        )
        val dropPosition = settledDropPosition
        val dropColor = MaterialTheme.colorScheme.secondaryContainer
        val dropOutline = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)

        BoxWithConstraints(
            modifier = dockModifier
                .fillMaxWidth()
                .height(62.dp)
        ) {
            val itemWidth = (maxWidth - 12.dp) / destinations.size
            if (backdrop != null) {
                Box(
                    modifier = Modifier
                        .offset(x = 6.dp + itemWidth * dropPosition, y = 6.dp)
                        .width(itemWidth)
                        .height(50.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(25.dp) },
                            effects = {
                                vibrancy()
                                backdropBlur(5.dp.toPx())
                                lens(
                                    refractionHeight = 12.dp.toPx(),
                                    refractionAmount = 18.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = true
                                )
                            },
                            onDrawSurface = {
                                drawRect(dropColor.copy(alpha = 0.30f))
                            }
                        )
                        .border(1.dp, dropOutline, RoundedCornerShape(25.dp))
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { (screen, icon, label) ->
                    val selected = selectedScreen == screen
                    val itemProgress by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = dockItemAnimation,
                        label = "FloatingDockProgress"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onNavigate(screen) },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = selected,
                            transitionSpec = {
                                if (!AppAnimationSettings.enabled) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else {
                                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.92f)) togetherWith
                                        (fadeOut(tween(120)) + scaleOut(targetScale = 0.94f))
                                }
                            },
                            label = "FloatingDockItem"
                        ) { isSelected ->
                            if (isSelected) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier
                                        .size(23.dp)
                                        .graphicsLayer {
                                            scaleX = 0.92f + itemProgress * 0.12f
                                            scaleY = 0.92f + itemProgress * 0.12f
                                        }
                                )
                            } else {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    NavigationBar(
        modifier = dockModifier.height(if (floating) 72.dp else 80.dp),
        containerColor = if (floating) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        windowInsets = if (floating) WindowInsets(0, 0, 0, 0) else NavigationBarDefaults.windowInsets
    ) {
        NavigationBarItem(
            selected = selectedScreen == Screen.LocalMusic,
            onClick = { onNavigate(Screen.LocalMusic) },
            icon = {
                val progress by animateFloatAsState(
                    targetValue = if (selectedScreen == Screen.LocalMusic) 1f else 0f,
                    animationSpec = if (AppAnimationSettings.enabled) spring() else snap(),
                    label = "DockLibraryIcon"
                )
                Icon(
                    Icons.Default.LibraryMusic,
                    appText("音乐库", "Library"),
                    modifier = Modifier.graphicsLayer {
                        scaleX = 0.9f + progress * 0.16f
                        scaleY = 0.9f + progress * 0.16f
                        alpha = 0.72f + progress * 0.28f
                    }
                )
            },
            label = {
                val progress by animateFloatAsState(
                    targetValue = if (selectedScreen == Screen.LocalMusic) 1f else 0f,
                    animationSpec = if (AppAnimationSettings.enabled) spring() else snap(),
                    label = "DockLibraryLabel"
                )
                Text(
                    appText("音乐库", "Library"),
                    modifier = Modifier.graphicsLayer { alpha = 0.72f + progress * 0.28f }
                )
            },
            colors = md3ItemColors
        )
        NavigationBarItem(
            selected = selectedScreen == Screen.PlayerView,
            onClick = { onNavigate(Screen.PlayerView) },
            icon = {
                val progress by animateFloatAsState(
                    targetValue = if (selectedScreen == Screen.PlayerView) 1f else 0f,
                    animationSpec = if (AppAnimationSettings.enabled) spring() else snap(),
                    label = "DockPlayerIcon"
                )
                Icon(
                    Icons.Default.PlayCircle,
                    appText("播放", "Player"),
                    modifier = Modifier.graphicsLayer {
                        scaleX = 0.9f + progress * 0.16f
                        scaleY = 0.9f + progress * 0.16f
                        alpha = 0.72f + progress * 0.28f
                    }
                )
            },
            label = {
                val progress by animateFloatAsState(
                    targetValue = if (selectedScreen == Screen.PlayerView) 1f else 0f,
                    animationSpec = if (AppAnimationSettings.enabled) spring() else snap(),
                    label = "DockPlayerLabel"
                )
                Text(
                    appText("播放", "Player"),
                    modifier = Modifier.graphicsLayer { alpha = 0.72f + progress * 0.28f }
                )
            },
            colors = md3ItemColors
        )
        NavigationBarItem(
            selected = selectedScreen == Screen.Settings,
            onClick = { onNavigate(Screen.Settings) },
            icon = {
                val progress by animateFloatAsState(
                    targetValue = if (selectedScreen == Screen.Settings) 1f else 0f,
                    animationSpec = if (AppAnimationSettings.enabled) spring() else snap(),
                    label = "DockSettingsIcon"
                )
                Icon(
                    Icons.Default.Settings,
                    appText("设置", "Settings"),
                    modifier = Modifier.graphicsLayer {
                        scaleX = 0.9f + progress * 0.16f
                        scaleY = 0.9f + progress * 0.16f
                        alpha = 0.72f + progress * 0.28f
                    }
                )
            },
            label = {
                val progress by animateFloatAsState(
                    targetValue = if (selectedScreen == Screen.Settings) 1f else 0f,
                    animationSpec = if (AppAnimationSettings.enabled) spring() else snap(),
                    label = "DockSettingsLabel"
                )
                Text(
                    appText("设置", "Settings"),
                    modifier = Modifier.graphicsLayer { alpha = 0.72f + progress * 0.28f }
                )
            },
            colors = md3ItemColors
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(
    isDjMode: Boolean,
    onDjModeChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val dockContentPadding = LocalDockContentPadding.current
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE) }
    var spectrumProgressEnabled by remember {
        mutableStateOf(preferences.getBoolean("spectrum_progress_enabled", true))
    }
    var openPlayerOnSongClick by remember {
        mutableStateOf(preferences.getBoolean("open_player_on_song_click", false))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(appText("播放设置", "Playback settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, appText("返回", "Back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = dockContentPadding)
        ) {
            SettingsSectionHeader(appText("播放行为", "Playback behavior"))
            ListItem(
                headlineContent = { Text(appText("打碟模式", "DJ mode")) },
                supportingContent = {
                    Text(appText("拖动播放进度时启用实时定位、动态速度与唱片转盘效果", "Enable live seeking, variable speed, and turntable effects while dragging"))
                },
                leadingContent = { Icon(Icons.Default.Album, null) },
                trailingContent = {
                    Switch(checked = isDjMode, onCheckedChange = onDjModeChange)
                },
                modifier = Modifier.clickable { onDjModeChange(!isDjMode) }
            )
            ListItem(
                headlineContent = { Text(appText("点击歌曲进入播放页", "Open player when selecting a song")) },
                supportingContent = {
                    Text(
                        if (openPlayerOnSongClick) {
                            appText("点击歌曲后立即打开播放页", "Open the player immediately after selecting a song")
                        } else {
                            appText("点击歌曲只开始播放，通过迷你播放器进入播放页", "Start playback only; open the player from the mini player")
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
            SettingsSectionHeader(appText("播放页显示", "Player display"))
            ListItem(
                headlineContent = { Text(appText("频谱进度条", "Spectrum progress bar")) },
                supportingContent = {
                    Text(appText("播放页进度条叠加实时频谱，可随时开关", "Overlay a live spectrum on the player progress bar"))
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
            }
        }
    }

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
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = LocalDockContentPadding.current)
        ) {
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
    val dockContentPadding = LocalDockContentPadding.current
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
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = dockContentPadding)
        ) {
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
    onSeek: (Float) -> Unit,
    backdrop: Backdrop? = null,
    compactFloating: Boolean = false
) {
    val artwork = rememberArtwork(song, maxPx = 128)
    var sliderPosition by remember(progress) { mutableFloatStateOf(progress) }

    if (compactFloating) {
        val glassShape = RoundedCornerShape(32.dp)
        val glassSurface = MaterialTheme.colorScheme.surfaceContainerHigh
        val glassOutline = MaterialTheme.colorScheme.outlineVariant
        val controlTint = MaterialTheme.colorScheme.primaryContainer
        val controlOutline = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        val glassModifier = if (backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { glassShape },
                effects = {
                    vibrancy()
                    backdropBlur(8.dp.toPx())
                    lens(18.dp.toPx(), 28.dp.toPx(), chromaticAberration = true)
                },
                onDrawSurface = {
                    drawRect(glassSurface.copy(alpha = 0.34f))
                }
            )
        } else {
            Modifier.background(glassSurface.copy(alpha = 0.58f), glassShape)
        }
        Box(
            modifier = glassModifier
                .fillMaxSize()
                .border(1.dp, glassOutline.copy(alpha = 0.58f), glassShape)
                .clickable { onClick() }
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (artwork != null) {
                        androidx.compose.foundation.Image(
                            bitmap = artwork.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    }
                    val controlModifier = if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { CircleShape },
                            effects = {
                                vibrancy()
                                backdropBlur(4.dp.toPx())
                                lens(
                                    refractionHeight = 9.dp.toPx(),
                                    refractionAmount = 15.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = true
                                )
                            },
                            onDrawSurface = {
                                drawCircle(controlTint.copy(alpha = 0.46f))
                            }
                        )
                    } else {
                        Modifier.background(controlTint, CircleShape)
                    }
                    Box(
                        modifier = controlModifier
                            .size(38.dp)
                            .border(1.dp, controlOutline, CircleShape)
                            .clickable { onTogglePlay() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) appText("暂停", "Pause") else appText("播放", "Play"),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
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
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
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
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(40.dp)
                    ) {
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
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(40.dp)
                    ) {
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
 * 按需加载封面：先查尺寸分级内存缓存，再在后台合并磁盘读取请求。
 */
@Composable
fun rememberArtwork(song: Song, maxPx: Int = 768): Bitmap? {
    val context = LocalContext.current
    var loaded by remember(song.uri, song.artwork, maxPx) {
        mutableStateOf(ScanRecordsManager.peekArtwork(song, maxPx))
    }
    LaunchedEffect(song.uri, song.artwork, maxPx) {
        if (loaded == null) {
            val bitmap = withContext(Dispatchers.IO) {
                ScanRecordsManager.loadArtworkAsync(context, song, maxPx)?.artwork
            }
            if (bitmap != null) loaded = bitmap
        }
    }
    return loaded
}

@Composable
private fun ConfigurableAlbumArtwork(
    bitmap: Bitmap,
    contentDescription: String?,
    visualizerEnergy: Float = 0f,
    modifier: Modifier = Modifier
) {
    if (AlbumDotMatrixSettings.enabled) {
        DotMatrixArtwork(bitmap = bitmap, visualizerEnergy = visualizerEnergy, modifier = modifier)
    } else {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
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
    if (!AppAnimationSettings.enabled) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
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
    context: android.content.Context,
    musicDirectories: List<Uri>,
    songs: List<Song>,
    onSelectSong: (Song) -> Unit,
    repeatMode: Int,
    onRepeatModeChange: () -> Unit,
    shuffleMode: Boolean,
    onShuffleModeChange: () -> Unit,
    onBack: () -> Unit,
    isLandscape: Boolean,
    floatingDockVisible: Boolean,
    fixedDockBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    mvModeEnabled: Boolean = false,
    onMvModeEnabledChange: (Boolean) -> Unit = {},
    mvVideoPlayer: ExoPlayer? = null
) {
    val configuration = LocalConfiguration.current
    val baseArtworkSize = if (isLandscape) minOf(320, (configuration.screenHeightDp * 0.45f).toInt()).dp else 252.dp
    val artworkShrink = if (AlbumDotMatrixSettings.enabled) 0.dp else with(LocalDensity.current) { 50f.toDp() }
    val artworkSize = maxOf(baseArtworkSize - artworkShrink, 0.dp)
    val artworkShape = RoundedCornerShape(if (isLandscape) 20.dp else 32.dp)
    val pagePadding = if (isLandscape) 16.dp else 32.dp
    val sectionPadding = if (isLandscape) 4.dp else 16.dp
    val floatingDockControlPadding = if (!floatingDockVisible) {
        0.dp
    } else if (isLandscape) {
        108.dp
    } else {
        108.dp
    }
    val dockControlPadding = floatingDockControlPadding + fixedDockBottomPadding
    var showLyrics by remember { mutableStateOf(false) }
    // MV 模式下退出歌词（模式状态本身由主界面持有，切页不丢失）
    LaunchedEffect(mvModeEnabled) {
        if (mvModeEnabled) showLyrics = false
    }
    var quickListExpanded by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var turntableRotation by remember { mutableFloatStateOf(0f) }
    var scratchSpeed by remember { mutableFloatStateOf(1f) }
    var lastDragPosition by remember { mutableFloatStateOf(0f) }
    var lastDragTime by remember { mutableLongStateOf(0L) }
    var lastScratchDispatchTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(position, duration) { if (!isDragging) sliderPosition = if (duration > 0) position.toFloat() / duration else 0f }
    val animationsEnabled = AppAnimationSettings.enabled
    LaunchedEffect(isDjMode, isPlaying, isDragging, animationsEnabled) {
        while (animationsEnabled && isDjMode && isPlaying && !isDragging) {
            turntableRotation = (turntableRotation + 1.8f) % 360f
            delay(16L)
        }
    }

    // 按需加载封面（避免播放页首次显示时主线程解码卡顿）
    val playerArtwork = rememberArtwork(song)
    // MV 模式下背景改用视频实时取帧，模糊后作为动态底色
    val mvSurfaceState = remember { mutableStateOf<SurfaceView?>(null) }
    val mvBackdrop = rememberMvBackdropFrame(
        videoPlayer = mvVideoPlayer,
        surfaceState = mvSurfaceState,
        enabled = mvModeEnabled && mvVideoPlayer != null && song.videoUri != null
    )
    val playerPrefs = remember {
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
    }
    var spectrumProgressEnabled by remember {
        mutableStateOf(playerPrefs.getBoolean("spectrum_progress_enabled", true))
    }
    // 从设置返回时刷新开关
    LaunchedEffect(song.uri, isPlaying) {
        spectrumProgressEnabled = playerPrefs.getBoolean("spectrum_progress_enabled", true)
    }
    // 点阵鼓点：仍用当前进度窗口的实时频谱
    val fileSpectrumEnabled = isPlaying && animationsEnabled &&
        AlbumDotMatrixSettings.enabled && AlbumDotMatrixSettings.visualizerDepthEnabled
    val fileSpectrum = rememberAudioFileSpectrum(
        context = context,
        uri = song.uri,
        positionMs = position,
        enabled = fileSpectrumEnabled,
        bandCount = 80
    )
    // 进度条频谱：整首曲目的静态频谱轮廓（每曲目计算一次）
    val trackSpectrum = rememberAudioTrackSpectrum(
        context = context,
        uri = song.uri,
        enabled = spectrumProgressEnabled && animationsEnabled,
        bandCount = 80
    )
    val beatEnergy = if (AlbumDotMatrixSettings.enabled && fileSpectrum.isNotEmpty()) {
        fileSpectrum.take(8).average().toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val beatScale by animateFloatAsState(
        targetValue = beatScaleFromEnergy(beatEnergy),
        animationSpec = tween(durationMillis = if (animationsEnabled) 100 else 0),
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 背景层随歌曲封面交叉渐变，避免切歌时瞬间跳变。
        AnimatedContent(
            targetState = song.uri,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (animationsEnabled) {
                    fadeIn(tween(700)) togetherWith fadeOut(tween(700))
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "PlayerBackgroundTransition"
        ) { targetUri ->
            val targetSong = songs.firstOrNull { it.uri == targetUri } ?: song
            val targetArtwork = rememberArtwork(targetSong)
            // MV 帧优先，未取到帧时退回封面
            val backdrop = mvBackdrop ?: targetArtwork
            Box(Modifier.fillMaxSize()) {
                if (backdrop != null) {
                    androidx.compose.foundation.Image(
                        bitmap = backdrop.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.18f
                                scaleY = 1.18f
                            }
                            .blur(60.dp),
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
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 28.dp, vertical = 12.dp)
                    .padding(bottom = dockControlPadding)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = appText("返回音乐列表", "Back to music library")
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        AnimatedSongText(song, songs, MaterialTheme.typography.headlineMedium)
                        AnimatedArtistText(song, songs, MaterialTheme.typography.titleMedium)
                    }
                    if (song.videoUri != null) {
                        IconButton(onClick = { onMvModeEnabledChange(!mvModeEnabled) }) {
                            Icon(
                                if (mvModeEnabled) Icons.Default.MusicNote else Icons.Default.Movie,
                                contentDescription = "切换 MV/音乐"
                            )
                        }
                    }
                    Icon(
                        Icons.Default.Sensors,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        modifier = Modifier.size(26.dp)
                    )
                }
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(34.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(0.47f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            AnimatedContent(
                                targetState = song.uri,
                                transitionSpec = { songChangeTransition(songs, initialState, targetState) },
                                label = "LandscapeArtworkTransition"
                            ) { targetUri ->
                                val targetSong = songs.firstOrNull { it.uri == targetUri } ?: song
                                val targetArtwork = rememberArtwork(targetSong)
                                if (targetArtwork != null) {
                                    ConfigurableAlbumArtwork(
                                        bitmap = targetArtwork,
                                        contentDescription = null,
                                        visualizerEnergy = beatEnergy,
                                        modifier = Modifier
                                            .size(artworkSize)
                                            .graphicsLayer {
                                                scaleX = beatScale
                                                scaleY = beatScale
                                            }
                                            .clip(RoundedCornerShape(20.dp)),
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
                        AudioFormatBadges(
                            audioFormat = audioFormat.value,
                            fileSize = song.duration,
                            centered = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SpectrumProgressSlider(
                            value = sliderPosition,
                            onValueChange = { isDragging = true; sliderPosition = it },
                            onValueChangeFinished = {
                                onSeek((sliderPosition * duration).toLong())
                                isDragging = false
                            },
                            isPlaying = isPlaying,
                            spectrumEnabled = spectrumProgressEnabled,
                            spectrumBars = trackSpectrum,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.onSurface,
                                activeTrackColor = MaterialTheme.colorScheme.onSurface
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
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            repeatMode = repeatMode,
                            onRepeatModeChange = onRepeatModeChange,
                            shuffleMode = shuffleMode,
                            onShuffleModeChange = onShuffleModeChange
                        )
                    }

                    Box(
                        modifier = Modifier.weight(0.53f).fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        val mvUri = song.videoUri
                        val mvPlayer = mvVideoPlayer
                        if (mvUri != null && mvModeEnabled && mvPlayer != null) {
                            MvVideoArea(
                                videoPlayer = mvPlayer,
                                videoUri = mvUri,
                                surfaceState = mvSurfaceState,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                            )
                        } else {
                            LyricView(lyrics, position, duration, onSeek, songArtwork = playerArtwork)
                        }
                    }
                }
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(pagePadding)
                .padding(bottom = dockControlPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 中部：歌词/专辑，带切换动画
            Box(
                Modifier
                    .weight(1f)
                    .requiredWidth(configuration.screenWidthDp.dp),
                Alignment.Center
            ) {
                AnimatedContent(
                    targetState = showLyrics to song.uri,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (!animationsEnabled) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else if (initialState.first != targetState.first) {
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
                    val targetMvUri = targetSong.videoUri
                    val mvPlayer = mvVideoPlayer
                    if (targetMvUri != null && mvModeEnabled && mvPlayer != null) {
                        MvVideoArea(
                            videoPlayer = mvPlayer,
                            videoUri = targetMvUri,
                            surfaceState = mvSurfaceState,
                            modifier = Modifier.size(artworkSize).clip(artworkShape)
                        )
                    } else if (targetShowLyrics) {
                        LyricView(lyrics, position, duration, onSeek, edgeToEdge = true, songArtwork = targetArtwork)
                    } else {
                        if (isDjMode) {
                            DjTurntable(
                                artwork = targetArtwork,
                                size = artworkSize,
                                rotation = turntableRotation,
                                speed = scratchSpeed,
                                isScratching = isDragging,
                                beatScale = beatScale,
                                visualizerEnergy = beatEnergy,
                                onClick = { showLyrics = true }
                            )
                        } else if (targetArtwork != null) {
                            ConfigurableAlbumArtwork(
                                bitmap = targetArtwork,
                                contentDescription = null,
                                visualizerEnergy = beatEnergy,
                                modifier = (if (AlbumDotMatrixSettings.enabled) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier
                                        .size(artworkSize)
                                        .clip(artworkShape)
                                })
                                    .graphicsLayer {
                                        scaleX = beatScale
                                        scaleY = beatScale
                                    }
                                    .clickable { showLyrics = true },
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
                            if (song.videoUri != null) {
                                IconButton(onClick = { onMvModeEnabledChange(!mvModeEnabled) }) {
                                    Icon(
                                        if (mvModeEnabled) Icons.Default.MusicNote else Icons.Default.Movie,
                                        contentDescription = "切换 MV/音乐"
                                    )
                                }
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
                        if (song.videoUri != null) {
                            IconButton(onClick = { onMvModeEnabledChange(!mvModeEnabled) }) {
                                Icon(
                                    if (mvModeEnabled) Icons.Default.MusicNote else Icons.Default.Movie,
                                    contentDescription = "切换 MV/音乐"
                                )
                            }
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
                    spectrumBars = trackSpectrum,
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
    visualizerEnergy: Float = 0f,
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
                ConfigurableAlbumArtwork(
                    bitmap = artwork,
                    contentDescription = null,
                    visualizerEnergy = visualizerEnergy,
                    modifier = Modifier.fillMaxSize()
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
        Surface(
            onClick = onTogglePlay,
            shape = RoundedCornerShape(if (compact) 18.dp else 24.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(playSize),
            shadowElevation = if (compact) 6.dp else 10.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        if (AppAnimationSettings.enabled) {
                            (fadeIn(tween(220)) +
                                scaleIn(tween(260), initialScale = 0.62f) +
                                slideInVertically(tween(260)) { if (targetState) it / 5 else -it / 5 }) togetherWith
                                (fadeOut(tween(160)) +
                                    scaleOut(tween(200), targetScale = 0.72f) +
                                    slideOutVertically(tween(200)) { if (targetState) -it / 5 else it / 5 })
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    label = "PlayPauseIconTransition"
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) appText("暂停", "Pause") else appText("播放", "Play"),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(playIconSize)
                            .graphicsLayer {
                                rotationZ = if (playing) 0f else -2f
                            }
                    )
                }
            }
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

private data class BilingualLyricLine(
    val timestamp: Long,
    val original: String,
    val translation: String?
)

private fun groupBilingualLyrics(lyrics: List<Pair<Long, String>>): List<BilingualLyricLine> =
    lyrics.groupBy { it.first }
        .toSortedMap()
        .map { (timestamp, entries) ->
            BilingualLyricLine(
                timestamp = timestamp,
                original = entries.first().second,
                translation = entries.drop(1).joinToString(" / ") { it.second }.takeIf { it.isNotBlank() }
            )
        }

/** 微信对话风格：一条歌词对应一条聊天气泡 */
private data class ChatLyricMessage(
    val timestamp: Long,
    val text: String,
    val subText: String?,
    val onRight: Boolean
)

private fun buildChatLyricMessages(
    lines: List<BilingualLyricLine>,
    layout: String
): List<ChatLyricMessage> {
    val bilingual = layout == "bilingual"
    val messages = ArrayList<ChatLyricMessage>(lines.size * 2)
    lines.forEachIndexed { index, line ->
        // 译文作为气泡主行，原文降为同一气泡内的次要一行
        val primary = line.translation ?: line.original
        val secondary = line.translation?.let { line.original }
        if (bilingual) {
            messages += ChatLyricMessage(line.timestamp, primary, null, false)
            secondary?.let {
                messages += ChatLyricMessage(line.timestamp, it, null, true)
            }
        } else {
            messages += ChatLyricMessage(
                timestamp = line.timestamp,
                text = primary,
                subText = secondary,
                onRight = index % 2 == 1
            )
        }
    }
    return messages
}

private fun revealLyricProgress(text: String, progress: Float): String {
    if (progress >= 1f || text.isEmpty()) return text
    val total = text.codePointCount(0, text.length)
    val shown = (total * progress).toInt().coerceIn(1, total)
    return text.substring(0, text.offsetByCodePoints(0, shown))
}

@Composable
private fun ChatLyricView(
    lines: List<BilingualLyricLine>,
    effectivePosition: Long,
    duration: Long,
    edgeToEdge: Boolean,
    screenWidthDp: Int,
    fontScale: Float,
    animationsEnabled: Boolean,
    animationSpeed: Float,
    autoFollow: Boolean,
    scrollEffect: String,
    artwork: Bitmap?,
    onSeek: (Long) -> Unit
) {
    val dark = isSystemInDarkTheme()
    val showAvatars = LyricsDisplaySettings.chatAvatarsEnabled
    val showTimestamps = LyricsDisplaySettings.chatTimestampsEnabled
    val chatLayout = LyricsDisplaySettings.chatLayout
    val messages = remember(lines, chatLayout) { buildChatLyricMessages(lines, chatLayout) }
    // 聊天风格只回放已经唱到的歌词，下一句要等它的时间戳到达才出现
    val visibleCount = (messages.indexOfLast { it.timestamp <= effectivePosition } + 1)
        .coerceAtLeast(0)
    val visibleMessages =
        if (visibleCount >= messages.size) messages else messages.subList(0, visibleCount)
    val currentMessageIndex = visibleMessages.lastIndex
    val bubbleMaxWidth = (screenWidthDp * if (edgeToEdge) 0.60f else 0.66f).dp

    val otherBubble = if (dark) Color(0xFF25262B) else Color.White
    val otherText = if (dark) Color(0xFFE9EAEE) else Color(0xFF1B1B1F)
    val selfBubble = if (dark) Color(0xFF2E5738) else Color(0xFF95EC69)
    val selfText = if (dark) Color(0xFFD6F0D2) else Color(0xFF14260F)
    val metaColor = if (dark) Color(0xFF8A8B90) else Color(0xFF9A9AA0)

    if (visibleMessages.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                appText("歌词会像消息一样逐条出现", "Lyrics arrive one message at a time"),
                style = MaterialTheme.typography.bodySmall,
                color = metaColor
            )
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(
        currentMessageIndex,
        autoFollow,
        scrollEffect,
        edgeToEdge,
        fontScale,
        showTimestamps,
        visibleMessages.size
    ) {
        if (!autoFollow || currentMessageIndex < 0) return@LaunchedEffect
        withFrameNanos { }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == currentMessageIndex }) {
            listState.scrollToItem(currentMessageIndex)
        }
        val layoutInfo = snapshotFlow { listState.layoutInfo }.first { info ->
            info.viewportEndOffset > info.viewportStartOffset &&
                info.visibleItemsInfo.any { it.index == currentMessageIndex }
        }
        val currentItem = layoutInfo.visibleItemsInfo.first { it.index == currentMessageIndex }
        val viewportHeight =
            (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
        // 最新一条停在底部，像聊天记录一样向上堆积
        val targetBottom =
            layoutInfo.viewportEndOffset - viewportHeight * 0.06f
        val delta = currentItem.offset + currentItem.size - targetBottom
        if (scrollEffect == "instant") listState.scrollBy(delta) else listState.animateScrollBy(delta)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxHeight()
            .then(
                if (edgeToEdge) Modifier.requiredWidth(screenWidthDp.dp) else Modifier.fillMaxWidth()
            ),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(visibleMessages) { index, message ->
            val isCurrent = index == currentMessageIndex
            val age = currentMessageIndex - index
            // 当前气泡只放出已经唱到的字，其余历史气泡保持完整文本
            val lineEnd = messages.getOrNull(index + 1)?.timestamp
                ?: duration.takeIf { it > message.timestamp }
                ?: (message.timestamp + 5_000L)
            val lineProgress = if (isCurrent) {
                ((effectivePosition - message.timestamp).toFloat() /
                        (lineEnd - message.timestamp).coerceAtLeast(1L).toFloat())
                    .coerceIn(0f, 1f)
            } else {
                1f
            }
            val transitionDuration =
                if (animationsEnabled) (420 / animationSpeed).toInt().coerceAtLeast(120) else 0
            val bubbleAlpha by animateFloatAsState(
                targetValue = when {
                    isCurrent -> 1f
                    age <= 2 -> 0.88f
                    age <= 5 -> 0.74f
                    else -> 0.6f
                },
                animationSpec = tween(transitionDuration),
                label = "chatBubbleAlpha"
            )
            Column(Modifier.fillMaxWidth()) {
                val previous = visibleMessages.getOrNull(index - 1)
                if (showTimestamps &&
                    (previous == null || message.timestamp - previous.timestamp >= 20_000L)
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSeek(
                                (message.timestamp - LyricsDisplaySettings.offsetMs.toLong())
                                    .coerceAtLeast(0L)
                            )
                        },
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = if (message.onRight) Arrangement.End else Arrangement.Start
                ) {
                    if (showAvatars && !message.onRight) {
                        ChatLyricAvatar(false, artwork, Modifier.padding(top = 2.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    ChatLyricBubble(
                        text = revealLyricProgress(message.text, lineProgress),
                        subText = message.subText?.let { revealLyricProgress(it, lineProgress) },
                        onRight = message.onRight,
                        bubbleColor = if (message.onRight) selfBubble else otherBubble,
                        textColor = if (message.onRight) selfText else otherText,
                        maxWidth = bubbleMaxWidth,
                        fontScale = fontScale,
                        modifier = Modifier.graphicsLayer { alpha = bubbleAlpha }
                    )
                    if (showAvatars && message.onRight) {
                        Spacer(Modifier.width(6.dp))
                        ChatLyricAvatar(true, artwork, Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatLyricBubble(
    text: String,
    subText: String?,
    onRight: Boolean,
    bubbleColor: Color,
    textColor: Color,
    maxWidth: androidx.compose.ui.unit.Dp,
    fontScale: Float,
    modifier: Modifier = Modifier
) {
    val bubbleShape = RoundedCornerShape(
        topStart = if (onRight) 15.dp else 4.dp,
        topEnd = if (onRight) 4.dp else 15.dp,
        bottomEnd = 15.dp,
        bottomStart = 15.dp
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = if (onRight) Arrangement.End else Arrangement.Start
    ) {
        if (!onRight) {
            ChatLyricTail(false, bubbleColor, Modifier.padding(top = 9.dp).size(7.dp, 12.dp))
        }
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (16.5f * fontScale).sp,
                    lineHeight = (24f * fontScale).sp
                ),
                color = textColor
            )
            subText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (13.5f * fontScale).sp,
                        lineHeight = (19f * fontScale).sp
                    ),
                    color = textColor.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        if (onRight) {
            ChatLyricTail(true, bubbleColor, Modifier.padding(top = 9.dp).size(7.dp, 12.dp))
        }
    }
}

@Composable
private fun ChatLyricTail(onRight: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val tailWidth = size.width
        val tailHeight = size.height
        val tail = Path().apply {
            if (onRight) {
                moveTo(0f, 0f)
                lineTo(tailWidth, tailHeight * 0.42f)
                lineTo(0f, tailHeight)
            } else {
                moveTo(tailWidth, 0f)
                lineTo(0f, tailHeight * 0.42f)
                lineTo(tailWidth, tailHeight)
            }
            close()
        }
        drawPath(tail, color)
    }
}

@Composable
private fun ChatLyricAvatar(
    self: Boolean,
    artwork: Bitmap?,
    modifier: Modifier = Modifier
) {
    val avatarShape = RoundedCornerShape(5.dp)
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(avatarShape)
            .background(
                if (self) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            ),
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
                imageVector = if (self) Icons.Default.Person else Icons.Default.MusicNote,
                contentDescription = null,
                tint = if (self) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun LyricView(
    lyrics: List<Pair<Long, String>>,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    edgeToEdge: Boolean = false,
    songArtwork: Bitmap? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    remember(context) { LyricsDisplaySettings.load(context) }
    val wordProgressEnabled = LyricsDisplaySettings.wordProgressEnabled
    val depthBlurEnabled = LyricsDisplaySettings.depthBlurEnabled
    val lyricFontScale = LyricsDisplaySettings.fontScale
    val lyricAlignment = LyricsDisplaySettings.alignment
    val lyricStyle = LyricsDisplaySettings.style
    val lyricDirection = LyricsDisplaySettings.direction
    val lyricScrollEffect = LyricsDisplaySettings.scrollEffect
    val lyricAnimations = LyricsDisplaySettings.animationEnabled
    val animationSpeed = LyricsDisplaySettings.animationSpeed
    val autoFollow = LyricsDisplaySettings.autoFollow
    val effectivePosition = (currentPosition + LyricsDisplaySettings.offsetMs.toLong()).coerceAtLeast(0L)
    val currentLyricColor = Color(LyricsDisplaySettings.currentColorArgb)
    val inactiveLyricColor = Color(LyricsDisplaySettings.inactiveColorArgb)
    val lineTextAlign = when (lyricAlignment) {
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> TextAlign.Start
    }
    if (lyrics.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text(appText("未找到歌词", "No lyrics found"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val displayLyrics = remember(lyrics) { groupBilingualLyrics(lyrics) }
    val lineIdx = displayLyrics.indexOfLast { it.timestamp <= effectivePosition }
    if (lyricStyle == "chat") {
        ChatLyricView(
            lines = displayLyrics,
            effectivePosition = effectivePosition,
            duration = duration,
            edgeToEdge = edgeToEdge,
            screenWidthDp = configuration.screenWidthDp,
            fontScale = lyricFontScale,
            animationsEnabled = lyricAnimations,
            animationSpeed = animationSpeed,
            autoFollow = autoFollow,
            scrollEffect = lyricScrollEffect,
            artwork = songArtwork,
            onSeek = onSeek
        )
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(
        lineIdx,
        configuration.orientation,
        configuration.screenHeightDp,
        lyricFontScale,
        autoFollow,
        displayLyrics.size
    ) {
        if (autoFollow && lineIdx >= 0) {
            val currentIndex = lineIdx.coerceAtMost(displayLyrics.lastIndex)
            withFrameNanos { }
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == currentIndex }) {
                listState.scrollToItem(currentIndex)
            }
            val layoutInfo = snapshotFlow { listState.layoutInfo }.first { info ->
                info.viewportEndOffset > info.viewportStartOffset &&
                    info.visibleItemsInfo.any { it.index == currentIndex }
            }
            val currentItem = layoutInfo.visibleItemsInfo.first { it.index == currentIndex }
            val viewportCenter =
                (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
            val currentItemCenter = currentItem.offset + currentItem.size / 2f
            if (lyricScrollEffect == "instant") {
                listState.scrollBy(currentItemCenter - viewportCenter)
            } else {
                listState.animateScrollBy(currentItemCenter - viewportCenter)
            }
        }
    }
    LazyColumn(
        state = listState,
        reverseLayout = lyricDirection == "bottom_up",
        modifier = Modifier
            .fillMaxHeight()
            .then(
                if (edgeToEdge) {
                    Modifier.requiredWidth(configuration.screenWidthDp.dp)
                } else {
                    Modifier.fillMaxWidth()
                }
            ),
        horizontalAlignment = Alignment.Start,
        contentPadding = PaddingValues(vertical = 220.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(displayLyrics) { index, entry ->
            val timestamp = entry.timestamp
            val text = entry.original
            val isCurrent = index == lineIdx
            val distanceFromCurrent = kotlin.math.abs(index - lineIdx)
            val nextTimestamp = if (isCurrent) {
                displayLyrics.asSequence()
                    .drop(index + 1)
                    .map { it.timestamp }
                    .firstOrNull { it > timestamp }
            } else {
                null
            }
            val lineEnd = nextTimestamp
                ?: duration.takeIf { isCurrent && it > timestamp }
                ?: (timestamp + 5_000L)
            val lineProgress = if (isCurrent) {
                ((effectivePosition - timestamp).toFloat() /
                        (lineEnd - timestamp).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val transitionDuration = if (lyricAnimations) (480 / animationSpeed).toInt().coerceAtLeast(120) else 0
            val textAlpha by animateFloatAsState(
                targetValue = when {
                    isCurrent -> 1f
                    distanceFromCurrent == 1 -> 0.58f
                    distanceFromCurrent == 2 -> 0.38f
                    else -> 0.22f
                },
                animationSpec = tween(transitionDuration),
                label = "lyricAlpha"
            )
            val lyricIndent by animateDpAsState(
                targetValue = if (isCurrent) 0.dp else 10.dp,
                animationSpec = tween(transitionDuration),
                label = "lyricIndent"
            )
            val lyricBlur by animateDpAsState(
                targetValue = if (!depthBlurEnabled) 0.dp else when {
                    isCurrent -> 0.dp
                    distanceFromCurrent == 1 -> 0.7.dp
                    distanceFromCurrent == 2 -> 1.4.dp
                    else -> 2.2.dp
                },
                animationSpec = tween(if (lyricAnimations) (520 / animationSpeed).toInt().coerceAtLeast(120) else 0),
                label = "lyricBlur"
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = (if (edgeToEdge) 8.dp else 24.dp) + lyricIndent,
                        end = if (edgeToEdge) 8.dp else 24.dp
                    )
                    .clickable {
                        onSeek((timestamp - LyricsDisplaySettings.offsetMs.toLong()).coerceAtLeast(0L))
                    }
                    .blur(lyricBlur)
                    .graphicsLayer {
                        alpha = textAlpha
                    },
                horizontalAlignment = when (lyricAlignment) {
                    "center" -> Alignment.CenterHorizontally
                    "end" -> Alignment.End
                    else -> Alignment.Start
                }
            ) {
                val inactiveSize = when (lyricStyle) {
                    "compact" -> 18f
                    "immersive" -> 24f
                    else -> 22f
                }
                val textStyle = if (isCurrent) {
                    MaterialTheme.typography.headlineMedium.copy(
                        fontSize = (inactiveSize * lyricFontScale).sp,
                        lineHeight = ((inactiveSize + 6f) * lyricFontScale).sp,
                        fontWeight = FontWeight.Black,
                        textAlign = lineTextAlign
                    )
                } else {
                    MaterialTheme.typography.titleLarge.copy(
                        fontSize = (inactiveSize * lyricFontScale).sp,
                        lineHeight = ((inactiveSize + 6f) * lyricFontScale).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = lineTextAlign,
                        color = inactiveLyricColor
                    )
                }
                if (isCurrent && wordProgressEnabled) {
                    val characterCount = text.codePointCount(0, text.length)
                    val playedCharacterCount = (characterCount * lineProgress).toInt()
                        .coerceIn(0, characterCount)
                    val playedTextEnd = text.offsetByCodePoints(0, playedCharacterCount)
                    val playedColor = currentLyricColor
                    val pendingColor = currentLyricColor.copy(alpha = 0.32f)
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
                        style = textStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(text = text, style = textStyle, color = if (isCurrent) currentLyricColor else inactiveLyricColor, modifier = Modifier.fillMaxWidth())
                }
                entry.translation?.let { translation ->
                    Text(
                        text = translation,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = (16f * lyricFontScale).sp,
                            lineHeight = (22f * lyricFontScale).sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = lineTextAlign
                        ),
                        color = if (isCurrent) currentLyricColor.copy(alpha = 0.72f) else inactiveLyricColor.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
                    )
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
fun isVideoFile(name: String?): Boolean { val lower = name?.lowercase() ?: return false; return listOf(".mp4", ".mkv", ".mov", ".avi", ".webm", ".flv", ".wmv", ".m4v", ".3gp", ".ts").any { lower.endsWith(it) } }
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
