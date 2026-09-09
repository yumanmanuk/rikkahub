package me.rerere.asr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class ASRProviderSetting {
    abstract val id: Uuid
    abstract val name: String

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
    ): ASRProviderSetting

    @Serializable
    @SerialName("openai_realtime")
    data class OpenAIRealtime(
        override val id: Uuid = Uuid.random(),
        override val name: String = "OpenAI Realtime ASR",
        val apiKey: String = "",
        val websocketUrl: String = "wss://api.openai.com/v1/realtime?intent=transcription",
        val model: String = "gpt-4o-transcribe",
        val language: String = "",
        val prompt: String = "",
        val sampleRate: Int = 24000,
        val vadThreshold: Float = 0.5f,
        val prefixPaddingMs: Int = 300,
        val silenceDurationMs: Int = 500,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("dashscope")
    data class DashScope(
        override val id: Uuid = Uuid.random(),
        override val name: String = "DashScope ASR",
        val apiKey: String = "",
        val websocketUrl: String = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
        val model: String = "qwen3-asr-flash-realtime",
        val language: String = "",
        val sampleRate: Int = 16000,
        val vadThreshold: Float = 0.0f,
        val silenceDurationMs: Int = 400,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("volcengine")
    data class Volcengine(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Volcengine ASR",
        val apiKey: String = "",
        val websocketUrl: String = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel",
        val resourceId: String = "volc.seedasr.sauc.duration",
        val language: String = "",
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    /**
     * 小米 MiMo ASR (mimo-v2.5-asr)。
     *
     * 与 OpenAIRealtime / DashScope / Volcengine 不同, MiMo ASR 是基于 OpenAI 兼容
     * chat/completions 的 HTTP 一次性识别接口, 不是 WebSocket 流式接口。
     * 客户端在录音期间按 [segmentDurationSec] 分段, 把每段 PCM 转成 WAV 后 base64
     * 内嵌到 messages[].content[].input_audio.data 字段, POST 到
     * {baseUrl}/chat/completions, 返回结果在 choices[0].message.content。
     *
     * 官方文档: https://platform.xiaomimimo.com/docs/zh-CN/api/audio/Speech-Recognition
     */
    @Serializable
    @SerialName("mimo")
    data class MiMo(
        override val id: Uuid = Uuid.random(),
        override val name: String = "MiMo ASR",
        val apiKey: String = "",
        val baseUrl: String = "https://api.xiaomimimo.com/v1",
        val model: String = "mimo-v2.5-asr",
        // auto | zh | en; 留空时不下发 asr_options, 服务端默认 auto
        val language: String = "auto",
        val sampleRate: Int = 16000,
        // 每多少秒自动 flush 一次当前缓冲区 (上传识别)。设为 0 表示禁用自动分段,
        // 仅在用户主动 stop() 时整体上传 (注意 MiMo 单次请求 raw 上限约 7.5MB,
        // 16kHz/16bit/mono 下约 234 秒)。
        val segmentDurationSec: Int = 30,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    /**
     * 阶跃星辰 Step ASR (stepaudio-2.5-asr)。
     *
     * 与 MiMo 类似也是 HTTP 一次性提交 + 分段上传, 但有两个差异:
     * 1) 鉴权用标准 `Authorization: Bearer` 而非 `api-key` 头
     * 2) 服务端以 SSE 流式返回 (text/event-stream), 事件类型包括
     *    transcript.text.delta / transcript.text.done / error
     * 3) 直接接受 PCM base64, 不需要 WAV 头封装
     *
     * 客户端按 [segmentDurationSec] 切段, 每段 PCM base64 后 POST 到
     * {baseUrl}/v1/audio/asr/sse, 流式接收 delta 拼到 partial transcript,
     * done 时把完整段文本 append 到 completedTranscripts。
     *
     * 官方文档: https://platform.stepfun.com/docs/zh/api-reference/audio/asr-sse
     */
    @Serializable
    @SerialName("step")
    data class Step(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Step ASR",
        val apiKey: String = "",
        val baseUrl: String = "https://api.stepfun.com",
        val model: String = "stepaudio-2.5-asr",
        // auto | zh | en; 留空时不下发 language 字段, 服务端默认 auto
        val language: String = "auto",
        val sampleRate: Int = 16000,
        // 每多少秒自动 flush 一次 (上传识别)。0 = 仅 stop() 时整体上传
        val segmentDurationSec: Int = 30,
        // 逆文本归一化 (Inverse Text Normalization): 把 "三百" -> "300" 等
        val enableItn: Boolean = true,
        // 是否返回词级时间戳 (rikkahub 当前不消费时间戳, 默认关闭节省 token)
        val enableTimestamp: Boolean = false,
        // 热词列表, 提升专有名词/术语识别准确率
        val hotwords: List<String> = emptyList(),
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    /**
     * Google Cloud Speech-to-Text v2 Chirp 3 ASR（gRPC 双向流式）。
     * 鉴权方式：Service Account JSON 中的 serviceAccountEmail + privateKey（PKCS8 PEM），
     * 在运行时换取 access token，通过 Vertex gRPC 端点发送请求。
     *
     * 注：denoiseAudio / snrThreshold 字段目前 SDK 暂不支持下发，预留供后续启用。
     */
    @Serializable
    @SerialName("chirp3")
    data class Chirp3(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Chirp 3 ASR",
        val serviceAccountEmail: String = "",
        val privateKey: String = "",
        val projectId: String = "",
        // chirp_3 仅部署在 us / eu 等多区域（见 CHIRP3_REGIONS），不支持 us-central1 / global
        val region: String = "us",
        val recognizerId: String = "",
        val language: String = "cmn-Hans-CN",
        val sampleRate: Int = 16000,
        val denoiseAudio: Boolean = false,
        val snrThreshold: Float = 3.0f,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    /**
     * 腾讯云 ASR（实时语音识别 WebSocket v2）。
     *
     * 与 OpenAI/DashScope 类似也是 WebSocket 流式接口，但鉴权使用腾讯云 HMAC-SHA1
     * 预签名 URL（SecretId/SecretKey 不出设备）。音频为 16-bit PCM 二进制帧，
     * 结束时发送 {"type":"end"} 控制帧。
     */
    @Serializable
    @SerialName("tencent")
    data class Tencent(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Tencent ASR",
        val secretId: String = "",
        val secretKey: String = "",
        val appId: String = "",
        val engineModelType: String = "16k_zh",
        val voiceFormat: Int = 1,
        val sampleRate: Int = 16000,
        val filterDirty: Int = 0,
        val filterModal: Int = 0,
        val filterPunc: Int = 0,
        val convertNumMode: Int = 1,
        val hotwordId: String = "",
        val customizationId: String = "",
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAIRealtime::class,
                DashScope::class,
                Volcengine::class,
                MiMo::class,
                Step::class,
                Chirp3::class,
                Tencent::class,
            )
        }
    }
}
