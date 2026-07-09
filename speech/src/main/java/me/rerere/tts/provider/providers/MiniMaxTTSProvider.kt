package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "MiniMaxTTSProvider"

@Serializable
private data class MiniMaxResponseData(
    val audio: String? = null,
    val status: Int = 0,
    val ced: String? = null
)

@Serializable
private data class MiniMaxBaseResp(
    @SerialName("status_code") val statusCode: Int = 0,
    @SerialName("status_msg") val statusMsg: String = ""
)

@Serializable
private data class MiniMaxResponse(
    val data: MiniMaxResponseData? = null,
    @SerialName("base_resp") val baseResp: MiniMaxBaseResp? = null
)

/**
 * 将 MiniMax base_resp.status_code 转换为对应异常：
 * - 1002: 触发限流 → 映射为 429
 * - 1039: 触发 TPM 限流 → 映射为 429
 * - 1004: 鉴权失败 → 映射为 401
 * - 其他非 0: 业务错误 → 映射为 500
 */
private fun MiniMaxBaseResp.checkOrThrow(bodySnippet: String = "") {
    if (statusCode == 0) return
    val httpCode = when (statusCode) {
        1002, 1039 -> 429
        1004 -> 401
        else -> 500
    }
    throw TTSHttpException(
        httpCode = httpCode,
        retryAfterSec = null,
        providerName = "MiniMax",
        errorBody = bodySnippet,
        message = "MiniMax API error: status_code=$statusCode, msg=$statusMsg"
    )
}

class MiniMaxTTSProvider : TTSProvider<TTSProviderSetting.MiniMax> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun emptyFinalChunk() = AudioChunk(
        data = byteArrayOf(),
        format = AudioFormat.MP3,
        sampleRate = 32000,
        isLast = true,
        metadata = mapOf("provider" to "minimax")
    )

    private fun audioChunk(
        hex: String,
        status: Int,
        model: String,
        voiceId: String
    ) = AudioChunk(
        data = hexStringToBytes(hex),
        format = AudioFormat.MP3,
        sampleRate = 32000,
        isLast = false,
        metadata = mapOf(
            "provider" to "minimax",
            "model" to model,
            "voice" to voiceId,
            "status" to status.toString()
        )
    )

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
                    // 最后一个 chunk 不附带拼接后的完整音频，减少流量
                    put("exclude_aggregated_audio", true)
                })
            }
            put("voice_setting", buildJsonObject {
                put("voice_id", providerSetting.voiceId)
                put("speed", providerSetting.speed)
                put("vol", providerSetting.vol)
                put("pitch", providerSetting.pitch)
            })
            // 显式声明音频格式，与 API 文档推荐保持一致
            put("audio_setting", buildJsonObject {
                put("sample_rate", 32000)
                put("bitrate", 128000)
                put("format", "mp3")
                put("channel", 1)
            })
        }

        // 使用 ByteArray.toRequestBody() 显式声明 Content-Type，避免某些 OkHttp 版本
        // 在 String.toRequestBody() 上自动追加 ; charset=utf-8
        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/t2a_v2")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .post(
                json.encodeToString(requestBody)
                    .toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        if (providerSetting.stream) {
            emitStreaming(httpRequest, providerSetting)
        } else {
            emitSync(httpRequest, providerSetting)
        }
    }

    /**
     * 流式模式：直接用 OkHttp 同步调用 + BufferedSource 逐行读取，
     * 不依赖 EventSource / content-type 校验，**不依赖响应 Content-Type 必须为 text/event-stream**。
     *
     * 实测 MiniMax 流式响应：
     * - 正常情况：Content-Type: text/event-stream; charset=utf-8，body 为标准 SSE（每行 data: {json}）
     * - 错误情况：Content-Type: application/json;charset=utf-8，body 为 JSON 错误对象
     *
     * 本实现：
     * 1. 同步执行请求，拿到 Response
     * 2. 校验 HTTP 状态码（2xx 才算成功）
     * 3. 用 BufferedSource.readUtf8Line() 逐行读取
     * 4. 自动剥离 SSE "data: " 前缀，兼容纯 JSON 行（NDJSON 格式）
     * 5. 跳过空行（SSE 消息分隔符）
     * 6. 解析失败的行仅 log 不抛错（避免流中混入注释行导致中断）
     * 7. status=2 帧（即使 audio 为空）emit isLast=true 结束标记
     * 8. 流结束未收到 status=2 时兜底补发
     * 9. 完全没收到音频时抛错，避免外层无限等待
     */
    private suspend fun FlowCollector<AudioChunk>.emitStreaming(
        httpRequest: Request,
        providerSetting: TTSProviderSetting.MiniMax
    ) {
        var hasEmittedAudio = false
        var hasEmittedFinal = false
        var lineCount = 0

        try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    throw TTSHttpException(
                        httpCode = response.code,
                        retryAfterSec = response.header("Retry-After")?.toIntOrNull(),
                        providerName = "MiniMax",
                        errorBody = errorBody,
                        message = "MiniMax TTS streaming request failed: ${response.code} ${response.message}, body=$errorBody"
                    )
                }

                val source: BufferedSource = response.body?.source()
                    ?: throw IOException("MiniMax TTS streaming response body is null")

                // 逐行读取（readUtf8Line 自动处理 \r\n 和 \n 两种行尾）
                while (!source.exhausted()) {
                    val rawLine = source.readUtf8Line() ?: break
                    lineCount++
                    if (rawLine.isBlank()) continue

                    // 兼容 SSE (data: {...}) 和 NDJSON ({...}) 两种格式
                    val jsonLine = if (rawLine.startsWith("data:")) {
                        rawLine.removePrefix("data:").trim()
                    } else {
                        rawLine
                    }
                    if (jsonLine.isBlank()) continue

                    // SSE 注释行（如 ": heartbeat"）或 event 字段行跳过
                    if (jsonLine.startsWith(":") || jsonLine.startsWith("event:") ||
                        jsonLine.startsWith("id:") || jsonLine.startsWith("retry:")
                    ) {
                        continue
                    }

                    val frame = try {
                        json.decodeFromString<MiniMaxResponse>(jsonLine)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse line #$lineCount, skipping: ${jsonLine.take(200)}", e)
                        continue
                    }

                    // 业务错误码（限流等），抛 TTSHttpException 触发上层重试
                    frame.baseResp?.let { resp ->
                        if (resp.statusCode != 0) resp.checkOrThrow(jsonLine.take(500))
                    }

                    val audioHex = frame.data?.audio
                    val status = frame.data?.status ?: 0

                    // 有音频数据时 emit（status=2 + exclude_aggregated_audio=true 时 audio 为空）
                    if (!audioHex.isNullOrEmpty()) {
                        emit(audioChunk(audioHex, status, providerSetting.model, providerSetting.voiceId))
                        hasEmittedAudio = true
                    }

                    // status=2 表示合成结束，发送 isLast=true 结束标记
                    if (status == 2 && !hasEmittedFinal) {
                        hasEmittedFinal = true
                        emit(emptyFinalChunk())
                    }
                }

                Log.i(TAG, "MiniMax streaming finished: lines=$lineCount, hasEmittedAudio=$hasEmittedAudio, hasEmittedFinal=$hasEmittedFinal")
            }
        } catch (e: TTSHttpException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "MiniMax streaming failed after $lineCount lines", e)
            throw e
        }

        // 兜底：流结束时未通过 status=2 发送结束标记，根据是否收到过音频决定行为
        if (!hasEmittedFinal) {
            if (hasEmittedAudio) {
                hasEmittedFinal = true
                emit(emptyFinalChunk())
            } else {
                throw Exception("MiniMax TTS: no audio data received in streaming response (lines=$lineCount)")
            }
        }
    }

    /**
     * 非流式模式：MiniMax 返回单个 JSON 对象。
     * { "data": { "audio": "<hex>", "status": 2 }, "extra_info": {...}, "base_resp": { "status_code": 0 } }
     */
    private suspend fun FlowCollector<AudioChunk>.emitSync(
        httpRequest: Request,
        providerSetting: TTSProviderSetting.MiniMax
    ) {
        val response = httpClient.newCall(httpRequest).execute()
        val bodyString = response.body?.string()

        if (!response.isSuccessful) {
            throw TTSHttpException(
                httpCode = response.code,
                retryAfterSec = response.header("Retry-After")?.toIntOrNull(),
                providerName = "MiniMax",
                errorBody = bodyString,
                message = "MiniMax TTS request failed: ${response.code} ${response.message}"
            )
        }

        if (bodyString.isNullOrEmpty()) {
            throw Exception("MiniMax TTS response body is empty")
        }

        val parsedResponse = json.decodeFromString<MiniMaxResponse>(bodyString)

        // 检查业务错误码（HTTP 200 但 status_code != 0 的情况）
        parsedResponse.baseResp?.checkOrThrow(bodyString.take(500))

        val responseData = parsedResponse.data
        if (responseData == null) {
            Log.e(TAG, "MiniMax TTS response data is null, body: ${bodyString.take(2000)}")
            throw Exception("MiniMax TTS response data is null: ${bodyString.take(2000)}")
        }
        val audioHex = responseData.audio
        if (audioHex.isNullOrEmpty()) {
            Log.e(TAG, "MiniMax TTS response audio is null or empty, body: ${bodyString.take(2000)}")
            throw Exception("MiniMax TTS response audio is null or empty: ${bodyString.take(2000)}")
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
                    "status" to responseData.status.toString()
                )
            )
        )
    }
}

private fun hexStringToBytes(hexString: String): ByteArray {
    val cleanHex = hexString.replace("\\s+".toRegex(), "")
    val length = cleanHex.length

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
