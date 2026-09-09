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

internal fun decodeMiMoAudioData(responseBody: String): ByteArray? {
    val mimoResponse = runCatching { mimoJson.decodeFromString<MiMoResponse>(responseBody) }.getOrNull() ?: return null
    val base64Audio = mimoResponse.choices.firstOrNull()?.message?.audio?.data ?: return null
    return runCatching { Base64.getDecoder().decode(base64Audio) }.getOrNull()
}
class MiMoTTSProvider : TTSProvider<TTSProviderSetting.MiMo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // MiMo 支持在朗读文本中嵌入风格/音频标签控制语气与情感
    // 官方文档: https://xiaomimimo.com (音频标签控制)
    override val promptGuidance: String = """
        The active text-to-speech engine (MiMo) supports emotion and style control via embedded tags.
        When you call the text_to_speech tool, you MAY enrich the "text" argument with these tags to make the speech more expressive.
        Put tags ONLY inside the tool's text argument — never in your visible reply to the user.

        Two kinds of tags:
        1. Overall style tag — place ONE at the very beginning of the text: (style) . Combine multiple styles with spaces inside the same brackets, e.g. (开心 磁性) . Brackets may be () , （） or [] .
           Common styles: 开心/悲伤/愤怒/恐惧/惊讶/兴奋/委屈/平静/冷漠/怅然/欣慰/无奈/释然/温柔/高冷/活泼/严肃/慵懒/俏皮/深沉/磁性/醇厚/清亮/空灵/甜美/沙哑/御姐音/正太音/大叔音/台湾腔/东北话/四川话/河南话/粤语 . Custom styles are also allowed.
           For singing, the text MUST start with (唱歌) followed by lyrics (Chinese lyrics work best).
        2. Inline audio tags — insert [tag] anywhere to fine-tune delivery, e.g. [吸气] [深呼吸] [叹气] [笑] [轻笑] [大笑] [冷笑] [抽泣] [哽咽] [颤抖] [气声] [撒娇] [疲惫] [震惊] .

        IMPORTANT constraints (required by this app's text pipeline):
        - Do NOT put any punctuation (，。！？、：；…) INSIDE a tag's brackets. Separate multiple styles with spaces only, e.g. write (紧张 深呼吸) NOT (紧张，深呼吸).
        - Keep inline audio tags standalone like [笑]; do not immediately follow a [tag] with a (…) group.
        - Do not use markdown emphasis (*, _) — it will be stripped.
        - Use tags naturally and sparingly; don't over-annotate.

        Example text argument: (磁性)夜已经深了[叹气]城市还在呼吸。我是今晚陪你的人。
    """.trimIndent()

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
                put("speed", providerSetting.speed)
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

        val audioBytes = decodeMiMoAudioData(responseBody)
            ?: throw Exception("MiMo TTS: no audio data in response")

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

