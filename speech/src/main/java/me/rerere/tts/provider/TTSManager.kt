package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.providers.ElevenLabsTTSProvider
import me.rerere.tts.provider.providers.FishAudioTTSProvider
import me.rerere.tts.provider.providers.GeminiTTSProvider
import me.rerere.tts.provider.providers.GroqTTSProvider
import me.rerere.tts.provider.providers.MiMoTTSProvider
import me.rerere.tts.provider.providers.MiniMaxTTSProvider
import me.rerere.tts.provider.providers.OpenAITTSProvider
import me.rerere.tts.provider.providers.QwenTTSProvider
import me.rerere.tts.provider.providers.StepTTSProvider
import me.rerere.tts.provider.providers.SystemTTSProvider
import me.rerere.tts.provider.providers.XAITTSProvider
// [FORK] GeminiVertex / VertexCloud TTS providers
import me.rerere.tts.provider.providers.GeminiVertexTTSProvider
import me.rerere.tts.provider.providers.VertexCloudTTSProvider
import kotlin.reflect.KClass

class TTSManager(private val context: Context) {

    /**
     * Provider 注册表：将 TTSProviderSetting 的具体类型映射到对应的 TTSProvider 实例。
     *
     * 新增 provider 时只需在此 Map 追加一行即可，无需修改多处 when 表达式，
     * 从而减少 upstream merge 冲突的概率。
     *
     * upstream 新增的 provider 通常追加在列表末尾；
     * [FORK] 自定义 provider 统一放在下方独立分组，便于识别与保留。
     */
    @Suppress("UNCHECKED_CAST")
    private val registry: Map<KClass<out TTSProviderSetting>, TTSProvider<TTSProviderSetting>> = mapOf(
        // ---- upstream providers ----
        TTSProviderSetting.OpenAI::class      to OpenAITTSProvider()      as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.Gemini::class      to GeminiTTSProvider()      as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.SystemTTS::class   to SystemTTSProvider()      as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.MiniMax::class     to MiniMaxTTSProvider()     as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.Qwen::class        to QwenTTSProvider()        as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.Groq::class        to GroqTTSProvider()        as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.XAI::class         to XAITTSProvider()         as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.MiMo::class        to MiMoTTSProvider()        as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.ElevenLabs::class  to ElevenLabsTTSProvider()  as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.FishAudio::class   to FishAudioTTSProvider()   as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.Step::class        to StepTTSProvider()        as TTSProvider<TTSProviderSetting>,
        // ---- [FORK] 自定义 providers ----
        TTSProviderSetting.GeminiVertex::class to GeminiVertexTTSProvider() as TTSProvider<TTSProviderSetting>,
        TTSProviderSetting.VertexCloud::class  to VertexCloudTTSProvider()  as TTSProvider<TTSProviderSetting>,
    )

    private fun providerFor(setting: TTSProviderSetting): TTSProvider<TTSProviderSetting> {
        return registry[setting::class]
            ?: error("No TTSProvider registered for ${setting::class.simpleName}")
    }

    fun generateSpeech(
        providerSetting: TTSProviderSetting,
        request: TTSRequest
    ): Flow<AudioChunk> {
        return providerFor(providerSetting).generateSpeech(context, providerSetting, request)
    }

    /**
     * 返回该 provider 硬编码的语气标记引导提示词（默认空）。
     * 供 text_to_speech 工具注入 system prompt 使用。
     */
    fun getPromptGuidance(providerSetting: TTSProviderSetting): String {
        return providerFor(providerSetting).promptGuidance
    }
}
