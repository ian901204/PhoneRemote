package com.remotedev.app.feature.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotedev.app.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SshUiState(
    val connecting: Boolean = false,
    val error: String? = null,
    val output: String = "",
)

@HiltViewModel
class SshViewModel @Inject constructor(
    private val ssh: SshConnectionManager,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SshUiState())
    val uiState: StateFlow<SshUiState> = _uiState.asStateFlow()

    /** 連線 / shell 狀態直接來自 Singleton manager(所有頁面一致) */
    val connected: StateFlow<Boolean> = ssh.connected
    val shellActive: StateFlow<Boolean> = ssh.shellActive

    /** Shell 輸出(base64)由 manager 持有,頁面重建不遺失 */
    val shellOutput: SharedFlow<String> = ssh.shellOutput

    fun getScrollback(): List<String> = ssh.getScrollback()

    fun emitShellText(text: String) = ssh.emitShellText(text)

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    init {
        if (ssh.isConnected()) {
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
                _uiState.update { it.copy(connecting = false, error = null) }
                ssh.emitShellText("已連線 ${settings.sshUser}@${settings.sshHost}\n")
                loadDir(_currentPath.value)
                startInteractiveShell()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(connecting = false, error = e.message ?: e.toString())
                }
            }
        }
    }

    /** 連線後開啟互動式 shell(PTY);reader loop 由 manager 持有 */
    fun startInteractiveShell() {
        if (ssh.shellActive.value) return
        viewModelScope.launch {
            try {
                ssh.openShell()
            } catch (e: Exception) {
                ssh.emitShellText("無法開啟互動 shell: ${e.message}\n")
            }
        }
    }

    /** 傳送文字到互動 shell */
    fun sendToShell(text: String) = ssh.sendToShell(text)

    /** 送出指令(附加 Enter) */
    fun sendCommand(cmd: String) {
        sendToShell(cmd + "\n")
    }

    /** PTY 視窗大小改變 */
    fun resizeShell(cols: Int, rows: Int) = ssh.resizeShell(cols, rows)

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
        ssh.closeShell()
        ssh.disconnect()
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

    // ---- 資料夾下載 ----

    data class DownloadState(
        val folder: String,
        val currentFile: String = "",
        val count: Int = 0,
        /** 0 = 掃描中(不確定進度) */
        val total: Int = 0,
        val done: Boolean = false,
        val error: String? = null,
    )

    private val _downloadState = MutableStateFlow<DownloadState?>(null)
    val downloadState: StateFlow<DownloadState?> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null

    /** 遞迴下載遠端資料夾到本機 SAF tree URI(下載進行中會忽略新的請求) */
    fun downloadFolder(remotePath: String, treeUri: android.net.Uri) {
        val active = downloadJob?.isActive == true && _downloadState.value?.done != true
        if (active) return
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            _downloadState.value = DownloadState(folder = remotePath)
            try {
                val n = ssh.downloadFolder(
                    remotePath,
                    treeUri,
                    onScan = { scanned ->
                        _downloadState.update { it?.copy(count = scanned) }
                    },
                    onProgress = { file, doneCount, total ->
                        _downloadState.update {
                            it?.copy(currentFile = file, count = doneCount, total = total)
                        }
                    },
                )
                _downloadState.update { it?.copy(done = true, count = n, currentFile = "") }
            } catch (e: Exception) {
                _downloadState.update {
                    it?.copy(done = true, currentFile = "", error = e.message ?: e.toString())
                }
            }
        }
    }

    /** 關閉下載進度提示(下載仍會在背景完成) */
    fun dismissDownloadState() {
        _downloadState.value = null
    }
}
