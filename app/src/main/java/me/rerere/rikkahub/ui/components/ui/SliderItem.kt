package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import kotlin.math.roundToInt

/**
 * 整数滑块三件套: Minus IconButton + Slider + Plus IconButton.
 *
 * 解决直接使用 M3 Slider 时整数范围过大难以精确调节 (尤其是小数值) 的问题。
 *
 * - +/- 按钮: 每按一下按 [step] 增减, 在 [valueRange] 边界处禁用.
 * - 滑块拖动: 拖动过程仅在本地缓冲, 松手时通过 [onValueChange] 一次性提交,
 *   避免每次像素级变化都触发上层数据模型与持久化.
 * - [onValueChanging]: 拖动过程中持续回调（不提交/不持久化），供调用方实时更新显示.
 * - 外部 [value] 变化 (例如重置) 通过 [LaunchedEffect] 回流到本地 buffer.
 */
@Composable
fun IntSliderItem(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    onValueChanging: (Int) -> Unit = {},
) {
    require(valueRange.first <= valueRange.last) {
        "valueRange.first must be <= valueRange.last"
    }
    require(step > 0) { "step must be positive" }

    val min = valueRange.first
    val max = valueRange.last
    val coerced = value.coerceIn(min, max)

    // 本地缓冲: 拖动时仅更新此 state, 不触发外部 onValueChange
    var sliderValue by remember(coerced) { mutableFloatStateOf(coerced.toFloat()) }

    // 外部 value 改变 (例如重置或被其他来源修改) 时同步到本地 buffer
    LaunchedEffect(coerced) {
        sliderValue = coerced.toFloat()
    }

    val canDecrement = enabled && coerced > min
    val canIncrement = enabled && coerced < max

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = {
                val next = (coerced - step).coerceAtLeast(min)
                if (next != coerced) onValueChange(next)
            },
            enabled = canDecrement,
        ) {
            Icon(Lucide.Minus, contentDescription = "Decrease")
        }
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                onValueChanging(it.roundToInt().coerceIn(min, max))
            },
            onValueChangeFinished = {
                val snapped = sliderValue.roundToInt().coerceIn(min, max)
                sliderValue = snapped.toFloat()
                if (snapped != coerced) onValueChange(snapped)
            },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                val next = (coerced + step).coerceAtMost(max)
                if (next != coerced) onValueChange(next)
            },
            enabled = canIncrement,
        ) {
            Icon(Lucide.Plus, contentDescription = "Increase")
        }
    }
}
