package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUpBig
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Sword02
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
// [FORK] 固定到上下文图标
import me.rerere.hugeicons.stroke.Pin02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.rikkahub.utils.toMessageTimeString
import java.util.Locale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.graphicsLayer
import sh.calvin.reorderable.ReorderableItem
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.rikkahub.data.datastore.findModelById

@Composable
fun ColumnScope.ChatMessageActionButtons(
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onScrollToQuestion: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    // 是否是对话中最后一条消息，只有最后一条才显示重试按钮
    isLastMessage: Boolean = true,
) {
    val settings = LocalSettings.current

    val isAssistant = message.role == MessageRole.ASSISTANT
    val tts = LocalTTSState.current
    val isSpeaking by tts.isSpeaking.collectAsState()
    val isAvailable by tts.isAvailable.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End,
    ) {
        val actionIconColor = MaterialTheme.colorScheme.onSurfaceVariant

        if (isAssistant) {
            // ASSISTANT 消息按钮：向上跳转 → 重试 → 语音 → 收藏 → 对战 → 更多

            // 向上跳转到提问处
            if (onScrollToQuestion != null) {
                Icon(
                    imageVector = HugeIcons.ArrowUpBig,
                    contentDescription = "Scroll to question",
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onScrollToQuestion() }
                        .padding(8.dp)
                        .size(16.dp),
                    tint = actionIconColor
                )
            }

            // 只有最后一条消息才显示重试按钮
            if (isLastMessage) {
                Icon(
                    imageVector = HugeIcons.Refresh03,
                    contentDescription = stringResource(R.string.regenerate),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onRegenerate() }
                        .padding(8.dp)
                        .size(16.dp),
                    tint = actionIconColor
                )
            }

            // 语音播放
            Icon(
                imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                contentDescription = stringResource(R.string.tts),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        enabled = isAvailable,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (!isSpeaking) {
                                val text = message.toText()
                                val textToSpeak = if (settings.displaySetting.ttsOnlyReadQuoted) {
                                    text.extractQuotedContentAsText() ?: text
                                } else {
                                    text
                                }
                                tts.speak(textToSpeak)
                            } else {
                                tts.stop()
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = if (isAvailable) actionIconColor else actionIconColor.copy(alpha = 0.38f)
            )

            // 收藏
            if (onToggleFavorite != null) {
                var bouncing by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(
                    targetValue = if (bouncing) 1.45f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "favoriteScale"
                )
                val iconColor by animateColorAsState(
                    targetValue = if (isFavorite) Color(0xFFE53935) else actionIconColor,
                    animationSpec = tween(durationMillis = 250),
                    label = "favoriteColor"
                )
                LaunchedEffect(bouncing) {
                    if (bouncing) {
                        delay(120)
                        bouncing = false
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = {
                                bouncing = true
                                onToggleFavorite()
                            }
                        )
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = isFavorite,
                        transitionSpec = {
                            // 新图标：用 tween 缩放入场（不用弹簧，避免过冲超出裁剪边界）
                            // 退场：立即消失，不显示旧图标残影
                            (scaleIn(
                                animationSpec = tween(durationMillis = 220),
                                initialScale = 0.3f
                            ) + fadeIn(tween(180))) togetherWith ExitTransition.None
                        },
                        label = "favoriteIcon"
                    ) { favorited ->
                        Icon(
                            imageVector = if (favorited) HugeIcons.InLove else HugeIcons.Favourite,
                            contentDescription = "Favourite",
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                },
                            tint = iconColor
                        )
                    }
                }
            }

            // Battle Mode 标识图标：多条来自不同模型的回答可切换时显示，点击打开排序面板
            val isBattleMode = node.messages.size > 1 &&
                node.messages.mapNotNull { it.modelId }.toSet().size > 1
            if (isBattleMode) {
                var showBattleSortSheet by remember { mutableStateOf(false) }
                Icon(
                    imageVector = HugeIcons.Sword02,
                    contentDescription = "Battle Mode",
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = { showBattleSortSheet = true }
                        )
                        .padding(8.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                if (showBattleSortSheet) {
                    BattleSortSheet(
                        node = node,
                        onDismissRequest = { showBattleSortSheet = false },
                        onUpdate = onUpdate
                    )
                }
            }

            // 更多
            Icon(
                imageVector = HugeIcons.MoreVertical,
                contentDescription = stringResource(R.string.more_options),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = { onOpenActionSheet() }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionIconColor
            )

            if (settings.displaySetting.showDateTimeInMessage) {
                Text(
                    text = message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // 占满剩余空间，将 BranchSelector 推到右侧
            Spacer(modifier = Modifier.weight(1f))

            // 分支切换器固定在右侧，与操作图标同行
            ChatMessageBranchSelector(
                node = node,
                onUpdate = onUpdate,
            )
        } else {
            // USER 消息按钮：重试 → 复制 → 更多 → 分支切换器，靠右对齐

            if (settings.displaySetting.showDateTimeInMessage) {
                Text(
                    text = message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // 只有最后一条消息才显示重试按钮
            if (isLastMessage) {
                Icon(
                    imageVector = HugeIcons.Refresh03,
                    contentDescription = stringResource(R.string.regenerate),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onRegenerate() }
                        .padding(8.dp)
                        .size(16.dp),
                    tint = actionIconColor
                )
            }

            // 复制
            if (onCopy != null) {
                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = stringResource(R.string.copy),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = { onCopy() }
                        )
                        .padding(8.dp)
                        .size(16.dp),
                    tint = actionIconColor
                )
            }

            // 更多
            Icon(
                imageVector = HugeIcons.MoreVertical,
                contentDescription = stringResource(R.string.more_options),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = { onOpenActionSheet() }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionIconColor
            )

            // 分支切换器（最右边）
            ChatMessageBranchSelector(
                node = node,
                onUpdate = onUpdate,
            )
        }
    }

}

@Composable
fun ChatMessageActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onDeleteBefore: () -> Unit,
    onDeleteAfter: () -> Unit,
    onCopy: () -> Unit,
    onSelectAndCopy: () -> Unit,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onWebViewPreview: () -> Unit,
    // [FORK] 固定到上下文
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showDeleteBeforeConfirm by remember { mutableStateOf(false) }
    var showDeleteAfterConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeight * 0.7f).dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 一键复制
            Card(
                onClick = {
                    onDismissRequest()
                    onCopy()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.copy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // [FORK] 固定到上下文
            if (onTogglePin != null) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onTogglePin()
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = if (isPinned) CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) else CardDefaults.cardColors()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.Pin02,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                        Text(
                            text = if (isPinned) "取消固定到上下文" else "固定到上下文",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isPinned) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                }
            }

            // Delete
            Card(
                onClick = {
                    onDismissRequest()
                    onDelete()
                },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Edit
            Card(
                onClick = {
                    onDismissRequest()
                    onEdit()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Edit01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.edit),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Create a Fork
            Card(
                onClick = {
                    onDismissRequest()
                    onFork()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.GitFork,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_fork),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Select and Copy
            Card(
                onClick = {
                    onDismissRequest()
                    onSelectAndCopy()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.TextSelection,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.select_and_copy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Share
            Card(
                onClick = {
                    onDismissRequest()
                    onShare()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Share04,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.share),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Translation
            if (onTranslate != null) {
                Card(
                    onClick = {
                        showTranslateDialog = true
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.Translate,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.translate),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // WebView Preview (only show if message has text content)
            val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
                .any { it.text.isNotBlank() }

            if (hasTextContent) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onWebViewPreview()
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.WebDesign01,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.render_with_webview),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Delete messages before this one
            Card(
                onClick = {
                    showDeleteBeforeConfirm = true
                },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.delete_messages_before),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Delete messages after this one
            Card(
                onClick = {
                    showDeleteAfterConfirm = true
                },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.delete_messages_after),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Message Info
            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                if (model != null) {
                    Text(model.displayName)
                }
            }
        }
    }

    // Delete before confirmation dialog
    RikkaConfirmDialog(
        show = showDeleteBeforeConfirm,
        title = stringResource(R.string.delete_messages_before),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteBeforeConfirm = false
            onDismissRequest()
            onDeleteBefore()
        },
        onDismiss = { showDeleteBeforeConfirm = false },
        text = { Text(stringResource(R.string.delete_messages_before_confirm)) }
    )

    // Delete after confirmation dialog
    RikkaConfirmDialog(
        show = showDeleteAfterConfirm,
        title = stringResource(R.string.delete_messages_after),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteAfterConfirm = false
            onDismissRequest()
            onDeleteAfter()
        },
        onDismiss = { showDeleteAfterConfirm = false },
        text = { Text(stringResource(R.string.delete_messages_after_confirm)) }
    )

    // Translation dialog
    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onDismissRequest()
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onDismissRequest()
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }
}

/**
 * Battle Mode 排序面板：展示所有模型的回答，支持长按拖拽调整顺序
 */
@Composable
fun BattleSortSheet(
    node: MessageNode,
    onDismissRequest: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
) {
    val settings = LocalSettings.current
    // 记录当前正在显示的消息 id，排序后 selectIndex 跟随它
    val currentMsgId = node.messages.getOrNull(node.selectIndex)?.id

    var messages by remember(node.messages) { mutableStateOf(node.messages) }
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIdx = messages.indexOfFirst { it.id.toString() == from.key }
        val toIdx = messages.indexOfFirst { it.id.toString() == to.key }
        if (fromIdx != -1 && toIdx != -1) {
            messages = messages.toMutableList().apply {
                add(toIdx, removeAt(fromIdx))
            }
            val newSelectIndex = messages.indexOfFirst { it.id == currentMsgId }.coerceAtLeast(0)
            onUpdate(node.copy(messages = messages, selectIndex = newSelectIndex))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = androidx.compose.ui.Modifier.padding(bottom = 4.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Sword02,
                    contentDescription = null,
                    modifier = androidx.compose.ui.Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Battle 排序",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text = "长按拖拽可调整回答顺序，排在前面的将优先显示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(
                state = lazyListState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(messages, key = { it.id.toString() }) { msg ->
                    ReorderableItem(
                        state = reorderState,
                        key = msg.id.toString(),
                    ) { isDragging ->
                        val model: me.rerere.ai.provider.Model? = msg.modelId?.let {
                            settings.findModelById(it)
                        }
                        val isSelected = msg.id == currentMsgId
                        Card(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = if (isDragging) 1.03f else 1f
                                    scaleY = if (isDragging) 1.03f else 1f
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                        ) {
                            Row(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // 模型图标
                                AutoAIIcon(
                                    name = model?.displayName ?: "",
                                    modifier = androidx.compose.ui.Modifier.size(28.dp),
                                )
                                // 模型名称
                                Text(
                                    text = model?.displayName ?: "Unknown",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = androidx.compose.ui.Modifier.weight(1f),
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // 拖拽把手（长按触发）
                                Icon(
                                    imageVector = HugeIcons.DragDropVertical,
                                    contentDescription = "拖拽排序",
                                    modifier = androidx.compose.ui.Modifier
                                        .size(20.dp)
                                        .longPressDraggableHandle(),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
