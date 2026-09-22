package io.github.miuzarte.littlewhale.scaffolds

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.miuzarte.littlewhale.constants.UiSpacing
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 页面级的滚动列表, 照抄 SFA 的 `scaffolds/LazyColumn.kt`
 *
 * 页面缩进在这里一处给: [horizontalPadding] 加到调用方传进来的 [contentPadding] 上, 于是每个
 * Card 都不用自己写水平外边距, 也就不会出现这段 16dp 那段 12dp 的错位 (Miuix 的 Card 自己没有
 * 内边距, 缩进全在列表这一层)。段间距同理, 由 [itemSpacing] 统一给, 页面里不再手写 Spacer
 *
 * 横屏时限宽并居中, 免得一行拉得老长
 *
 * SFA 那个版本还有一个给底部栏留位置的 `bottomInnerPadding`, 这边没有底部栏, 所以没搬
 * @param modifier layout modifier from the caller, which is the whole page.
 * @param contentPadding the insets the page already has to respect, usually Scaffold's innerPadding.
 * @param scrollBehavior 传了才能让顶栏跟着列表滚.
 * @param state 列表滚动位置.
 * @param itemSpacing 相邻两项之间的距离, 也是段与段的距离.
 * @param horizontalPadding 页面左右两侧的缩进, 所有内容共用.
 * @param verticalPadding 列表首尾的额外留白.
 * @param clearFocusOnTap 点空白处是否收起键盘.
 * @param limitLandscapeWidth 横屏时是否限宽.
 * @param landscapeMaxWidth 限宽的上限.
 * @param content 列表内容, 一段一个 item.
 */
@Composable
fun LazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    scrollBehavior: ScrollBehavior? = null,
    state: LazyListState = rememberLazyListState(),
    itemSpacing: Dp = UiSpacing.PageItem,
    horizontalPadding: Dp = UiSpacing.PageHorizontal,
    verticalPadding: Dp = UiSpacing.PageVertical,
    clearFocusOnTap: Boolean = true,
    limitLandscapeWidth: Boolean = true,
    landscapeMaxWidth: Dp = 640.dp,
    content: LazyListScope.() -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val focusManager = LocalFocusManager.current

    val mergedContentPadding = PaddingValues(
        start = contentPadding.calculateLeftPadding(layoutDirection) + horizontalPadding,
        top = contentPadding.calculateTopPadding() + verticalPadding,
        end = contentPadding.calculateRightPadding(layoutDirection) + horizontalPadding,
        bottom = contentPadding.calculateBottomPadding() + verticalPadding,
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (clearFocusOnTap) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val contentWidthModifier =
            if (limitLandscapeWidth && maxWidth > maxHeight) Modifier.widthIn(max = landscapeMaxWidth)
            else Modifier.fillMaxWidth()

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = contentWidthModifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .scrollEndHaptic()
                    .then(
                        if (scrollBehavior != null) {
                            Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                        } else {
                            Modifier
                        },
                    ),
                state = state,
                contentPadding = mergedContentPadding,
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
            ) {
                content()
            }
        }
    }
}
