package com.remotedev.app.feature.ssh

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 資料夾下載:先記住要下載的遠端路徑,再請使用者挑本機目錄
    var pendingDownloadPath by remember { mutableStateOf<String?>(null) }
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val remote = pendingDownloadPath
        if (uri != null && remote != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // 部分 ROM 不支援持久授權,不影響本次下載
            }
            viewModel.downloadFolder(remote, uri)
        }
        pendingDownloadPath = null
    }
    val startDownload: (String) -> Unit = { remote ->
        pendingDownloadPath = remote
        treePicker.launch(null)
    }

    // 下載完成時提示
    LaunchedEffect(downloadState?.done) {
        val st = downloadState
        if (st?.done == true) {
            Toast.makeText(
                context,
                st.error?.let { "下載失敗:$it" } ?: "下載完成,共 ${st.count} 個檔案",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

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
            // 下載目前目錄
            IconButton(onClick = { startDownload(currentPath) }) {
                Icon(Icons.Filled.Download, contentDescription = "下載此資料夾")
            }
            // 在目前目錄開啟 Terminal
            IconButton(onClick = {
                viewModel.setPendingTerminalPath(currentPath)
                onOpenTerminal(currentPath)
            }) {
                Icon(Icons.Filled.Terminal, contentDescription = "在此開啟 Terminal")
            }
        }

        // 下載進度條
        downloadState?.let { st ->
            if (!st.done) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "下載中:${st.folder}(已完成 ${st.count} 個檔案${if (st.currentFile.isNotEmpty()) ",正在:${st.currentFile}" else ""})",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        IconButton(onClick = { viewModel.dismissDownloadState() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "隱藏進度",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
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
                    // 資料夾可下載到本機
                    if (file.isDirectory) {
                        IconButton(onClick = { startDownload(file.path) }) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = "下載此資料夾",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
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
