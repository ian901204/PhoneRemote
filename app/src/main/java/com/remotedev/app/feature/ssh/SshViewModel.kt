package com.remotedev.app.feature.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotedev.app.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                    it.copy(
                        connected = true,
                        connecting = false,
                        error = null,
                        output = it.output + "已連線 ${settings.sshUser}@${settings.sshHost}\n",
                    )
                }
                loadDir(_currentPath.value)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(connected = false, connecting = false, error = e.message ?: e.toString())
                }
            }
        }
    }

    fun disconnect() {
        ssh.disconnect()
        _uiState.update { it.copy(connected = false) }
        _files.value = emptyList()
    }

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
}
