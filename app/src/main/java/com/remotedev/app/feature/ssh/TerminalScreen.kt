package com.remotedev.app.feature.ssh

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val TerminalBackground = Color(0xFF0D1117)

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
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val shellActive by viewModel.shellActive.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webReady by remember { mutableStateOf(false) }

    // 隱形輸入代理:讓軟鍵盤直接對 shell 打字(無可見 textbox)
    // 欄位內容永遠保持單一哨兵字元 " ",游標固定在最後;
    // onValueChange 用 diff 推算「新增的字元 / 退格次數」再送進 shell。
    val inputFocusRequester = remember { FocusRequester() }
    var proxy by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }

    fun handleProxyChange(newValue: TextFieldValue) {
        val oldText = proxy.text
        val newText = newValue.text
        if (newText != oldText) {
            // 共同前綴長度
            var p = 0
            val maxP = minOf(oldText.length, newText.length)
            while (p < maxP && oldText[p] == newText[p]) p++
            // 刪除的部分 → 送 DEL
            val removed = oldText.length - p
            if (removed > 0) {
                viewModel.sendToShell("\u007F".repeat(removed))
            }
            // 新增的部分 → 直接送出(換行轉 Enter)
            val added = newText.substring(p)
            if (added.isNotEmpty()) {
                viewModel.sendToShell(added.replace("\n", "\r"))
            }
        }
        // 重置回哨兵狀態,讓下次輸入永遠是「新增」
        proxy = TextFieldValue(" ", TextRange(1))
    }

    // shell 就緒後自動聚焦隱形代理 → 進入頁面即可直接打字
    LaunchedEffect(connected, shellActive) {
        if (connected && shellActive) {
            kotlinx.coroutines.delay(300)
            runCatching { inputFocusRequester.requestFocus() }
        }
    }

    // Files 頁「在此開啟 Terminal」:進入本頁時 cd 到指定目錄
    LaunchedEffect(connected, shellActive) {
        if (connected && shellActive) {
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
            if (!connected || !shellActive) {
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
                    if (connected && !shellActive && !uiState.connecting) {
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
                            else if (connected) "重新連線"
                            else "連線",
                        )
                    }
                }
            } else {
                // xterm.js 終端(WebView)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            webReady = false
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                isFocusable = true
                                isFocusableInTouchMode = true
                                // 點終端 → 隱形代理取焦點並喚起鍵盤,直接在 shell 打字
                                setOnTouchListener { _, _ ->
                                    runCatching { inputFocusRequester.requestFocus() }
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

                                    @JavascriptInterface
                                    fun onResize(cols: Int, rows: Int) {
                                        viewModel.resizeShell(cols, rows)
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
                    // 隱形輸入代理(1dp 透明),持有鍵盤焦點
                    BasicTextField(
                        value = proxy,
                        onValueChange = { handleProxyChange(it) },
                        modifier = Modifier
                            .size(1.dp)
                            .alpha(0f)
                            .focusRequester(inputFocusRequester),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrect = false,
                        ),
                    )
                }

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
            }
        }
    }
}
