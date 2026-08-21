package com.remotedev.app.feature.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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

@Composable
fun TerminalScreen(viewModel: SshViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.output) {
        val lines = uiState.output.lines()
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
                    items(uiState.output.lines()) { line ->
                        Text(
                            text = line,
                            color = TerminalGreen,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                            viewModel.runCommand(command)
                            command = ""
                        }),
                    )
                    Button(
                        onClick = {
                            viewModel.runCommand(command)
                            command = ""
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("執行")
                    }
                }
            }
        }
    }
}
