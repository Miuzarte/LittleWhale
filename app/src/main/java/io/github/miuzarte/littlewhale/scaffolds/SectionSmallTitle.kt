package io.github.miuzarte.littlewhale.scaffolds

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 段标题, 照抄 SFA 的同名文件
 *
 * 它比 Miuix 默认的更靠左 (16dp 而不是 28dp), 这样标题的左边缘和 Card 里那些设置项的左边缘
 * (Card 的 12dp 加上控件自己的 16dp) 对齐
 */
@Composable
fun SectionSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = colorScheme.onBackgroundVariant,
    insideMargin: PaddingValues = PaddingValues(16.dp, 8.dp),
) {
    SmallTitle(
        text = text,
        modifier = modifier,
        textColor = textColor,
        insideMargin = insideMargin,
    )
}
