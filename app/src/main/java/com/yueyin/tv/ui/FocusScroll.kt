package com.yueyin.tv.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 让焦点项在**普通滚动容器**里自动滚入视野。
 *
 * `LazyColumn` / `LazyVerticalGrid` 自带焦点跟随滚动：焦点移到屏幕外的 item 时，
 * LazyLayout 会自己发起 bring-into-view 请求。但 `Column + verticalScroll` 不会——
 * 它只有一个可滚动节点，并不知道"哪一个子项获得了焦点"，
 * 于是设置页这类页面按遥控器往下走时，焦点会跑到屏幕外、内容却纹丝不动。
 *
 * 这里给每个可聚焦项挂一个 [BringIntoViewRequester]，获得焦点时主动请求把自己滚进视野，
 * 由外层 `verticalScroll` 提供的 bring-into-view 响应者完成实际滚动。
 *
 * [verticalMargin] 是滚动后保留的呼吸空间：不留的话焦点项会正好贴在屏幕上下边缘。
 *
 * 用法：`modifier.focusBringIntoView()`，必须加在**真正持有焦点的那个节点**上
 * （Button 的 modifier、`focusable()` 所在的那条 Modifier 链），加在外层容器上无效。
 */
fun Modifier.focusBringIntoView(verticalMargin: Dp = 16.dp): Modifier = composed {
    val scope = rememberCoroutineScope()
    val marginPx = with(LocalDensity.current) { verticalMargin.toPx() }
    val requester = remember { BringIntoViewRequester() }
    var nodeSize by remember { mutableStateOf(IntSize.Zero) }
    this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { nodeSize = it.size }
        .onFocusChanged { state ->
            // 只在"拿到焦点"时滚；失焦时不动，否则会跟着上一个位置乱跳。
            if (state.isFocused) {
                scope.launch {
                    val rect = Rect(
                        left = 0f,
                        top = -marginPx,
                        right = nodeSize.width.toFloat(),
                        bottom = nodeSize.height + marginPx,
                    )
                    runCatching { requester.bringIntoView(rect) }
                }
            }
        }
}
