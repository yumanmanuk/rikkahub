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

    // 缓存参数：值未变化时跳过 setXxx，避免触发引擎内部声码器重建
    private var lastSpeechRate: Float = 1.0f
    private var lastPitch: Float = 1.0f

    // 引擎重建双触发机制
    private var synthesisCount = 0
    private var needsRebuild = false           // 由 onError 回调设置
    private val ENGINE_REBUILD_THRESHOLD = 10  // ~2000 字 ≈ 80 秒朗读，更频繁兜底

    // App 启动后首次调用时重建引擎：强制 shutdown() 旧连接并重新 bind，
    // 让 TTS Service 为新连接分配干净的 session，自动恢复已存在的杂音状态
    private var isFirstRun = true

    /**
     * 标记需要重建引擎。下次 generateSpeech 进入 mutex 块时会自动调 recycleEngine()。
     * 用于用户主动触发(浮窗"重建"按钮)。
     */
    fun markNeedsRebuild() {
        Log.i(TAG, "TTS engine rebuild requested by user")
        needsRebuild = true
    }

    private fun recycleEngine() {
        // 先记录日志再清零，否则 log 里永远是 0
        Log.i(TAG, "TTS engine recycled after $synthesisCount syntheses, needsRebuild=$needsRebuild")
        ttsEngine?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.shutdown() } catch (_: Exception) {}
        }
        ttsEngine = null
        ttsReady = false
        synthesisCount = 0
        needsRebuild = false
        lastSpeechRate = 1.0f
        lastPitch = 1.0f
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.SystemTTS,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val audioData = mutex.withLock {
            // 触发 1：达到阈值，定期预防性重建；触发 2：onError 标记，立即异常重建
            // 触发 3：首次运行，主动重建以恢复可能已存在的系统级杂音状态
            if (synthesisCount >= ENGINE_REBUILD_THRESHOLD || needsRebuild || isFirstRun) {
                isFirstRun = false
                recycleEngine()
            }

            val engine = getOrCreateEngine(context)

            // 仅在参数变化时才调用 setXxx，避免触发引擎内部声码器重建
            if (providerSetting.speechRate != lastSpeechRate) {
                engine.setSpeechRate(providerSetting.speechRate)
                lastSpeechRate = providerSetting.speechRate
            }
            if (providerSetting.pitch != lastPitch) {
                engine.setPitch(providerSetting.pitch)
                lastPitch = providerSetting.pitch
            }

            // 合成到临时文件，并对音频头尾做淡入/淡出以消除引擎噪声和切换爆音
            val raw = synthesizeToBytes(context, engine, request.text)
            synthesisCount++
            applyWavFadeOut(applyWavFadeIn(raw))
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
                    // 告知系统这是语音/无障碍内容，有助于走正确的音频策略
                    engine?.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
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
        // Mutex 已保证串行，上一次 onDone 结束后才进入下一次合成，无需前置 stop()
        // 高频 stop() 会持续中断引擎内部声码器收尾工作，累积损坏引擎 Service 状态
        val tempDir = context.appTempFolder
        val utteranceId = UUID.randomUUID().toString()
        // 文件名改用 utteranceId（UUID），避免毫秒级时间戳在极端情况下碰撞
        val audioFile = File(tempDir, "tts_$utteranceId.wav")

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                // 过滤旧合成的回调，避免干扰当前合成
                if (id != utteranceId) return
                Log.i(TAG, "onStart: TTS engine started!")
            }

            override fun onDone(id: String?) {
                if (id != utteranceId) return
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

            override fun onError(id: String?) {
                if (id != utteranceId) return
                Log.e(TAG, "onError: TTS synthesis failed for $id")
                // 合成失败，标记下次合成前重建引擎（异常恢复机制）
                needsRebuild = true
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
            // 仅在协程被外部取消时才 stop，正常完成流程不调用
            engine.stop()
            audioFile.delete()
        }
    }

    /**
     * 扫描 RIFF/WAVE 文件，找到 "data" chunk 的起始偏移和数据长度。
     * 不假设固定 44 字节头，兼容含 LIST/INFO/fact 等额外 chunk 的 WAV。
     * 找不到时回退到 (44, wav.size - 44)，保持原有行为。
     */
    private fun findDataChunkOffset(wav: ByteArray): Pair<Int, Int> {
        if (wav.size < 44 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") {
            return Pair(44, (wav.size - 44).coerceAtLeast(0))
        }
        var offset = 12  // 跳过 RIFF header（12 字节）
        while (offset + 8 <= wav.size) {
            val chunkId = String(wav, offset, 4, Charsets.US_ASCII)
            val chunkSize = (wav[offset + 4].toInt() and 0xFF) or
                ((wav[offset + 5].toInt() and 0xFF) shl 8) or
                ((wav[offset + 6].toInt() and 0xFF) shl 16) or
                ((wav[offset + 7].toInt() and 0xFF) shl 24)
            if (chunkId == "data") {
                val dataSize = minOf(chunkSize, wav.size - offset - 8).coerceAtLeast(0)
                return Pair(offset + 8, dataSize)
            }
            offset += 8 + chunkSize
            // WAV chunk 需要 2-byte 对齐
            if (chunkSize % 2 != 0) offset++
        }
        return Pair(44, (wav.size - 44).coerceAtLeast(0))
    }

    /**
     * 对 WAV 数据的开头做淡入（默认 100ms），
     * 消除 Android TTS 引擎在 pipeline 初始化阶段产生的 click/pop 噪声。
     * 增益曲线使用二次方（t*t）而非线性：前 50ms 增益仅 0.25，前 30ms 仅 0.09，
     * 对爆音/噪声的压制效果远强于线性淡入。
     * 仅处理 16-bit PCM WAV，其余格式原样返回。
     */
    private fun applyWavFadeIn(wav: ByteArray, fadeMs: Int = 100): ByteArray {
        if (wav.size < 44) return wav

        // 解析关键字段（little-endian）
        val audioFormat = ((wav[21].toInt() and 0xFF) shl 8) or (wav[20].toInt() and 0xFF)
        if (audioFormat != 1) return wav // 非 PCM，不处理

        val channels   = ((wav[23].toInt() and 0xFF) shl 8) or (wav[22].toInt() and 0xFF)
        // WAV 是 little-endian，低字节在前
        val sampleRate = (wav[24].toInt() and 0xFF) or
                         ((wav[25].toInt() and 0xFF) shl 8) or
                         ((wav[26].toInt() and 0xFF) shl 16) or
                         ((wav[27].toInt() and 0xFF) shl 24)
        val bitsPerSample = ((wav[35].toInt() and 0xFF) shl 8) or (wav[34].toInt() and 0xFF)
        if (bitsPerSample != 16) return wav // 仅处理 16-bit

        val bytesPerFrame = channels * (bitsPerSample / 8)
        // 动态获取 data chunk 偏移，替代硬编码 44，防止额外 chunk 的 WAV 数据被误操作
        val (dataOffset, dataSize) = findDataChunkOffset(wav)
        val fadeSamples = ((sampleRate.toLong() * fadeMs / 1000).toInt())
            .coerceAtMost(dataSize / bytesPerFrame)

        if (fadeSamples <= 0) return wav

        val result = wav.copyOf()
        for (i in 0 until fadeSamples) {
            // 二次方曲线：t^2，前期增益极低，对 TTS 引擎启动噪声的压制效果远强于线性
            val t = i.toFloat() / fadeSamples
            val gain = t * t
            // frameOffset 从 dataOffset 开始，而非硬编码 44
            val frameOffset = dataOffset + i * bytesPerFrame
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

    /**
     * 对 WAV 数据的结尾做淡出（默认 50ms），
     * 消除 ExoPlayer 切换音频源或播放结束时的爆音，
     * 更长的淡出确保 chunk 结尾足够干净，不把尾部噪音带入下一段。
     * 仅处理 16-bit PCM WAV，其余格式原样返回。
     */
    private fun applyWavFadeOut(wav: ByteArray, fadeMs: Int = 50): ByteArray {
        if (wav.size < 44) return wav

        val audioFormat = ((wav[21].toInt() and 0xFF) shl 8) or (wav[20].toInt() and 0xFF)
        if (audioFormat != 1) return wav

        val channels   = ((wav[23].toInt() and 0xFF) shl 8) or (wav[22].toInt() and 0xFF)
        val sampleRate = (wav[24].toInt() and 0xFF) or
                         ((wav[25].toInt() and 0xFF) shl 8) or
                         ((wav[26].toInt() and 0xFF) shl 16) or
                         ((wav[27].toInt() and 0xFF) shl 24)
        val bitsPerSample = ((wav[35].toInt() and 0xFF) shl 8) or (wav[34].toInt() and 0xFF)
        if (bitsPerSample != 16) return wav

        val bytesPerFrame = channels * (bitsPerSample / 8)
        // 动态获取 data chunk 偏移，替代硬编码 44
        val (dataOffset, dataSize) = findDataChunkOffset(wav)
        val fadeSamples   = ((sampleRate.toLong() * fadeMs / 1000).toInt())
            .coerceAtMost(dataSize / bytesPerFrame)

        if (fadeSamples <= 0) return wav

        val result = wav.copyOf()
        val totalFrames = dataSize / bytesPerFrame
        for (i in 0 until fadeSamples) {
            val gain = (fadeSamples - 1 - i).toFloat() / fadeSamples
            // frameOffset 从 dataOffset 开始，而非硬编码 44
            val frameOffset = dataOffset + (totalFrames - fadeSamples + i) * bytesPerFrame
            for (ch in 0 until channels) {
                val byteIdx = frameOffset + ch * 2
                if (byteIdx + 1 >= result.size) break
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
