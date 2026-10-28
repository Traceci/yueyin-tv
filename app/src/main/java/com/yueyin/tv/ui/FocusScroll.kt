package com.yueyin.tv.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch

/**
 * 让焦点项在普通滚动容器里自动滚入视野。
 *
 * `LazyColumn` / `LazyVerticalGrid` 自带焦点跟随滚动；但 `Column + verticalScroll` 不会，
 * 于是设置页这类页面按遥控器往下走时，焦点会跑到屏幕外、内容却纹丝不动。
 * 这里给每个可聚焦项挂一个 [BringIntoViewRequester]，拿到焦点时主动请求把自己滚进视野，
 * 由外层 `verticalScroll` 提供的 bring-into-view 响应者完成实际滚动。
 *
 * 注意：只把控件「自身边界」最小滚入视野，不要向上扩展余量、也不要强制定位到视口顶端。
 * 早期版本在 rect 里用了负 top（向上扩 16dp）会把控件每次都顶到最顶端，且用 onGloballyPositioned
 * 记录尺寸触发了额外重测；一旦「界面缩放」改变全局 density 引发整页重测，滚动目标反复变化、
 * 动画互相打断，设置页就表现为持续抖动。当前实现去掉了这些，抖动消除、焦点跟随仍有效。
 *
 * 用法：`modifier.focusBringIntoView()`，必须加在真正持有焦点的那个节点上
 * （Button 的 modifier、`focusable()` 所在的那条 Modifier 链），加在外层容器上无效。
 */
fun Modifier.focusBringIntoView(): Modifier = composed {
    val scope = rememberCoroutineScope()
    val requester = remember { BringIntoViewRequester() }
    this
        .bringIntoViewRequester(requester)
        .onFocusChanged { state ->
            // 只在拿到焦点时滚一次；失焦时不动，否则会跟着上一个位置乱跳。
            if (state.isFocused) {
                scope.launch { runCatching { requester.bringIntoView() } }
            }
        }
}
