package com.remotedev.app.feature.ssh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun FileBrowserScreen(
    onFileOpen: (String) -> Unit,
    onOpenTerminal: (String) -> Unit = {},
    viewModel: SshViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()

    if (!uiState.connected) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("尚未連線，請先到 Terminal 頁連線。")
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.navigateUp() }) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "上一層")
            }
            Text(
                text = currentPath,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                maxLines = 1,
            )
            // 在目前目錄開啟 Terminal
            IconButton(onClick = {
                viewModel.setPendingTerminalPath(currentPath)
                onOpenTerminal(currentPath)
            }) {
                Icon(Icons.Filled.Terminal, contentDescription = "在此開啟 Terminal")
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(files, key = { it.path }) { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (file.isDirectory) {
                                viewModel.loadDir(file.path)
                            } else {
                                onFileOpen(file.path)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (file.isDirectory) {
                            Icons.Filled.Folder
                        } else {
                            Icons.AutoMirrored.Filled.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = if (file.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = file.name,
                        modifier = Modifier.padding(start = 12.dp).weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // 資料夾可直接在此開啟 Terminal(cd 到該目錄)
                    if (file.isDirectory) {
                        IconButton(onClick = {
                            viewModel.setPendingTerminalPath(file.path)
                            onOpenTerminal(file.path)
                        }) {
                            Icon(
                                Icons.Filled.Terminal,
                                contentDescription = "在此開啟 Terminal",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
