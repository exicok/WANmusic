package com.example.music

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsAndDevicesSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
    }
    var overlayEnabled by remember {
        mutableStateOf(OverlayLyricsService.isEnabled(context) || OverlayLyricsService.isRunning())
    }
    var carLyricsEnabled by remember {
        mutableStateOf(preferences.getBoolean("car_lyrics_enabled", true))
    }
    var lyricsProviderEnabled by remember {
        mutableStateOf(preferences.getBoolean("lyrics_provider_enabled", true))
    }
    var canDrawOverlays by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    val hostActivity = context as? ComponentActivity

    DisposableEffect(hostActivity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = android.provider.Settings.canDrawOverlays(context)
                overlayEnabled = OverlayLyricsService.isEnabled(context) || OverlayLyricsService.isRunning()
                if (OverlayLyricsService.isEnabled(context) && canDrawOverlays) {
                    OverlayLyricsService.startIfEnabled(context)
                    overlayEnabled = true
                }
            }
        }
        hostActivity?.lifecycle?.addObserver(observer)
        onDispose { hostActivity?.lifecycle?.removeObserver(observer) }
    }

    fun setOverlayLyrics(enabled: Boolean) {
        if (enabled && !android.provider.Settings.canDrawOverlays(context)) {
            OverlayLyricsService.setEnabled(context, true)
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
            )
            overlayEnabled = false
            return
        }
        OverlayLyricsService.setEnabled(context, enabled)
        if (enabled) OverlayLyricsService.start(context) else OverlayLyricsService.stop(context)
        overlayEnabled = enabled
    }

    fun setLyricsProviderEnabled(enabled: Boolean) {
        lyricsProviderEnabled = enabled
        preferences.edit { putBoolean("lyrics_provider_enabled", enabled) }
        LyricsProviderNotifier.notifyChanged(context, force = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("歌词与外部设备") },
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
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSectionHeader("显示")
            ListItem(
                headlineContent = { Text("悬浮歌词") },
                supportingContent = {
                    Text(
                        if (canDrawOverlays) {
                            "在其他应用上方显示可拖动歌词和单字进度"
                        } else {
                            "需要显示在其他应用上层权限，开启时前往系统设置"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Subtitles, null) },
                trailingContent = {
                    Switch(checked = overlayEnabled, onCheckedChange = ::setOverlayLyrics)
                },
                modifier = Modifier.clickable { setOverlayLyrics(!overlayEnabled) }
            )

            SettingsSectionHeader("车载")
            ListItem(
                headlineContent = { Text("车载歌词") },
                supportingContent = {
                    Text("通过媒体元数据在 Android Auto、蓝牙车机和媒体卡片显示当前歌词")
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

            SettingsSectionHeader("外部应用")
            ListItem(
                headlineContent = { Text("歌词查询服务") },
                supportingContent = {
                    Text("允许歌词软件通过只读 ContentProvider 查询歌曲、播放位置、完整歌词和单字进度；不发送系统广播")
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

            SettingsSectionHeader("桌面")
            ListItem(
                headlineContent = { Text("桌面歌词部件") },
                supportingContent = {
                    Text("从系统桌面的小部件列表添加，可显示专辑图、歌词并控制播放")
                },
                leadingContent = { Icon(Icons.Default.Widgets, null) }
            )
        }
    }
}
