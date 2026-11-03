package com.yueyin.tv.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.yueyin.tv.core.lyrics.lyricText
import com.yueyin.tv.ui.FnColors

/**
 * 手机端歌词视图。
 *
 * 与电视端 `TvLyrics` 的差别只有排版，取数逻辑完全一致（都由 `NowPlayingPresenter` 提供）：
 * - 电视是横屏大画布，歌词要占满整块右侧区域；手机竖屏只有一列，行高必须固定，
 *   否则「当前行字号变大」会把下方的行整体推下去，滚动时一跳一跳；
 * - 电视靠遥控器，不能点歌词跳转；手机必须能点某句直接跳过去。
 *
 * **行高固定 44dp 是有意的**：当前行用更大的字号显示，如果行高跟着字号走，
 * 每次换行都会引起 LazyColumn 重新测量，视觉上就是歌词在抖。
 */
@Composable
internal fun PhoneLyrics(
    syncedLyrics: SyncedLyrics?,
    staticLyric: String?,
    loading: Boolean,
    failed: Boolean,
    positionMs: Long,
    onSeekLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = remember(syncedLyrics) {
        syncedLyrics?.lines?.mapNotNull { line ->
            val text = line.lyricText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            text to line.start.toLong()
        }.orEmpty()
    }

    // 有歌词的时间轴时，当前行 = 最后一个「开始时间 <= 播放位置」的行。
    // 只算到 0 不兜底成 -1：第一句之前（前奏）也应该把第一句高亮出来，
    // 否则前奏阶段整屏都是灰的，用户会以为歌词没加载。
    val currentIndex = remember(lines, positionMs) {
        if (lines.isEmpty()) {
            -1
        } else {
            lines.indexOfLast { it.second <= positionMs }.coerceAtLeast(0)
        }
    }

    when {
        lines.isNotEmpty() -> TimedLyrics(
            lines = lines,
            currentIndex = currentIndex,
            onSeekLine = onSeekLine,
            modifier = modifier,
        )

        loading -> LyricNotice(text = "歌词加载中…", modifier = modifier)

        // 失败与「确实没有」分开说：前者用户还能重进一次，后者是纯音乐。
        failed -> LyricNotice(text = "歌词加载失败", modifier = modifier, caption = "点播放页右上角重试")

        staticLyric != null -> UntimedLyrics(text = staticLyric, modifier = modifier)

        else -> LyricNotice(text = "纯音乐，请欣赏", modifier = modifier)
    }
}

/**
 * 带时间轴的歌词。
 *
 * 上下留出半屏的 `contentPadding`，让任意一行滚到列表顶部时正好落在视口中央——
 * 这比「算 offset 手动画」简单，也不会在最后几行滚不动（padding 是列表自身的一部分）。
 */
@Composable
private fun TimedLyrics(
    lines: List<Pair<String, Long>>,
    currentIndex: Int,
    onSeekLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        // 视口高度的一半减去半行高，就是「让这一行居中」需要的上下留白。
        val verticalPadding = (maxHeight / 2 - LYRIC_ROW_HEIGHT / 2).coerceAtLeast(0.dp)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = verticalPadding),
            // 注意：这个滚动容器会把点击事件全部吃掉（连按下事件都在 Main 阶段被消费），
            // 所以它外面的舞台层收不到「点空白」。控制层的唤出靠两处：
            // 点上方头部（见 LyricsStage）、或点任意一句歌词——点歌词在跳转的同时
            // 也会把控制层叫出来，理由见 PhonePlayerPage 里 onSeekLine 的注释。
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines) { index, line ->
                LyricLine(
                    text = line.first,
                    active = index == currentIndex,
                    onClick = { onSeekLine(line.second) },
                )
            }
        }
        // 跟着当前行滚。用 animateScrollToItem 而不是 scrollToItem：一句一句滑过去，
        // 而不是瞬移，用户能看清滚到了哪。
        LaunchedEffect(currentIndex, lines.size) {
            if (currentIndex >= 0) {
                listState.animateScrollToItem(currentIndex)
            }
        }
    }
}

@Composable
private fun LyricLine(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(LYRIC_ROW_HEIGHT)
            .phoneTap(onClick)
            .padding(horizontal = PhoneSpace.screenHorizontal),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) FnColors.Coral else Color.White.copy(alpha = 0.42f),
            fontSize = if (active) PhoneType.Title else PhoneType.Body,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 无时间轴的纯文本歌词：整段可滚动，不做高亮（没有时间点就对不上哪句）。 */
@Composable
private fun UntimedLyrics(text: String, modifier: Modifier = Modifier) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = PhoneSpace.xxl),
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = PhoneType.Body,
                lineHeight = 26.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhoneSpace.screenHorizontal),
            )
        }
    }
}

/** 空态 / 加载中 / 失败：居中一行说明，可选第二行小字提示下一步。 */
@Composable
private fun LyricNotice(
    text: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = Color.White.copy(alpha = 0.52f),
            fontSize = PhoneType.Section,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
        if (caption != null) {
            Spacer(Modifier.height(PhoneSpace.sm))
            Text(
                caption,
                color = Color.White.copy(alpha = 0.30f),
                fontSize = PhoneType.Caption,
                maxLines = 1,
            )
        }
    }
}

/** 歌词行高：固定值，理由见 [PhoneLyrics] 注释。 */
private val LYRIC_ROW_HEIGHT = 44.dp
