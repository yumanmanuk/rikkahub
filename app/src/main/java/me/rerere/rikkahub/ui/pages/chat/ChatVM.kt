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
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
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
// [FORK] 对话专属记忆
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.handleMessageChunk

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
    // [FORK] 对话专属记忆
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val providerManager: ProviderManager,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

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

    // 网络搜索
    val enableWebSearch = settings.map {
        it.enableWebSearch
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
    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
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
            chatService.editMessage(_conversationId, messageId, parts)
            if (regenerate) {
                // 找到编辑后的消息（已经是新版本），触发重新生成
                val editedMessage = conversation.value.messageNodes
                    .firstOrNull { node -> node.messages.any { it.id == messageId } }
                    ?.currentMessage
                if (editedMessage != null) {
                    chatService.regenerateAtMessage(_conversationId, editedMessage)
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

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
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
            val updatedConversation = conversationFull.copy(assistantId = targetAssistantId)
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
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
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
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

    // [FORK] 固定到上下文：切换节点的 isPinned 状态并持久化
    fun toggleMessagePin(node: MessageNode) {
        viewModelScope.launch {
            val newPinnedState = !node.isPinned
            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isPinned = newPinnedState)
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

    // [FORK] 对话专属记忆：用 LLM 自动提炼消息内容并写入对话隔离记忆库
    // onResult(true) = 提炼并保存成功，onResult(false) = 提炼失败
    fun extractMemoryFromMessage(
        message: me.rerere.ai.ui.UIMessage,
        onResult: (success: Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            val conv = conversation.value
            val assistant = settings.getAssistantById(conv.assistantId)
                ?: settings.getCurrentAssistant()
            val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                ?: run { onResult(false); return@launch }
            val provider = model.findProvider(settings.providers)
                ?: run { onResult(false); return@launch }

            val conversationMemoryKey = conv.id.toString()
            val messageText = message.parts
                .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .take(4000)
            if (messageText.isBlank()) {
                onResult(false)
                return@launch
            }

            // 构建提炼请求，要求输出中文
            val prompt = """从以下消息中提取值得记忆的关键信息，用于在本对话后续中参考。要求简洁、客观，**必须使用中文输出**，无论原始消息是何种语言。

消息内容：
$messageText

只输出需要记忆的关键要点，每行一条，不要添加编号或额外说明。"""

            runCatching {
                val providerImpl = providerManager.getProviderByType(provider)
                val requestMessages = listOf(me.rerere.ai.ui.UIMessage.user(prompt))
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
                val extracted = resultMessages.lastOrNull()
                    ?.parts
                    ?.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                    ?.joinToString("\n") { it.text }
                    ?.trim()
                if (!extracted.isNullOrBlank()) {
                    memoryRepository.addMemory(conversationMemoryKey, extracted)
                    onResult(true)
                } else {
                    onResult(false)
                }
            }.onFailure {
                onResult(false)
            }
        }
    }

    // [FORK] 对话专属记忆：直接将指定内容写入对话隔离记忆库
    fun saveMessageAsMemory(content: String) {
        viewModelScope.launch {
            if (content.isBlank()) return@launch
            val conversationMemoryKey = conversation.value.id.toString()
            memoryRepository.addMemory(conversationMemoryKey, content.trim())
        }
    }

    // [FORK] 对话专属记忆：当前对话的记忆列表（响应式）
    val conversationMemories = conversation
        .flatMapLatest { conv ->
            memoryRepository.getMemoriesOfAssistantFlow(conv.id.toString())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // [FORK] 对话专属记忆：删除指定记忆条目
    fun deleteConversationMemory(memory: me.rerere.rikkahub.data.model.AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(memory.id)
        }
    }

    // [FORK] 对话专属记忆：更新指定记忆条目内容
    fun updateConversationMemory(memory: me.rerere.rikkahub.data.model.AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.updateContent(memory.id, memory.content)
        }
    }

}
