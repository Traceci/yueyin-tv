package com.yueyin.tv.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDotsDefaults
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import com.yueyin.tv.core.lyrics.lyricText
import com.yueyin.tv.ui.FnColors

/**
 * 手机端歌词视图。
 *
 * 与电视端 `TvLyrics` 用同一个卡拉 OK 渲染器、同一份 `SyncedLyrics`（带逐字时间轴），
 * 差别只在排版：电视是横屏大画布、字号更大；手机竖屏收紧字号与行距。
 * 「逐字填充」开关在这里直接接到 `karaokeProgressEnabled`：关掉时当前句整体点亮，
 * 打开时按逐字时间轴从左到右扫填——设置项不再是一个空开关。
 *
 * 逐字进度只从 `currentPosition` 这个 lambda 读，不在重组里直接读 [positionMs]，
 * 避免 250ms 的播放进度 tick 把整棵歌词树重排一遍。
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
    karaokeFill: Boolean = true,
) {
    val hasTimedLines = remember(syncedLyrics) {
        syncedLyrics?.lines.orEmpty().any { it.lyricText().isNotBlank() }
    }

    when {
        syncedLyrics != null && hasTimedLines -> KaraokeTimedLyrics(
            lyrics = syncedLyrics,
            positionMs = positionMs,
            karaokeFill = karaokeFill,
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
 * 带时间轴的歌词：交给 `KaraokeLyricsView` 画，逐字扫填与电视端一致。
 *
 * `currentPosition` 用 `rememberUpdatedState` 包一层，渲染器内部按帧读取即可拿到最新进度，
 * 不必靠重组刷新；点某句仍然走 [onSeekLine] 跳转。
 */
@Composable
private fun KaraokeTimedLyrics(
    lyrics: SyncedLyrics,
    positionMs: Long,
    karaokeFill: Boolean,
    onSeekLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // offset 在 KaraokeLyricsView 里的语义是「当前句被滚动到的距顶距离」，不是普通留白：
    // 它同时用作 LazyColumn 首尾 contentPadding 与自动滚动的目标位置。
    // 取歌词区高度的一半：当前句落在正中，上面的歌词与下面的歌词对称展开。
    BoxWithConstraints(modifier.fillMaxSize()) {
        val centerOffset = maxHeight * 0.5f
        val listState = rememberLazyListState()
        val positionState = rememberUpdatedState(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        val currentPosition = remember { { positionState.value } }
        // 普通行压暗，当前句保持亮色：LyricsLineItem 用 activeAlpha=1 / inactiveAlpha=0.4
        // 再拉一档差距；逐字扫填的 activeColor 也用亮色，两种歌词类型都能看出当前句。
        val dimText = FnColors.Text.copy(alpha = 0.62f)
        CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides TextStyle(
                color = dimText,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        ) {
            KaraokeLyricsView(
                listState = listState,
                lyrics = lyrics,
                currentPosition = currentPosition,
                onLineClicked = { line -> onSeekLine(line.start.toLong()) },
                onLinePressed = {},
                modifier = Modifier.fillMaxSize(),
                normalLineTextStyle = TextStyle(
                    color = dimText,
                    fontSize = 21.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.Bold,
                ),
                accompanimentLineTextStyle = TextStyle(
                    color = dimText,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                textColor = FnColors.Text,
                blendMode = BlendMode.SrcOver,
                useBlurEffect = false,
                showTranslation = true,
                showPhonetic = false,
                offset = centerOffset,
                keepAliveZone = 0.dp,
                breathingDotsDefaults = KaraokeBreathingDotsDefaults(
                    number = 1,
                    size = 0.dp,
                    margin = 0.dp,
                ),
                karaokeProgressEnabled = karaokeFill,
                // 手机全歌词页要求歌词整块居中；这个参数默认 false，电视端不传、版式不变。
                centerLines = true,
                // 以下三项只在本页开启，电视端走默认值，行为零变化：
                // ① 当前句不在可见区时也对齐到居中（原来用负偏移会把整块歌词推到顶上）；
                // ② 手动翻看后 3 秒内不自动拽回；
                // ③ 时间窗口没命中时仍高亮最近一句，避免整屏没有当前句。
                centerAlignCurrentLine = true,
                manualScrollPauseMs = 3_000L,
                fallbackFocusLine = true,
            )
        }
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
