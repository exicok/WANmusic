package com.example.music

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

object LyricsDisplaySettings {
    var wordProgressEnabled by mutableStateOf(true)
    var depthBlurEnabled by mutableStateOf(true)
    var fontScale by mutableFloatStateOf(1f)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        wordProgressEnabled = prefs.getBoolean("lyrics_word_progress", true)
        depthBlurEnabled = prefs.getBoolean("lyrics_depth_blur", true)
        fontScale = prefs.getFloat("lyrics_font_scale", 1f).coerceIn(0.9f, 1.18f)
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
        }
    }
}
