package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.R
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.ConversationParams
import me.rerere.rikkahub.ui.components.ai.BattleModelPicker
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningXHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium
import kotlin.uuid.Uuid

/**
 * [FORK] Battle Mode UI section.
 *
 * Rendered at the bottom of ConversationParamsSheet.
 * Provides a toggle switch plus a multi-model picker:
 *   - Selected models shown as custom chips in a wrapping FlowRow layout.
 *     Long-press a chip to drag-reorder; tap chip body to set thinking depth via DropdownMenu;
 *     tap the trailing X icon to remove.
 *   - "Select model" button (ModelSelector with modelId=null) appends new models to the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BattleModeSection(
    params: ConversationParams,
    settings: Settings,
    onUpdate: (ConversationParams) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Battle Mode 主开关行
        FormItem(
            modifier = Modifier.padding(8.dp),
            label = {
                Text("Battle Mode")
            },
            description = {
                Text(
                    text = if (params.battleModeEnabled)
                        "发消息时用选中的模型各并发回答，通过 <> 切换对比"
                    else
                        "关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            tail = {
                Switch(
                    checked = params.battleModeEnabled,
                    onCheckedChange = { enabled ->
                        onUpdate(params.copy(battleModeEnabled = enabled))
                    }
                )
            }
        )

        // 展开内容：独立上下文开关 + 模型列表（与外层 FormItem 分离，避免开关并行）
        if (params.battleModeEnabled) {
            val haptic = LocalHapticFeedback.current
            val assistant = settings.getCurrentAssistant()

            // 每个 chip 的窗口坐标（用于拖拽命中检测）
            val itemBounds = remember { mutableStateMapOf<Int, Rect>() }
            // 正在拖拽的 index，-1 表示未拖拽
            var draggingIndex by remember { mutableIntStateOf(-1) }
            // 当前悬停的目标 index
            var hoverIndex by remember { mutableIntStateOf(-1) }
            // 拖拽起始的窗口坐标
            var startWindowPos by remember { mutableStateOf(Offset.Zero) }
            // 从拖拽开始累计的位移
            var cumulativeDrag by remember { mutableStateOf(Offset.Zero) }
            // 当前展开 DropdownMenu 的 chip index，-1 表示无
            var expandedDropdownIndex by remember { mutableIntStateOf(-1) }

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // [FORK] Battle Mode: 独立上下文开关
                FormItem(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    label = { Text("独立上下文") },
                    tail = {
                        Switch(
                            checked = params.battleIndependentContext,
                            onCheckedChange = { enabled ->
                                onUpdate(params.copy(battleIndependentContext = enabled))
                            }
                        )
                    }
                )
                // 已选模型 chip 列表 —— 自动换行，长按拖动排序
                // 点击 chip 主体 → 弹出 DropdownMenu 设置思考深度
                // 点击 X 图标 → 移除模型
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    params.battleModelIds.forEachIndexed { index, modelId ->
                        val model = settings.providers.findModelById(modelId)
                        val isDragging = draggingIndex == index
                        val isHover = hoverIndex == index && !isDragging
                        // 该模型当前设置的思考深度，回退到 assistant 默认
                        val currentReasoningLevel = params.battleModelReasoningLevels[modelId]
                            ?: assistant.reasoningLevel

                        // 用 Box 包裹 chip 和 DropdownMenu，确保 Menu 位置正确锚定
                        Box(
                            modifier = Modifier
                                .alpha(if (isDragging) 0.4f else 1f)
                                .scale(if (isHover) 1.08f else 1f)
                                .onGloballyPositioned { coords ->
                                    itemBounds[index] = coords.boundsInWindow()
                                }
                                .pointerInput(modelId) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { localOffset ->
                                            // 长按开始拖拽时关闭任何已打开的 dropdown
                                            expandedDropdownIndex = -1
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            draggingIndex = index
                                            hoverIndex = index
                                            cumulativeDrag = Offset.Zero
                                            val bounds = itemBounds[index]
                                            startWindowPos = if (bounds != null) {
                                                Offset(
                                                    bounds.left + localOffset.x,
                                                    bounds.top + localOffset.y,
                                                )
                                            } else Offset.Zero
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            cumulativeDrag += dragAmount
                                            val currentPos = startWindowPos + cumulativeDrag
                                            val nearest = itemBounds.entries.minByOrNull { (_, bounds) ->
                                                (bounds.center - currentPos).getDistance()
                                            }
                                            if (nearest != null) {
                                                hoverIndex = nearest.key
                                            }
                                        },
                                        onDragEnd = {
                                            val from = draggingIndex
                                            val to = hoverIndex
                                            draggingIndex = -1
                                            hoverIndex = -1
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            if (from != -1 && to != -1 && from != to) {
                                                val newList = params.battleModelIds.toMutableList()
                                                newList.add(to, newList.removeAt(from))
                                                onUpdate(params.copy(battleModelIds = newList))
                                            }
                                        },
                                        onDragCancel = {
                                            draggingIndex = -1
                                            hoverIndex = -1
                                        },
                                    )
                                }
                        ) {
                            // 自定义 chip：分离主体点击（思考深度）和 X 点击（移除）
                            BattleModelChip(
                                modelName = model?.displayName ?: modelId.toString().take(8),
                                onChipClick = {
                                    // 仅未拖拽时打开 dropdown
                                    if (draggingIndex == -1) {
                                        expandedDropdownIndex = if (expandedDropdownIndex == index) -1 else index
                                    }
                                },
                                onRemoveClick = {
                                    if (draggingIndex == -1) {
                                        // 移除模型同时清理 reasoning map 中的孤立 key
                                        onUpdate(
                                            params.copy(
                                                battleModelIds = params.battleModelIds.filter { it != modelId },
                                                battleModelReasoningLevels = params.battleModelReasoningLevels - modelId,
                                            )
                                        )
                                        // 关闭该 chip 的 dropdown（如果打开了）
                                        if (expandedDropdownIndex == index) {
                                            expandedDropdownIndex = -1
                                        }
                                    }
                                }
                            )

                            // 思考深度选择 DropdownMenu
                            DropdownMenu(
                                expanded = expandedDropdownIndex == index,
                                onDismissRequest = { expandedDropdownIndex = -1 },
                            ) {
                                ReasoningLevel.entries.forEach { level ->
                                    val isSelected = level == currentReasoningLevel
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = when (level) {
                                                    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
                                                    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
                                                    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
                                                    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
                                                    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
                                                    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
                                                },
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        },
                                        leadingIcon = {
                                            BattleReasoningIcon(
                                                level = level,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        trailingIcon = {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = HugeIcons.CheckmarkCircle01,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        },
                                        onClick = {
                                            onUpdate(
                                                params.copy(
                                                    battleModelReasoningLevels = params.battleModelReasoningLevels + (modelId to level)
                                                )
                                            )
                                            expandedDropdownIndex = -1
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 「添加模型」按钮：弹一次框可多选，选完后用返回键/下滑关闭
                BattleModelPicker(
                    providers = settings.providers,
                    selectedModelIds = params.battleModelIds,
                    onToggle = { model ->
                        val newList = if (params.battleModelIds.contains(model.id)) {
                            params.battleModelIds.filter { it != model.id }
                        } else {
                            params.battleModelIds + model.id
                        }
                        onUpdate(params.copy(battleModelIds = newList))
                    }
                )
            }
        }
    }
}

/**
 * 自定义 Battle Mode 模型 Chip。
 *
 * 将主体点击（触发思考深度 DropdownMenu）与 X 图标点击（移除模型）完全分离，
 * 避免 FilterChip trailingIcon 的事件冒泡问题。
 */
@Composable
private fun BattleModelChip(
    modelName: String,
    onChipClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 0.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // 主体区：模型名，点击 → 打开思考深度 DropdownMenu
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onChipClick,
                    )
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            // X 图标区：点击 → 移除该模型
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRemoveClick,
                    )
                    .padding(start = 2.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = "remove",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Battle Mode DropdownMenu 中的思考深度图标
 */
@Composable
private fun BattleReasoningIcon(
    level: ReasoningLevel,
    tint: androidx.compose.ui.graphics.Color,
) {
    val icon = when (level) {
        ReasoningLevel.OFF -> HugeIcons.Idea
        ReasoningLevel.AUTO -> HugeIcons.Idea01
        ReasoningLevel.LOW -> ReasoningLow
        ReasoningLevel.MEDIUM -> ReasoningMedium
        ReasoningLevel.HIGH -> ReasoningHigh
        ReasoningLevel.XHIGH -> ReasoningXHigh
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = tint,
    )
}

