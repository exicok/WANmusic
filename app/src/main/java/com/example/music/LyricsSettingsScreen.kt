package com.example.music

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

object LyricsDisplaySettings {
    var wordProgressEnabled by mutableStateOf(true)
    var depthBlurEnabled by mutableStateOf(true)
    var fontScale by mutableFloatStateOf(1f)
    var alignment by mutableStateOf("start")
    var style by mutableStateOf("melox")
    var direction by mutableStateOf("top_down")
    var scrollEffect by mutableStateOf("smooth")
    var animationEnabled by mutableStateOf(true)
    var animationSpeed by mutableFloatStateOf(1f)
    var autoFollow by mutableStateOf(true)
    var offsetMs by mutableFloatStateOf(0f)
    var currentColorArgb by mutableStateOf(0xFFFFFFFF.toInt())
    var inactiveColorArgb by mutableStateOf(0x99FFFFFF.toInt())
    var chatLayout by mutableStateOf("alternate")
    var chatAvatarsEnabled by mutableStateOf(true)
    var chatTimestampsEnabled by mutableStateOf(true)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        wordProgressEnabled = prefs.getBoolean("lyrics_word_progress", true)
        depthBlurEnabled = prefs.getBoolean("lyrics_depth_blur", true)
        fontScale = prefs.getFloat("lyrics_font_scale", 1f).coerceIn(0.75f, 1.5f)
        alignment = prefs.getString("lyrics_alignment", "start") ?: "start"
        style = prefs.getString("lyrics_style", "melox") ?: "melox"
        direction = prefs.getString("lyrics_direction", "top_down") ?: "top_down"
        scrollEffect = prefs.getString("lyrics_scroll_effect", "smooth") ?: "smooth"
        animationEnabled = prefs.getBoolean("lyrics_animation_enabled", true)
        animationSpeed = prefs.getFloat("lyrics_animation_speed", 1f).coerceIn(0.6f, 1.8f)
        autoFollow = prefs.getBoolean("lyrics_auto_follow", true)
        offsetMs = prefs.getFloat("lyrics_offset_ms", 0f).coerceIn(-5_000f, 5_000f)
        currentColorArgb = prefs.getInt("lyrics_current_color", 0xFFFFFFFF.toInt())
        inactiveColorArgb = prefs.getInt("lyrics_inactive_color", 0x99FFFFFF.toInt())
        chatLayout = when (prefs.getString("lyrics_chat_layout", "alternate")) {
            "bilingual" -> "bilingual"
            else -> "alternate"
        }
        chatAvatarsEnabled = prefs.getBoolean("lyrics_chat_avatars", true)
        chatTimestampsEnabled = prefs.getBoolean("lyrics_chat_timestamps", true)
    }

    fun setWordProgress(context: Context, enabled: Boolean) {
        wordProgressEnabled = enabled
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("lyrics_word_progress", enabled)
        }
    }

    fun setDepthBlur(context: Context, enabled: Boolean) {
        depthBlurEnabled = enabled
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("lyrics_depth_blur", enabled)
        }
    }

    fun cycleFontScale(context: Context) {
        fontScale = when {
            fontScale < 0.98f -> 1f
            fontScale < 1.08f -> 1.14f
            else -> 0.92f
        }
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit {
            putFloat("lyrics_font_scale", fontScale)
        }
    }

    fun setFontScale(context: Context, value: Float) {
        fontScale = value.coerceIn(0.75f, 1.5f)
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit {
            putFloat("lyrics_font_scale", fontScale)
        }
    }

    fun setAlignment(context: Context, value: String) = saveString(context, "lyrics_alignment", value) { alignment = it }
    fun setStyle(context: Context, value: String) = saveString(context, "lyrics_style", value) { style = it }
    fun setDirection(context: Context, value: String) = saveString(context, "lyrics_direction", value) { direction = it }
    fun setScrollEffect(context: Context, value: String) = saveString(context, "lyrics_scroll_effect", value) { scrollEffect = it }
    fun setAnimationEnabled(context: Context, value: Boolean) = saveBoolean(context, "lyrics_animation_enabled", value) { animationEnabled = it }
    fun setAutoFollow(context: Context, value: Boolean) = saveBoolean(context, "lyrics_auto_follow", value) { autoFollow = it }
    fun setAnimationSpeed(context: Context, value: Float) {
        animationSpeed = value.coerceIn(0.6f, 1.8f)
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit { putFloat("lyrics_animation_speed", animationSpeed) }
    }
    fun setOffset(context: Context, value: Float) {
        offsetMs = value.coerceIn(-5_000f, 5_000f)
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit { putFloat("lyrics_offset_ms", offsetMs) }
    }
    fun setCurrentColor(context: Context, value: Int) {
        currentColorArgb = value
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit { putInt("lyrics_current_color", value) }
    }
    fun setInactiveColor(context: Context, value: Int) {
        inactiveColorArgb = value
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit { putInt("lyrics_inactive_color", value) }
    }
    fun setChatLayout(context: Context, value: String) = saveString(context, "lyrics_chat_layout", value) { chatLayout = it }
    fun setChatAvatarsEnabled(context: Context, value: Boolean) = saveBoolean(context, "lyrics_chat_avatars", value) { chatAvatarsEnabled = it }
    fun setChatTimestampsEnabled(context: Context, value: Boolean) = saveBoolean(context, "lyrics_chat_timestamps", value) { chatTimestampsEnabled = it }
    fun reset(context: Context) {
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit {
            remove("lyrics_alignment"); remove("lyrics_style"); remove("lyrics_direction"); remove("lyrics_scroll_effect"); remove("lyrics_animation_enabled")
            remove("lyrics_animation_speed"); remove("lyrics_auto_follow"); remove("lyrics_offset_ms")
            remove("lyrics_current_color"); remove("lyrics_inactive_color"); remove("lyrics_font_scale")
            remove("lyrics_chat_layout"); remove("lyrics_chat_avatars"); remove("lyrics_chat_timestamps")
        }
        load(context)
    }
    private fun saveString(context: Context, key: String, value: String, update: (String) -> Unit) {
        update(value); context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit { putString(key, value) }
    }
    private fun saveBoolean(context: Context, key: String, value: Boolean, update: (Boolean) -> Unit) {
        update(value); context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit { putBoolean(key, value) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dockContentPadding = LocalDockContentPadding.current
    remember(context) { LyricsDisplaySettings.load(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("歌词设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = dockContentPadding)
        ) {
            SettingsSectionHeader("Apple Music 风格")
            ListItem(
                headlineContent = { Text("逐字进度") },
                supportingContent = { Text("当前歌词按照播放进度逐字填充") },
                leadingContent = { Icon(Icons.Default.Lyrics, null) },
                trailingContent = {
                    Switch(
                        checked = LyricsDisplaySettings.wordProgressEnabled,
                        onCheckedChange = { LyricsDisplaySettings.setWordProgress(context, it) }
                    )
                },
                modifier = Modifier.clickable {
                    LyricsDisplaySettings.setWordProgress(context, !LyricsDisplaySettings.wordProgressEnabled)
                }
            )
            ListItem(
                headlineContent = { Text("歌词景深") },
                supportingContent = { Text("非当前歌词使用透明度与模糊形成纵向景深") },
                leadingContent = { Icon(Icons.Default.BlurOn, null) },
                trailingContent = {
                    Switch(
                        checked = LyricsDisplaySettings.depthBlurEnabled,
                        onCheckedChange = { LyricsDisplaySettings.setDepthBlur(context, it) }
                    )
                },
                modifier = Modifier.clickable {
                    LyricsDisplaySettings.setDepthBlur(context, !LyricsDisplaySettings.depthBlurEnabled)
                }
            )
            ListItem(
                headlineContent = { Text("歌词字号") },
                supportingContent = {
                    Text(
                        when {
                            LyricsDisplaySettings.fontScale < 0.98f -> "紧凑"
                            LyricsDisplaySettings.fontScale > 1.08f -> "大号"
                            else -> "标准"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.FormatSize, null) },
                modifier = Modifier.clickable { LyricsDisplaySettings.cycleFontScale(context) }
            )
            Slider(
                value = LyricsDisplaySettings.fontScale,
                onValueChange = { LyricsDisplaySettings.setFontScale(context, it) },
                valueRange = 0.75f..1.5f,
                steps = 14,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )

            SettingsSectionHeader(appText("布局与样式", "Layout and style"))
            ChoiceRow(
                options = listOf("start" to appText("左对齐", "Left"), "center" to appText("居中", "Center"), "end" to appText("右对齐", "Right")),
                selected = LyricsDisplaySettings.alignment,
                onSelected = { LyricsDisplaySettings.setAlignment(context, it) }
            )
            ChoiceRow(
                options = listOf("compact" to appText("紧凑", "Compact"), "melox" to "MeloX", "immersive" to appText("沉浸", "Immersive"), "chat" to appText("微信对话", "Chat bubbles")),
                selected = LyricsDisplaySettings.style,
                onSelected = { LyricsDisplaySettings.setStyle(context, it) }
            )
            ChoiceRow(
                options = listOf("top_down" to appText("从上到下", "Top down"), "bottom_up" to appText("从下到上", "Bottom up")),
                selected = LyricsDisplaySettings.direction,
                onSelected = { LyricsDisplaySettings.setDirection(context, it) }
            )
            ChoiceRow(
                options = listOf("smooth" to appText("平滑滚动", "Smooth"), "instant" to appText("立即定位", "Instant")),
                selected = LyricsDisplaySettings.scrollEffect,
                onSelected = { LyricsDisplaySettings.setScrollEffect(context, it) }
            )

            if (LyricsDisplaySettings.style == "chat") {
                SettingsSectionHeader(appText("微信对话", "Chat bubbles"))
                ListItem(
                    headlineContent = { Text(appText("左右分配", "Bubble sides")) },
                    supportingContent = { Text(appText("左右各一句歌词，逐行交替；或译文在左、原文在右", "Alternate one line per side, or translations left with originals right")) },
                    leadingContent = { Icon(Icons.Default.Chat, null) }
                )
                ChoiceRow(
                    options = listOf(
                        "alternate" to appText("逐行交替", "Alternate"),
                        "bilingual" to appText("译文/原文", "Translation / original")
                    ),
                    selected = LyricsDisplaySettings.chatLayout,
                    onSelected = { LyricsDisplaySettings.setChatLayout(context, it) }
                )
                ListItem(
                    headlineContent = { Text(appText("显示头像", "Show avatars")) },
                    supportingContent = { Text(appText("对方气泡用专辑封面，本机气泡用默认头像", "Use album artwork for incoming bubbles and a default avatar for outgoing ones")) },
                    leadingContent = { Icon(Icons.Default.Person, null) },
                    trailingContent = {
                        Switch(
                            checked = LyricsDisplaySettings.chatAvatarsEnabled,
                            onCheckedChange = { LyricsDisplaySettings.setChatAvatarsEnabled(context, it) }
                        )
                    },
                    modifier = Modifier.clickable {
                        LyricsDisplaySettings.setChatAvatarsEnabled(context, !LyricsDisplaySettings.chatAvatarsEnabled)
                    }
                )
                ListItem(
                    headlineContent = { Text(appText("显示时间戳", "Show timestamps")) },
                    supportingContent = { Text(appText("两条歌词间隔超过 20 秒时插入时间分隔", "Insert a time separator when lines are more than 20 seconds apart")) },
                    leadingContent = { Icon(Icons.Default.Schedule, null) },
                    trailingContent = {
                        Switch(
                            checked = LyricsDisplaySettings.chatTimestampsEnabled,
                            onCheckedChange = { LyricsDisplaySettings.setChatTimestampsEnabled(context, it) }
                        )
                    },
                    modifier = Modifier.clickable {
                        LyricsDisplaySettings.setChatTimestampsEnabled(context, !LyricsDisplaySettings.chatTimestampsEnabled)
                    }
                )
            }

            SettingsSectionHeader(appText("颜色", "Colors"))
            ListItem(headlineContent = { Text(appText("当前歌词", "Current lyric")) })
            LyricsColorRow(
                colors = listOf(0xFFFFFFFF.toInt(), 0xFFFFD54F.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt(), 0xFFF48FB1.toInt()),
                selected = LyricsDisplaySettings.currentColorArgb,
                onSelected = { LyricsDisplaySettings.setCurrentColor(context, it) }
            )
            ListItem(headlineContent = { Text(appText("非当前歌词", "Inactive lyrics")) })
            LyricsColorRow(
                colors = listOf(0x99FFFFFF.toInt(), 0x80FFFFFF.toInt(), 0x66B0BEC5.toInt(), 0x6690A4AE.toInt(), 0x6678909C.toInt()),
                selected = LyricsDisplaySettings.inactiveColorArgb,
                onSelected = { LyricsDisplaySettings.setInactiveColor(context, it) }
            )

            SettingsSectionHeader(appText("同步与动画", "Sync and animation"))
            ListItem(
                headlineContent = { Text(appText("自动跟随", "Auto follow")) },
                supportingContent = { Text(appText("切歌、拖动进度和重新进入页面后自动定位当前行", "Keep the active line centered after seek, song changes, and re-entry")) },
                trailingContent = { Switch(checked = LyricsDisplaySettings.autoFollow, onCheckedChange = { LyricsDisplaySettings.setAutoFollow(context, it) }) },
                modifier = Modifier.clickable { LyricsDisplaySettings.setAutoFollow(context, !LyricsDisplaySettings.autoFollow) }
            )
            ListItem(
                headlineContent = { Text(appText("行切换动画", "Line transition animation")) },
                leadingContent = { Icon(Icons.Default.Animation, null) },
                trailingContent = { Switch(checked = LyricsDisplaySettings.animationEnabled, onCheckedChange = { LyricsDisplaySettings.setAnimationEnabled(context, it) }) },
                modifier = Modifier.clickable { LyricsDisplaySettings.setAnimationEnabled(context, !LyricsDisplaySettings.animationEnabled) }
            )
            ListItem(headlineContent = { Text(appText("动画速度", "Animation speed")) }, supportingContent = { Text("${String.format("%.1f", LyricsDisplaySettings.animationSpeed)}x") })
            Slider(
                value = LyricsDisplaySettings.animationSpeed,
                onValueChange = { LyricsDisplaySettings.setAnimationSpeed(context, it) },
                valueRange = 0.6f..1.8f,
                steps = 5,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
            ListItem(
                headlineContent = { Text(appText("歌词时间偏移", "Lyrics timing offset")) },
                supportingContent = { Text("${LyricsDisplaySettings.offsetMs.toInt()} ms") }
            )
            Slider(
                value = LyricsDisplaySettings.offsetMs,
                onValueChange = { LyricsDisplaySettings.setOffset(context, it) },
                valueRange = -5_000f..5_000f,
                steps = 39,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
            Button(
                onClick = { LyricsDisplaySettings.reset(context) },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(20.dp)
            ) {
                Icon(Icons.Default.Restore, null)
                Text(appText("恢复歌词默认设置", "Restore lyric defaults"), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelected(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun LyricsColorRow(colors: List<Int>, selected: Int, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        colors.forEach { value ->
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(value))
                    .border(if (selected == value) 3.dp else 1.dp, Color.White.copy(alpha = if (selected == value) 1f else 0.35f), CircleShape)
                    .clickable { onSelected(value) }
            )
        }
    }
}
