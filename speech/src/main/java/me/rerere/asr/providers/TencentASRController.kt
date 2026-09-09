package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "TencentASR"
private const val TENCENT_ASR_HOST = "asr.cloud.tencent.com"
private const val DEFAULT_ENGINE_MODEL_TYPE = "16k_zh"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
private const val STOP_FLUSH_TIMEOUT_MS = 1_000L

class TencentASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.Tencent,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())
    private var partialTranscript = ""

    @Volatile
    private var cancelRequested = false

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

        this.onTranscriptChange = onTranscriptChange
        cancelRequested = false
        completedTranscripts.clear()
        partialTranscript = ""
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true
            )
        }

        val wsUrl = runCatching { provider.buildTencentAsrWsUrl() }.getOrElse {
            setError(it.message ?: "构建腾讯云 ASR WebSocket URL 失败")
            return
        }

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (cancelRequested || webSocket !== this@TencentASRController.webSocket) {
                    runCatching { webSocket.close(1000, "cancelled") }
                    return
                }
                Log.d(TAG, "Tencent ASR websocket opened, waiting server confirm")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === this@TencentASRController.webSocket) {
                    handleServerEvent(webSocket, text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cancelRequested || webSocket !== this@TencentASRController.webSocket) {
                    Log.d(TAG, "Tencent ASR websocket failure after cancel, ignored", t)
                    return
                }
                Log.e(TAG, "Tencent ASR websocket failed", t)
                recorderJob?.cancel()
                releaseRecorder()
                setError(t.message ?: "ASR websocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@TencentASRController.webSocket) return
                Log.d(TAG, "Tencent ASR websocket closed: code=$code, reason=$reason")
                releaseRecorder()
                this@TencentASRController.webSocket = null
                _state.update {
                    it.copy(
                        status = ASRStatus.Idle,
                        errorMessage = null
                    )
                }
            }
        })
    }

    override fun stop() {
        cancelRequested = true
        recorderJob?.cancel()
        releaseRecorder()
        val socket = webSocket
        if (socket != null) {
            val wasListening = state.value.status == ASRStatus.Listening
            _state.update { it.copy(status = ASRStatus.Stopping) }
            scope.launch {
                if (!wasListening) {
                    socket.cancel()
                    webSocket = null
                    _state.update { it.copy(status = ASRStatus.Idle) }
                    return@launch
                }

                val sent = runCatching { socket.send("""{"type":"end"}""") }.getOrDefault(false)
                if (!sent) {
                    Log.w(TAG, "Failed to send Tencent ASR end frame; closing websocket")
                    socket.cancel()
                    webSocket = null
                    _state.update { it.copy(status = ASRStatus.Idle) }
                    return@launch
                }

                delay(STOP_FLUSH_TIMEOUT_MS)
                if (webSocket === socket) {
                    socket.close(1000, "stop")
                    webSocket = null
                    _state.update { it.copy(status = ASRStatus.Idle) }
                }
            }
        } else {
            _state.update { it.copy(status = ASRStatus.Idle) }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        releaseRecorder()
        webSocket?.cancel()
        webSocket = null
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(socket: WebSocket) {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val sampleRate = provider.sampleRate
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize
                .coerceAtLeast(sampleRate / 10 * 2)
                .coerceAtLeast(4096)

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
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                        if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                            socket.send(buffer.copyOfRange(0, read).toByteString())
                        } else {
                            Log.w(TAG, "WebSocket queue full, dropping audio frame")
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Audio recording failed", e)
                    setError(e.message ?: "Audio recording failed")
                }
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun handleServerEvent(socket: WebSocket, text: String) {
        val payload = runCatching { JSONObject(text) }.getOrElse {
            Log.w(TAG, "Invalid Tencent ASR event: $text", it)
            return
        }

        val code = payload.optInt("code", 0)
        if (code != 0) {
            val message = payload.optString("message").ifBlank { "Tencent ASR error" }
            recorderJob?.cancel()
            releaseRecorder()
            if (webSocket === socket) {
                webSocket = null
                socket.close(1000, "error")
            }
            setError("$message (code=$code)")
            return
        }

        if (state.value.status == ASRStatus.Connecting) {
            _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
            startRecorder(socket)
            return
        }

        val result = payload.optJSONObject("result")
        if (result != null) {
            val textValue = result.optString("voice_text_str")
            val sliceType = result.optInt("slice_type", 0)
            if (textValue.isNotBlank()) {
                val trimmed = textValue.trim()
                if (sliceType == 2) {
                    if (completedTranscripts.lastOrNull() != trimmed) {
                        completedTranscripts.add(trimmed)
                    }
                    partialTranscript = ""
                } else {
                    partialTranscript = trimmed
                }
                publishTranscript()
            }
        }

        if (payload.optInt("final", 0) == 1) {
            Log.d(TAG, "Tencent ASR final=1 received, closing session")
            if (webSocket === socket) {
                socket.close(1000, "session finished")
            }
        }
    }

    private fun publishTranscript() {
        val transcript = (completedTranscripts + partialTranscript)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch {
            onTranscriptChange?.invoke(transcript)
        }
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

private fun ASRProviderSetting.Tencent.buildTencentAsrWsUrl(): String {
    val secretId = secretId.trim()
    val secretKey = secretKey.trim()
    val appId = appId.trim()
    require(secretId.isNotEmpty()) { "缺少腾讯云 SecretId" }
    require(secretKey.isNotEmpty()) { "缺少腾讯云 SecretKey" }
    require(appId.isNotEmpty()) { "缺少腾讯云 APPID（实时语音识别必填）" }

    val timestamp = System.currentTimeMillis() / 1000
    val expired = timestamp + 86400
    val nonce = (0..999999).random()
    val voiceId = generateTencentVoiceId()

    val params = linkedMapOf<String, String>()
    params["secretid"] = secretId
    params["timestamp"] = timestamp.toString()
    params["expired"] = expired.toString()
    params["nonce"] = nonce.toString()
    params["engine_model_type"] = engineModelType.trim().ifEmpty { DEFAULT_ENGINE_MODEL_TYPE }
    params["voice_id"] = voiceId
    params["voice_format"] = voiceFormat.toString()
    params["needvad"] = "1"
    params["filter_dirty"] = filterDirty.toString()
    params["filter_modal"] = filterModal.toString()
    params["filter_punc"] = filterPunc.toString()
    params["convert_num_mode"] = convertNumMode.toString()
    params["word_info"] = "0"
    if (hotwordId.isNotBlank()) params["hotword_id"] = hotwordId.trim()
    if (customizationId.isNotBlank()) params["customization_id"] = customizationId.trim()

    val signature = createTencentAsrSignature(params, secretKey, appId)
    val query = params.entries
        .sortedBy { it.key }
        .joinToString("&") { (key, value) ->
            "${encodeUriComponent(key)}=${encodeUriComponent(value)}"
        } + "&signature=${encodeUriComponent(signature)}"

    return "wss://$TENCENT_ASR_HOST/asr/v2/$appId?$query"
}

private fun createTencentAsrSignature(
    params: Map<String, String>,
    secretKey: String,
    appId: String,
): String {
    val queryString = params.entries
        .sortedBy { it.key }
        .joinToString("&") { (key, value) -> "$key=$value" }
    val signSource = "asr.cloud.tencent.com/asr/v2/$appId?$queryString"

    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
    val digest = mac.doFinal(signSource.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(digest, Base64.NO_WRAP)
}

private fun generateTencentVoiceId(): String {
    val hex = UUID.randomUUID().toString().replace("-", "").take(8)
    return "cs_${System.currentTimeMillis()}_$hex"
}

private val HEX_CHARS = "0123456789ABCDEF"

private fun encodeUriComponent(value: String): String {
    return buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val isUnreserved = c in 'a'.code..'z'.code ||
                c in 'A'.code..'Z'.code ||
                c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (isUnreserved) {
                append(c.toChar())
            } else {
                append('%')
                append(HEX_CHARS[(c shr 4) and 0xF])
                append(HEX_CHARS[c and 0xF])
            }
        }
    }
}
