package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.common.android.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSHttpException
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.SystemTTSProvider
import java.util.UUID

private const val TAG = "TtsController"

/**
 * 判断异常是否属于可重试的瞬时网络错误（connection reset、超时等）
 */
private fun Throwable.isRetryableNetworkError(): Boolean {
    return this is java.io.IOException
            || this is java.net.SocketException
            || this is java.net.SocketTimeoutException
            || this is java.net.ConnectException
            || this is java.net.UnknownHostException
            || this is javax.net.ssl.SSLException
}

/**
 * 判断异常是否属于 429 速率限制错误。
 * 封装 TTSHttpException 类型判断，并兼容普通 Exception message 含关键字的情况。
 */
private fun Throwable.isRateLimitError(): Boolean {
    if (this is TTSHttpException && httpCode == 429) return true
    val msg = message ?: return false
    return msg.contains("429", ignoreCase = true)
        || msg.contains("RATE_EXCEEDED", ignoreCase = true)
        || msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
}

/**
 * 判断异常是否属于 5xx 服务端错误。
 */
private fun Throwable.isServerError(): Boolean {
    return this is TTSHttpException && httpCode in 500..599
}

/**
 * TTS 控制器（重构版）
 * - 负责文本分片、预取合成、排队播放与状态上报
 * - 对外 API 与原版兼容
 */
class TtsController(
    context: Context,
    private val ttsManager: TTSManager
) {
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 组件
    private val synthesizer = TtsSynthesizer(ttsManager)
    private val audio = AudioPlayer(context)
    private val systemTtsProvider = SystemTTSProvider()  // 单例,供 resetSystemTtsEngine 用

    // Provider & 作业
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null
    private var isPaused = false

    // 队列与缓存（基于稳定 ID）
    private val queue: java.util.concurrent.ConcurrentLinkedQueue<TtsChunk> = java.util.concurrent.ConcurrentLinkedQueue()
    private val allChunks: MutableList<TtsChunk> = mutableListOf()
    private val cache = java.util.concurrent.ConcurrentHashMap<UUID, kotlinx.coroutines.Deferred<TTSResponse>>()
    private var lastPrefetchedIndex: Int = -1

    // 行为参数
    // 60ms：与 pcmToWav 追加的 60ms 静音等量补偿，使 PCM 格式总片段间隙维持在 ~200ms
    private val chunkDelayMs = 60L
    // 预取窗口大小通过 getPrefetchCount(provider) 动态获取

    // 状态流（保留与旧版兼容的 StateFlow）
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    // 统一播放状态（融合音频播放 + 分片进度）
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        // 同步底层播放器状态到统一状态，并补充分片信息
        scope.launch {
            audio.playbackState.collectLatest { audioState ->
                _playbackState.update {
                    audioState.copy(
                        currentChunkIndex = _currentChunk.value,
                        totalChunks = _totalChunks.value,
                        status = if (!_isAvailable.value) PlaybackStatus.Idle else audioState.status
                    )
                }
            }
        }
    }

    /** 选择/取消选择 Provider */
    fun setProvider(provider: TTSProviderSetting?) {
        currentProvider = provider
        _isAvailable.update { provider != null }
        if (provider == null) stop()
    }

    /**
     * 当前 provider 是否是 SystemTTS(决定是否在浮窗显示"重建引擎"按钮)
     */
    fun isSystemTtsActive(): Boolean = currentProvider is TTSProviderSetting.SystemTTS

    /**
     * 主动请求重建 SystemTTS 引擎(用于消除累积的 vocoder 漂移 / 偶发杂音)。
     * 通过 markNeedsRebuild() 让下次 generateSpeech 时自动 recycleEngine,
     * 避免在播放进行中重建导致当前 chunk 被中断。
     */
    fun resetSystemTtsEngine() {
        if (currentProvider is TTSProviderSetting.SystemTTS) {
            systemTtsProvider.markNeedsRebuild()
        }
    }

    /**
     * 朗读文本
     * - flush=true: 清空当前进度并重新开始
     * - flush=false: 继续队列，追加朗读
     */
    fun speak(text: String, flush: Boolean = true) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val newChunks = createChunker(provider).split(text)
        if (newChunks.isEmpty()) return

        if (flush) {
            internalReset()
            allChunks.addAll(newChunks)
            queue.addAll(newChunks)
            _currentChunk.update { 0 }
        } else {
            // 追加时，重映射 index 以保持全局顺序
            val startIndex = (allChunks.lastOrNull()?.index ?: -1) + 1
            val remapped = newChunks.mapIndexed { i, c -> c.copy(index = startIndex + i) }
            allChunks.addAll(remapped)
            queue.addAll(remapped)
        }
        _totalChunks.update { queue.size }
        _error.update { null }

        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                status = PlaybackStatus.Buffering
            )
        }

        if (workerJob?.isActive != true) startWorker()
        prefetchFrom((_currentChunk.value).coerceAtLeast(0))
    }

    private fun internalReset() {
        // Reset current session while keeping provider availability
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Reset")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _error.update { null }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 暂停播放（保留进度） */
    fun pause() {
        isPaused = true
        audio.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    /** 恢复播放 */
    fun resume() {
        isPaused = false
        audio.resume()
        _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
    }

    /** 快进当前音频 */
    fun fastForward(ms: Long = 5_000) {
        audio.seekBy(ms)
    }

    /** 设置播放速度 */
    fun setSpeed(speed: Float) {
        audio.setSpeed(speed)
    }

    /** 跳过下一段（不打断当前正在播放） */
    fun skipNext() {
        if (queue.isNotEmpty()) {
            queue.poll()
            _totalChunks.update { queue.size }
        }
    }

    /** 停止并清空状态 */
    fun stop() {
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Stopped")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 释放资源 */
    fun dispose() {
        stop()
        scope.cancel()
        audio.release()
    }

    // region 内部：播放调度
    private fun startWorker() {
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        workerJob = scope.launch {
            _isSpeaking.update { true }
            var processedCount = _currentChunk.value
            try {
                while (isActive) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val chunk = queue.poll() ?: break

                    // 更新状态（1-based）
                    _currentChunk.update { processedCount + 1 }
                    _totalChunks.update { queue.size + 1 }
                    _playbackState.update {
                        it.copy(
                            currentChunkIndex = _currentChunk.value,
                            totalChunks = _totalChunks.value
                        )
                    }

                    // 预取下一窗口
                    prefetchFrom(chunk.index + 1)

                    val response = try {
                        synthesizeWithRetry(chunk, provider)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Synthesis failed after retries, stopping", e)
                        val errorMsg = e.message ?: "TTS synthesis error"
                        _error.update { errorMsg }
                        Logging.logError(
                            tag = TAG,
                            title = "TTS 合成失败",
                            message = errorMsg,
                            throwable = e
                        )
                        break
                    }

                    // 播放
                    try {
                        audio.play(response)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Playback error, stopping", e)
                        val errorMsg = e.message ?: "Audio playback error"
                        _error.update { errorMsg }
                        Logging.logError(
                            tag = TAG,
                            title = "TTS 播放失败",
                            message = errorMsg,
                            throwable = e
                        )
                        break
                    }

                    // 播放完毕，立即释放缓存中的音频数据，避免内存累积导致GC杂音
                    cache.remove(chunk.id)

                    if (queue.isNotEmpty()) delay(chunkDelayMs)

                    processedCount++
                }
            } finally {
                _isSpeaking.update { false }
                if (queue.isEmpty()) {
                    _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                }
            }
        }
    }

    private fun prefetchFrom(startIndex: Int) {
        val provider = currentProvider ?: return
        val effectivePrefetch = getPrefetchCount(provider)
        val begin = startIndex.coerceAtLeast(lastPrefetchedIndex + 1)
        val endExclusive = (begin + effectivePrefetch).coerceAtMost(allChunks.size)
        if (begin >= endExclusive) return

        for (i in begin until endExclusive) {
            val chunk = allChunks.getOrNull(i) ?: continue
            cache.computeIfAbsent(chunk.id) {
                scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
            }
        }
        lastPrefetchedIndex = endExclusive - 1
    }

    /**
     * 对合成请求进行分类重试：
     * - 429 速率限制：指数退避 1s/2s/4s，最多 3 次，优先读 Retry-After header
     * - 5xx 服务端错误：线性退避 500ms/1s，最多 2 次
     * - 网络异常：线性退避 300ms/600ms，最多 2 次
     * - 其他 4xx：不重试，直接抛出
     */
    private suspend fun synthesizeWithRetry(
        chunk: TtsChunk,
        provider: TTSProviderSetting
    ): TTSResponse {
        val rateLimitDelays = longArrayOf(1_000, 2_000, 4_000)
        val serverDelays = longArrayOf(500, 1_000)
        val networkDelays = longArrayOf(300, 600)

        var rateLimitAttempt = 0
        var serverAttempt = 0
        var networkAttempt = 0

        // 总尝试上限 = 1（首次）+ 3（429）+ 2（5xx）+ 2（网络）= 8 次
        repeat(8) {
            try {
                return awaitOrCreate(chunk, provider)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 清除失败的缓存，让下次重新发起真正的 HTTP 请求
                cache.remove(chunk.id)?.cancel(CancellationException("Retry"))

                when {
                    e.isRateLimitError() -> {
                        if (rateLimitAttempt >= rateLimitDelays.size) {
                            Log.w(TAG, "429 rate limit retries exhausted", e)
                            throw e
                        }
                        val delayMs = (e as? TTSHttpException)
                            ?.retryAfterSec?.let { it * 1000L }
                            ?: rateLimitDelays[rateLimitAttempt]
                        Log.w(TAG, "Rate limited (429), retry in ${delayMs}ms (attempt ${rateLimitAttempt + 1})", e)
                        delay(delayMs)
                        rateLimitAttempt++
                    }
                    e.isServerError() -> {
                        if (serverAttempt >= serverDelays.size) {
                            Log.w(TAG, "5xx server error retries exhausted", e)
                            throw e
                        }
                        val delayMs = serverDelays[serverAttempt]
                        Log.w(TAG, "Server error (${(e as TTSHttpException).httpCode}), retry in ${delayMs}ms", e)
                        delay(delayMs)
                        serverAttempt++
                    }
                    e.isRetryableNetworkError() -> {
                        if (networkAttempt >= networkDelays.size) throw e
                        val delayMs = networkDelays[networkAttempt]
                        Log.w(TAG, "Network error, retry in ${delayMs}ms (attempt ${networkAttempt + 1})", e)
                        delay(delayMs)
                        networkAttempt++
                    }
                    // 其他错误（如 4xx 参数错误）不重试
                    else -> throw e
                }
            }
        }
        error("synthesizeWithRetry: unreachable, exceeded max attempts")
    }

    private suspend fun awaitOrCreate(chunk: TtsChunk, provider: TTSProviderSetting): TTSResponse {
        val deferred = cache.computeIfAbsent(chunk.id) {
            scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
        }
        return try {
            deferred.await()
        } finally {
            // 可按需保留缓存（此处保留，便于重播/重试）
        }
    }

    /**
     * 根据 Provider 类型创建合适的 TextChunker:
     * - SystemTTS: 大 chunk (200字) + 跨段落归并，减少 engine.stop()/synthesize 次数
     * - VertexCloud Chirp3/Studio: 极小 chunk (30字/90字节)，Chirp3-HD 单句硬限 ~100 byte
     * - VertexCloud 其他语音: 大 chunk (200字)，标准 voice 上限宽松
     * - Gemini/GeminiVertex: 中等 chunk (120字)，减少请求次数缓解 429
     * - MiMo: 较大 chunk (120字)，限流额度充足，减少分片数量降低段落间等待
     * - MiniMax: 较大 chunk (120字)，speech-2.8 系列限流宽松，降低分片数量
     * - 其他云端: 80字
     */
    private fun createChunker(provider: TTSProviderSetting): TextChunker {
        return when (provider) {
            is TTSProviderSetting.SystemTTS ->
                TextChunker(maxChunkLength = 200, crossParagraph = true)
            is TTSProviderSetting.VertexCloud -> {
                val isChirp3OrStudio = provider.voiceName.contains("Chirp3", ignoreCase = true)
                    || provider.voiceName.contains("Studio", ignoreCase = true)
                if (isChirp3OrStudio) {
                    // Chirp3-HD 单句硬限 ~100 byte，30 中文字符 ≈ 90 byte UTF-8
                    TextChunker(maxChunkLength = 30, maxChunkBytes = 90)
                } else {
                    TextChunker(maxChunkLength = 200)
                }
            }
            is TTSProviderSetting.Gemini,
            is TTSProviderSetting.GeminiVertex ->
                // 120字/段：优先保证合成速度，减少首字延迟
                TextChunker(maxChunkLength = 120)
            is TTSProviderSetting.MiMo ->
                // 限流额度充足，使用较大 chunk 降低分片总数，减少段落间等待
                TextChunker(maxChunkLength = 120)
            is TTSProviderSetting.MiniMax ->
                // speech-2.8 系列限流宽松，使用较大 chunk 降低分片数量
                TextChunker(maxChunkLength = 120)
            else ->
                TextChunker(maxChunkLength = 80)
        }
    }

    /**
     * 根据 Provider 类型返回预取窗口大小。
     * - MiMo: 4，限流额度大，激进预取减少段落间等待
     * - MiniMax: 5，ultra 套餐限流最宽松，激进预取最大化减少卡顿
     * - Gemini/GeminiVertex: 1，并发控制降低 429 概率
     * - 其他: 2
     */
    private fun getPrefetchCount(provider: TTSProviderSetting): Int {
        return when (provider) {
            is TTSProviderSetting.MiMo -> 4
            is TTSProviderSetting.MiniMax -> 5
            is TTSProviderSetting.Gemini,
            is TTSProviderSetting.GeminiVertex -> 1
            else -> 2
        }
    }
    // endregion
}
