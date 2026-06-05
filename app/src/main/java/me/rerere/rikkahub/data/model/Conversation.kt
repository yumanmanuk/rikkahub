package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 对话专属系统提示词与助手提示词的合并模式。
 */
@Serializable
enum class SystemPromptMode {
    /** 完全替换助手提示词 */
    OVERRIDE,
    /** 追加到助手提示词末尾 */
    APPEND,
}

/**
 * 对话专属参数，优先级高于助手设置。
 * null 表示使用助手的设置。
 */
@Serializable
data class ConversationParams(
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageSize: Int? = null,
    val systemPrompt: String? = null,
    val systemPromptMode: SystemPromptMode = SystemPromptMode.APPEND,
    // [FORK] Battle Mode: 开启后发消息时用所有选中模型各并发回答一次
    val battleModeEnabled: Boolean = false,
    val battleModelIds: List<Uuid> = emptyList(),
    // [FORK] Battle Mode: 各模型是否使用独立上下文（true = 每个模型看到自己之前的回答作为上下文）
    val battleIndependentContext: Boolean = false,
    // [FORK] Battle Mode: 每个模型对应的思考深度，key=modelId；未设置则继承 assistant.reasoningLevel
    val battleModelReasoningLevels: Map<Uuid, ReasoningLevel> = emptyMap(),
    // [FORK] 对话专属记忆：开启后记忆以 conversation.id 为 key 存储，与其他对话完全隔离
    val enableConversationMemory: Boolean = false,
)

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    @Transient
    val newConversation: Boolean = false,
    @Transient
    val isTemporary: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    /**
     * [FORK] Battle Mode 独立上下文：
     * 对于 battle 节点，选取该 modelId 生成的那条 message 作为该模型的上下文；
     * 对于非 battle 节点，直接取当前选中的 message。
     * 若 battle 节点中找不到对应 modelId 的消息，则回退到 selectIndex。
     */
    fun getMessagesForModel(modelId: Uuid): List<UIMessage> {
        return messageNodes.map { node ->
            if (node.isBattleNode) {
                node.messages.firstOrNull { it.modelId == modelId }
                    ?: node.messages.getOrElse(node.selectIndex) { node.messages.first() }
            } else {
                node.messages[node.selectIndex]
            }
        }
    }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        // 预构建 messageId -> nodeIndex 查找表，O(N)，避免嵌套线性扫描
        val msgIdToNodeIndex = HashMap<Uuid, Int>(newNodes.size * 2)
        newNodes.forEachIndexed { nodeIndex, node ->
            node.messages.forEach { msg ->
                msgIdToNodeIndex[msg.id] = nodeIndex
            }
        }

        messages.forEach { message ->
            val existingNodeIndex = msgIdToNodeIndex[message.id]
            if (existingNodeIndex != null) {
                // 已有消息：在正确的 node 中原地更新，不改变 selectIndex
                val node = newNodes[existingNodeIndex]
                val msgIdx = node.messages.indexOfFirst { it.id == message.id }
                val updatedMessages = node.messages.toMutableList()
                updatedMessages[msgIdx] = message
                newNodes[existingNodeIndex] = node.copy(messages = updatedMessages)
            } else {
                // 新消息（如 assistant 回复）：追加为新 node
                val newNode = message.toMessageNode()
                newNodes.add(newNode)
                msgIdToNodeIndex[message.id] = newNodes.lastIndex
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    // [FORK] Battle Mode: 标记该节点是否由 BattleService 创建（多模型并发生成）
    val isBattleNode: Boolean = false,
    @Transient
    val isFavorite: Boolean = false,
    // [FORK] 固定到上下文：设置了上下文长度时，该节点不会被截断
    @Transient
    val isPinned: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
