package me.rerere.tts.provider

/**
 * TTS HTTP 层结构化异常，携带 HTTP 状态码与限流信息，供 TtsController 按错误类型分支重试。
 */
class TTSHttpException(
    val httpCode: Int,
    // 解析 Retry-After header（单位秒），429 时优先使用
    val retryAfterSec: Int?,
    val providerName: String,
    val errorBody: String?,
    message: String = "$providerName HTTP $httpCode"
) : Exception(message) {

    // 429 速率限制 或 5xx 服务端错误 均可重试
    fun isRetryable(): Boolean = httpCode == 429 || httpCode in 500..599

    // 4xx（除 429）属于客户端参数错误，不应重试
    fun isClientError(): Boolean = httpCode in 400..499 && httpCode != 429
}
