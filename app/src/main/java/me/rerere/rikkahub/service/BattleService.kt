package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.MemoryRepository
import java.time.Instant
import kotlin.uuid.Uuid
import me.rerere.common.android.Logging

private const val BATTLE_TAG = "BattleService"

/**
 * [FORK] 收集对话中所有固定到上下文节点的「整 turn」消息 id。
 *
 * Turn 范围:
 *  - 节点内:该节点所有 messages(覆盖 USER 提问 + ASSISTANT 回答/分支)
 *  - Battle 节点 / 普通 ASSISTANT 节点:+ 前一个 USER 节点(防止孤立回答)
 *  - USER 节点:+ 下一个 ASSISTANT 节点(防止孤立提问)
 *
 * [FORK] 收藏(favorite)仅作为书签，不再进入上下文；只有固定(pinned)会被保护。
 * 这些消息在 GenerationHandler.limitContext 中优先占用总预算（固定由用户手动 pin/unpin 控制，不自动砍）。
 */
internal fun Conversation.collectProtectedMessageIds(): Set<Uuid> = buildSet {
    messageNodes.forEachIndexed { index, node ->
        if (!node.isPinned) return@forEachIndexed
        // 节点内所有 messages
        node.messages.forEach { add(it.id) }
        if (index > 0) {
            val prevNode = messageNodes[index - 1]
            // Battle 节点:USER 提问在前一个节点,把前一个节点也纳入 turn
            // 普通 ASSISTANT 节点:同理把前一个 USER 节点也一并保护,防止孤立回答
            if (node.isBattleNode || node.role == MessageRole.ASSISTANT) {
                prevNode.messages.forEach { add(it.id) }
            }
        }
        // USER 节点被固定:把下一个 ASSISTANT 节点也一并保护,防止孤立提问
        if (node.role == MessageRole.USER && index < messageNodes.lastIndex) {
            messageNodes[index + 1].messages.forEach { add(it.id) }
        }
    }
}

/**
 * [FORK] Battle Mode Service
 *
 * 负责并发调用多个 AI 模型，各自生成回答并存储到同一个 MessageNode 的不同
 * messages 中，用户通过 <> 箭头切换查看不同模型的回答。
 */
class BattleService(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val localTools: LocalTools,
    private val skillManager: SkillManager,
    private val mcpManager: McpManager,
) {
    // Per-slot Job 追踪: key = "${conversationId}_${messageId}"
    private val slotJobs = ConcurrentHashMap<String, Job>()
    // Battle 专用作用域: key = conversationId，SupervisorJob 防止单个 slot 失败影响其他
    private val battleScopes = ConcurrentHashMap<Uuid, CoroutineScope>()
    // 活跃 slot 计数: key = conversationId
    private val _activeSlotCounts = MutableStateFlow<Map<Uuid, Int>>(emptyMap())
    val activeSlotCounts: StateFlow<Map<Uuid, Int>> get() = _activeSlotCounts.asStateFlow()

    private fun incrementSlotCount(conversationId: Uuid) {
        _activeSlotCounts.update { map ->
            map + (conversationId to (map[conversationId] ?: 0) + 1)
        }
    }

    private fun decrementSlotCount(conversationId: Uuid) {
        _activeSlotCounts.update { map ->
            val newCount = (map[conversationId] ?: 1) - 1
            if (newCount <= 0) {
                // 所有 slot 已完成，清理临时作用域（runBattle 的 scope 由 runBattle 自行清理）
                battleScopes.remove(conversationId)?.cancel()
                map - conversationId
            } else {
                map + (conversationId to newCount)
            }
        }
    }
    /**
     * 执行 Battle Mode 生成。
     *
     * @param conversationId 对话 ID
     * @param contextMessages 发送给模型的上下文消息列表（不含待生成的助手消息）
     * @param battleModelIds 参战模型 ID 列表
     * @param getConversation 获取当前对话的回调（每次调用返回最新状态）
     * @param updateConversationState 更新对话状态的回调
     * @param saveConversation 持久化对话的回调
     * @param processingStatus 进度状态 flow
     */
    suspend fun runBattle(
        conversationId: Uuid,
        conversation: Conversation,
        battleModelIds: List<Uuid>,
        getConversation: () -> Conversation,
        updateConversationState: (Uuid, (Conversation) -> Conversation) -> Unit,
        saveConversation: suspend (Uuid, Conversation) -> Unit,
        processingStatus: MutableStateFlow<String?>,
    ) {
        val settings = settingsStore.settingsFlow.first()

        // 解析出有效的模型实例
        val battleModels = battleModelIds.mapNotNull { settings.providers.findModelById(it) }
        if (battleModels.isEmpty()) {
            Log.w(BATTLE_TAG, "runBattle: no valid battle models found")
            return
        }

        val assistant = settings.getCurrentAssistant()
        val memories: List<AssistantMemory> = if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(settings.assistantId.toString())
        }
        val tools = buildTools(settings)

        // 为每个模型创建空占位 UIMessage，并预先写入 modelId 以便失败时仍可单独重试
        val placeholderMessages = battleModels.map { model ->
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
                modelId = model.id,
            )
        }

        // 创建 battle MessageNode，默认显示第一条（selectIndex = 0）
        val battleNode = MessageNode(
            messages = placeholderMessages,
            selectIndex = 0,
            // [FORK] Battle Mode: 标记为 battle 节点，用于 UI 区分
            isBattleNode = true,
        )

        // 将 battle 节点追加到对话并保存，让 UI 立即展示（带 "<>" 箭头）
        val initialConversation = getConversation()
        val conversationWithBattle = initialConversation.copy(
            messageNodes = initialConversation.messageNodes + battleNode,
        )
        saveConversation(conversationId, conversationWithBattle)

        val independentContext = conversation.conversationParams.battleIndependentContext
        Log.d(BATTLE_TAG, "runBattle: independentContext=$independentContext, models=${battleModels.map { it.displayName }}")

        // 创建 Battle 专用作用域（SupervisorJob 防止单个 slot 失败影响其他）
        val battleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        battleScopes[conversationId] = battleScope

        // 并发为每个模型生成回答（per-slot 独立 Job，可单独取消/重试）
        val totalCount = battleModels.size
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        processingStatus.value = "⚔️ Battle: 0/$totalCount 完成"

        battleModels.forEachIndexed { index, model ->
            val placeholderMsgId = placeholderMessages[index].id
            val key = "${conversationId}_${placeholderMsgId}"

            val job = battleScope.launch {
                incrementSlotCount(conversationId)
                try {
                    // [FORK] Battle Mode: 根据开关决定上下文取法
                    val modelContextMessages = if (independentContext) {
                        conversation.getMessagesForModel(model.id).also {
                            Log.d(BATTLE_TAG, "runBattle [${model.displayName}]: independentContext, msgs=${it.size}")
                        }
                    } else {
                        conversation.currentMessages.also {
                            Log.d(BATTLE_TAG, "runBattle [${model.displayName}]: sharedContext, msgs=${it.size}")
                        }
                    }
                    // [FORK] 收藏仅为书签，只有固定进入上下文并优先占用总预算
                    val protectedMsgIds = conversation.collectProtectedMessageIds()
                    runCatching {
                        generateForModel(
                            model = model,
                            contextMessages = modelContextMessages,
                            conversationId = conversationId,
                            battleNodeId = battleNode.id,
                            placeholderMsgId = placeholderMsgId,
                            memories = memories,
                            tools = tools,
                            getConversation = getConversation,
                            updateConversationState = updateConversationState,
                            processingStatus = processingStatus,
                            // [FORK] Battle Mode：每个模型使用独立的思考深度
                            reasoningLevel = conversation.conversationParams.battleModelReasoningLevels[model.id]
                                ?: assistant.reasoningLevel,
                            protectedMessageIds = protectedMsgIds,
                        )
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.e(BATTLE_TAG, "Model '${model.displayName}' failed: ${e.message}", e)
                        Logging.logError(
                            tag = BATTLE_TAG,
                            title = "Battle: ${model.displayName} 生成失败",
                            message = e.message ?: "Unknown error",
                            throwable = e,
                        )
                        updateConversationState(conversationId) { conv ->
                            updateMessageInBattleNode(conv, battleNode.id, placeholderMsgId) {
                                // 保留 modelId，确保失败后单独重试时能识别目标模型
                                it.copy(
                                    modelId = model.id,
                                    parts = listOf(UIMessagePart.Text("❌ ${model.displayName} 生成失败：${e.message}")),
                                )
                            }
                        }
                    }
                    // 无论成功或失败，该模型完成后递增并更新进度
                    val done = completedCount.incrementAndGet()
                    processingStatus.value = "⚔️ Battle: $done/$totalCount 完成"
                } finally {
                    decrementSlotCount(conversationId)
                }
            }
            slotJobs[key] = job
        }

        // 等待所有 slot 完成
        slotJobs.entries
            .filter { it.key.startsWith("${conversationId}_") }
            .forEach { runCatching { it.value.join() } }

        // 全部完成后重置进度文案，避免下次普通提问时残留 Battle loading 文案
        processingStatus.value = null

        // 清理 Battle 作用域
        battleScopes.remove(conversationId)?.cancel()

        // 全部完成后更新时间戳并保存
        val finalConversation = getConversation().copy(updateAt = Instant.now())
        saveConversation(conversationId, finalConversation)
    }

    // --- Battle 节点单 Slot 重试 ---

    /**
     * 对已有 Battle 节点中的某个 slot（message）重新生成，不影响其他模型的回答。
     * 只取消目标 slot 的 Job，在 Battle 作用域内重新启动该 slot 的生成。
     *
     * @param conversationId 对话 ID
     * @param battleNodeId 目标 battle 节点 ID
     * @param messageId 需要重新生成的 message ID（即当前显示的那条）
     * @param modelId 对应的模型 ID
     * @param conversation battle 节点之前的对话（不含待生成的助手节点），用于按模型取上下文
     * @param getConversation 获取当前对话的回调
     * @param updateConversationState 更新对话状态的回调
     * @param saveConversation 持久化对话的回调
     * @param processingStatus 进度状态 flow
     */
    suspend fun rerunSlot(
        conversationId: Uuid,
        battleNodeId: Uuid,
        messageId: Uuid,
        modelId: Uuid,
        conversation: Conversation,
        getConversation: () -> Conversation,
        updateConversationState: (Uuid, (Conversation) -> Conversation) -> Unit,
        saveConversation: suspend (Uuid, Conversation) -> Unit,
        processingStatus: MutableStateFlow<String?>,
    ) {
        val key = "${conversationId}_${messageId}"

        // 只取消该 slot 的 Job（先捕获旧引用，避免竞态）
        val oldJob = slotJobs[key]
        oldJob?.cancel()
        runCatching { oldJob?.join() }

        val settings = settingsStore.settingsFlow.first()
        val model = settings.providers.findModelById(modelId)
        if (model == null) {
            Log.w(BATTLE_TAG, "rerunSlot: model not found for id=$modelId")
            return
        }

        val assistant = settings.getCurrentAssistant()
        val memories: List<AssistantMemory> = if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(settings.assistantId.toString())
        }
        val tools = buildTools(settings)

        // 根据开关决定重试时使用哪种上下文
        val independentContext = conversation.conversationParams.battleIndependentContext
        val contextMessages = if (independentContext) {
            conversation.getMessagesForModel(model.id).also {
                Log.d(BATTLE_TAG, "rerunSlot [${model.displayName}]: independentContext, msgs=${it.size}")
            }
        } else {
            conversation.currentMessages.also {
                Log.d(BATTLE_TAG, "rerunSlot [${model.displayName}]: sharedContext, msgs=${it.size}")
            }
        }

        // [FORK] 收藏仅为书签，只有固定进入上下文并优先占用总预算
        val protectedMsgIds = conversation.collectProtectedMessageIds()

        // 清空当前 slot 内容，给用户即时反馈
        updateConversationState(conversationId) { conv ->
            updateMessageInBattleNode(conv, battleNodeId, messageId) { msg ->
                msg.copy(parts = emptyList(), finishedAt = null)
            }
        }

        processingStatus.value = "⚔️ 重试中..."

        // 复用 Battle 作用域（若 Battle 仍在）或创建临时作用域（若 Battle 已结束）
        val battleScope = battleScopes[conversationId]
        val scope = if (battleScope != null) {
            battleScope
        } else {
            // Battle 已结束，创建临时作用域
            CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
                battleScopes[conversationId] = it
            }
        }

        val job = scope.launch {
            incrementSlotCount(conversationId)
            try {
                runCatching {
                    generateForModel(
                        model = model,
                        contextMessages = contextMessages,
                        conversationId = conversationId,
                        battleNodeId = battleNodeId,
                        placeholderMsgId = messageId,
                        memories = memories,
                        tools = tools,
                        getConversation = getConversation,
                        updateConversationState = updateConversationState,
                        processingStatus = processingStatus,
                        // [FORK] Battle Mode：重试时同样应用该模型独立的思考深度
                        reasoningLevel = conversation.conversationParams.battleModelReasoningLevels[modelId]
                            ?: assistant.reasoningLevel,
                        protectedMessageIds = protectedMsgIds,
                    )
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.e(BATTLE_TAG, "rerunSlot '${model.displayName}' failed: ${e.message}", e)
                    Logging.logError(
                        tag = BATTLE_TAG,
                        title = "Battle 重试: ${model.displayName} 生成失败",
                        message = e.message ?: "Unknown error",
                        throwable = e,
                    )
                    updateConversationState(conversationId) { conv ->
                        updateMessageInBattleNode(conv, battleNodeId, messageId) {
                            // 保留 modelId，确保失败后再次单独重试时能识别目标模型
                            it.copy(
                                modelId = model.id,
                                parts = listOf(UIMessagePart.Text("❌ ${model.displayName} 重试失败：${e.message}")),
                            )
                        }
                    }
                }
                processingStatus.value = null
                val finalConversation = getConversation().copy(updateAt = Instant.now())
                saveConversation(conversationId, finalConversation)
            } finally {
                decrementSlotCount(conversationId)
            }
        }
        slotJobs[key] = job
    }

    /**
     * 取消指定对话的所有 slot Job（供"停止生成"调用）。
     */
    fun cancelAllSlots(conversationId: Uuid) {
        slotJobs.entries
            .filter { it.key.startsWith("${conversationId}_") }
            .forEach { it.value.cancel() }
        battleScopes.remove(conversationId)?.cancel()
    }

    // --- 单模型生成 ---

    internal suspend fun generateForModel(
        model: Model,
        contextMessages: List<UIMessage>,
        conversationId: Uuid,
        battleNodeId: Uuid,
        placeholderMsgId: Uuid,
        memories: List<AssistantMemory>,
        tools: List<Tool>,
        getConversation: () -> Conversation,
        updateConversationState: (Uuid, (Conversation) -> Conversation) -> Unit,
        processingStatus: MutableStateFlow<String?>,
        // [FORK] Battle Mode 独立重试
        retryCount: Int = 0,
        // [FORK] Battle Mode：该模型独立的思考深度
        reasoningLevel: ReasoningLevel,
        // [FORK] 固定到上下文的消息 id：在 limitContext 中优先占用总预算(收藏不再进入上下文)
        protectedMessageIds: Set<Uuid> = emptySet(),
    ) {
        val maxRetries = 3
        val retryDelayMs = 2000L

        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getCurrentAssistant()
        val conversation = getConversation()

        runCatching {
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = contextMessages,
                assistant = assistant,
                conversationParams = conversation.conversationParams,
                memories = memories,
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                tools = tools,
                processingStatus = processingStatus,
                // [FORK] Battle Mode：传入该模型独立的思考深度
                reasoningLevelOverride = reasoningLevel,
                protectedMessageIds = protectedMessageIds,
            ).onCompletion {
                // 生成结束后确保 reasoning 状态归位
                updateConversationState(conversationId) { conv ->
                    updateMessageInBattleNode(conv, battleNodeId, placeholderMsgId) { msg ->
                        msg.finishReasoning()
                    }
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val latestAiMsg = chunk.messages.lastOrNull() ?: return@collect
                        if (latestAiMsg.role != MessageRole.ASSISTANT) return@collect
                        val updatedMsg = latestAiMsg.copy(id = placeholderMsgId)
                        updateConversationState(conversationId) { conv ->
                            updateMessageInBattleNode(conv, battleNodeId, placeholderMsgId) { updatedMsg }
                        }
                    }
                }
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            val is429 = e.message?.contains("429") == true
            if (!is429 && retryCount < maxRetries) {
                Log.i(BATTLE_TAG, "[${model.displayName}] auto-retry ${retryCount + 1}/$maxRetries: ${e.message}")
                // 在占位消息中显示重试状态
                updateConversationState(conversationId) { conv ->
                    updateMessageInBattleNode(conv, battleNodeId, placeholderMsgId) { msg ->
                        msg.copy(parts = listOf(UIMessagePart.Text("⏳ ${model.displayName} 重试中 (${retryCount + 1}/$maxRetries)...")))
                    }
                }
                delay(retryDelayMs)
                generateForModel(
                    model = model,
                    contextMessages = contextMessages,
                    conversationId = conversationId,
                    battleNodeId = battleNodeId,
                    placeholderMsgId = placeholderMsgId,
                    memories = memories,
                    tools = tools,
                    getConversation = getConversation,
                    updateConversationState = updateConversationState,
                    processingStatus = processingStatus,
                    retryCount = retryCount + 1,
                    // [FORK] Battle Mode：重试时保持相同的思考深度
                    reasoningLevel = reasoningLevel,
                )
            } else {
                // 超过重试次数或 429，向上抛出让外层显示最终错误
                throw e
            }
        }
    }

    // --- 工具构建 ---

    private fun buildTools(settings: Settings): List<Tool> = buildList {
        val assistant = settings.getCurrentAssistant()
        if (assistant.enableWebSearch) {
            addAll(createSearchTools(settings))
        }
        addAll(localTools.getTools(assistant.localTools))
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                )
            )
        }
        mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            )
        }
    }

    // --- 状态更新工具 ---

    /**
     * 在 battle 节点中，按 messageId 找到对应消息并用 transform 替换，返回更新后的对话。
     */
    private fun updateMessageInBattleNode(
        conversation: Conversation,
        battleNodeId: Uuid,
        messageId: Uuid,
        transform: (UIMessage) -> UIMessage,
    ): Conversation {
        return conversation.copy(
            messageNodes = conversation.messageNodes.map { node ->
                if (node.id != battleNodeId) return@map node
                node.copy(
                    messages = node.messages.map { msg ->
                        if (msg.id == messageId) transform(msg) else msg
                    }
                )
            }
        )
    }

    companion object {
        private val inputTransformers = listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
        )

        private val outputTransformers = listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )
    }
}
