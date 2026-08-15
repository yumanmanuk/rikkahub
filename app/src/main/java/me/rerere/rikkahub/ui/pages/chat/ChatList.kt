package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Filter
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.Pin02
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.pinnedGroupCount
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.Tooltip

import me.rerere.rikkahub.ui.theme.ChatFontProvider
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.wordCount
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"

/**
 * 从缩略页跳转到指定消息的请求。
 * nonce 用于保证同一 index 重复点击时 LaunchedEffect 也能重新触发。
 */
data class JumpRequest(val index: Int, val nonce: Int)

// [FORK] 预览页筛选模式：全部 → 收藏 → 固定 循环切换
private enum class PreviewFilter { ALL, FAVORITE, PINNED }

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    showJumper: Boolean = false,
    onDismissJumper: () -> Unit = {},
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onDeleteBeforeMessage: (UIMessage) -> Unit = {},
    onDeleteAfterMessage: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onJumpToMessage: (Int) -> Unit = {},
    // 缩略页跳转请求：非 null 时 ChatListNormal 会原子地禁用自动贴底再滚动
    jumpRequest: JumpRequest? = null,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    // [FORK] 固定到上下文：仅当有上下文限制时才显示此选项
    onTogglePin: ((MessageNode) -> Unit)? = null,
) {
    // 提升到 AnimatedContent 外部，保证预览模式滚动位置在切换时不丢失
    val previewListState = rememberLazyListState()

    // [FORK] 筛选模式同样提升：退出预览再进入时保持上次选择（按会话分开记忆）
    var previewFilter by rememberSaveable(conversation.id) { mutableStateOf(PreviewFilter.ALL) }

    AnimatedContent(
        targetState = previewMode,
        label = "ChatListMode",
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
        }
    ) { target ->
        if (target) {
            ChatListPreview(
                innerPadding = innerPadding,
                conversation = conversation,
                settings = settings,
                hazeState = hazeState,
                onJumpToMessage = onJumpToMessage,
                animatedVisibilityScope = this@AnimatedContent,
                listState = previewListState,
                previewFilter = previewFilter,
                onPreviewFilterChange = { previewFilter = it },
            )
        } else {
            ChatListNormal(
                innerPadding = innerPadding,
                conversation = conversation,
                state = state,
                loading = loading,
                processingStatus = processingStatus,
                settings = settings,
                hazeState = hazeState,
                showJumper = showJumper,
                onDismissJumper = onDismissJumper,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onForkMessage = onForkMessage,
                onDelete = onDelete,
                onDeleteBeforeMessage = onDeleteBeforeMessage,
                onDeleteAfterMessage = onDeleteAfterMessage,
                onUpdateMessage = onUpdateMessage,
                onClickSuggestion = onClickSuggestion,
                onTranslate = onTranslate,
                onClearTranslation = onClearTranslation,
                animatedVisibilityScope = this@AnimatedContent,
                jumpRequest = jumpRequest,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToggleFavorite = onToggleFavorite,
                onConversationSystemPromptChange = onConversationSystemPromptChange,
                // [FORK] 固定到上下文
                onTogglePin = onTogglePin,
            )
        }
    }
}

@Composable
private fun ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    settings: Settings,
    hazeState: HazeState,
    showJumper: Boolean = false,
    onDismissJumper: () -> Unit = {},
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onDeleteBeforeMessage: (UIMessage) -> Unit,
    onDeleteAfterMessage: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    // 缩略页跳转请求，非 null 时原子地禁用自动贴底再跳转
    jumpRequest: JumpRequest? = null,
    // [FORK] 固定到上下文
    onTogglePin: ((MessageNode) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)

    var isUserDragging by remember { mutableStateOf(false) }
    // 如果入场时就带有跳转请求，直接以 false 初始化，封堕贴底 LaunchedEffect 在跳转请求处理之前抓先触发
    var shouldAutoFollow by remember { mutableStateOf(jumpRequest == null) }
    val conversationUpdated by rememberUpdatedState(conversation)
    val density = LocalDensity.current
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity

    // 处理缩略页跳转请求：禁用自动贴底，然后瞬间跳转到目标位置（不带动画，和之前行为一致）
    LaunchedEffect(jumpRequest) {
        jumpRequest?.let { req ->
            shouldAutoFollow = false
            state.scrollToItem(req.index)
        }
    }

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        val inputBarHeight = with(density) { innerPadding.calculateBottomPadding().toPx() }
        val lastPos = lastItem.offset + lastItem.size
        val inputPos = (state.layoutInfo.viewportEndOffset - inputBarHeight.roundToInt())
        return lastPos <= inputPos - 8
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }


    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(conversation)
    var showSizeWarningDialog by rememberSaveable(conversation.id) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    val assistant = remember(settings.assistants, conversation.assistantId) {
        settings.getAssistantById(conversation.assistantId)
    }
    val modelById = remember(settings.providers) {
        settings.providers
            .flatMap { it.models }
            .associateBy { it.id }
    }
    val lastMessageIndex = conversation.messageNodes.lastIndex
    // 分别记录 USER 和 ASSISTANT 角色的最后一条 node 的 index，用于控制重试按钮显示
    val lastUserMessageIndex = conversation.messageNodes.indexOfLast {
        it.role == me.rerere.ai.core.MessageRole.USER
    }
    val lastAssistantMessageIndex = conversation.messageNodes.indexOfLast {
        it.role == me.rerere.ai.core.MessageRole.ASSISTANT
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        LaunchedEffect(state) {
            state.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> isUserDragging = true
                    is DragInteraction.Stop, is DragInteraction.Cancel -> isUserDragging = false
                }
            }
        }

        if (settings.displaySetting.enableAutoScroll) {
            LaunchedEffect(state, isUserDragging) {
                snapshotFlow {
                    Pair(isUserDragging, state.canScrollForward)
                }.collect { (dragging, canScrollForward) ->
                    if (dragging && canScrollForward) {
                        shouldAutoFollow = false
                    } else if (!canScrollForward) {
                        shouldAutoFollow = true
                    }
                }
            }

            LaunchedEffect(state, isUserDragging, shouldAutoFollow, loadingState, conversation) {
                snapshotFlow {
                    Triple(
                        state.layoutInfo.totalItemsCount,
                        conversation.messageNodes.lastOrNull()?.currentMessage?.toText()?.length ?: 0,
                        state.isScrollInProgress,
                    )
                }.collect { (totalItems, _, inProgress) ->
                    if (!isUserDragging && !inProgress && loadingState && shouldAutoFollow && totalItems > 0) {
                        state.requestScrollToItem(totalItems - 1)
                    }
                }
            }
        }


        // 拦截 BringIntoView 请求：当 ChatInput 的 TextField 持有焦点时，
        // Compose 会隐式触发 BringIntoView 试图把焦点组件拉入可视区，
        // 这会导致 LazyColumn 在用户上滑时突然闪跳到底部。
        // 通过一个空实现的 BringIntoViewResponder 来吞没这些请求。
        @Suppress("DEPRECATION")
        val noopBringIntoViewResponder = remember {
            object : androidx.compose.foundation.relocation.BringIntoViewResponder {
                override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
                override suspend fun bringChildIntoView(localRect: () -> Rect?) { /* 吞没 */ }
            }
        }

        ChatFontProvider(displaySetting = settings.displaySetting) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp) + PaddingValues(bottom = 8.dp + innerPadding.calculateBottomPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
            itemsIndexed(
                items = conversation.messageNodes,
                key = { index, item -> item.id },
            ) { index, node ->
                Column {
                    ListSelectableItem(
                        key = node.id,
                        onSelectChange = {
                            if (!selectedItems.contains(node.id)) {
                                selectedItems.add(node.id)
                            } else {
                                selectedItems.remove(node.id)
                            }
                        },
                        selectedKeys = selectedItems,
                        enabled = selecting,
                    ) {
                        ChatMessage(
                            node = node,
                            model = node.currentMessage.modelId?.let(modelById::get),
                            assistant = assistant,
                            loading = loading && index == lastMessageIndex,
                            onRegenerate = {
                                onRegenerate(node.currentMessage)
                            },
                            onEdit = {
                                onEdit(node.currentMessage)
                            },
                            onFork = {
                                onForkMessage(node.currentMessage)
                            },
                            onDelete = {
                                onDelete(node.currentMessage)
                            },
                            onDeleteBefore = {
                                onDeleteBeforeMessage(node.currentMessage)
                            },
                            onDeleteAfter = {
                                onDeleteAfterMessage(node.currentMessage)
                            },
                            onShare = {
                                selecting = true
                                selectedItems.clear()
                                val nodeIndex = conversation.messageNodes.indexOf(node)
                                val role = node.currentMessage.role
                                if (role == me.rerere.ai.core.MessageRole.USER) {
                                    // 选中当前 USER 消息 + 紧跟的 ASSISTANT 消息（若有）
                                    selectedItems.add(node.id)
                                    val next = conversation.messageNodes.getOrNull(nodeIndex + 1)
                                    if (next != null && next.currentMessage.role == me.rerere.ai.core.MessageRole.ASSISTANT) {
                                        selectedItems.add(next.id)
                                    }
                                } else {
                                    // 选中前面的 USER 消息（若有）+ 当前 ASSISTANT 消息
                                    val prev = conversation.messageNodes.getOrNull(nodeIndex - 1)
                                    if (prev != null && prev.currentMessage.role == me.rerere.ai.core.MessageRole.USER) {
                                        selectedItems.add(prev.id)
                                    }
                                    selectedItems.add(node.id)
                                }
                            },
                            onUpdate = {
                                onUpdateMessage(it)
                            },
                            isFavorite = node.favoriteMessageId == node.currentMessage.id,
                            onToggleFavorite = {
                                onToggleFavorite?.invoke(node)
                            },
                            onTranslate = onTranslate,
                            onClearTranslation = onClearTranslation,
                            onToolApproval = onToolApproval,
                            onToolAnswer = onToolAnswer,
                            lastMessage = when (node.role) {
                                me.rerere.ai.core.MessageRole.USER -> index == lastUserMessageIndex
                                // 只有当该回答确实是最后一条提问的回答（其后没有新的提问）时才允许重试，
                                // 避免用户发起新提问但模型未响应/被中断时，上一条问答的回答仍显示重试按钮
                                else -> index == lastAssistantMessageIndex && lastAssistantMessageIndex > lastUserMessageIndex
                            },
                            onScrollToQuestion = if (node.currentMessage.role == me.rerere.ai.core.MessageRole.ASSISTANT && index > 0) {
                                val targetIndex = index - 1
                                {
                                    // 点击向上跳转时，暂停自动贴底，防止跳转到问题后又被拉回底部
                                    shouldAutoFollow = false
                                    scope.launch { state.animateScrollToItem(targetIndex) }
                                }
                            } else null,
                            onScrollToAnswer = if (
                                node.currentMessage.role == me.rerere.ai.core.MessageRole.USER &&
                                conversation.messageNodes.getOrNull(index + 1)?.currentMessage?.role == me.rerere.ai.core.MessageRole.ASSISTANT
                            ) {
                                val targetIndex = index + 1
                                {
                                    // 点击向下跳转时，暂停自动贴底，防止跳转到回答后又被拉回底部
                                    shouldAutoFollow = false
                                    scope.launch {
                                        // 记录当前位置，测量后可无痕还原
                                        val savedIndex = state.firstVisibleItemIndex
                                        val savedOffset = state.firstVisibleItemScrollOffset
                                        // 先瞬时定位到回答完成组合与测量；同一协程内立即还原，
                                        // 中间不让出帧，这一步不会被真正绘制出来
                                        state.scrollToItem(targetIndex)
                                        val info = state.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == targetIndex }
                                        val scrollOffset = if (info != null) {
                                            val inputBarHeightPx = with(density) {
                                                innerPadding.calculateBottomPadding().toPx()
                                            }
                                            val viewportBottom =
                                                state.layoutInfo.viewportEndOffset - inputBarHeightPx
                                            val itemBottom = info.offset + info.size
                                            // 回答比可视区高时需要额外下滚的距离；否则为 0（回答顶部对齐即可）
                                            (itemBottom - viewportBottom).coerceAtLeast(0f).roundToInt()
                                        } else 0
                                        // 还原到原始位置，让动画从当前位置出发
                                        state.scrollToItem(savedIndex, savedOffset)
                                        // 单段平滑动画：带 scrollOffset 直接把回答底部(操作按钮区)带到输入栏上方，
                                        // 一气呵成、无“先到顶再到底”的顿挫
                                        state.animateScrollToItem(targetIndex, scrollOffset)
                                    }
                                }
                            } else null,
                            // [FORK] 固定到上下文：仅当前显示的分支正是锚定分支时才显示固定标识/高亮
                            isPinned = node.pinnedMessageId != null && node.pinnedMessageId == node.currentMessage.id,
                            onTogglePin = if (onTogglePin != null) {
                                { onTogglePin(node) }
                            } else null,
                        )
                    }
                }
            }

            if (!loading && assistant?.allowConversationSystemPrompt == true && onConversationSystemPromptChange != null) {
                item(key = "ConversationSystemPrompt") {
                    ConversationSystemPromptButton(
                        value = conversation.conversationParams.systemPrompt,
                        onSystemPromptChange = onConversationSystemPromptChange,
                    )
                }
            }

            if (loading) {
                item(LoadingIndicatorKey) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(28.dp)
                        )
                        AnimatedVisibility(
                            visible = processingStatus != null,
                        ) {
                            Text(
                                text = processingStatus ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 为了能正确滚动到这
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误消息卡片
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )


            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 预先计算所有 USER 消息在列表中的 index（用于按提问跳转）
            val userMessageIndices = remember(conversation.messageNodes) {
                conversation.messageNodes.mapIndexedNotNull { index, node ->
                    if (node.currentMessage.role == me.rerere.ai.core.MessageRole.USER) index else null
                }
            }

            // 消息快速跳转（由按钮触发，不再依赖滚动状态）
            if (!captureProgress) {
                MessageJumper(
                    show = showJumper && settings.displaySetting.showMessageJumper,
                    onLeft = settings.displaySetting.messageJumperOnLeft,
                    scope = scope,
                    state = state,
                    userMessageIndices = userMessageIndices,
                    onDismissJumper = onDismissJumper,
                    onDisableAutoFollow = { shouldAutoFollow = false },
                )
            }

            // Suggestion
            if (conversation.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    conversation = conversation,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color,
    onHighlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本，使用语义配对色保证深浅色主题下都可读
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = onHighlightColor
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@Composable
private fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    hazeState: HazeState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    listState: LazyListState,
    previewFilter: PreviewFilter,
    onPreviewFilterChange: (PreviewFilter) -> Unit,
    onJumpToMessage: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showOnlyFavorites by remember { mutableStateOf(false) }

    // 统计数据：对话轮次、提问总字数、回答总字数
    val conversationStats = remember(conversation.messageNodes) {
        var rounds = 0
        var questionChars = 0
        var answerChars = 0
        conversation.messageNodes.forEach { node ->
            val msg = node.currentMessage
            when (msg.role) {
                me.rerere.ai.core.MessageRole.USER -> {
                    rounds++
                    questionChars += msg.toText().wordCount()
                }
                me.rerere.ai.core.MessageRole.ASSISTANT -> {
                    answerChars += msg.toText().wordCount()
                }
                else -> {}
            }
        }
        Triple(rounds, questionChars, answerChars)
    }

    // 统计数据：对话轮次、提问总字数、回答总字数
    val conversationStats = remember(conversation.messageNodes) {
        var rounds = 0
        var questionChars = 0
        var answerChars = 0
        conversation.messageNodes.forEach { node ->
            val msg = node.currentMessage
            when (msg.role) {
                me.rerere.ai.core.MessageRole.USER -> {
                    rounds++
                    questionChars += msg.toText().wordCount()
                }
                me.rerere.ai.core.MessageRole.ASSISTANT -> {
                    answerChars += msg.toText().wordCount()
                }
                else -> {}
            }
        }
        Triple(rounds, questionChars, answerChars)
    }

    // 统计收藏和固定的“组”数（一问一答算一组，口径一致）
    val favoriteAndPinnedStats = remember(conversation.messageNodes) {
        var favoriteCount = 0
        conversation.messageNodes.forEach { node ->
            if (node.isFavorite) favoriteCount++
        }
        Pair(favoriteCount, conversation.pinnedGroupCount())
    }

    // 过滤消息，同时保留原始 index 避免后续 O(n) indexOf 查找
    val filteredMessages = remember(conversation.messageNodes, searchQuery, previewFilter) {
        var messages = conversation.messageNodes.mapIndexed { index, node -> index to node }

        // 先按搜索词过滤
        if (searchQuery.isNotBlank()) {
            messages = messages.filter { (_, node) -> node.currentMessage.toText().contains(searchQuery, ignoreCase = true) }
        }

        // [FORK] 再按筛选模式过滤：仅收藏 / 仅固定
        if (previewFilter != PreviewFilter.ALL) {
            fun matches(node: MessageNode): Boolean = when (previewFilter) {
                PreviewFilter.FAVORITE -> node.isFavorite
                PreviewFilter.PINNED -> node.isPinned
                PreviewFilter.ALL -> false
            }
            messages = messages.filter { (_, node) ->
                matches(node) || node.currentMessage.role == me.rerere.ai.core.MessageRole.USER
            }
            // 当显示收藏/固定消息时，同时显示对应的提问（前一个消息如果是USER）
            val result = mutableListOf<Pair<Int, MessageNode>>()
            val addedIndices = mutableSetOf<Int>()
            messages.forEach { (index, node) ->
                val isProtected = matches(node)
                if (isProtected && node.currentMessage.role == me.rerere.ai.core.MessageRole.ASSISTANT) {
                    // 添加对应的提问（前一个消息）
                    if (index > 0 && !addedIndices.contains(index - 1)) {
                        result.add(index - 1 to conversation.messageNodes[index - 1])
                        addedIndices.add(index - 1)
                    }
                    // 添加当前收藏/固定的回答
                    if (!addedIndices.contains(index)) {
                        result.add(index to node)
                        addedIndices.add(index)
                    }
                } else if (isProtected && node.currentMessage.role == me.rerere.ai.core.MessageRole.USER) {
                    // 收藏/固定的提问：带出自身
                    if (!addedIndices.contains(index)) {
                        result.add(index to node)
                        addedIndices.add(index)
                    }
                    // 带出紧跟的回答（若有）
                    if (index + 1 < conversation.messageNodes.size && !addedIndices.contains(index + 1)) {
                        result.add(index + 1 to conversation.messageNodes[index + 1])
                        addedIndices.add(index + 1)
                    }
                } else if (node.currentMessage.role == me.rerere.ai.core.MessageRole.USER) {
                    // 检查下一个消息是否是收藏/固定的回答
                    if (index + 1 < conversation.messageNodes.size &&
                        matches(conversation.messageNodes[index + 1]) &&
                        !addedIndices.contains(index)) {
                        result.add(index to node)
                        addedIndices.add(index)
                    }
                }
            }
            messages = result
        }

        messages
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize()
            .hazeSource(state = hazeState),
    ) {
        // 统计信息：全部模式显示轮次/字数，收藏/固定模式显示对应数量
        Text(
            text = when (previewFilter) {
                PreviewFilter.FAVORITE -> "收藏 ${favoriteAndPinnedStats.first} 组"
                PreviewFilter.PINNED -> "固定 ${favoriteAndPinnedStats.second} 组"
                PreviewFilter.ALL -> "${conversationStats.first}轮  提问${"%,d".format(conversationStats.second)}字  回答${"%,d".format(conversationStats.third)}字"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // 搜索框和筛选按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.history_page_search)) },
                leadingIcon = {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = "Clear",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                maxLines = 1,
            )

            // [FORK] 筛选按钮：全部 → 收藏 → 固定 循环切换
            Surface(
                onClick = {
                    onPreviewFilterChange(
                        when (previewFilter) {
                            PreviewFilter.ALL -> PreviewFilter.FAVORITE
                            PreviewFilter.FAVORITE -> PreviewFilter.PINNED
                            PreviewFilter.PINNED -> PreviewFilter.ALL
                        }
                    )
                },
                shape = CircleShape,
                color = if (previewFilter != PreviewFilter.ALL) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = when (previewFilter) {
                            PreviewFilter.ALL -> HugeIcons.Filter
                            PreviewFilter.FAVORITE -> HugeIcons.Favourite
                            PreviewFilter.PINNED -> HugeIcons.Pin02
                        },
                        contentDescription = when (previewFilter) {
                            PreviewFilter.ALL -> "Show favorites"
                            PreviewFilter.FAVORITE -> "Show pinned"
                            PreviewFilter.PINNED -> "Show all messages"
                        },
                        modifier = Modifier.size(20.dp),
                        tint = if (previewFilter != PreviewFilter.ALL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 消息预览
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.second.id },
            ) { _, (originalIndex, node) ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                val isFavorite = node.isFavorite
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (isUser) Modifier.fillMaxWidth(0.75f) else Modifier
                                .fillMaxWidth()
                                .padding(end = 24.dp)
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(originalIndex)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 点赞标识
                            if (isFavorite) {
                                Icon(
                                    imageVector = HugeIcons.InLove,
                                    contentDescription = "Favorite",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            // [FORK] 固定到上下文标识：仅 USER 消息显示
                            if (isUser && node.isPinned) {
                                Icon(
                                    imageVector = HugeIcons.Pin02,
                                    contentDescription = "固定到上下文",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            val onHighlightColor = MaterialTheme.colorScheme.onTertiaryContainer
                            val highlightedText = remember(searchQuery, message) {
                                val fullText = message.toText().trim().ifBlank { "[...]" }
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                )
                                buildHighlightedText(
                                    text = messageText,
                                    query = searchQuery,
                                    highlightColor = highlightColor,
                                    onHighlightColor = onHighlightColor
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    onClickSuggestion: (String) -> Unit
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(conversation.chatSuggestions) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState,
    userMessageIndices: List<Int> = emptyList(),
    onDismissJumper: () -> Unit = {},
    onDisableAutoFollow: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 去顶部（点击后自动关闭导航栏）
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(0)
                    }
                    onDismissJumper()
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUpDouble,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            // 上一个提问（不关闭导航栏）
            Surface(
                onClick = {
                    // 手动跳转时停止自动贴底，防止被 auto-follow 立即拉回底部
                    onDisableAutoFollow()
                    scope.launch {
                        val current = state.firstVisibleItemIndex
                        // 找小于当前位置的最大 USER index
                        val target = userMessageIndices.lastOrNull { it < current }
                            ?: userMessageIndices.firstOrNull()
                            ?: (current - 1).fastCoerceAtLeast(0)
                        state.animateScrollToItem(target)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            // 下一个提问（不关闭导航栏）
            Surface(
                onClick = {
                    // 手动跳转时停止自动贴底，防止被 auto-follow 立即拉回底部
                    onDisableAutoFollow()
                    scope.launch {
                        val current = state.firstVisibleItemIndex
                        // 找大于当前位置的最小 USER index
                        val nextUserIndex = userMessageIndices.firstOrNull { it > current }
                        if (nextUserIndex != null) {
                            // 还有更靠下的提问，跳过去
                            state.animateScrollToItem(nextUserIndex)
                        } else {
                            // 已经跨过最后一条提问，直接滚到底部
                            state.animateScrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                        }
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            // 去底部（点击后自动关闭导航栏）
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                    onDismissJumper()
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDownDouble,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}
