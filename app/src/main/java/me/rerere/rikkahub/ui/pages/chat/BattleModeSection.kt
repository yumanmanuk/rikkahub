package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.ConversationParams
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.FormItem

/**
 * [FORK] Battle Mode UI section.
 *
 * Rendered at the bottom of ConversationParamsSheet.
 * Provides a toggle switch plus a multi-model picker:
 *   - Selected models shown as FilterChips (tap to remove)
 *   - "Select model" button (ModelSelector with modelId=null) appends new models to the list
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BattleModeSection(
    params: ConversationParams,
    settings: Settings,
    onUpdate: (ConversationParams) -> Unit,
) {
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
    ) {
        if (params.battleModeEnabled) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 已选模型 —— 点击 chip 移除
                params.battleModelIds.forEach { modelId ->
                    val model = settings.providers.findModelById(modelId)
                    FilterChip(
                        selected = true,
                        onClick = {
                            onUpdate(
                                params.copy(
                                    battleModelIds = params.battleModelIds.filter { it != modelId }
                                )
                            )
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
                        }
                    )
                }

                // 使用 ModelSelector(modelId=null) 作为"添加模型"入口。
                // 它会显示"选择模型"按钮，选中后追加到 battleModelIds，不替换。
                ModelSelector(
                    modelId = null,
                    providers = settings.providers,
                    type = ModelType.CHAT,
                    onlyIcon = false,
                    allowClear = false,
                    onSelect = { model ->
                        if (!params.battleModelIds.contains(model.id)) {
                            onUpdate(
                                params.copy(battleModelIds = params.battleModelIds + model.id)
                            )
                        }
                    }
                )
            }
        }
    }
}
