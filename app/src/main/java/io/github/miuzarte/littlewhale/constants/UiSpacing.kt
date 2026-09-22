package io.github.miuzarte.littlewhale.constants

import androidx.compose.ui.unit.dp

/**
 * 界面上的间距刻度, 照抄 SFA 的 `constants/UiSpacing.kt`
 *
 * 只留了这边用得到的那几个: 页面缩进靠 [PageHorizontal] 一处给, 段与段之间靠 [PageItem],
 * Card 自己不带水平缩进, SFA 那套里用不上的档位等用到了再加
 */
object UiSpacing {
    val Small = 4.dp
    val Medium = 8.dp
    val PageItem = 12.dp
    val Large = 16.dp
    val PageHorizontal = 12.dp
    val PageVertical = 12.dp
    val ContentVertical = 12.dp
}
