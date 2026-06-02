package me.rerere.rikkahub.data.ai

// [FORK] 上下文摘要管理器 - 完全独立的新模块，不修改 upstream 代码
// 负责判断何时生成/更新摘要，以及调用 LLM 生成摘要文本。

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ConversationParams

private const val SUMMARY_TAG = "ContextSummaryManager"

// 摘要生成所需的最小"截断比例"：只有当超出窗口的消息数达到上下文大小一半时才生成
private const val SUMMARY_TRIGGER_RATIO = 0.5f

/**
 * 判断是否需要生成/更新摘要，并执行摘要生成。
 *
 * 触发条件：
 * - 启用了摘要功能
 * - contextMessageSize > 0（有上下文限制）
 * - 消息总数 > contextMessageSize（发生了截断）
 * - 最近一次摘要之后又累积了足够多的新消息（避免每条消息都重新摘要）
 *
 * @return 更新后的 ConversationParams（包含新摘要），若无需更新则返回 null
 */
suspend fun generateContextSummaryIfNeeded(
    params: ConversationParams,
    allMessages: List<UIMessage>,
    assistant: Assistant,
    settings: Settings,
    providerManager: ProviderManager,
    enableContextSummary: Boolean,
): ConversationParams? {
    val effectiveContextSize = params.contextMessageSize ?: assistant.contextMessageSize
    if (!enableContextSummary) return null
    if (effectiveContextSize <= 0) return null
    if (allMessages.size <= effectiveContextSize) return null

    // 计算需要被摘要覆盖的消息数量
    // 保留最近 effectiveContextSize 条，其余都需要摘要
    val newSummarizeUntil = allMessages.size - effectiveContextSize

    // 如果当前摘要已经覆盖到足够新的位置，判断是否需要增量更新
    val currentSummarizedUntil = params.summarizedUntilIndex
    val newMessagesToSummarize = newSummarizeUntil - currentSummarizedUntil
    val minBatchSize = (effectiveContextSize * SUMMARY_TRIGGER_RATIO).toInt().coerceAtLeast(1)

    if (newMessagesToSummarize < minBatchSize && params.contextSummary != null) {
        // 已有摘要且新增量不够大，跳过
        return null
    }

    // 找到生成摘要所用的模型（使用当前助手的聊天模型）
    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
    if (model == null) {
        Log.w(SUMMARY_TAG, "generateContextSummaryIfNeeded: chat model not found, skip")
        return null
    }
    val provider = model.findProvider(settings.providers)
    if (provider == null) {
        Log.w(SUMMARY_TAG, "generateContextSummaryIfNeeded: provider not found, skip")
        return null
    }

    // 需要被摘要的消息列表
    val messagesToSummarize = allMessages.subList(0, newSummarizeUntil)
    Log.i(SUMMARY_TAG, "Generating summary for messages[0..$newSummarizeUntil] (total=${allMessages.size}, contextSize=$effectiveContextSize)")

    val newSummary = runCatching {
        callSummaryLLM(
            existingSummary = params.contextSummary,
            messages = messagesToSummarize,
            model = model,
            provider = provider,
            providerManager = providerManager,
        )
    }.onFailure {
        Log.e(SUMMARY_TAG, "Summary generation failed", it)
    }.getOrNull() ?: return null

    Log.i(SUMMARY_TAG, "Summary generated successfully (${newSummary.length} chars)")
    return params.copy(
        contextSummary = newSummary,
        summarizedUntilIndex = newSummarizeUntil,
    )
}

/**
 * 调用 LLM 执行摘要生成。
 * 如果已有历史摘要，采用增量模式（告知 LLM 在旧摘要基础上更新）。
 */
private suspend fun callSummaryLLM(
    existingSummary: String?,
    messages: List<UIMessage>,
    model: me.rerere.ai.provider.Model,
    provider: me.rerere.ai.provider.ProviderSetting,
    providerManager: ProviderManager,
): String {
    val providerImpl = providerManager.getProviderByType(provider)

    // 构建摘要 prompt
    val historyText = buildString {
        messages.forEach { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Assistant"
                MessageRole.SYSTEM -> "System"
                else -> msg.role.name
            }
            val text = msg.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString(" ") { it.text }
                .take(2000) // 限制每条消息的长度防止 token 过多
            if (text.isNotBlank()) {
                appendLine("[$role]: $text")
            }
        }
    }

    val systemPrompt = if (existingSummary.isNullOrBlank()) {
        """You are a conversation summarizer. Summarize the following conversation history concisely.
Focus on:
- Key questions and requests from the user
- Important facts, decisions, and conclusions
- Code, configurations, and specific technical details
- Unresolved issues or ongoing tasks

Be concise but complete. Omit any section that has no relevant content — do not include empty sections or filler like "all questions resolved". IMPORTANT: You MUST write the summary in the SAME language as the conversation. If the conversation is in Chinese, write in Chinese. If in English, write in English. Never translate the summary to a different language."""
    } else {
        """You are a conversation summarizer. Update the existing summary with new conversation content.
Existing summary:
$existingSummary

Add the new conversation content to this summary. Keep all important information from the existing summary, and integrate the new content naturally. Be concise but complete. Omit any section that has no relevant content — do not include empty sections or filler like "all questions resolved". IMPORTANT: You MUST write the summary in the SAME language as the conversation. If the conversation is in Chinese, write in Chinese. If in English, write in English. Never translate the summary to a different language."""
    }

    val requestMessages = buildList {
        add(UIMessage.system(systemPrompt))
        add(UIMessage.user("Please summarize this conversation:\n\n$historyText"))
    }

    var resultMessages = requestMessages
    val chunk = providerImpl.generateText(
        providerSetting = provider,
        messages = requestMessages,
        params = TextGenerationParams(
            model = model,
            temperature = 0.3f,
        )
    )
    resultMessages = resultMessages.handleMessageChunk(chunk, model)
    return resultMessages.lastOrNull()
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim() ?: ""
}
