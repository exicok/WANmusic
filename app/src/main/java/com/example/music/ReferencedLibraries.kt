package com.example.music

data class ReferencedLibrary(
    val name: String,
    val usage: String,
    val artifact: String,
    val version: String,
    val license: String,
    val url: String,
)

object ReferencedLibraries {
    val thirdParty = listOf(
        ReferencedLibrary(
            name = "Lyricon",
            usage = "歌词解析、桌面歌词与实时歌词联动",
            artifact = "io.github.proify.lyricon:provider",
            version = "0.1.70",
            license = "Apache-2.0",
            url = "https://github.com/proify/Lyricon"
        ),
        ReferencedLibrary(
            name = "Lyricon Lyric Model",
            usage = "富文本歌词行与逐词时间戳数据结构",
            artifact = "io.github.proify.lyricon.lyric:model",
            version = "0.1.70",
            license = "Apache-2.0",
            url = "https://github.com/proify/Lyricon"
        ),
        ReferencedLibrary(
            name = "Backdrop",
            usage = "悬浮 Dock 的模糊、透射与 Vibrancy 效果",
            artifact = "io.github.kyant0:backdrop",
            version = "1.0.6",
            license = "Apache-2.0",
            url = "https://github.com/Kyant0/AndroidLiquidGlass"
        ),
        ReferencedLibrary(
            name = "Shapes",
            usage = "形状路径过渡（Backdrop 的传递依赖）",
            artifact = "io.github.kyant0:shapes",
            version = "1.2.0",
            license = "Apache-2.0",
            url = "https://github.com/Kyant0/Shapes"
        ),
        ReferencedLibrary(
            name = "Xposed API",
            usage = "仅编译期引用，不打包进安装包",
            artifact = "de.robv.android.xposed:api",
            version = "82",
            license = "Apache-2.0",
            url = "https://api.xposed.info/"
        )
    )

    val jetpack = listOf(
        ReferencedLibrary(
            name = "Compose BOM",
            usage = "统一 Compose 各组件版本",
            artifact = "androidx.compose:compose-bom",
            version = "2025.01.01",
            license = "Apache-2.0",
            url = "https://developer.android.com/develop/ui/compose/bom/bom-mapping"
        ),
        ReferencedLibrary(
            name = "Compose UI",
            usage = "声明式界面渲染与图形层",
            artifact = "androidx.compose.ui:ui",
            version = "BOM 管理",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/compose-ui"
        ),
        ReferencedLibrary(
            name = "Compose Material 3",
            usage = "界面组件、主题与配色",
            artifact = "androidx.compose.material3:material3",
            version = "1.3.1",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/compose-material3"
        ),
        ReferencedLibrary(
            name = "Material Icons",
            usage = "设置页与播放器使用的图标集",
            artifact = "androidx.compose.material:material-icons-extended",
            version = "BOM 管理",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/compose-material"
        ),
        ReferencedLibrary(
            name = "Activity Compose",
            usage = "单 Activity 承载与返回处理",
            artifact = "androidx.activity:activity-compose",
            version = "1.10.0",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/activity"
        ),
        ReferencedLibrary(
            name = "Media3 ExoPlayer",
            usage = "音频与视频解码、播放管线",
            artifact = "androidx.media3:media3-exoplayer",
            version = "1.5.1",
            license = "Apache-2.0",
            url = "https://github.com/androidx/media"
        ),
        ReferencedLibrary(
            name = "Media3 Session",
            usage = "系统媒体会话、通知与车机控制",
            artifact = "androidx.media3:media3-session",
            version = "1.5.1",
            license = "Apache-2.0",
            url = "https://github.com/androidx/media"
        ),
        ReferencedLibrary(
            name = "DocumentFile",
            usage = "SAF 目录选择与音乐库扫描",
            artifact = "androidx.documentfile:documentfile",
            version = "1.0.1",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/documentfile"
        ),
        ReferencedLibrary(
            name = "Core KTX",
            usage = "Context、Intent 等常用扩展",
            artifact = "androidx.core:core-ktx",
            version = "1.15.0",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/core"
        ),
        ReferencedLibrary(
            name = "Lifecycle Runtime KTX",
            usage = "生命周期感知与协程作用域",
            artifact = "androidx.lifecycle:lifecycle-runtime-ktx",
            version = "2.8.7",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/lifecycle"
        )
    )

    val toolchain = listOf(
        ReferencedLibrary(
            name = "Kotlin",
            usage = "语言运行时与标准库",
            artifact = "org.jetbrains.kotlin:kotlin-stdlib",
            version = "2.3.20",
            license = "Apache-2.0",
            url = "https://github.com/JetBrains/kotlin"
        ),
        ReferencedLibrary(
            name = "Compose Compiler",
            usage = "将 @Composable 编译为 UI 代码",
            artifact = "org.jetbrains.kotlin.plugin.compose",
            version = "2.3.20",
            license = "Apache-2.0",
            url = "https://github.com/JetBrains/kotlin"
        ),
        ReferencedLibrary(
            name = "Android Gradle Plugin",
            usage = "构建与打包 Android 应用",
            artifact = "com.android.application",
            version = "8.9.2",
            license = "Apache-2.0",
            url = "https://developer.android.com/build/releases/gradle-plugin"
        )
    )
}
