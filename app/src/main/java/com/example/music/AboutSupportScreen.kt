package com.example.music

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dockContentPadding = LocalDockContentPadding.current
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = packageInfo.versionName ?: "1.0"

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于与支持") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = dockContentPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("WANmusic", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "版本 $versionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "本地音乐播放、实时歌词、桌面部件与车载媒体支持。",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item { HorizontalDivider() }
            item {
                SupportLinkItem(
                    title = "GitHub 项目主页",
                    subtitle = "查看源代码、项目说明与开发进度",
                    icon = Icons.Default.Code,
                    onClick = { openUrl(ProjectLinks.GITHUB_HOME) }
                )
            }
            item {
                SupportLinkItem(
                    title = "问题反馈",
                    subtitle = "提交故障、功能建议与设备兼容性问题",
                    icon = Icons.Default.BugReport,
                    onClick = { openUrl(ProjectLinks.GITHUB_ISSUES) }
                )
            }
            item {
                SupportLinkItem(
                    title = "版本发布",
                    subtitle = "查看更新记录并下载最新版本",
                    icon = Icons.Default.NewReleases,
                    onClick = { openUrl(ProjectLinks.GITHUB_RELEASES) }
                )
            }
            item {
                SupportLinkItem(
                    title = "开发者主页",
                    subtitle = "查看开发者的其他开源项目",
                    icon = Icons.Default.Person,
                    onClick = { openUrl(ProjectLinks.AUTHOR_GITHUB) }
                )
            }
            item {
                SupportLinkItem(
                    title = "分享项目",
                    subtitle = ProjectLinks.GITHUB_HOME,
                    icon = Icons.Default.Share,
                    trailingIcon = null,
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "WANmusic")
                            .putExtra(Intent.EXTRA_TEXT, "WANmusic\n${ProjectLinks.GITHUB_HOME}")
                        context.startActivity(Intent.createChooser(shareIntent, "分享 WANmusic"))
                    }
                )
            }
            item { HorizontalDivider(Modifier.padding(top = 4.dp)) }
            item {
                ListItem(
                    headlineContent = { Text("应用信息") },
                    supportingContent = { Text(context.packageName) },
                    leadingContent = { Icon(Icons.Default.Info, null) }
                )
            }
        }
    }
}

@Composable
private fun SupportLinkItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.OpenInNew,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = {
            trailingIcon?.let { Icon(it, null) }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
