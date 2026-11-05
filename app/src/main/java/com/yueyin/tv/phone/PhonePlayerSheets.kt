package com.yueyin.tv.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yueyin.tv.core.audiofx.FxPreset
import com.yueyin.tv.core.audiofx.FxSettings
import com.yueyin.tv.core.model.playback.PlaybackQueueItem
import com.yueyin.tv.ui.FnColors

/**
 * 手机端底部弹层容器。
 *
 * 为什么不用 material3 的 `ModalBottomSheet`：手机壳没有套 M3 主题（电视端只用了 tv-material3），
 * 弹出来的面板会取到 M3 默认的紫调、圆角和字体，和全局深蓝黑 + 珊瑚色完全脱节。
 * P2 的退出登录确认框已经踩过一次（自绘 Dialog 取到默认主题色），这里直接自绘。
 *
 * 遮罩点击关闭，面板本身吃掉点击（不往下传）。
 */
@Composable
internal fun PhoneSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    heightFraction: Float = 0.72f,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 面板高度按内容多少分档：队列要占大半屏好滚动，音效只有 8 个格子，
                        // 用队列那个高度会让面板下半截空着，像是内容没加载出来。
                        .fillMaxHeight(heightFraction)
                        .background(
                            color = FnColors.SurfaceAlt,
                            shape = RoundedCornerShape(topStart = PhoneRadius.xl, topEnd = PhoneRadius.xl),
                        )
                        // 面板自己吃掉点击，否则点内容区也会把面板关掉
                        .pointerInput(Unit) { detectTapGestures { } }
                        .navigationBarsPadding()
                        .padding(top = PhoneSpace.md),
                ) {
                    // 顶部小横条：移动端公认的「可以往下拖/点遮罩关闭」的暗示
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(38.dp)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.height(PhoneSpace.md))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PhoneSpace.screenHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            color = FnColors.Text,
                            fontSize = PhoneType.Section,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .size(LocalCarProfile.current.touchTarget)
                                .phoneTap(onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            PhoneIcon(PhoneGlyph.ChevronDown, FnColors.Muted, Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(PhoneSpace.sm))
                    content()
                }
            }
        }
    }
}

/**
 * 播放队列。
 *
 * 只做「看得到现在排了什么、跳到某一首、删掉不想要的」三件事。
 * 电视端队列还有「当前播放位置预取进度」「队列分页加载」等提示，手机上一屏能滚完，
 * 那些信息反而成了噪声，所以不搬。
 */
@Composable
internal fun PhoneQueueSheet(
    visible: Boolean,
    items: List<PlaybackQueueItem>,
    loadedCount: Int,
    queueError: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PhoneSheet(visible = visible, title = "播放队列 (${items.size})", onDismiss = onDismiss) {
        if (queueError != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhoneSpace.screenHorizontal, vertical = PhoneSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    queueError,
                    color = FnColors.Coral,
                    fontSize = PhoneType.Caption,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                if (canRetry) {
                    Spacer(Modifier.width(PhoneSpace.md))
                    Text(
                        "重试",
                        color = FnColors.Text,
                        fontSize = PhoneType.Caption,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.phoneTap(onRetry).padding(PhoneSpace.sm),
                    )
                }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(PhoneSpace.xl), contentAlignment = Alignment.Center) {
                Text(
                    "队列是空的",
                    color = FnColors.Muted,
                    fontSize = PhoneType.Body,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { "${it.queueIndex}:${it.mediaId}" }) { item ->
                    QueueRow(
                        item = item,
                        // 删除按钮只给「非当前曲」：删当前曲会立刻跳下一首，
                        // 用户往往只是想「这首不听了」而不是「现在就切歌」，容易误伤播放。
                        canRemove = !item.isCurrent,
                        onSelect = { onSelect(item.queueIndex) },
                        onRemove = { onRemove(item.queueIndex) },
                    )
                }
                // 末尾提示还能不能继续加载。loadedCount 小于总数时说明队列还在后台补，
                // 用户看不到这条会以为「怎么只有 30 首」。
                if (loadedCount in 1 until items.size) {
                    item {
                        Text(
                            "已就绪 $loadedCount / ${items.size} 首，其余正在补链",
                            color = FnColors.Muted.copy(alpha = 0.7f),
                            fontSize = PhoneType.Micro,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = PhoneSpace.md),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: PlaybackQueueItem,
    canRemove: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PhoneSize.RowItem)
            .phoneTap(onSelect)
            .padding(horizontal = PhoneSpace.screenHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 当前曲左侧一条珊瑚竖条：和详情页曲目行同一套「正在播放」标记，用户不用重新学
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .background(
                    if (item.isCurrent) FnColors.Coral else Color.Transparent,
                    RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(PhoneSpace.md))
        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { "未知曲目" },
                color = if (item.isCurrent) FnColors.Coral else FnColors.Text,
                fontSize = PhoneType.Row,
                fontWeight = if (item.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.artist.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.artist,
                    color = FnColors.Muted,
                    fontSize = PhoneType.Micro,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canRemove) {
            Spacer(Modifier.width(PhoneSpace.sm))
            Box(
                Modifier.size(36.dp).phoneTap(onRemove),
                contentAlignment = Alignment.Center,
            ) {
                // 复用「更多」的三个点会让人以为能展开菜单；这里要表达的是「移除」，
                // 用一个小叉更直白，也不需要为它单独加一个垃圾桶图标。
                Text("×", color = FnColors.Muted, fontSize = PhoneType.Title, maxLines = 1)
            }
        }
    }
}

/**
 * 音效面板。
 *
 * 与电视端 `PlayerFxPanel` 同一套数据（`FxPreset.selectable` + `AudioFxController`），
 * 但只保留「开 / 关 + 选一档」——电视端那 8 档挤在一行里是为了遥控器一次左右扫完，
 * 手机上有足够竖向空间，换成两行网格，点错的概率小得多。
 *
 * 低音 / 声场 / 夜间模式这些细项留在设置页（P4），播放页的音效面板不该比设置页还全。
 */
@Composable
internal fun PhoneFxSheet(
    visible: Boolean,
    current: FxSettings,
    onApply: (FxSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    PhoneSheet(
        visible = visible,
        title = "音效",
        onDismiss = onDismiss,
        heightFraction = 0.46f,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = PhoneSpace.screenHorizontal)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (current.enabled) "已开启 · ${current.effectLabel}" else "未开启",
                        color = if (current.enabled) FnColors.Coral else FnColors.Muted,
                        fontSize = PhoneType.Body,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "对人声与低频做实时补偿，切换后立即生效",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Micro,
                        maxLines = 2,
                    )
                }
                PhoneSwitch(
                    checked = current.enabled,
                    onToggle = { onApply(current.copy(enabled = it)) },
                )
            }
            Spacer(Modifier.height(PhoneSpace.lg))

            // 两列网格：8 档正好 4 行，每格 44dp 高，拇指够得到
            val presets = FxPreset.selectable
            presets.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = PhoneSpace.sm),
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm),
                ) {
                    pair.forEach { preset ->
                        FxPresetChip(
                            label = preset.displayName,
                            selected = current.effectLabel == preset.displayName,
                            onClick = {
                                // 选档位视为「我要开音效」：用户既然点了「流行」，
                                // 再让他去拨一次总开关才生效是多余的一步。
                                onApply(preset.toSettings(current).copy(enabled = true))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 奇数档时右侧补一个等宽占位，避免最后一格被拉成半屏
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FxPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(PhoneSize.PrimaryButton)
            .background(
                color = if (selected) FnColors.Coral.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(PhoneRadius.md),
            )
            .phoneTap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) FnColors.Coral else FnColors.Text,
            fontSize = PhoneType.Row,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 简易开关。
 *
 * tv-material3 没有 `Switch`（它面向遥控器，用「选中态 + 确定键」表达布尔值），
 * 引 material3 又会被默认主题染色，所以自绘一个：40×22 的胶囊 + 一个滑动的圆点。
 */
@Composable
internal fun PhoneSwitch(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Box(
        Modifier
            .width(46.dp)
            .height(28.dp)
            .background(
                color = if (checked) FnColors.Coral else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
            )
            .phoneTap { onToggle(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(if (checked) FnColors.Background else Color.White.copy(alpha = 0.6f), RoundedCornerShape(11.dp)),
        )
    }
}
