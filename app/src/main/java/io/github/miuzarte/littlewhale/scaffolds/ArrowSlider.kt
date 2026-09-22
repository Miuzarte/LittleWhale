package io.github.miuzarte.littlewhale.scaffolds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.ui.confirm
import io.github.miuzarte.littlewhale.ui.contextClick
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults.SliderHapticEffect
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles

/**
 * 一个设置项, 上面是标题加当前值, 下面是一条滑块, 照抄 SFA 的 `scaffolds/ArrowSlider.kt`
 *
 * 点标题那一行可以打字给出精确的值 (那些数是设给机器的, 一条滑块滑不到一位不差), 这就是
 * [ArrowPreference] 那个箭头的用处; 滑块本身在 `bottomAction` 里, 所以它与那一行是同一张卡片的一项
 *
 * 两处的取舍是 SFA 定的, 照搬:
 *
 * - **滑块与对话框可以不在同一个坐标系里**: [value] / [valueRange] / [steps] 只管滑块, 而
 *   [displayFormatter] / [displayText] 管那一行显示的数字, [inputInitialValue] / [inputValueRange] /
 *   [onInputConfirm] 管对话框收下的数字。于是"滑块只停在几个预设上, 但手打的值照收"是能表达的
 * - **`steps` 是端点之间有几个点**, 不是有几个值: 三档就是 `valueRange = 0f..2f` 加 `steps = 1`
 * - [keyPoints] 只负责画点与吸附, 不是 `steps` 的替代: 想"连续滑动 + 靠近时吸住"就得 `steps = 0`
 *
 * @param title 设置项的名字.
 * @param summary 名字下面那行说明.
 * @param value 滑块现在在哪.
 * @param onValueChange 滑块动一次叫一次, 拖动的每一帧都会叫.
 * @param valueRange 滑块的两端.
 * @param steps 两端之间有几个离散点, 0 表示连续.
 * @param onValueChangeFinished 手抬起时叫一次.
 * @param enabled 能不能动.
 * @param hapticEffect 滑块的触感风格.
 * @param showKeyPoints 要不要画那些点.
 * @param keyPoints 画点与吸附用的值, 传了它 [steps] 就只管滑块的分档.
 * @param magnetThreshold 离吸附点多近算吸住 (占整条range的比例).
 * @param unit 显示值与输入值的单位, 空字符串就不显示.
 * @param zeroStateText 值为 0 时显示什么 (比如"默认").
 * @param showUnitWhenZeroState 值为 0 时还带不带单位.
 * @param displayFormatter 那一行怎么显示 [value].
 * @param displayText 直接给定那一行显示的文字, 给了就不用 [displayFormatter].
 * @param inputTitle 对话框的标题, 默认与 [title] 一样.
 * @param inputSummary 对话框的说明.
 * @param inputLabel 输入框的标签.
 * @param useLabelAsPlaceholder 标签是不是当占位符用.
 * @param inputInitialValue 输入框一开始的内容.
 * @param inputFilter 输入时逐字过滤.
 * @param inputValueRange 对话框收下的范围, 不给就用 [valueRange].
 * @param onInputConfirm 对话框按下确定, 参数是那一串原文.
 */
@Composable
fun ArrowSlider(
    title: String,
    summary: String? = null,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    hapticEffect: SliderHapticEffect = SliderHapticEffect.Edge,
    showKeyPoints: Boolean = false,
    keyPoints: List<Float> = emptyList(),
    magnetThreshold: Float = 0.02f,
    unit: String = "",
    zeroStateText: String? = null,
    showUnitWhenZeroState: Boolean = false,
    displayFormatter: (Float) -> String = { it.toInt().toString() },
    displayText: String? = null,
    inputTitle: String = title,
    inputSummary: String? = summary,
    inputLabel: String = unit,
    useLabelAsPlaceholder: Boolean = false,
    inputInitialValue: String = displayFormatter(value),
    inputFilter: (String) -> String = { text -> text.filter { it.isDigit() || it == '.' } },
    inputValueRange: ClosedFloatingPointRange<Float>? = null,
    onInputConfirm: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    var showInputDialog by remember { mutableStateOf(false) }
    var holdArrow by remember { mutableStateOf(false) }

    ArrowPreference(
        title = title,
        summary = summary,
        onClick = {
            haptic.contextClick()
            showInputDialog = true
            holdArrow = true
        },
        holdDownState = holdArrow,
        endActions = {
            val isZeroState = value == 0f && zeroStateText != null
            val valueText =
                if (isZeroState) zeroStateText
                else (displayText ?: displayFormatter(value))
            val shouldShowUnit = unit.isNotBlank() && (!isZeroState || showUnitWhenZeroState)
            val text = if (shouldShowUnit) "$valueText $unit" else valueText
            Text(
                text = text,
                color = colorScheme.onSurfaceVariantActions,
                fontSize = textStyles.body2.fontSize,
            )
        },
        enabled = enabled,
        bottomAction = {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                onValueChangeFinished = onValueChangeFinished,
                enabled = enabled,
                hapticEffect = hapticEffect,
                showKeyPoints = showKeyPoints,
                keyPoints = keyPoints,
                magnetThreshold = magnetThreshold,
            )
        },
    )

    SliderInputDialog(
        showDialog = showInputDialog,
        title = inputTitle,
        summary = inputSummary,
        label = inputLabel,
        useLabelAsPlaceholder = useLabelAsPlaceholder,
        initialValue = inputInitialValue,
        inputFilter = inputFilter,
        inputValueRange = inputValueRange ?: valueRange,
        onDismissRequest = { showInputDialog = false },
        onDismissFinished = { holdArrow = false },
        onConfirm = { input ->
            onInputConfirm(input)
            showInputDialog = false
        },
    )
}

@Composable
private fun SliderInputDialog(
    showDialog: Boolean,
    title: String,
    summary: String? = null,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    initialValue: String,
    inputFilter: (String) -> String,
    inputValueRange: ClosedFloatingPointRange<Float>,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    OverlayDialog(
        show = showDialog,
        title = title,
        summary = summary,
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }

        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            label = label,
            useLabelAsPlaceholder = useLabelAsPlaceholder,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            onValueChange = { newValue ->
                text = inputFilter(newValue)
            },
        )

        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.button_confirm),
                onClick = {
                    haptic.confirm()
                    val inputValue = text.toFloatOrNull() ?: 0f
                    if (inputValue >= inputValueRange.start && inputValue <= inputValueRange.endInclusive) {
                        onConfirm(text.trim())
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
