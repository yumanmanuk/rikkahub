package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool 和 ServerTool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning、客户端 Tool 和 ServerTool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    // 强制：将所有 Reasoning/Tool parts 提取到最前面，绝不让思考过程出现在正文中
    val reasoningParts = mutableListOf<UIMessagePart.Reasoning>()
    val toolSteps = mutableListOf<ThinkingStep.ToolStep>()
    val serverToolSteps = mutableListOf<ThinkingStep.ServerToolStep>()
    val contentBlocks = mutableListOf<MessagePartBlock>()

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                reasoningParts.add(part)
            }

            is UIMessagePart.Tool -> {
                toolSteps.add(ThinkingStep.ToolStep(part))
            }

            is UIMessagePart.ServerTool -> {
                serverToolSteps.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                contentBlocks.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }

    // 将所有碎片化的 Reasoning 合并为唯一一段，避免顶部出现多个思考气泡
    val mergedThinkingSteps = mutableListOf<ThinkingStep>()
    if (reasoningParts.isNotEmpty()) {
        val merged = UIMessagePart.Reasoning(
            reasoning = reasoningParts.joinToString("") { it.reasoning },
            createdAt = reasoningParts.first().createdAt,
            finishedAt = reasoningParts.lastOrNull { it.finishedAt != null }?.finishedAt
                ?: reasoningParts.last().finishedAt,
        )
        mergedThinkingSteps.add(ThinkingStep.ReasoningStep(merged))
    }
    mergedThinkingSteps.addAll(toolSteps)
    mergedThinkingSteps.addAll(serverToolSteps)

    // 思考过程始终在最前面
    return if (mergedThinkingSteps.isNotEmpty()) {
        listOf(MessagePartBlock.ThinkingBlock(mergedThinkingSteps)) + contentBlocks
    } else {
        contentBlocks
    }
}
