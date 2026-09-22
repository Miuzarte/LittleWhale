package io.github.miuzarte.littlewhale.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * 触感反馈的短名, 照 SFA 的 `ui/Haptic.kt` 搬过来的
 *
 * 只搬了当前用到的两个 (那边有十几个, 都是 `HapticFeedbackType` 的一一对应), 以后用到哪个再补哪个
 */

/** 一次点击, 比如点开一个能改值的设置项 */
fun HapticFeedback.contextClick() =
    performHapticFeedback(HapticFeedbackType.ContextClick)

/** 一次选择落定, 比如对话框里按下确定 */
fun HapticFeedback.confirm() =
    performHapticFeedback(HapticFeedbackType.Confirm)
