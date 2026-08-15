package me.rerere.tts.provider.providers

import kotlinx.serialization.Serializable

/**
 * Gemini TTS API 响应数据类，由 GeminiTTSProvider 和 GeminiVertexTTSProvider 共用。
 */
@Serializable
internal data class GeminiTTSResponse(
    val candidates: List<GeminiCandidate> = emptyList()
)

@Serializable
internal data class GeminiCandidate(
    val content: GeminiContent = GeminiContent()
)

@Serializable
internal data class GeminiContent(
    val parts: List<GeminiPart> = emptyList()
)

@Serializable
internal data class GeminiPart(
    val inlineData: GeminiInlineData? = null
)

@Serializable
internal data class GeminiInlineData(
    val data: String,
    val mimeType: String
)
