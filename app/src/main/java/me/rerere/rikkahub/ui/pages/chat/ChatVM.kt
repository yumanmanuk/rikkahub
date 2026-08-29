package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationParams
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.buildFavoritePreview
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    // [FORK] Firebase removed
    // private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    val conversationLoaded: StateFlow<Boolean> = chatService.getConversationInitializedFlow(_conversationId)
    // 会话初始化当前所处阶段（诊断冷启动加载卡顿用）
    fun getConversationInitStage(): String = chatService.getConversationInitStage(_conversationId)
    // 初始滚动位置是否已记录并放行渲染（在列表渲染前写入 LazyListState），
    // 就绪前 UI 保持 loading，避免先渲染顶部再跳底的闪烁；亦兼作“初始滚动已完成”标记
    var chatListReady by mutableStateOf(false)
    // 加载超时兜底标记：数据加载异常缓慢时按旧行为放行渲染，避免用户被永久困在 loading
    var chatListForcedOpen by mutableStateOf(false)

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()



    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    // Battle Mode: 活跃 slot 计数，用于单独重试 slot 时维持全局 loading
    val hasActiveSlots: StateFlow<Boolean> =
        chatService
            .getActiveSlotCountFlow(_conversationId)
            .map { it > 0 }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
            // [FORK] 临时对话：初始化完成后消费 pending 标记，避免竞争条件
            if (chatService.consumePendingTemporary(_conversationId)) {
                chatService.updateConversationState(_conversationId) { it.copy(isTemporary = true) }
            } else {
                // 非临时对话才写 lastConversationId，避免下次启动恢复到已丢弃的对话
                context.writeStringPreference("lastConversationId", _conversationId.toString())
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    // [FORK] 禁用官方更新检查，fork 通过 git 追踪上游
    val updateState = kotlinx.coroutines.flow.MutableStateFlow<UiState<me.rerere.rikkahub.utils.UpdateInfo>>(UiState.Loading)
        // updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        // analytics.logEvent("ai_send_message", null) // [FORK] Firebase removed

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid, regenerate: Boolean = true) {
        if (parts.isEmptyInputMessage()) return
        // analytics.logEvent("ai_edit_message", null) // [FORK] Firebase removed

        viewModelScope.launch {
            // editMessage 为原地替换（保留消息 id 与元数据），
            // 但仍需在编辑前用 messageId 定位节点，确认是否为最后一条用户节点，并记录 node id
            val conversationBefore = conversation.value
            val editedNodeBefore = conversationBefore.getMessageNodeByMessageId(messageId)
            // 重试门槛与输入框“发送/保存”图标共用 Conversation.editWillRegenerate，避免逻辑漂移
            val shouldRegenerate = regenerate && conversationBefore.editWillRegenerate(messageId)
            // 记录节点 id，以便 editMessage 后在更新的状态中重新查找
            val editedNodeId = editedNodeBefore?.id

            chatService.editMessage(_conversationId, messageId, parts)

            if (shouldRegenerate && editedNodeId != null) {
                // editMessage 已完成，从最新 conversation 中通过节点 id 找到该节点
                val updatedNodes = conversation.value.messageNodes
                val updatedNode = updatedNodes.firstOrNull { it.id == editedNodeId }
                if (updatedNode != null) {
                    chatService.regenerateAtMessage(_conversationId, updatedNode.currentMessage)
                }
            }
        }
    }


    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun deleteMessagesBeforeMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessagesBeforeMessage(_conversationId, message.id)
        }
    }

    fun deleteMessagesAfterMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessagesAfterMessage(_conversationId, message.id)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        // analytics.logEvent("ai_regenerate_at_message", null) // [FORK] Firebase removed
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        // analytics.logEvent("ai_tool_approval", null) // [FORK] Firebase removed
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        // analytics.logEvent("ai_tool_answer", null) // [FORK] Firebase removed
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun updateConversationTitle(conversation: Conversation, title: String) {
        viewModelScope.launch {
            if (conversation.id == _conversationId) {
                val updatedConversation = this@ChatVM.conversation.value.copy(title = title)
                chatService.saveConversation(_conversationId, updatedConversation)
            } else {
                val full = conversationRepo.getConversationById(conversation.id) ?: return@launch
                conversationRepo.updateConversation(full.copy(title = title))
            }
        }
    }

    fun deleteConversation(conversation: Conversation): Job {
        return viewModelScope.launch {
            // 先标记已删除，防止并发中的异步任务（如生成标题）在删库后重新将其 insert 回数据库
            chatService.markConversationDeleted(conversation.id)
            conversationRepo.deleteConversation(conversation)
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun updateConversationParams(params: ConversationParams) {
        // 先强制更新内存状态，确保发消息时能读到最新参数
        // saveConversation 在对话为空时会提前返回，不更新内存，导致参数丢失
        chatService.updateConversationState(_conversationId) { it.copy(conversationParams = params) }
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(conversationParams = params)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }



    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentMessage = node.currentMessage
            // 当前显示的 message 是否是被收藏的那条
            val isCurrentMessageFavorited = node.favoriteMessageId == currentMessage.id

            if (isCurrentMessageFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                // 先移除旧收藏（换了一条 message 来收藏时），再添加新收藏
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)

                // 查找前一条用户提问
                val nodes = conversation.value.messageNodes
                val nodeIndex = nodes.indexOfFirst { it.id == node.id }
                val questionPreview = if (nodeIndex > 0) {
                    val prevNode = nodes[nodeIndex - 1]
                    if (prevNode.currentMessage.role == me.rerere.ai.core.MessageRole.USER) {
                        prevNode.currentMessage.buildFavoritePreview(maxLength = 200)
                    } else null
                } else null

                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node,
                        questionPreview = questionPreview,
                        messageId = currentMessage.id,
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(
                                favoriteMessageId = if (isCurrentMessageFavorited) null else currentMessage.id
                            )
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

    // [FORK] 固定到上下文：固定时锚定到当前显示的具体分支（pinnedMessageId）并持久化，
    // 之后切换 selectIndex 不影响上下文中使用的分支。
    // 提问与回答是配套的，固定/取消固定时同步更新配对节点（USER↔ASSISTANT 相邻节点）。
    // 在已固定节点的其他分支上点固定 = 把锚点移到该分支；在已锚定的分支上点固定 = 取消整组固定。
    fun toggleMessagePin(node: MessageNode) {
        viewModelScope.launch {
            // 当前显示的分支已被锚定 → 取消固定；否则（未固定或锚在其他分支）→ 锚定到当前分支
            val unpin = node.pinnedMessageId != null && node.pinnedMessageId == node.messages.getOrNull(node.selectIndex)?.id
            chatService.updateConversationState(_conversationId) { currentConversation ->
                val nodes = currentConversation.messageNodes
                val nodeIndex = nodes.indexOfFirst { it.id == node.id }
                // 安全防护：定位不到目标节点时不做任何修改，避免误操作历史消息
                if (nodeIndex < 0) return@updateConversationState currentConversation
                // 找到配对节点的索引：固定回答时联动前一条提问，固定提问时联动后一条回答
                val pairedIndex = when {
                    nodeIndex > 0 &&
                        nodes[nodeIndex].role == MessageRole.ASSISTANT &&
                        nodes[nodeIndex - 1].role == MessageRole.USER -> nodeIndex - 1
                    nodeIndex + 1 < nodes.size &&
                        nodes[nodeIndex].role == MessageRole.USER &&
                        nodes[nodeIndex + 1].role == MessageRole.ASSISTANT -> nodeIndex + 1
                    else -> -1
                }
                currentConversation.copy(
                    messageNodes = nodes.mapIndexed { index, existingNode ->
                        if (index == nodeIndex || index == pairedIndex) {
                            // 只改 pinnedMessageId，不碰 messages/selectIndex，不存在删改历史消息的可能
                            existingNode.copy(
                                pinnedMessageId = if (unpin) {
                                    null
                                } else {
                                    // 锚定到各自当前显示的分支；空节点（理论不存在）保持不变
                                    existingNode.messages.getOrNull(existingNode.selectIndex)?.id
                                        ?: existingNode.pinnedMessageId
                                }
                            )
                        } else {
                            existingNode
                        }
                    }
                )
            }
            val updatedConversation = conversation.value
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    // [FORK] 对话标签：更新对话所属标签
    fun updateConversationTag(conversationId: Uuid, tagId: Uuid?) {
        viewModelScope.launch {
            conversationRepo.updateConversationTag(conversationId, tagId)
        }
    }

    // [FORK] 对话标签：新增标签到 Settings
    fun addConversationTag(name: String) {
        viewModelScope.launch {
            settingsStore.update { s ->
                val newTag = me.rerere.rikkahub.data.model.Tag(id = Uuid.random(), name = name.trim())
                s.copy(conversationTags = s.conversationTags + newTag)
            }
        }
    }
    // [FORK] 临时对话：将临时对话转为永久保存
    fun saveTemporaryConversation() {
        viewModelScope.launch {
            val current = conversation.value.copy(isTemporary = false)
            chatService.updateConversationState(_conversationId) { current }
            chatService.saveConversation(_conversationId, current)
        }
    }

    // [FORK] 临时对话：在导航前预登记新对话 ID，导航后由 ChatVM.init 消费
    fun prepareTemporaryConversation(): Uuid {
        val newId = Uuid.random()
        chatService.schedulePendingTemporary(newId)
        return newId
    }

}
