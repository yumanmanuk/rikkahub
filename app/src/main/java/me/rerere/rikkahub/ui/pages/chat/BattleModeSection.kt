package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.ConversationParams
import me.rerere.rikkahub.ui.components.ai.BattleModelPicker
import me.rerere.rikkahub.ui.components.ui.FormItem

/**
 * [FORK] Battle Mode UI section.
 *
 * Rendered at the bottom of ConversationParamsSheet.
 * Provides a toggle switch plus a multi-model picker:
 *   - Selected models shown as FilterChips in a wrapping FlowRow layout.
 *     Long-press a chip to drag-reorder; tap to remove.
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
                // 已选模型 chip 列表 —— 自动换行，长按拖动排序，点击移除
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    params.battleModelIds.forEachIndexed { index, modelId ->
                        val model = settings.providers.findModelById(modelId)
                        val isDragging = draggingIndex == index
                        val isHover = hoverIndex == index && !isDragging

                        FilterChip(
                            selected = true,
                            onClick = {
                                // 未拖拽时点击移除该模型
                                if (draggingIndex == -1) {
                                    onUpdate(
                                        params.copy(
                                            battleModelIds = params.battleModelIds.filter { it != modelId }
                                        )
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = model?.displayName ?: modelId.toString().take(8),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = "remove",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            modifier = Modifier
                                // 拖拽中半透明；悬停目标略微放大
                                .alpha(if (isDragging) 0.4f else 1f)
                                .scale(if (isHover) 1.08f else 1f)
                                // 记录每个 chip 在窗口中的位置
                                .onGloballyPositioned { coords ->
                                    itemBounds[index] = coords.boundsInWindow()
                                }
                                .pointerInput(modelId) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { localOffset ->
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            draggingIndex = index
                                            hoverIndex = index
                                            cumulativeDrag = Offset.Zero
                                            // 拖拽起始窗口坐标 = chip 左上角 + 手指在 chip 内的局部偏移
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
                                            // 当前手指的近似窗口坐标
                                            val currentPos = startWindowPos + cumulativeDrag
                                            // 找中心点距手指最近的 chip 作为悬停目标
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
                        )
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
