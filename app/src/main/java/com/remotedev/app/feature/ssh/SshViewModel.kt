package com.remotedev.app.feature.ssh

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotedev.app.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SshUiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    val output: String = "",
    val shellActive: Boolean = false,
)

@HiltViewModel
class SshViewModel @Inject constructor(
    private val ssh: SshConnectionManager,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SshUiState())
    val uiState: StateFlow<SshUiState> = _uiState.asStateFlow()

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private var shellReaderJob: Job? = null

    /** Shell 原始輸出(base64 編碼的 UTF-8 bytes),供 xterm.js WebView 渲染 */
    private val _shellOutput = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val shellOutput: SharedFlow<String> = _shellOutput.asSharedFlow()

    /** 捲動緩衝:WebView 就緒前/重建後重放用 */
    private val scrollback = ArrayDeque<String>()
    private val maxScrollbackChunks = 500

    private fun emitShellBytes(bytes: ByteArray, length: Int) {
        val b64 = Base64.encodeToString(bytes, 0, length, Base64.NO_WRAP)
        scrollback.addLast(b64)
        while (scrollback.size > maxScrollbackChunks) scrollback.removeFirst()
        _shellOutput.tryEmit(b64)
    }

    fun emitShellText(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        emitShellBytes(b, b.size)
    }

    fun getScrollback(): List<String> = scrollback.toList()

    init {
        // 連線由 Singleton SshConnectionManager 持有;
        // 不同頁面(Terminal/Files)各有自己的 ViewModel,初始化時同步實際連線狀態
        if (ssh.isConnected()) {
            _uiState.update { it.copy(connected = true, shellActive = ssh.getShell() != null) }
            loadDir(_currentPath.value)
        }
    }

    fun connectFromSettings() {
        if (_uiState.value.connecting) return
        viewModelScope.launch {
            _uiState.update { it.copy(connecting = true, error = null) }
            try {
                val settings = settingsRepo.settings.first()
                ssh.connect(
                    host = settings.sshHost,
                    port = settings.sshPort,
                    user = settings.sshUser,
                    password = settings.sshPassword.ifBlank { null },
                    privateKey = settings.sshKey.ifBlank { null },
                    passphrase = settings.sshPassphrase.ifBlank { null },
                )
                _uiState.update {
                    it.copy(connected = true, connecting = false, error = null)
                }
                emitShellText("已連線 ${settings.sshUser}@${settings.sshHost}\n")
                loadDir(_currentPath.value)
                startInteractiveShell()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(connected = false, connecting = false, error = e.message ?: e.toString())
                }
            }
        }
    }

    /** 連線後開啟互動式 shell(PTY),持續讀取輸出 */
    fun startInteractiveShell() {
        if (_uiState.value.shellActive) return
        viewModelScope.launch {
            try {
                val shell = ssh.openShell()
                _uiState.update { it.copy(shellActive = true) }
                shellReaderJob = viewModelScope.launch(Dispatchers.IO) {
                    val buf = ByteArray(8192)
                    while (isActive) {
                        val n = try {
                            shell.output.read(buf)
                        } catch (e: Exception) {
                            -1
                        }
                        if (n <= 0) break
                        // 原始 bytes(含 ANSI 控制碼)直接交給 xterm.js 渲染
                        emitShellBytes(buf, n)
                    }
                    // shell 結束 = 連線已斷,標記斷線讓 UI 顯示重新連線
                    _uiState.update { it.copy(shellActive = false, connected = false) }
                    emitShellText("\n[連線已中斷,請點「重新連線」]\n")
                }
            } catch (e: Exception) {
                emitShellText("無法開啟互動 shell: ${e.message}\n")
            }
        }
    }

    /** 傳送文字到互動 shell(一般輸入會附加換行) */
    fun sendToShell(text: String) {
        viewModelScope.launch {
            try {
                ssh.sendToShell(text)
            } catch (e: Exception) {
                _uiState.update { it.copy(output = it.output + "傳送失敗: ${e.message}\n") }
            }
        }
    }

    /** 送出指令(附加 Enter) */
    fun sendCommand(cmd: String) {
        sendToShell(cmd + "\n")
    }

    /** 特殊按鍵 */
    fun sendSpecialKey(key: String) {
        val seq = when (key) {
            "CTRL_C" -> "\u0003"
            "CTRL_D" -> "\u0004"
            "CTRL_Z" -> "\u001A"
            "ESC" -> "\u001B"
            "TAB" -> "\t"
            "UP" -> "\u001B[A"
            "DOWN" -> "\u001B[B"
            "LEFT" -> "\u001B[D"
            "RIGHT" -> "\u001B[C"
            else -> return
        }
        sendToShell(seq)
    }

    /** Files 頁呼叫:設定 Terminal 要 cd 到的目錄 */
    fun setPendingTerminalPath(path: String) {
        ssh.pendingTerminalPath = path
    }

    /** Terminal 頁呼叫:取出待切換目錄(一次性) */
    fun consumePendingTerminalPath(): String? = ssh.consumePendingTerminalPath()

    fun disconnect() {
        shellReaderJob?.cancel()
        ssh.closeShell()
        ssh.disconnect()
        _uiState.update { it.copy(connected = false, shellActive = false) }
        _files.value = emptyList()
    }

    /** 保留:非互動單次指令執行(供未來擴充) */
    fun runCommand(cmd: String) {
        if (cmd.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(output = it.output + "$ $cmd\n") }
            try {
                val result = ssh.exec(cmd)
                _uiState.update { it.copy(output = it.output + result) }
            } catch (e: Exception) {
                _uiState.update { it.copy(output = it.output + "錯誤: ${e.message ?: e}\n") }
            }
        }
    }

    fun loadDir(path: String) {
        viewModelScope.launch {
            try {
                val list = ssh.listFiles(path)
                _currentPath.value = path
                _files.value = list
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current == "/" || current.isBlank()) return
        val parent = current.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }
        loadDir(parent)
    }

    override fun onCleared() {
        shellReaderJob?.cancel()
        super.onCleared()
    }
}
