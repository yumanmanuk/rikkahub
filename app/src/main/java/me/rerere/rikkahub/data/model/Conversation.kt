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
    // [FORK] v24 起由 conversationParams.systemPrompt 替代，保留作向后兼容
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // [FORK] 对话专属参数（v24 新增）：覆盖助手级别的 temperature/topP/systemPrompt 等
    val conversationParams: ConversationParams = ConversationParams(),
    // [FORK] 标签 ID
    val conversationTagId: Uuid? = null,
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
     * [FORK] 发送给模型的上下文消息：
     * 被固定(pinned)的节点强制使用固定时锚定的那条分支（pinnedMessageId），
     * 未固定的节点跟随 selectIndex。纯只读取值，绝不修改 messageNodes。
     */
    val contextMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.contextMessage }
        }

    /**
     * [FORK] Battle Mode 独立上下文：
     * 对于 battle 节点，选取该 modelId 生成的那条 message 作为该模型的上下文；
     * 对于非 battle 节点，直接取当前选中的 message。
     * 若 battle 节点中找不到对应 modelId 的消息，则回退到 selectIndex。
     *
     * [FORK] 收藏/固定语义：
     * - 固定(pinned)的 battle 节点：所有模型统一使用固定时锚定的那条回答（pinnedMessageId），
     *   与共享上下文的 contextMessages 口径一致；
     * - 收藏(favorite)的 battle 节点：所有模型统一使用 selectIndex 对应的那条回答，
     *   确保收藏内容跨模型一致传递。
     */
    fun getMessagesForModel(modelId: Uuid): List<UIMessage> {
        return messageNodes.map { node ->
            if (node.isBattleNodeEffective) {
                // 固定的 battle 节点：所有模型都使用固定锚定的那条回答
                if (node.isPinned) {
                    node.contextMessage
                } else if (node.isFavorite) {
                    // 收藏的 battle 节点：所有模型都使用用户选定（收藏）的那条回答
                    node.messages.getOrElse(node.selectIndex) { node.messages.first() }
                } else {
                    node.messages.firstOrNull { it.modelId == modelId }
                        ?: node.messages.getOrElse(node.selectIndex) { node.messages.first() }
                }
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

    /**
     * [FORK] 编辑指定消息后点击发送是否会触发重新生成：
     * 仅当被编辑消息属于“最后一条用户节点”时为 true。
     * ChatVM 的重试门槛与输入框发送按钮图标共用此判断，避免两处逻辑漂移。
     */
    fun editWillRegenerate(messageId: Uuid): Boolean {
        val editedNode = getMessageNodeByMessageId(messageId) ?: return false
        if (editedNode.role != MessageRole.USER) return false
        val lastUserNode = messageNodes.lastOrNull { it.role == MessageRole.USER }
        return editedNode.id == lastUserNode?.id
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
    val favoriteMessageId: Uuid? = null,
    // [FORK] 固定到上下文：锚定到具体某条分支消息，构建上下文时强制使用该分支（不随 selectIndex 变化），
    // 且该 turn 不会被 limitContext 截断。null 表示未固定。
    @Transient
    val pinnedMessageId: Uuid? = null,
) {
    // 节点是否有收藏（只要 favoriteMessageId 不为 null 即为 true）
    val isFavorite: Boolean get() = favoriteMessageId != null
    // [FORK] 节点是否被固定（只要 pinnedMessageId 不为 null 即为 true）
    val isPinned: Boolean get() = pinnedMessageId != null

    /**
     * [FORK] battle 节点判定：节点内存在多条来自不同模型的回答即视为 battle 节点（与 UI 图标口径一致）。
     * 不依赖 isBattleNode 内存标记（@Transient 不持久化，重启/从 DB 重建后丢失），
     * 因此会话内与重启后的判定结果完全一致。
     * 只剩 1 条回答（单模型 battle 或被删到剩 1 条）时退化为普通回答：
     * 不显示 battle 图标，重试按普通节点处理（截断后走 dispatchGeneration）。
     */
    val isBattleNodeEffective: Boolean
        get() = messages.size > 1 && messages.mapNotNull { it.modelId }.toSet().size > 1

    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    /**
     * [FORK] 构建上下文时使用的消息：固定节点取锚定分支，
     * 找不到锚定分支（如分支已被删除）或未固定时回退到 selectIndex。
     */
    val contextMessage: UIMessage
        get() = messages.firstOrNull { it.id == pinnedMessageId } ?: messages[selectIndex]

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
 * [FORK] 统计“固定到上下文”的问答组数：成对固定的 USER+ASSISTANT 记为一组，
 * 孤立固定的单节点（如尚无回答的提问）各记一组。用于 UI 展示，与“条数(消息)”区分。
 */
fun Conversation.pinnedGroupCount(): Int {
    var count = 0
    var i = 0
    while (i < messageNodes.size) {
        val node = messageNodes[i]
        if (node.isPinned) {
            val next = messageNodes.getOrNull(i + 1)
            if (node.role == MessageRole.USER && next != null && next.isPinned && next.role == MessageRole.ASSISTANT) {
                count++
                i += 2
                continue
            }
            count++
        }
        i++
    }
    return count
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
