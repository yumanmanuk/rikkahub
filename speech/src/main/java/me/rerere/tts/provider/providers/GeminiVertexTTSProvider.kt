package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSHttpException
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiVertexTTSProvider"

class GeminiVertexTTSProvider : TTSProvider<TTSProviderSetting.GeminiVertex> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    // Service Account token 缓存（Provider 实例级别）
    private val tokenProvider by lazy { ServiceAccountTokenProvider(httpClient) }

    @Serializable
    private data class GeminiTTSResponse(
        val candidates: List<Candidate> = emptyList()
    )

    @Serializable
    private data class Candidate(
        val content: Content = Content()
    )

    @Serializable
    private data class Content(
        val parts: List<Part> = emptyList()
    )

    @Serializable
    private data class Part(
        val inlineData: InlineData? = null
    )

    @Serializable
    private data class InlineData(
        val data: String,
        val mimeType: String
    )

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.GeminiVertex,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        // 获取 Vertex AI Bearer Token
        val token = tokenProvider.fetchAccessToken(
            serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
            privateKeyPem = providerSetting.privateKey.trim(),
            scopes = listOf("https://www.googleapis.com/auth/cloud-platform")
        )

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    // Gemini API 要求 contents 中每个 content 必须指定 role
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", request.text)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    put("AUDIO")
                })
                // Gemini TTS speechConfig: languageCode 不是有效的顶层字段，模型会自动检测语言
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", providerSetting.voiceName)
                        })
                    })
                })
            })
        }

        val endpoint = providerSetting.buildEndpoint()
        Log.i(TAG, "generateSpeech: endpoint=$endpoint, voice=${providerSetting.voiceName}")

        val httpRequest = Request.Builder()
            .url(endpoint)
            // Vertex AI 用 Bearer Token，不用 x-goog-api-key
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                // 将完整错误 body 单独打印，方便在 Logcat 过滤 GeminiVertexTTSProvider 查看
                Log.e(TAG, "HTTP ${response.code} ${response.message}")
                Log.e(TAG, "Error body: $body")
                throw TTSHttpException(
                    httpCode = response.code,
                    retryAfterSec = response.header("Retry-After")?.toIntOrNull(),
                    providerName = "gemini_vertex",
                    errorBody = body,
                    message = "Gemini Vertex TTS request failed: ${response.code} ${response.message} $body"
                )
            }

            val geminiResponse = json.decodeFromString<GeminiTTSResponse>(body)
            val inlineData = geminiResponse.candidates
                .firstOrNull()
                ?.content
                ?.parts
                ?.firstOrNull()
                ?.inlineData
                ?: throw Exception("No audio data returned from Gemini Vertex TTS")

            val audioData = Base64.decode(inlineData.data, Base64.DEFAULT)

            emit(
                AudioChunk(
                    data = audioData,
                    format = AudioFormat.PCM,
                    // Gemini TTS 返回 24kHz 16-bit mono PCM
                    sampleRate = 24000,
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "gemini_vertex",
                        "model" to providerSetting.model,
                        "voice" to providerSetting.voiceName,
                        "sampleRate" to "24000",
                        "channels" to "1",
                        "bitDepth" to "16"
                    )
                )
            )
        }
    }
}
