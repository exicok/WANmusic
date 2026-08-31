package com.example.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val LocalDockContentPadding = staticCompositionLocalOf { 0.dp }

@Composable
fun SettingsItem(label: String, icon: ImageVector, detail: String? = null, onClick: () -> Unit) {
    var expanded by remember(label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (expanded && !detail.isNullOrBlank()) {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = detail,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth()
        ) {
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = {
                    Icon(
                        icon,
                        null,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                },
                modifier = Modifier.clickable(onClick = onClick)
            )
        }
    }
}

@Composable
fun SettingsSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 16.dp,
            top = 18.dp,
            end = 16.dp,
            bottom = 6.dp
        )
    )
}
