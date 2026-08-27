package com.remotedev.app.feature.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotedev.app.feature.ssh.SshConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repo: AiRepository,
    private val ssh: SshConnectionManager,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** Agent 模式:AI 可呼叫 SSH 工具自主完成任務 */
    private val _agentMode = MutableStateFlow(true)
    val agentMode: StateFlow<Boolean> = _agentMode.asStateFlow()

    fun toggleAgentMode() {
        _agentMode.value = !_agentMode.value
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return

        if (_agentMode.value && ssh.isConnected()) {
            sendAgent(trimmed)
        } else {
            sendPlain(trimmed)
        }
    }

    // ---- 一般串流聊天 ----

    private fun sendPlain(trimmed: String) {
        val conversation = _messages.value + ChatMessage(role = "user", content = trimmed)
        _messages.value = conversation + ChatMessage(role = "assistant", content = "")
        _isGenerating.value = true

        viewModelScope.launch {
            repo.chatStream(conversation)
                .catch { e -> appendToLastAssistant("錯誤：${e.message ?: e.toString()}") }
                .onCompletion { _isGenerating.value = false }
                .collect { token -> appendToLastAssistant(token) }
        }
    }

    // ---- Agent 模式:tool calling 循環 ----

    private fun sendAgent(trimmed: String) {
        _messages.value = _messages.value + ChatMessage(role = "user", content = trimmed)
        _isGenerating.value = true

        viewModelScope.launch {
            try {
                // API 訊息歷史:只取 user/assistant(忽略 UI 用的 tool 展示訊息)
                val history = _messages.value
                    .filter { it.role == "user" || it.role == "assistant" }
                    .map { m ->
                        buildJsonObject {
                            put("role", m.role)
                            put("content", m.content)
                        }
                    }

                val apiMessages = (mutableListOf(
                    buildJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            "你是一個 SSH 遠端開發助手,已連上使用者的 Linux 伺服器。" +
                                "你可以使用工具執行 shell 指令、瀏覽/讀取/寫入檔案。" +
                                "執行破壞性操作(rm、覆寫重要檔案)前先向使用者確認。" +
                                "回答使用繁體中文,簡潔扼要。",
                        )
                    },
                ) + history).toMutableList()

                val maxRounds = 12
                var finalText: String? = null

                for (round in 1..maxRounds) {
                    val result = repo.chatCompletion(
                        buildJsonArray { apiMessages.forEach { add(it) } },
                        includeTools = true,
                    )

                    if (result.error != null) {
                        finalText = "錯誤:${result.error}"
                        break
                    }

                    if (result.toolCalls.isEmpty()) {
                        finalText = result.content ?: "(無回應)"
                        break
                    }

                    // 把 assistant 的 tool_calls 訊息放入歷史
                    apiMessages.add(buildJsonObject {
                        put("role", "assistant")
                        result.content?.let { put("content", it) }
                        put("tool_calls", buildJsonArray {
                            result.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", tc.argumentsJson)
                                    })
                                })
                            }
                        })
                    })

                    // 逐一執行工具並回報結果
                    for (tc in result.toolCalls) {
                        addUiToolMessage("🔧 呼叫工具 ${tc.name}(${summarizeArgs(tc.argumentsJson)})")
                        val output = executeTool(tc)
                        // UI 展示(截斷避免洗版)
                        addUiToolMessage(
                            "↳ 結果:${output.take(800)}" + if (output.length > 800) "…(截斷)" else "",
                        )
                        apiMessages.add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", output.take(6000))
                        })
                    }
                }

                if (finalText == null) {
                    finalText = "(已達工具呼叫次數上限,請縮小任務範圍再試)"
                }
                _messages.value = _messages.value + ChatMessage("assistant", finalText)
            } catch (e: Exception) {
                _messages.value = _messages.value +
                    ChatMessage("assistant", "錯誤:${e.message ?: e}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun addUiToolMessage(text: String) {
        _messages.value = _messages.value + ChatMessage(role = "tool", content = text)
    }

    private fun summarizeArgs(argumentsJson: String): String {
        return runCatching {
            val o = json.parseToJsonElement(argumentsJson).jsonObject
            o.entries.joinToString(", ") { (k, v) ->
                val value = v.jsonPrimitive.contentOrNull ?: v.toString()
                "$k=${value.take(60)}"
            }
        }.getOrDefault(argumentsJson.take(80))
    }

    /** 實際執行工具(透過 SSH) */
    private suspend fun executeTool(tc: AiRepository.ToolCall): String {
        val args = runCatching { json.parseToJsonElement(tc.argumentsJson).jsonObject }.getOrNull()
            ?: return "參數解析失敗:${tc.argumentsJson}"
        fun arg(name: String) = args[name]?.jsonPrimitive?.contentOrNull
        return try {
            when (tc.name) {
                "ssh_exec" -> ssh.exec(arg("command") ?: return "缺少參數 command")
                "ssh_list_files" -> {
                    val files = ssh.listFiles(arg("path") ?: return "缺少參數 path")
                    files.joinToString("\n") {
                        (if (it.isDirectory) "[dir] " else "[file]") + " ${it.name} (${it.size}B)"
                    }.ifBlank { "(空目錄)" }
                }
                "ssh_read_file" -> ssh.readFile(arg("path") ?: return "缺少參數 path")
                "ssh_write_file" -> {
                    val p = arg("path") ?: return "缺少參數 path"
                    val c = arg("content") ?: return "缺少參數 content"
                    ssh.writeFile(p, c)
                    "已寫入 $p(${c.length} 字元)"
                }
                else -> "未知工具:${tc.name}"
            }
        } catch (e: Exception) {
            "工具執行失敗:${e.message ?: e}"
        }
    }

    private fun appendToLastAssistant(token: String) {
        val list = _messages.value
        if (list.isEmpty()) return
        val last = list.last()
        _messages.value = list.dropLast(1) + last.copy(content = last.content + token)
    }

    fun clear() {
        if (_isGenerating.value) return
        _messages.value = emptyList()
    }
}
