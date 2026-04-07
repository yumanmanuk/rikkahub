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

            // 合成到临时文件，并对音频头部做淡入以消除 TTS 引擎初始化噪声
            applyWavFadeIn(synthesizeToBytes(context, engine, request.text))
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
        // 每次合成前先 stop，确保上一次若未正常结束的合成不会干扰本次
        engine.stop()
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
            // 协程被取消时，必须 stop TTS 引擎，否则 synthesizeToFile 会继续在后台跑
            // 导致下一次合成时两个任务并发，产生杂音
            engine.stop()
            audioFile.delete()
        }
    }

    /**
     * 对 WAV 数据的开头做短暂淡入（默认 30ms），
     * 消除 Android TTS 引擎在 pipeline 初始化阶段产生的 click/pop 噪声。
     * 仅处理 16-bit PCM WAV，其余格式原样返回。
     */
    private fun applyWavFadeIn(wav: ByteArray, fadeMs: Int = 50): ByteArray {
        // WAV 头至少 44 字节
        if (wav.size < 44) return wav

        // 解析关键字段（little-endian）
        val audioFormat = ((wav[21].toInt() and 0xFF) shl 8) or (wav[20].toInt() and 0xFF)
        if (audioFormat != 1) return wav // 非 PCM，不处理

        val channels   = ((wav[23].toInt() and 0xFF) shl 8) or (wav[22].toInt() and 0xFF)
        val sampleRate = (wav[27].toInt() and 0xFF shl 24) or
                         (wav[26].toInt() and 0xFF shl 16) or
                         (wav[25].toInt() and 0xFF shl 8)  or
                         (wav[24].toInt() and 0xFF)
        val bitsPerSample = ((wav[35].toInt() and 0xFF) shl 8) or (wav[34].toInt() and 0xFF)
        if (bitsPerSample != 16) return wav // 仅处理 16-bit

        val bytesPerFrame = channels * (bitsPerSample / 8)
        // 淡入帧数：sampleRate * fadeMs / 1000，但不超过实际数据长度
        val dataBytes   = wav.size - 44
        val fadeSamples = ((sampleRate.toLong() * fadeMs / 1000).toInt())
            .coerceAtMost(dataBytes / bytesPerFrame)

        if (fadeSamples <= 0) return wav

        val result = wav.copyOf()
        for (i in 0 until fadeSamples) {
            val gain = i.toFloat() / fadeSamples
            val frameOffset = 44 + i * bytesPerFrame
            for (ch in 0 until channels) {
                val byteIdx = frameOffset + ch * 2
                if (byteIdx + 1 >= result.size) break
                // 读取 little-endian int16
                val raw = (result[byteIdx].toInt() and 0xFF) or
                          (result[byteIdx + 1].toInt() shl 8)
                val sample = raw.toShort()
                val faded  = (sample * gain).toInt().coerceIn(-32768, 32767).toShort()
                result[byteIdx]     = (faded.toInt() and 0xFF).toByte()
                result[byteIdx + 1] = ((faded.toInt() shr 8) and 0xFF).toByte()
            }
        }
        return result
    }
}
