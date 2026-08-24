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
}
