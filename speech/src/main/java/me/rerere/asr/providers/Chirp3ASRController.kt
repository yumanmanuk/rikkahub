package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.api.gax.core.CredentialsProvider
import com.google.api.gax.rpc.ApiStreamObserver
import com.google.api.gax.rpc.BidiStreamingCallable
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2Credentials
import com.google.cloud.speech.v2.ExplicitDecodingConfig
import com.google.cloud.speech.v2.RecognitionConfig
import com.google.cloud.speech.v2.SpeechClient
import com.google.cloud.speech.v2.SpeechSettings
import com.google.cloud.speech.v2.StreamingRecognitionConfig
import com.google.cloud.speech.v2.StreamingRecognitionFeatures
import com.google.cloud.speech.v2.StreamingRecognizeRequest
import com.google.cloud.speech.v2.StreamingRecognizeResponse
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import me.rerere.common.android.Logging
import me.rerere.tts.provider.providers.ServiceAccountTokenProvider
import okhttp3.OkHttpClient
import io.grpc.StatusRuntimeException
import io.grpc.Status
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Chirp3ASR"
// UNAVAILABLE 时的最大重试次数
private const val MAX_CONNECT_RETRIES = 5

/**
 * Google Cloud Speech-to-Text V2 + Chirp 3 ASR Controller.
 *
 * 走 gRPC bidirectional streamingRecognize。
 * 鉴权复用 TTS 侧的 ServiceAccountTokenProvider（JWT 换 access token + 5 分钟提前量缓存）。
 * 端点格式：{region}-speech.googleapis.com:443
 *
 * 说明：google-cloud-speech 4.56.0 SDK 的 V2 proto 暂未暴露 DenoiserConfig 字段，
 * 本实现仅设置 model="chirp_3"；[ASRProviderSetting.Chirp3.denoiseAudio] 与
 * [ASRProviderSetting.Chirp3.snrThreshold] 字段目前**不会**发往服务端，等 SDK 升级后再启用。
 */
class Chirp3ASRController(
    private val context: Context,
    // 保留 httpClient 参数以与 ASRController 工厂签名一致（其他三个 controller 都接 httpClient）。
    @Suppress("unused", "UNUSED_PARAMETER")
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.Chirp3
) : ASRController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 独立 closeScope，仅用于 dispose() 时在 IO 线程异步关闭 SpeechClient，
    // 不复用 controller 自己的 scope（dispose 后 scope 已 cancel，再 launch 会失败）。
    // close 任务完成后立即 cancel closeScope 自身，避免长生命周期 scope 泄漏。
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // isAvailable = true，UI 麦克风按钮才能点
    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private val tokenProvider = ServiceAccountTokenProvider(httpClient)

    private var client: SpeechClient? = null
    private val requestStream =
        AtomicReference<ApiStreamObserver<StreamingRecognizeRequest>?>()

    // 与其他三个 controller 保持一致的回调字段模式，避免闭包引用泄漏与 GC 不及时
    private var onTranscriptChange: ((String) -> Unit)? = null

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null

    // 累积已定稿的句子（isFinal=true），以及当前 interim 句，保证 transcript 单调递增
    private val completedTexts = mutableListOf<String>()
    private var partialText: String = ""

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        // 防止上一次的 start() 残留（旧的 recorder 协程可能尚未结束）
        scope.coroutineContext.cancelChildren()

        this.onTranscriptChange = onTranscriptChange
        completedTexts.clear()
        partialText = ""
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true
            )
        }

        scope.launch {
            // 最多重试 MAX_CONNECT_RETRIES 次，只对 UNAVAILABLE（网络未就绪、VPN 没连上等）进行退避重试
            val maxRetries = MAX_CONNECT_RETRIES
            var attempt = 0
            while (attempt < maxRetries) {
                attempt++
                try {
                    // 1. 用 Service Account JWT 换 access token（复用 TTS 的实现）
                    val token = tokenProvider.fetchAccessToken(
                        serviceAccountEmail = provider.serviceAccountEmail.trim(),
                        privateKeyPem = provider.privateKey.trim()
                    )

                    // 2. 把 access token 包装成 OAuth2Credentials 喂给 SpeechClient
                    val credentials = OAuth2Credentials.create(
                        AccessToken(token, Date(System.currentTimeMillis() + 60 * 60 * 1000L))
                    )
                    val endpoint = "${provider.region}-speech.googleapis.com:443"
                    val settings = SpeechSettings.newBuilder()
                        .setEndpoint(endpoint)
                        .setCredentialsProvider(FixedCredentialsProvider(credentials))
                        .build()
                    client = SpeechClient.create(settings)

                    // 3. 构造响应观察者，启 streaming call
                    val responseObserver = object : ApiStreamObserver<StreamingRecognizeResponse> {
                        override fun onNext(response: StreamingRecognizeResponse) {
                            val firstResult = response.resultsList.firstOrNull() ?: return
                            val firstAlt = firstResult.alternativesList.firstOrNull() ?: return
                            val text = firstAlt.transcript
                            if (text.isBlank()) return

                            if (firstResult.isFinal) {
                                completedTexts.add(text.trim())
                                partialText = ""
                            } else {
                                partialText = text
                            }
                            val snapshot = (completedTexts + listOf(partialText))
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                            publishTranscript(snapshot)
                        }

                        override fun onError(t: Throwable) {
                            Log.e(TAG, "Chirp 3 streaming error", t)
                            setError(t.message ?: "Chirp 3 streaming error")
                        }

                        override fun onCompleted() {
                            Log.d(TAG, "Chirp 3 stream completed")
                        }
                    }

                    val callable: BidiStreamingCallable<StreamingRecognizeRequest, StreamingRecognizeResponse> =
                        client!!.streamingRecognizeCallable()
                    @Suppress("DEPRECATION")
                    val reqStream = callable.bidiStreamingCall(responseObserver)
                    requestStream.set(reqStream)

                    // 4. 发首帧 recognizer + config
                    val recognizerPath =
                        "projects/${provider.projectId.trim()}/locations/${provider.region}/recognizers/${provider.recognizerId.ifBlank { "_" }}"
                    val sampleRate = provider.sampleRate.coerceIn(8000, 48000)
                    val recognitionConfig = RecognitionConfig.newBuilder()
                        .setExplicitDecodingConfig(
                            ExplicitDecodingConfig.newBuilder()
                                .setEncoding(ExplicitDecodingConfig.AudioEncoding.LINEAR16)
                                .setSampleRateHertz(sampleRate)
                                .setAudioChannelCount(1)
                                .build()
                        )
                        .addLanguageCodes(provider.language.ifBlank { "cmn-Hans-CN" })
                        .setModel("chirp_3")
                        .build()
                    val streamingConfig = StreamingRecognitionConfig.newBuilder()
                        .setConfig(recognitionConfig)
                        .setStreamingFeatures(
                            StreamingRecognitionFeatures.newBuilder()
                                .setInterimResults(true)
                                .build()
                        )
                        .build()
                    val firstReq = StreamingRecognizeRequest.newBuilder()
                        .setRecognizer(recognizerPath)
                        .setStreamingConfig(streamingConfig)
                        .build()
                    reqStream.onNext(firstReq)

                    // 5. 切到 Listening 并启录音
                    _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                    startRecorder()
                    // 连接成功，退出重试循环
                    break

                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: StatusRuntimeException) {
                    // UNAVAILABLE = 网络不可达（VPN 未就绪 / 网络抖动），可重试
                    if (e.status.code == Status.Code.UNAVAILABLE && attempt < maxRetries) {
                        Log.w(TAG, "Chirp3 UNAVAILABLE (第 $attempt 次)，准备重试...", e)
                        // 清理未建立完成的 client 再重试
                        runCatching { client?.close() }
                        client = null
                        requestStream.set(null)
                        // 继续循环进行下一次重试
                    } else {
                        // 其他 gRPC 错误或重试已耗尽
                        Log.e(TAG, "Failed to start Chirp 3 ASR", e)
                        setError(e.message ?: "Failed to start Chirp 3 ASR")
                        break
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to start Chirp 3 ASR", e)
                    setError(e.message ?: "Failed to start Chirp 3 ASR")
                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val sampleRate = provider.sampleRate.coerceIn(8000, 48000)
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize =
                (minBufferSize.coerceAtLeast(sampleRate / 10 * 2)).coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            audioRecord = recorder

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // 统一风格：扩展函数 + appendAmplitude
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update {
                            it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude))
                        }

                        // 每次新建 builder，避免字段残留
                        val audioReq = StreamingRecognizeRequest.newBuilder()
                            .setAudio(ByteString.copyFrom(buffer, 0, read))
                            .build()
                        val obs = requestStream.get() ?: break
                        try {
                            obs.onNext(audioReq)
                        } catch (e: Throwable) {
                            // dispose() 主动关闭时 client 已被置 null，此时抛异常是预期行为，静默退出
                            if (client == null) break
                            Log.w(TAG, "Failed to send audio frame, stream may be closed", e)
                            requestStream.set(null)
                            setError(e.message ?: "Stream closed")
                            break
                        }
                        delay(20)
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                // CancellationException 是协程正常取消，不应视为错误
                if (e is kotlinx.coroutines.CancellationException) throw e
                // dispose() 主动关闭时 client 已被置 null，此时 gRPC 抛 UNAVAILABLE 是预期行为，不报错
                if (client == null) return@launch
                Log.e(TAG, "Audio recording failed", e)
                setError(e.message ?: "Audio recording failed")
            } finally {
                releaseRecorder()
            }
        }
    }

    override fun stop() {
        recorderJob?.cancel()
        releaseRecorder()
        val obs = requestStream.getAndSet(null)
        if (obs != null) {
            _state.update { it.copy(status = ASRStatus.Stopping) }
            scope.launch {
                try {
                    obs.onCompleted()
                } catch (e: Throwable) {
                    Log.w(TAG, "Error closing stream", e)
                }
                // delay 后若没有新 stream 进来（没有 start() 被触发），就置 Idle
                delay(500)
                if (requestStream.get() == null) {
                    _state.update { it.copy(status = ASRStatus.Idle, errorMessage = null) }
                }
            }
        } else {
            _state.update { it.copy(status = ASRStatus.Idle) }
        }
    }

    override fun dispose() {
        // 用 cancelChildren 而非 cancel()，保留 scope 自身的复用能力（与 start() 中 cancelChildren 对称）
        scope.coroutineContext.cancelChildren()
        runCatching { requestStream.getAndSet(null)?.onCompleted() }
        // 关闭 SpeechClient 用独立的 closeScope 在 IO 异步执行（gRPC close 是阻塞调用）；
        // close 任务完成后立即 cancel closeScope 自身，避免 scope 长生命周期泄漏。
        val toClose = client
        client = null
        if (toClose != null) {
            closeScope.launch {
                try {
                    toClose.close()
                } finally {
                    closeScope.coroutineContext.cancelChildren()
                }
            }
        } else {
            closeScope.coroutineContext.cancelChildren()
        }
        releaseRecorder()
        onTranscriptChange = null
    }

    private fun publishTranscript(transcript: String) {
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        @Suppress("UNNECESSARY_SAFE_CALL")
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
        Logging.logError(
            tag = TAG,
            title = "ASR error",
            message = message
        )
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}

/** 把单个 Credentials 包装成 CredentialsProvider（GAX 需要）。 */
private class FixedCredentialsProvider(
    private val credentials: com.google.auth.oauth2.OAuth2Credentials
) : CredentialsProvider {
    override fun getCredentials() = credentials
}
