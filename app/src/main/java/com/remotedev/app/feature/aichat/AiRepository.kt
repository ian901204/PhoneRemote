package com.remotedev.app.feature.aichat

import com.remotedev.app.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiRepository @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepo: SettingsRepository,
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun chatStream(messages: List<ChatMessage>): Flow<String> = flow {
        val settings = settingsRepo.settings.first()
        if (settings.aiApiKey.isBlank()) {
            emit("請先在設定中填入 API Key")
            return@flow
        }

        val url = settings.aiBaseUrl.trimEnd('/') + "/v1/chat/completions"

        val bodyJson = buildJsonObject {
            put("model", settings.aiModel)
            put("stream", true)
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.aiApiKey}")
            .header("Accept", "text/event-stream")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body?.string() }.getOrNull()
                emit("錯誤：HTTP ${response.code}" + (errBody?.take(200)?.let { "\n$it" } ?: ""))
                return@flow
            }
            val source = response.body?.source() ?: return@flow
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val deltaContent = runCatching {
                    json.parseToJsonElement(payload)
                        .jsonObject["choices"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("delta")?.jsonObject
                        // contentOrNull:JSON null 會回傳 null,而非字串 "null"
                        ?.get("content")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (!deltaContent.isNullOrEmpty()) emit(deltaContent)
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    // ---- Agent 模式:tool calling ----

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
    )

    data class CompletionResult(
        val content: String?,
        val toolCalls: List<ToolCall>,
        val error: String? = null,
    )

    /** Agent 模式可用的 SSH 工具定義(OpenAI tools 格式) */
    val sshTools = buildJsonArray {
        fun stringParam(name: String, desc: String) = buildJsonObject {
            put("type", "string"); put("description", desc)
        }
        fun tool(name: String, desc: String, props: kotlinx.serialization.json.JsonObject, required: List<String>) {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", name)
                    put("description", desc)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", props)
                        put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    })
                })
            })
        }
        tool(
            "ssh_exec", "在已連線的 SSH 伺服器上執行 shell 指令,回傳 stdout/stderr",
            buildJsonObject { put("command", stringParam("command", "要執行的 shell 指令")) },
            listOf("command"),
        )
        tool(
            "ssh_list_files", "列出 SSH 伺服器上指定目錄的檔案與子目錄",
            buildJsonObject { put("path", stringParam("path", "目錄路徑,例如 /home/user")) },
            listOf("path"),
        )
        tool(
            "ssh_read_file", "讀取 SSH 伺服器上指定檔案的文字內容",
            buildJsonObject { put("path", stringParam("path", "檔案完整路徑")) },
            listOf("path"),
        )
        tool(
            "ssh_write_file", "寫入(覆蓋)SSH 伺服器上指定檔案的文字內容",
            buildJsonObject {
                put("path", stringParam("path", "檔案完整路徑"))
                put("content", stringParam("content", "要寫入的完整內容"))
            },
            listOf("path", "content"),
        )
    }

    /**
     * 非串流的 chat completion(供 agent loop 使用),附 tools 定義。
     * 回傳助理訊息的 content 與 tool_calls。
     */
    suspend fun chatCompletion(
        messages: kotlinx.serialization.json.JsonArray,
        includeTools: Boolean,
    ): CompletionResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val settings = settingsRepo.settings.first()
        if (settings.aiApiKey.isBlank()) {
            return@withContext CompletionResult(null, emptyList(), "請先在設定中填入 API Key")
        }
        val url = settings.aiBaseUrl.trimEnd('/') + "/v1/chat/completions"

        val bodyJson = buildJsonObject {
            put("model", settings.aiModel)
            put("stream", false)
            put("messages", messages)
            if (includeTools) {
                put("tools", sshTools)
                put("tool_choice", "auto")
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.aiApiKey}")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext CompletionResult(
                        null, emptyList(),
                        "HTTP ${response.code}: ${body.take(300)}",
                    )
                }
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@withContext CompletionResult(null, emptyList(), "回應解析失敗")
                val msg = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?: return@withContext CompletionResult(null, emptyList(), "回應缺少 message")
                val content = msg["content"]?.jsonPrimitive?.contentOrNull
                val calls = msg["tool_calls"]?.jsonArray?.mapNotNull { el ->
                    runCatching {
                        val o = el.jsonObject
                        val fn = o["function"]!!.jsonObject
                        ToolCall(
                            id = o["id"]!!.jsonPrimitive.content,
                            name = fn["name"]!!.jsonPrimitive.content,
                            argumentsJson = fn["arguments"]!!.jsonPrimitive.content,
                        )
                    }.getOrNull()
                }.orEmpty()
                CompletionResult(content, calls)
            }
        } catch (e: Exception) {
            CompletionResult(null, emptyList(), e.message ?: e.toString())
        }
    }
}
