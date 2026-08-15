package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import kotlin.uuid.Uuid

/**
 * Battle Mode 专用多选模型选择器。
 *
 * 点击「添加模型」按钮弹出底部选择框，选中/取消选中模型时触发 [onToggle]，
 * 弹框保持打开状态，用户可连续选择多个模型，最后用返回键或下滑关闭。
 */
@Composable
fun BattleModelPicker(
    providers: List<ProviderSetting>,
    selectedModelIds: List<Uuid>,
    onToggle: (Model) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 「添加模型」按钮
    TextButton(
        onClick = { showSheet = true },
        modifier = modifier,
    ) {
        Icon(
            imageVector = HugeIcons.Add01,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(16.dp),
        )
        Text(
            text = "添加模型",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxHeight(0.8f)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val filteredProviders = providers.filter { provider ->
                    provider.enabled && provider.models.any { it.type == ModelType.CHAT }
                }

                // 复用 ModelList，但 onSelect 不关闭弹框（改为 toggle 行为）
                BattleModelList(
                    providers = filteredProviders,
                    selectedModelIds = selectedModelIds,
                    onToggle = onToggle,
                )
            }
        }
    }
}
