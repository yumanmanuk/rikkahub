package me.rerere.tts.provider.providers

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.common.android.appTempFolder
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SystemTTSProvider"

/**
 * System TTS provider — 复用单个 TextToSpeech 引擎实例，
 * 避免每个 chunk 都新建/销毁引擎导致资源竞争和杂音。
 */
class SystemTTSProvider : TTSProvider<TTSProviderSetting.SystemTTS> {
    // 复用引擎：同一 Context 下只初始化一次
    private var ttsEngine: TextToSpeech? = null
    private var ttsReady = false
    private val mutex = Mutex() // 串行化合成请求

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.SystemTTS,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val audioData = mutex.withLock {
            // 确保引擎已初始化
            val engine = getOrCreateEngine(context)

            // 配置参数
            engine.setSpeechRate(providerSetting.speechRate)
            engine.setPitch(providerSetting.pitch)

            // 合成到临时文件
            synthesizeToBytes(context, engine, request.text)
        }

        emit(
            AudioChunk(
                data = audioData,
                format = me.rerere.tts.model.AudioFormat.WAV,
                isLast = true,
                metadata = mapOf(
                    "provider" to "system",
                    "speechRate" to providerSetting.speechRate.toString(),
                    "pitch" to providerSetting.pitch.toString()
                )
            )
        )
    }

    /**
     * 获取或创建 TTS 引擎（仅初始化一次）
     */
    private suspend fun getOrCreateEngine(context: Context): TextToSpeech {
        ttsEngine?.let { if (ttsReady) return it }

        return suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // 设置语言
                    val locale = Locale.getDefault()
                    val langResult = engine?.setLanguage(locale)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                        langResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.w(TAG, "Language $locale not supported")
                    }
                    ttsReady = true
                    if (cont.isActive) cont.resume(engine!!)
                } else {
                    if (cont.isActive) cont.resumeWithException(
                        Exception("Failed to initialize TextToSpeech engine")
                    )
                }
            }
            ttsEngine = engine

            cont.invokeOnCancellation {
                engine.shutdown()
                ttsEngine = null
                ttsReady = false
            }
        }
    }

    /**
     * 使用已有引擎合成文本到 ByteArray
     */
    private suspend fun synthesizeToBytes(
        context: Context,
        engine: TextToSpeech,
        text: String
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val tempDir = context.appTempFolder
        val audioFile = File(tempDir, "tts_${System.currentTimeMillis()}.wav")
        val utteranceId = UUID.randomUUID().toString()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "onStart: TTS engine started!")
            }

            override fun onDone(utteranceId: String?) {
                try {
                    if (audioFile.exists()) {
                        val audioData = audioFile.readBytes()
                        audioFile.delete()
                        if (cont.isActive) cont.resume(audioData)
                    } else {
                        if (cont.isActive) cont.resumeWithException(
                            Exception("Failed to generate audio file")
                        )
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "onError: TTS synthesis failed!")
                audioFile.delete()
                if (cont.isActive) cont.resumeWithException(
                    Exception("TTS synthesis failed")
                )
            }
        })

        val result = engine.synthesizeToFile(text, null, audioFile, utteranceId)

        if (result != TextToSpeech.SUCCESS) {
            if (cont.isActive) cont.resumeWithException(
                Exception("Failed to start TTS synthesis")
            )
        }

        cont.invokeOnCancellation {
            audioFile.delete()
        }
    }
}
