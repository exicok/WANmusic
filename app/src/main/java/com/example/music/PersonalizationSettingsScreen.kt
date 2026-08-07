package com.example.music

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material.icons.filled.Dock
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

object MusicLibraryStyleSettings {
    private const val PREFS = "music_prefs"
    private const val KEY_DECK_STYLE = "music_library_deck_style"

    var deckStyleEnabled by mutableStateOf(true)
        private set

    fun load(context: Context) {
        deckStyleEnabled = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DECK_STYLE, true)
    }

    fun setDeckStyleEnabled(context: Context, enabled: Boolean) {
        deckStyleEnabled = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_DECK_STYLE, enabled)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationSettingsScreen(
    dockPosition: String,
    onDockPositionChange: (String) -> Unit,
    playerLandscape: Boolean,
    onPlayerLandscapeChange: (Boolean) -> Unit,
    isAmoledMode: Boolean,
    onAmoledModeChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE) }
    remember(context) { AlbumDotMatrixSettings.load(context) }
    val dockContentPadding = LocalDockContentPadding.current
    var appLanguage by remember { mutableStateOf(AppLanguageSettings.load(context)) }
    var animationsEnabled by remember {
        mutableStateOf(preferences.getBoolean("app_animations_enabled", true))
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        animationsEnabled = enabled
        AppAnimationSettings.enabled = enabled
        preferences.edit { putBoolean("app_animations_enabled", enabled) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(appText("个性化设置", "Personalization")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, appText("返回", "Back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = dockContentPadding)
        ) {
            SettingsSectionHeader(appText("语言", "Language"))
            ListItem(
                headlineContent = { Text(appText("界面语言", "Interface language")) },
                supportingContent = { Text(appText("切换后自动重新载入界面", "The interface reloads after changing language")) },
                leadingContent = { Icon(Icons.Default.Language, null) }
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                AppLanguage.entries.forEachIndexed { index, language ->
                    SegmentedButton(
                        selected = appLanguage == language,
                        onClick = {
                            if (appLanguage != language) {
                                appLanguage = language
                                AppLanguageSettings.save(context, language)
                                (context as? android.app.Activity)?.recreate()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, AppLanguage.entries.size)
                    ) {
                        Text(when (language) {
                            AppLanguage.SYSTEM -> appText("系统", "System")
                            AppLanguage.SIMPLIFIED_CHINESE -> "简体中文"
                            AppLanguage.ENGLISH -> "English"
                        })
                    }
                }
            }

            SettingsSectionHeader(appText("Dock", "Dock"))
            ListItem(
                headlineContent = { Text(appText("Dock 位置", "Dock position")) },
                supportingContent = { Text(appText("选择顶部、底部或悬浮显示", "Choose top, bottom, or floating placement")) },
                leadingContent = { Icon(Icons.Default.Dock, null) }
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                listOf("top", "bottom", "float").forEachIndexed { index, position ->
                    SegmentedButton(
                        selected = dockPosition == position,
                        onClick = { onDockPositionChange(position) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3)
                    ) {
                        Text(when (position) {
                            "top" -> appText("顶部", "Top")
                            "float" -> appText("悬浮", "Floating")
                            else -> appText("底部", "Bottom")
                        })
                    }
                }
            }

            SettingsSectionHeader(appText("音乐库", "Music library"))
            ListItem(
                headlineContent = { Text(appText("卡片堆叠音乐库", "Stacked card library")) },
                supportingContent = {
                    Text(
                        appText(
                            "上下吸附切换卡片，向右滑动当前卡片播放",
                            "Snap between cards vertically and swipe the current card right to play"
                        )
                    )
                },
                leadingContent = { Icon(Icons.Default.LibraryMusic, null) },
                trailingContent = {
                    Switch(
                        checked = MusicLibraryStyleSettings.deckStyleEnabled,
                        onCheckedChange = { MusicLibraryStyleSettings.setDeckStyleEnabled(context, it) }
                    )
                },
                modifier = Modifier.clickable {
                    MusicLibraryStyleSettings.setDeckStyleEnabled(
                        context,
                        !MusicLibraryStyleSettings.deckStyleEnabled
                    )
                }
            )

            SettingsSectionHeader(appText("播放页", "Player"))
            ListItem(
                headlineContent = { Text(appText("播放页方向", "Player orientation")) },
                supportingContent = { Text(if (playerLandscape) appText("固定横屏", "Landscape") else appText("固定竖屏", "Portrait")) },
                leadingContent = { Icon(Icons.Default.ScreenRotation, null) }
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                SegmentedButton(
                    selected = !playerLandscape,
                    onClick = { onPlayerLandscapeChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    modifier = Modifier.weight(1f)
                ) { Text(appText("竖屏", "Portrait")) }
                SegmentedButton(
                    selected = playerLandscape,
                    onClick = { onPlayerLandscapeChange(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    modifier = Modifier.weight(1f)
                ) { Text(appText("横屏", "Landscape")) }
            }

            SettingsSectionHeader(appText("显示效果", "Display effects"))
            ListItem(
                headlineContent = { Text(appText("专辑图片点阵", "Album dot matrix")) },
                supportingContent = { Text(appText("使用专辑图片颜色重绘可调节点阵", "Redraw album artwork as an adjustable color dot matrix")) },
                leadingContent = { Icon(Icons.Default.GridOn, null) },
                trailingContent = {
                    Switch(
                        checked = AlbumDotMatrixSettings.enabled,
                        onCheckedChange = { AlbumDotMatrixSettings.setEnabled(context, it) }
                    )
                },
                modifier = Modifier.clickable {
                    AlbumDotMatrixSettings.setEnabled(context, !AlbumDotMatrixSettings.enabled)
                }
            )
            ListItem(
                headlineContent = { Text(appText("点阵密度", "Dot density")) },
                supportingContent = { Text(appText("每行 ${AlbumDotMatrixSettings.columns} 个点", "${AlbumDotMatrixSettings.columns} dots per row")) },
                leadingContent = { Icon(Icons.Default.GridOn, null) }
            )
            Slider(
                value = AlbumDotMatrixSettings.columns.toFloat(),
                onValueChange = { AlbumDotMatrixSettings.setColumns(context, it.toInt()) },
                valueRange = 12f..80f,
                steps = 67,
                enabled = AlbumDotMatrixSettings.enabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
            ListItem(
                headlineContent = { Text(appText("圆点大小", "Dot size")) },
                supportingContent = {
                    Text("${(AlbumDotMatrixSettings.dotScale * 100).toInt()}%")
                },
                leadingContent = { Icon(Icons.Default.FormatSize, null) }
            )
            Slider(
                value = AlbumDotMatrixSettings.dotScale,
                onValueChange = { AlbumDotMatrixSettings.setDotScale(context, it) },
                valueRange = 0.35f..1f,
                enabled = AlbumDotMatrixSettings.enabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            )
            ListItem(
                headlineContent = { Text(appText("3D 自动旋转", "3D auto rotation")) },
                supportingContent = { Text(appText("围绕纵轴旋转点阵专辑", "Rotate the dot matrix artwork around its vertical axis")) },
                leadingContent = { Icon(Icons.Default.ScreenRotation, null) },
                trailingContent = {
                    Switch(
                        checked = AlbumDotMatrixSettings.rotationEnabled,
                        onCheckedChange = { AlbumDotMatrixSettings.setRotationEnabled(context, it) }
                    )
                },
                modifier = Modifier.clickable {
                    AlbumDotMatrixSettings.setRotationEnabled(context, !AlbumDotMatrixSettings.rotationEnabled)
                }
            )
            ListItem(
                headlineContent = { Text(appText("旋转速度", "Rotation speed")) },
                supportingContent = { Text(String.format("%.2fx", AlbumDotMatrixSettings.rotationSpeed)) }
            )
            Slider(
                value = AlbumDotMatrixSettings.rotationSpeed,
                onValueChange = { AlbumDotMatrixSettings.setRotationSpeed(context, it) },
                valueRange = 0.1f..1.5f,
                enabled = AlbumDotMatrixSettings.enabled && AlbumDotMatrixSettings.rotationEnabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
            ListItem(
                headlineContent = { Text(appText("像素前后跳动", "Pixel depth visualizer")) },
                supportingContent = { Text(appText("随机圆点跟随音乐节奏沿景深方向跳动", "Move random dots through depth with the music rhythm")) },
                leadingContent = { Icon(Icons.Default.GraphicEq, null) },
                trailingContent = {
                    Switch(
                        checked = AlbumDotMatrixSettings.visualizerDepthEnabled,
                        onCheckedChange = { AlbumDotMatrixSettings.setVisualizerDepthEnabled(context, it) }
                    )
                },
                modifier = Modifier.clickable {
                    AlbumDotMatrixSettings.setVisualizerDepthEnabled(context, !AlbumDotMatrixSettings.visualizerDepthEnabled)
                }
            )
            ListItem(
                headlineContent = { Text(appText("景深幅度", "Depth amount")) },
                supportingContent = { Text("${(AlbumDotMatrixSettings.depth * 100).toInt()}%") }
            )
            Slider(
                value = AlbumDotMatrixSettings.depth,
                onValueChange = { AlbumDotMatrixSettings.setDepth(context, it) },
                valueRange = 0f..1f,
                enabled = AlbumDotMatrixSettings.enabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            )
            ListItem(
                headlineContent = { Text("AMOLED") },
                supportingContent = { Text(appText("深色模式使用纯黑背景", "Use a pure black background in dark mode")) },
                leadingContent = { Icon(Icons.Default.Brightness2, null) },
                trailingContent = { Switch(isAmoledMode, onAmoledModeChange) },
                modifier = Modifier.clickable { onAmoledModeChange(!isAmoledMode) }
            )
            ListItem(
                headlineContent = { Text(appText("动画效果", "Animations")) },
                supportingContent = { Text(appText("控制页面、封面和频谱动画", "Control screen, artwork, and spectrum animations")) },
                leadingContent = { Icon(Icons.Default.Speed, null) },
                trailingContent = { Switch(animationsEnabled, ::setAnimationsEnabled) },
                modifier = Modifier.clickable { setAnimationsEnabled(!animationsEnabled) }
            )
        }
    }
}
