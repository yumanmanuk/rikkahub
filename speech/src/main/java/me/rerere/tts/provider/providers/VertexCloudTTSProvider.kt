package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.TTSHttpException
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "VertexCloudTTSProvider"

class VertexCloudTTSProvider : TTSProvider<TTSProviderSetting.VertexCloud> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    // Provider 实例级别缓存 token，避免频繁签 JWT
    private val tokenProvider by lazy { ServiceAccountTokenProvider(httpClient) }

    @Serializable
    private data class CloudTTSResponse(
        val audioContent: String? = null
    )

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.VertexCloud,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val token = tokenProvider.fetchAccessToken(
            serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
            privateKeyPem = providerSetting.privateKey.trim(),
            scopes = listOf("https://www.googleapis.com/auth/cloud-platform")
        )

        // Chirp3 HD 系列是生成式 AI 语音，不支持 speakingRate / pitch，传入会触发 400
        val isChirp3HD = providerSetting.voiceName.contains("Chirp3", ignoreCase = true)

        val requestBody = JSONObject().apply {
            put("input", JSONObject().apply {
                put("text", request.text)
            })
            put("voice", JSONObject().apply {
                put("languageCode", providerSetting.languageCode)
                put("name", providerSetting.voiceName)
            })
            put("audioConfig", JSONObject().apply {
                put("audioEncoding", providerSetting.audioEncoding)
                if (!isChirp3HD) {
                    put("speakingRate", providerSetting.speakingRate.toDouble())
                    put("pitch", providerSetting.pitch.toDouble())
                }
            })
        }

        Log.i(TAG, "generateSpeech: voice=${providerSetting.voiceName}, lang=${providerSetting.languageCode}")

        val httpRequest = Request.Builder()
            .url("https://texttospeech.googleapis.com/v1/text:synthesize")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // 用 use{} 包裹确保无论成功/失败都释放 socket 连接回连接池
        httpClient.newCall(httpRequest).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw TTSHttpException(
                    httpCode = response.code,
                    retryAfterSec = parseRetryAfter(response),
                    providerName = "vertex_cloud",
                    errorBody = body,
                    message = "Vertex Cloud TTS request failed: ${response.code} ${response.message} $body"
                )
            }
            val ttsResponse = json.decodeFromString<CloudTTSResponse>(body)
            val audioBase64 = ttsResponse.audioContent
                ?: throw Exception("No audio data returned from Vertex Cloud TTS")
            val mp3Bytes = Base64.decode(audioBase64, Base64.DEFAULT)
            emit(
                AudioChunk(
                    data = mp3Bytes,
                    format = AudioFormat.MP3,
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "vertex_cloud",
                        "voice" to providerSetting.voiceName
                    )
                )
            )
        }
    }

    /**
     * 解析 Retry-After header，支持整数秒格式。
     * Google Cloud TTS 通常返回整数秒，暂不处理 HTTP-date 格式。
     */
    private fun parseRetryAfter(response: okhttp3.Response): Int? {
        return response.header("Retry-After")?.toIntOrNull()
    }
}

/**
 * ServiceAccountTokenProvider — 与 ai 模块的同名实现等价。
 * speech 模块独立持有一份，避免直接依赖 ai 模块，同时保持 Provider 实例级别的 token 缓存。
 * internal 可见性：允许同 module 内跨文件访问（GeminiVertexTTSProvider 需要复用）。
 */
internal class ServiceAccountTokenProvider(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    @Serializable
    private data class CachedToken(
        val token: String,
        val expiresAt: Long
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null
    )

    private fun cacheKey(email: String, scopes: List<String>) =
        "$email:${scopes.sorted().joinToString(",")}"

    private fun isValid(cached: CachedToken): Boolean {
        val now = Instant.now().epochSecond
        // 提前 5 分钟视为过期，留出缓冲
        return cached.expiresAt > (now + 300)
    }

    suspend fun fetchAccessToken(
        serviceAccountEmail: String,
        privateKeyPem: String,
        scopes: List<String> = listOf("https://www.googleapis.com/auth/cloud-platform")
    ): String = withContext(Dispatchers.IO) {
        val key = cacheKey(serviceAccountEmail, scopes)
        tokenCache[key]?.let { if (isValid(it)) return@withContext it.token }

        val now = Instant.now().epochSecond
        val exp = now + 3600

        val headerB64 = base64UrlNoPad("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claimB64 = base64UrlNoPad(
            """{"iss":"$serviceAccountEmail","scope":"${scopes.joinToString(" ")}","aud":"https://oauth2.googleapis.com/token","iat":$now,"exp":$exp}"""
                .toByteArray()
        )
        val signingInput = "$headerB64.$claimB64"
        val privateKey = parsePkcs8(privateKeyPem)
        val sig = signRs256(signingInput.toByteArray(), privateKey)
        val assertion = "$signingInput.${base64UrlNoPad(sig)}"

        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", assertion)
            .build()

        val req = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(form)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body.string()
                throw IllegalStateException("Token endpoint ${resp.code}: $body")
            }
            val body = resp.body.string()
            val tokenResp = json.decodeFromString<TokenResponse>(body)
            val accessToken = tokenResp.accessToken ?: error("No access_token in response")
            val expiresAt = now + (tokenResp.expiresIn ?: 3600)
            tokenCache[key] = CachedToken(accessToken, expiresAt)
            accessToken
        }
    }

    private fun base64UrlNoPad(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun parsePkcs8(pem: String): PrivateKey {
        val normalized = pem
            // 兼容两种粘贴来源：JSON 文件直接复制（\n 字面量）和手动换行的 PEM
            .replace("\\n", "\n")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = java.util.Base64.getDecoder().decode(normalized)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun signRs256(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }
}
