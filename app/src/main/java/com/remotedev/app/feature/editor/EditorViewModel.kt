package com.remotedev.app.feature.editor

import android.content.Context
import androidx.lifecycle.ViewModel
import com.remotedev.app.feature.ssh.SshConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject

private const val SSH_PREFIX = "ssh://"

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val ssh: SshConnectionManager,
    @ApplicationContext context: Context,
) : ViewModel() {

    // context 保留供未來本地檔案相對路徑解析使用
    private val appContext = context.applicationContext

    data class EditorUiState(
        val path: String? = null,
        val content: String = "",
        val isRemote: Boolean = false,
        val loading: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    suspend fun load(path: String) {
        _uiState.update { it.copy(loading = true, error = null, saved = false) }
        if (path.startsWith(SSH_PREFIX)) {
            val remotePath = path.removePrefix(SSH_PREFIX)
            if (!ssh.isConnected()) {
                _uiState.update {
                    it.copy(path = path, isRemote = true, loading = false, error = "尚未建立 SSH 連線")
                }
                return
            }
            try {
                val content = ssh.readFile(remotePath)
                _uiState.update {
                    it.copy(path = path, content = content, isRemote = true, loading = false, saved = false)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(path = path, isRemote = true, loading = false, error = e.message ?: "讀取遠端檔案失敗")
                }
            }
        } else {
            try {
                val content = File(path).readText()
                _uiState.update {
                    it.copy(path = path, content = content, isRemote = false, loading = false, saved = false)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(path = path, isRemote = false, loading = false, error = e.message ?: "讀取本機檔案失敗")
                }
            }
        }
    }

    fun onContentChange(text: String) {
        _uiState.update { it.copy(content = text, saved = false) }
    }

    suspend fun save() {
        val state = _uiState.value
        val path = state.path ?: return
        try {
            if (state.isRemote) {
                ssh.writeFile(path.removePrefix(SSH_PREFIX), state.content)
            } else {
                File(path).writeText(state.content)
            }
            _uiState.update { it.copy(saved = true, error = null) }
        } catch (e: Exception) {
            _uiState.update { it.copy(saved = false, error = e.message ?: "儲存失敗") }
        }
    }

    /** 依副檔名回傳 TextMate language scope。 */
    /** 對應內建 TextMate grammar 的 scope;不支援的副檔名回傳 null(純文字)。 */
    fun languageScopeFor(path: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "source.kotlin"
            "py" -> "source.python"
            "js", "jsx", "ts", "tsx" -> "source.js"
            "java" -> "source.java"
            "xml" -> "text.xml"
            "html", "htm" -> "text.html.basic"
            "md", "markdown" -> "text.html.markdown"
            else -> null
        }
    }
}
