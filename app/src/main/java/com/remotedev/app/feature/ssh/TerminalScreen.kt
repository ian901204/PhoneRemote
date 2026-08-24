package com.remotedev.app.feature.ssh

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val TerminalBackground = Color(0xFF000000)
private val TerminalGreen = Color(0xFF33FF66)

private val SPECIAL_KEYS = listOf(
    "ESC" to "ESC",
    "TAB" to "TAB",
    "Ctrl+C" to "CTRL_C",
    "Ctrl+D" to "CTRL_D",
    "Ctrl+Z" to "CTRL_Z",
    "↑" to "UP",
    "↓" to "DOWN",
    "←" to "LEFT",
    "→" to "RIGHT",
)

@Composable
fun TerminalScreen(viewModel: SshViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 輸出更新時捲到底部(限制保留行數避免記憶體膨脹)
    val lines = remember(uiState.output) {
        uiState.output.lines().let { if (it.size > 2000) it.takeLast(2000) else it }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = TerminalBackground) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            if (!uiState.connected) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (uiState.error != null) {
                        Text(
                            text = "錯誤: ${uiState.error}",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Button(
                        onClick = { viewModel.connectFromSettings() },
                        enabled = !uiState.connecting,
                    ) {
                        Text(if (uiState.connecting) "連線中..." else "連線")
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            color = TerminalGreen,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // 特殊按鍵列(Esc / Tab / Ctrl / 方向鍵)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SPECIAL_KEYS.forEach { (label, key) ->
                        OutlinedButton(onClick = { viewModel.sendSpecialKey(key) }) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("輸入指令") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            viewModel.sendCommand(command)
                            command = ""
                        }),
                    )
                    Button(
                        onClick = {
                            viewModel.sendCommand(command)
                            command = ""
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("送出")
                    }
                }
            }
        }
    }
}
