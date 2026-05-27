package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

// MiMo V2.5 TTS 非流式返回 WAV 格式音频，通过 choices[0].message.audio.data 的 base64 获取
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

// 只解析非流式响应中需要的字段，其余忽略
private val mimoJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class MiMoResponse(
    val choices: List<MiMoResponseChoice> = emptyList()
)

@Serializable
private data class MiMoResponseChoice(
    val message: MiMoResponseMessage? = null
)

@Serializable
private data class MiMoResponseMessage(
    val audio: MiMoResponseAudio? = null
)

@Serializable
private data class MiMoResponseAudio(
    val data: String? = null
)

class MiMoTTSProvider : TTSProvider<TTSProviderSetting.MiMo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiMo,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        // V2.5 使用非流式调用：文本放在 assistant role，请求 wav 格式
        // 文档：https://platform.xiaomimimo.com/docs/zh-CN/usage-guide/speech-synthesis-v2.5
        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", request.text)
                })
            })
            put("audio", buildJsonObject {
                // 非流式使用 wav 格式，更稳定；音色由设置决定
                put("format", "wav")
                put("voice", providerSetting.voice)
            })
        }

        // baseUrl 允许用户在设置页自定义
        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/chat/completions")
            // MiMo 使用 api-key 头传 token（非 Bearer）
            .addHeader("api-key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw Exception("MiMo TTS request failed: ${response.code} ${response.message} $errorBody")
        }

        val responseBody = response.body?.string()
            ?: throw Exception("MiMo TTS response body is empty")

        val mimoResponse = mimoJson.decodeFromString<MiMoResponse>(responseBody)
        val base64Audio = mimoResponse.choices.firstOrNull()?.message?.audio?.data
            ?: throw Exception("MiMo TTS: no audio data in response")

        val audioBytes = Base64.getDecoder().decode(base64Audio)

        emit(
            AudioChunk(
                data = audioBytes,
                format = AudioFormat.WAV,
                isLast = true,
                metadata = mapOf(
                    "provider" to "mimo",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voice
                )
            )
        )
    }
}

