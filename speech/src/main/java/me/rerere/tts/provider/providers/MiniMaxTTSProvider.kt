package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
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
import java.util.concurrent.TimeUnit

private const val TAG = "MiniMaxTTSProvider"

@Serializable
private data class MiniMaxResponseData(
    val audio: String? = null,
    val status: Int = 0,
    val ced: String? = null
)

@Serializable
private data class MiniMaxResponse(
    val data: MiniMaxResponseData? = null
)

class MiniMaxTTSProvider : TTSProvider<TTSProviderSetting.MiniMax> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiniMax,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            put("text", request.text)
            put("stream", providerSetting.stream)
            put("output_format", "hex")
            if (providerSetting.stream) {
                put("stream_options", buildJsonObject {
                    put("exclude_aggregated_audio", true)
                })
            }
            put("voice_setting", buildJsonObject {
                put("voice_id", providerSetting.voiceId)
                put("emotion", providerSetting.emotion)
                put("speed", providerSetting.speed)
            })
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/t2a_v2")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        if (providerSetting.stream) {
            emitStreaming(httpRequest, providerSetting)
        } else {
            emitSync(httpRequest, providerSetting)
        }
    }

    private suspend fun FlowCollector<AudioChunk>.emitStreaming(
        httpRequest: Request,
        providerSetting: TTSProviderSetting.MiniMax
    ) {
        var hasEmittedAudio = false

        httpClient.sseFlow(httpRequest).collect {
            when (it) {
                is SseEvent.Open -> Log.i(TAG, "SSE connection opened")
                is SseEvent.Event -> {
                    try {
                        val data = json.decodeFromString<MiniMaxResponse>(it.data)
                        val audioHex = data.data?.audio ?: return@collect

                        // Convert hex string to bytes
                        val audioBytes = hexStringToBytes(audioHex)

                        emit(
                            AudioChunk(
                                data = audioBytes,
                                format = AudioFormat.MP3, // MiniMax returns MP3 format
                                sampleRate = 32000, // Default sample rate from MiniMax
                                isLast = false, // Will be set to true on last chunk
                                metadata = mapOf(
                                    "provider" to "minimax",
                                    "model" to providerSetting.model,
                                    "voice" to providerSetting.voiceId,
                                    "status" to (data.data?.status?.toString() ?: ""),
                                    "ced" to (data.data?.ced ?: "")
                                )
                            )
                        )
                        hasEmittedAudio = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process audio chunk", e)
                    }
                }

                is SseEvent.Closed -> {
                    Log.i(TAG, "SSE connection closed")
                    // Emit final chunk if we haven't already
                    if (hasEmittedAudio) {
                        emit(
                            AudioChunk(
                                data = byteArrayOf(), // Empty data for last chunk
                                format = AudioFormat.MP3,
                                sampleRate = 32000,
                                isLast = true,
                                metadata = mapOf("provider" to "minimax")
                            )
                        )
                    }
                }

                is SseEvent.Failure -> {
                    Log.e(TAG, "SSE connection failed", it.throwable)
                    throw it.throwable ?: Exception("MiniMax TTS streaming failed")
                }
            }
        }
    }

    private suspend fun FlowCollector<AudioChunk>.emitSync(
        httpRequest: Request,
        providerSetting: TTSProviderSetting.MiniMax
    ) {
        val response = httpClient.newCall(httpRequest).execute()
        val errorBody = response.body?.string()

        if (!response.isSuccessful) {
            throw TTSHttpException(
                httpCode = response.code,
                retryAfterSec = response.header("Retry-After")?.toIntOrNull(),
                providerName = "MiniMax",
                errorBody = errorBody,
                message = "MiniMax TTS request failed: ${response.code} ${response.message}"
            )
        }

        val bodyString = errorBody
            ?: throw Exception("MiniMax TTS response body is empty")

        val parsedResponse = json.decodeFromString<MiniMaxResponse>(bodyString)
        val responseData = parsedResponse.data
        if (responseData == null) {
            val trimmedBody = bodyString.take(2000)
            Log.e(TAG, "MiniMax TTS response data is null, body: $trimmedBody")
            throw Exception("MiniMax TTS response data is null: $trimmedBody")
        }
        val audioHex = responseData.audio
        if (audioHex.isNullOrEmpty()) {
            val trimmedBody = bodyString.take(2000)
            Log.e(TAG, "MiniMax TTS response audio is null or empty, body: $trimmedBody")
            throw Exception("MiniMax TTS response audio is null or empty: $trimmedBody")
        }

        emit(
            AudioChunk(
                data = hexStringToBytes(audioHex),
                format = AudioFormat.MP3,
                sampleRate = 32000,
                isLast = true,
                metadata = mapOf(
                    "provider" to "minimax",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voiceId,
                    "status" to responseData.status.toString(),
                    "ced" to (responseData.ced ?: "")
                )
            )
        )
    }
}

private fun hexStringToBytes(hexString: String): ByteArray {
    val cleanHex = hexString.replace("\\s+".toRegex(), "")
    val length = cleanHex.length

    // Check for even number of characters
    if (length % 2 != 0) {
        throw IllegalArgumentException("Hex string must have even number of characters")
    }

    val bytes = ByteArray(length / 2)
    for (i in 0 until length step 2) {
        val hexByte = cleanHex.substring(i, i + 2)
        bytes[i / 2] = hexByte.toInt(16).toByte()
    }
    return bytes
}
