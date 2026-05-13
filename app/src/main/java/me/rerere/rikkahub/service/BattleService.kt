package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
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

private const val BATTLE_TAG = "BattleService"

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
        contextMessages: List<UIMessage>,
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

        // 为每个模型创建空占位 UIMessage
        val placeholderMessages = battleModels.map {
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
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

        // 并发为每个模型生成回答
        val totalCount = battleModels.size
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        processingStatus.value = "⚔️ Battle: 0/$totalCount 完成"

        coroutineScope {
            val deferreds = battleModels.mapIndexed { index, model ->
                async {
                    val placeholderMsgId = placeholderMessages[index].id
                    runCatching {
                        generateForModel(
                            model = model,
                            contextMessages = contextMessages,
                            conversationId = conversationId,
                            battleNodeId = battleNode.id,
                            placeholderMsgId = placeholderMsgId,
                            memories = memories,
                            tools = tools,
                            getConversation = getConversation,
                            updateConversationState = updateConversationState,
                            processingStatus = processingStatus,
                        )
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.e(BATTLE_TAG, "Model '${model.displayName}' failed: ${e.message}", e)
                        updateConversationState(conversationId) { conv ->
                            updateMessageInBattleNode(conv, battleNode.id, placeholderMsgId) {
                                it.copy(parts = listOf(UIMessagePart.Text("❌ ${model.displayName} 生成失败：${e.message}")))
                            }
                        }
                    }
                    // 无论成功或失败，该模型完成后递增并更新进度
                    val done = completedCount.incrementAndGet()
                    processingStatus.value = "⚔️ Battle: $done/$totalCount 完成"
                }
            }
            deferreds.forEach { it.await() }
        }

        // 全部完成后重置进度文案，避免下次普通提问时残留 Battle loading 文案
        processingStatus.value = null

        // 全部完成后更新时间戳并保存
        val finalConversation = getConversation().copy(updateAt = Instant.now())
        saveConversation(conversationId, finalConversation)
    }

    // --- Battle 节点单 Slot 重试 ---

    /**
     * 对已有 Battle 节点中的某个 slot（message）重新生成，不影响其他模型的回答。
     *
     * @param conversationId 对话 ID
     * @param battleNodeId 目标 battle 节点 ID
     * @param messageId 需要重新生成的 message ID（即当前显示的那条）
     * @param modelId 对应的模型 ID
     * @param contextMessages 上下文消息列表（不含待生成的助手消息）
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
        contextMessages: List<UIMessage>,
        getConversation: () -> Conversation,
        updateConversationState: (Uuid, (Conversation) -> Conversation) -> Unit,
        saveConversation: suspend (Uuid, Conversation) -> Unit,
        processingStatus: MutableStateFlow<String?>,
    ) {
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

        // 清空当前 slot 内容，给用户即时反馈
        updateConversationState(conversationId) { conv ->
            updateMessageInBattleNode(conv, battleNodeId, messageId) { msg ->
                msg.copy(parts = emptyList())
            }
        }

        processingStatus.value = "⚔️ 重试中..."

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
            )
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.e(BATTLE_TAG, "rerunSlot '${model.displayName}' failed: ${e.message}", e)
            updateConversationState(conversationId) { conv ->
                updateMessageInBattleNode(conv, battleNodeId, messageId) {
                    it.copy(parts = listOf(UIMessagePart.Text("❌ ${model.displayName} 重试失败：${e.message}")))
                }
            }
        }

        processingStatus.value = null
        val finalConversation = getConversation().copy(updateAt = Instant.now())
        saveConversation(conversationId, finalConversation)
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
                )
            } else {
                // 超过重试次数或 429，向上抛出让外层显示最终错误
                throw e
            }
        }
    }

    // --- 工具构建 ---

    private fun buildTools(settings: Settings): List<Tool> = buildList {
        if (settings.enableWebSearch) {
            addAll(createSearchTools(settings))
        }
        val assistant = settings.getCurrentAssistant()
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
        mcpManager.getAllAvailableTools().forEach { (serverId, mcpTool) ->
            add(
                Tool(
                    name = "mcp__" + mcpTool.name,
                    description = mcpTool.description ?: "",
                    parameters = { mcpTool.inputSchema },
                    needsApproval = mcpTool.needsApproval,
                    execute = { mcpManager.callTool(serverId, mcpTool.name, it.jsonObject) },
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
