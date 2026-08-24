package com.remotedev.app.feature.ssh

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val TerminalBackground = Color(0xFF000000)

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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen(viewModel: SshViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webReady by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }

    // Files 頁「在此開啟 Terminal」:進入本頁時 cd 到指定目錄
    LaunchedEffect(uiState.connected, uiState.shellActive) {
        if (uiState.connected) {
            viewModel.consumePendingTerminalPath()?.let { path ->
                viewModel.sendCommand("cd \"$path\"")
            }
        }
    }

    // Shell 輸出 → xterm.js
    LaunchedEffect(webReady) {
        if (!webReady) return@LaunchedEffect
        val wv = webView ?: return@LaunchedEffect
        // 重放緩衝(進入頁面前已有的輸出)
        viewModel.getScrollback().forEach { b64 ->
            wv.evaluateJavascript("writeB64('$b64')", null)
        }
        viewModel.shellOutput.collect { b64 ->
            wv.evaluateJavascript("writeB64('$b64')", null)
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView = null }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = TerminalBackground) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!uiState.connected || !uiState.shellActive) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
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
                    if (uiState.connected && !uiState.shellActive && !uiState.connecting) {
                        Text(
                            text = "連線已中斷",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.disconnect()
                            viewModel.connectFromSettings()
                        },
                        enabled = !uiState.connecting,
                    ) {
                        Text(
                            if (uiState.connecting) "連線中..."
                            else if (uiState.connected) "重新連線"
                            else "連線",
                        )
                    }
                }
            } else {
                // xterm.js 終端(WebView)
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        webReady = false
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            isFocusable = true
                            isFocusableInTouchMode = true
                            // 點終端區域時喚起鍵盤(部分裝置 WebView 不會自動彈出)
                            setOnTouchListener { v, _ ->
                                v.requestFocus()
                                val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                    as android.view.inputmethod.InputMethodManager
                                imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                                false
                            }
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onInput(b64: String) {
                                    val bytes = android.util.Base64.decode(
                                        b64, android.util.Base64.NO_WRAP,
                                    )
                                    viewModel.sendToShell(String(bytes, Charsets.UTF_8))
                                }
                            }, "AndroidInput")
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    webReady = true
                                }
                            }
                            loadUrl("file:///android_asset/terminal.html")
                            webView = this
                        }
                    },
                )

                // 特殊按鍵列(Esc / Tab / Ctrl / 方向鍵)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SPECIAL_KEYS.forEach { (label, key) ->
                        OutlinedButton(onClick = { viewModel.sendSpecialKey(key) }) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // 指令輸入列(送出即進 shell)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("輸入指令(或點上方終端直接打字)") },
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
