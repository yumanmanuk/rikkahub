package me.rerere.rikkahub.data.ai

// [FORK] 对话专属参数解析器
// 将 ConversationParams 与 Assistant 设置合并为最终生效值，
// 使 GenerationHandler 无需感知 ConversationParams 的存在，减少与 upstream 的冲突面。
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ConversationParams
import me.rerere.rikkahub.data.model.SystemPromptMode

/**
 * ConversationParams 与 Assistant 合并后的最终生效参数。
 * 由 [ConversationParams.resolveWith] 生成，传入 GenerationHandler 使用。
 */
data class ResolvedConversationParams(
    val temperature: Float?,
    val topP: Float?,
    val contextMessageSize: Int,
    val systemPrompt: String,
)

/**
 * 将对话专属参数与助手默认参数合并，对话参数优先级更高。
 * null 表示回退到助手设置。
 */
fun ConversationParams.resolveWith(assistant: Assistant): ResolvedConversationParams {
    val effectiveSystemPrompt: String = when {
        systemPrompt == null -> assistant.systemPrompt
        systemPromptMode == SystemPromptMode.OVERRIDE -> systemPrompt
        else -> buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                append(assistant.systemPrompt)
                append("\n\n")
            }
            append(systemPrompt)
        }
    }
    return ResolvedConversationParams(
        temperature = temperature ?: assistant.temperature,
        topP = topP ?: assistant.topP,
        contextMessageSize = contextMessageSize ?: assistant.contextMessageLimit,
        systemPrompt = effectiveSystemPrompt,
    )
}
