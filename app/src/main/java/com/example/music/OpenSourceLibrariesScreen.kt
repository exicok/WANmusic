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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.LibraryBooks
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val libraryGroups = listOf(
    "第三方库" to ReferencedLibraries.thirdParty,
    "Jetpack / AndroidX" to ReferencedLibraries.jetpack,
    "构建工具链" to ReferencedLibraries.toolchain
)

private const val APACHE_2_0_TEXT = "Apache License 2.0"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLibrariesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dockContentPadding = LocalDockContentPadding.current
    val libraryCount = libraryGroups.sumOf { (_, libraries) -> libraries.size }

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
                title = { Text("引用库") },
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
            contentPadding = PaddingValues(bottom = 24.dp + dockContentPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "共引用 $libraryCount 个开源库",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "以下库构成本应用的功能基础，均以 $APACHE_2_0_TEXT 分发。" +
                            "点击任意条目可访问其项目主页查看完整许可文本。",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item { HorizontalDivider() }
            libraryGroups.forEach { (groupTitle, libraries) ->
                item { SettingsSectionHeader(groupTitle) }
                items(libraries, key = { it.artifact }) { library ->
                    LibraryRowItem(
                        library = library,
                        onClick = { openUrl(library.url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryRowItem(library: ReferencedLibrary, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(library.name) },
        supportingContent = {
            Column {
                Text(library.usage)
                Text(
                    "${library.artifact} · ${library.version} · ${library.license}",
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        leadingContent = { Icon(Icons.Default.LibraryBooks, null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
