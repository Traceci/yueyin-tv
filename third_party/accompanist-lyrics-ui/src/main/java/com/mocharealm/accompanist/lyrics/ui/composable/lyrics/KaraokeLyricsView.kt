package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastRoundToInt
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.utils.isRtl
import com.mocharealm.accompanist.lyrics.ui.utils.modifier.springPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

internal data class FocusState(
    val firstIndex: Int,
    val allIndices: List<Int>,
    val activeInterludeIndex: Int?,
    val activeIntro: Boolean
)

/**
 * A comprehensive lyrics view that supports Karaoke and Synced lyrics with advanced rendering.
 *
 * This composable handles:
 * - Scrolling and auto-scrolling to the current line
 * - Rendering karaoke lines with syllable-level timing and animations
 * - Rendering synced lines
 * - Displaying breathing dots during instrumental interludes
 * - Determining active and accompaniment lines
 *
 * @param listState The scroll state for the lazy list.
 * @param lyrics The lyrics data to display.
 * @param currentPosition A lambda returning the current playback position in milliseconds.
 * @param onLineClicked Callback when a line is clicked (seek to position).
 * @param onLinePressed Callback when a line is long-pressed (share/menu).
 * @param modifier The modifier to apply to the layout.
 * @param normalLineTextStyle The style for normal text lines.
 * @param accompanimentLineTextStyle The style for accompaniment/background vocals lines.
 * @param textColor The primary text color.
 * @param breathingDotsDefaults Styling defaults for the breathing dots.
 * @param blendMode The blend mode used for rendering text (e.g., [BlendMode.Plus] for glowing effects).
 * @param useBlurEffect Whether to apply blur effect to non-active lines.
 * @param offset The vertical padding/offset at the start and end of the list.
 * @param showDebugRectangles Debug flag to draw bounding boxes around glyphs.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun KaraokeLyricsView(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    textColor: Color = Color.White,
    breathingDotsDefaults: KaraokeBreathingDotsDefaults = KaraokeBreathingDotsDefaults(),
    phoneticTextStyle: TextStyle = normalLineTextStyle.copy(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
    ),
//    TODO: expose it
//    verticalFadeBrush: Brush = Brush.verticalGradient(
//        0f to Color.White.copy(0f),
//        0.05f to Color.White,
//        0.6f to Color.White,
//        1f to Color.White.copy(0f)
//    ),
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    offset: Dp = 32.dp,
    keepAliveZone: Dp = 100.dp,
    blurDelta: Float = 3f,
    showDebugRectangles: Boolean = false,
    /** 是否启用逐字卡拉OK填充（关：当前句整体点亮，不从左到右扫填）。 */
    karaokeProgressEnabled: Boolean = true,
    /** 每行歌词水平居中。默认 false 保持原来的左/右对齐（电视端行为不变）。 */
    centerLines: Boolean = false,
    /**
     * 当前句不在可见区时，把「滚到当前句」的目标对齐到 `+offset`（居中）而不是 `-offset`（贴顶）。
     * 默认 false 保持电视端原有行为；手机全歌词页传 true。
     */
    centerAlignCurrentLine: Boolean = false,
    /** 手动滚动结束后暂停自动对齐的毫秒数；0 表示不暂停（电视端默认）。 */
    manualScrollPauseMs: Long = 0L,
    /**
     * 时间窗口没命中任何一行时，仍把「最近一句」当作当前句（用于高亮与对齐目标）。
     * 默认 false 保持电视端原有行为；手机全歌词页传 true。
     */
    fallbackFocusLine: Boolean = false,
    /**
     * 逐句滚动是否走缓动。
     *
     * 默认 false 保持原来的瞬时平移（换句那一帧整块歌词直接跳一行，看着像"咔"地一下）；
     * 电视播放页与手机全歌词页开启后改为 380ms 缓动滑动。
     */
    smoothScroll: Boolean = false,
    /**
     * 连续滚动：由播放进度驱动整块歌词持续缓慢上移。
     *
     * 关掉时是「换句那一帧平移一行」——无论加不加缓动，句间都是静止的，
     * 观感就是一行一行往上顶。开启后当前句从中间慢慢升到中线上方一行，
     * 下一句同时从下方补到中间，换句处无缝。
     */
    continuousScroll: Boolean = false,
) {
    val density = LocalDensity.current
    val stableNormalTextStyle = remember(normalLineTextStyle) { normalLineTextStyle }
    val stableAccompanimentTextStyle =
        remember(accompanimentLineTextStyle) { accompanimentLineTextStyle }
    val stablePhoneticTextStyle = remember(phoneticTextStyle) { phoneticTextStyle }
    val stableOffset = remember(offset) { offset }
    val stableOffsetPx =
        remember(stableOffset) { with(density) { stableOffset.toPx().fastRoundToInt() } }
    val keepAliveZonePx = with(density) { keepAliveZone.toPx() }
    val stableBlendMode = remember(blendMode) { blendMode }

    val textMeasurer = rememberTextMeasurer()
    val layoutCache = remember { mutableStateMapOf<Int, List<SyllableLayout>>() }

    LaunchedEffect(
        lyrics,
        stableNormalTextStyle,
        stableAccompanimentTextStyle,
        stablePhoneticTextStyle
    ) {
        layoutCache.clear()
        withContext(Dispatchers.Default) {
            val normalStyle = stableNormalTextStyle.copy(textDirection = TextDirection.Content)
            val accompanimentStyle =
                stableAccompanimentTextStyle.copy(textDirection = TextDirection.Content)
            val phoneticStyle = stablePhoneticTextStyle.copy(textDirection = TextDirection.Content)

            val normalSpaceWidth = textMeasurer.measure(" ", normalStyle).size.width.toFloat()
            val accompanimentSpaceWidth =
                textMeasurer.measure(" ", accompanimentStyle).size.width.toFloat()

            lyrics.lines.forEachIndexed { index, line ->
                if (!isActive) return@forEachIndexed
                if (line is KaraokeLine) {
                    val style =
                        if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentStyle else normalStyle
                    val spaceWidth =
                        if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentSpaceWidth else normalSpaceWidth

                    val processedSyllables = if (line.alignment == KaraokeAlignment.End) {
                        line.syllables.dropLastWhile { it.content.isBlank() }
                    } else {
                        line.syllables
                    }

                    val layout = measureSyllablesAndDetermineAnimation(
                        syllables = processedSyllables,
                        textMeasurer = textMeasurer,
                        style = style,
                        phoneticStyle = phoneticStyle,
                        isAccompanimentLine = line is KaraokeLine.AccompanimentKaraokeLine,
                        spaceWidth = spaceWidth
                    )

                    withContext(Dispatchers.Main) {
                        layoutCache[index] = layout
                    }
                }
            }
        }
    }

    val currentTimeMs: () -> Int = currentPosition

    val timeProvider = remember { currentPosition }

    val accompanimentToMainMap = remember(lyrics.lines) {
        val map = mutableMapOf<Int, Int>()
        val mainLinesIndices = lyrics.lines.indices.filter { index ->
            val line = lyrics.lines[index]
            line !is KaraokeLine || line !is KaraokeLine.AccompanimentKaraokeLine
        }
        if (mainLinesIndices.isNotEmpty()) {
            lyrics.lines.forEachIndexed { index, line ->
                if (line is KaraokeLine && line is KaraokeLine.AccompanimentKaraokeLine) {
                    // Find the main line that is closest in time (either the one just before or just after)
                    val beforeIdx = mainLinesIndices.findLast { it <= index }
                    val afterIdx = mainLinesIndices.find { it >= index }

                    val anchorIndex = when {
                        beforeIdx != null && afterIdx != null -> {
                            val distBefore =
                                (line.start - lyrics.lines[beforeIdx].start).absoluteValue
                            val distAfter =
                                (lyrics.lines[afterIdx].start - line.start).absoluteValue
                            if (distBefore <= distAfter) beforeIdx else afterIdx
                        }

                        beforeIdx != null -> beforeIdx
                        afterIdx != null -> afterIdx
                        else -> mainLinesIndices.first()
                    }
                    map[index] = anchorIndex
                }
            }
        }
        map
    }
    val effectiveEndTimes = remember(lyrics.lines) {
        IntArray(lyrics.lines.size) { index ->
            val line = lyrics.lines[index]
            var maxEnd = line.end

            if (line is KaraokeLine.MainKaraokeLine) {
                line.accompanimentLines?.forEach { acc ->
                    if (acc.end > maxEnd) maxEnd = acc.end
                }
            }
            maxEnd
        }
    }

    val firstLine = lyrics.lines.firstOrNull()

    val haveDotsIntro by remember(firstLine) {
        derivedStateOf {
            if (firstLine == null) false
            else (firstLine.start > 5000)
        }
    }

    val lyricsFocusState by remember(lyrics, effectiveEndTimes, accompanimentToMainMap, haveDotsIntro) {
        derivedStateOf {
            val time = currentTimeMs()
            val activeIndex = lyrics.lines.indices.find { idx ->
                time >= lyrics.lines[idx].start && time < effectiveEndTimes[idx]
            }

            val first = if (activeIndex != null) {
                activeIndex
            } else if (fallbackFocusLine) {
                // 时间窗口没命中时（例如歌词时间戳与播放进度有毫秒级偏差、或行尾时间缺失），
                // 取「已经开始的最近一句」继续高亮与对齐，别让整屏歌词都变成 inactive。
                lyrics.lines.indices.lastOrNull { idx -> time >= lyrics.lines[idx].start }
                    ?: lyrics.lines.indexOfFirst { it.start > time }.takeIf { it != -1 }
                    ?: lyrics.lines.lastIndex
            } else {
                val nextIdx = lyrics.lines.indexOfFirst { it.start > time }
                if (nextIdx != -1) nextIdx else lyrics.lines.lastIndex
            }

            val base = lyrics.lines.indices.filter { index ->
                time >= lyrics.lines[index].start && time < effectiveEndTimes[index]
            }
            val result = if (fallbackFocusLine && base.isEmpty()) mutableSetOf(first) else base.toMutableSet()
            base.forEach { index ->
                val line = lyrics.lines.getOrNull(index)
                if (line is KaraokeLine && line is KaraokeLine.AccompanimentKaraokeLine) {
                    accompanimentToMainMap[index]?.let { result.add(it) }
                }
            }

            val activeInterludeIndex = lyrics.lines.indices.find { index ->
                val line = lyrics.lines[index]
                val previousLine = lyrics.lines.getOrNull(index - 1)
                previousLine != null && (line.start - previousLine.end > 5000) && time in previousLine.end..line.start
            }
            val activeIntro = haveDotsIntro && time in 0 until (firstLine?.start ?: 0)

            FocusState(first, result.toList().sorted(), activeInterludeIndex, activeIntro)
        }
    }

    val scrollInCode = remember { mutableStateOf(false) }
    // 连续滚动时不足 1px 的余量：由 GPU 平移补上，避免列表整数滚动造成的抖动。
    val subPixelOffset = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    val isManualScrolling by remember {
        derivedStateOf {
            listState.isScrollInProgress && !scrollInCode.value
        }
    }

    // 用户手动翻看歌词后，给一个短暂的「不自动拽回」窗口，否则一松手就被拉走。
    val manualPauseUntil = remember { mutableStateOf(0L) }
    val wasManualScrolling = remember { mutableStateOf(false) }
    if (manualScrollPauseMs > 0L) {
        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress && !scrollInCode.value }
                .collect { manual: Boolean ->
                    if (wasManualScrolling.value && !manual) {
                        manualPauseUntil.value = System.currentTimeMillis() + manualScrollPauseMs
                    }
                    wasManualScrolling.value = manual
                }
        }
    }

    LaunchedEffect(
        layoutCache,
        stableOffsetPx,
    ) {
        androidx.compose.runtime.snapshotFlow { lyricsFocusState.firstIndex }
            .collectLatest { firstIndex ->
                if (System.currentTimeMillis() < manualPauseUntil.value) return@collectLatest
                // 连续滚动模式下，换句不再触发滚动（位移交给下面的帧循环），
                // 只有「当前句根本不在可见区」时才需要立刻定位——否则会一直滑不到目标附近。
                if (continuousScroll) {
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == firstIndex }) {
                        val alignOffset = if (centerAlignCurrentLine) {
                            stableOffsetPx + keepAliveZonePx.toInt()
                        } else {
                            -stableOffsetPx - keepAliveZonePx.toInt()
                        }
                        runCatching {
                            scrollInCode.value = true
                            listState.animateScrollToItem(firstIndex, alignOffset.toInt())
                        }
                        scrollInCode.value = false
                    }
                    return@collectLatest
                }
                if (!scrollInCode.value) {
                    val items = listState.layoutInfo.visibleItemsInfo
                    val targetItem = items.firstOrNull { it.index == firstIndex }
                    val scrollOffset =
                        (targetItem?.offset?.minus(listState.layoutInfo.viewportStartOffset + stableOffsetPx + keepAliveZonePx))
                    try {
                        scrollInCode.value = true
                        if (scrollOffset != null) {
                            // 换句时这块原本是瞬时平移，看不出过渡，观感就是"一句一句跳"。
                            // 开启 smoothScroll 后走 380ms 缓动；但一次要跨两行以上
                            // （拖进度、切歌、跳段）仍然瞬移，否则会慢悠悠追一大段，反而更怪。
                            val lineHeightPx = targetItem?.size?.toFloat() ?: 0f
                            val nearEnough = lineHeightPx <= 0f ||
                                kotlin.math.abs(scrollOffset) <= lineHeightPx * 2f
                            if (smoothScroll && nearEnough) {
                                listState.animateScrollBy(
                                    scrollOffset.toFloat(),
                                    tween(durationMillis = 380, easing = FastOutSlowInEasing),
                                )
                            } else {
                                listState.scrollBy(scrollOffset.toFloat())
                            }
                        } else {
                            // 当前句不在可见区时，两条分支必须用同一个目标（距顶 stableOffsetPx）。
                            // 原实现传负值，会把当前句推到 viewport 上方；offset 只有几十 dp 时
                            // （电视端）误差看不出来，手机端 offset 是半个歌词区，误差被放大到整屏。
                            val alignOffset = if (centerAlignCurrentLine) {
                                stableOffsetPx + keepAliveZonePx.toInt()
                            } else {
                                -stableOffsetPx - keepAliveZonePx.toInt()
                            }
                            listState.animateScrollToItem(firstIndex, alignOffset.toInt())
                        }
                    } catch (_: Exception) {
                    } finally {
                        scrollInCode.value = false
                    }
                }
            }
    }
    // 连续滚动：每帧把当前句推到「居中位置 − 本句进度 × 行高」。
    // 一句唱完，它的行顶正好走完一行高，下一句同时补到居中位置——衔接处没有跳变，
    // 整首歌词是一条连续的上行动线，而不是一行一行地顶。
    if (continuousScroll) {
        LaunchedEffect(lyrics, stableOffsetPx, effectiveEndTimes) {
            while (isActive) {
                withFrameNanos { }
                // 用户手动翻看期间不要抢回去（手机端用；电视端 manualScrollPauseMs = 0 不生效）。
                if (System.currentTimeMillis() < manualPauseUntil.value) continue
                if (scrollInCode.value) continue
                val index = lyricsFocusState.firstIndex
                val line = lyrics.lines.getOrNull(index) ?: continue
                val end = effectiveEndTimes.getOrElse(index) { line.end }
                val span = (end - line.start).coerceAtLeast(1)
                val progress = ((currentTimeMs() - line.start).toFloat() / span).coerceIn(0f, 1f)
                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                    ?: continue
                val linePx = item.size.toFloat().coerceAtLeast(1f)
                val desiredTop = stableOffsetPx - progress * linePx
                val delta = (item.offset - listState.layoutInfo.viewportStartOffset) - desiredTop
                if (kotlin.math.abs(delta) > linePx * 3f) {
                    // 拖进度、切歌：跨度太大，直接跟上，别一帧一帧爬。
                    listState.scrollBy(delta)
                    subPixelOffset.floatValue = 0f
                } else {
                    // LazyList 的滚动量是整数像素，每帧不足 1px 的位移会被截断，
                    // 攒几帧才动一格——这就是「抖」的来源。整数部分交给列表，
                    // 零头交给 GPU 平移补偿，两者相加才是连续的。
                    val whole = delta.toInt()
                    if (whole != 0) listState.scrollBy(whole.toFloat())
                    subPixelOffset.floatValue = delta - whole
                }
            }
        }
    }
    LookaheadScope {
        Crossfade(lyrics) { lyrics ->
            Box(modifier = modifier.clipToBounds()) {
                LazyColumn(
                    state = listState,
                    modifier = modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 亚像素余量在这里落地：读 State 只触发重绘，不会引起重组。
                            translationY = subPixelOffset.floatValue
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithCache {
                            onDrawWithContent {
                                drawContent()
                                val topFade = 20.dp.toPx() / size.height
                                val bottomFade = 100.dp.toPx() / size.height
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        topFade to Color.Black,
                                        1f - bottomFade to Color.Black,
                                        1f to Color.Transparent
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        }
                        .layout { measurable, constraints ->
                            val extraHeightPx = (keepAliveZone * 2).roundToPx()

                            val placeable = measurable.measure(
                                constraints.copy(
                                    maxHeight = constraints.maxHeight + extraHeightPx
                                )
                            )

                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(0, -(keepAliveZone.roundToPx()))
                            }
                        },
                    contentPadding = PaddingValues(vertical = stableOffset + keepAliveZone)
                ) {
                    itemsIndexed(
                        items = lyrics.lines,
                        key = { index, line -> "${line.start}-${line.end}-$index" }
                    ) { index, line ->
                        val isCurrentFocusLine = index in lyricsFocusState.allIndices
                        val isLineRtl =
                            when (line) {
                                is KaraokeLine -> {
                                    remember(line.syllables) { line.syllables.any { it.content.isRtl() } }
                                }

                                else -> false
                            }
                        val isLineRightAligned = when (line) {
                            is KaraokeLine -> {
                                remember { line.alignment == KaraokeAlignment.End }
                            }

                            else -> false
                        }
                        val isVisualRightAligned = remember(isLineRightAligned, isLineRtl) {
                            if (isLineRightAligned) !isLineRtl
                            else isLineRtl
                        }

                        val distanceWeightState = remember(useBlurEffect, lyricsFocusState) {
                            derivedStateOf {
                                val start = lyricsFocusState.allIndices.firstOrNull() ?: lyricsFocusState.firstIndex
                                val end = lyricsFocusState.allIndices.lastOrNull() ?: lyricsFocusState.firstIndex
                                maxOf(0, start - index, index - end)
                            }
                        }

                        val dynamicStiffness by remember(distanceWeightState.value) {
                            derivedStateOf {
                                (120f - (distanceWeightState.value * 20f)).coerceAtLeast(20f)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .springPlacement(
                                    this@LookaheadScope,
                                    "${line.start}-${line.end}-$index",
                                    isManualScrolling,
                                    stiffness = dynamicStiffness
                                ),
                            horizontalAlignment = when {
                                centerLines -> Alignment.CenterHorizontally
                                isVisualRightAligned -> Alignment.End
                                else -> Alignment.Start
                            }
                        ) {
                            val animDuration = 600

                            val previousLine = lyrics.lines.getOrNull(index - 1)

                            val showDotsInterlude = lyricsFocusState.activeInterludeIndex == index
                            val showDotsIntro = lyricsFocusState.activeIntro && index == 0

                            AnimatedVisibility(showDotsInterlude || showDotsIntro) {
                                KaraokeBreathingDots(
                                    alignment = when (val line = previousLine ?: firstLine) {
                                        is KaraokeLine -> line.alignment
                                        is SyncedLine -> if (line.content.isRtl()) KaraokeAlignment.End else KaraokeAlignment.Start
                                        else -> KaraokeAlignment.Start
                                    },
                                    startTimeMs = previousLine?.end ?: 0,
                                    endTimeMs = if (showDotsIntro) firstLine!!.start else line.start,
                                    currentTimeProvider = timeProvider,
                                    defaults = breathingDotsDefaults,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }


                            val blurRadiusState = animateFloatAsState(
                                targetValue = (
                                        if (!useBlurEffect) 0f
                                        else if (distanceWeightState.value > 0 && (!listState.isScrollInProgress || scrollInCode.value)) {
                                            distanceWeightState.value * blurDelta
                                        } else 0f),
                                animationSpec = tween(300),
                            )

                            when (line) {
                                is KaraokeLine -> {
                                    if (line is KaraokeLine.MainKaraokeLine) {
                                        LyricsLineItem(
                                            isFocused = isCurrentFocusLine,
                                            isRightAligned = isVisualRightAligned,
                                            onLineClicked = { onLineClicked(line) },
                                            onLinePressed = { onLinePressed(line) },
                                        blurRadius = { blurRadiusState.value },
                                        blendMode = stableBlendMode,
                                        centerHorizontally = centerLines,
                                    ) {
                                        KaraokeLineText(
                                                line = line,
                                                currentTimeProvider = timeProvider,
                                                normalLineTextStyle = stableNormalTextStyle,
                                                accompanimentLineTextStyle = stableAccompanimentTextStyle,
                                                phoneticTextStyle = stablePhoneticTextStyle,
                                                activeColor = textColor,
                                                blendMode = stableBlendMode,
                                                showDebugRectangles = showDebugRectangles,
                                                showTranslation = showTranslation,
                                                showPhonetic = showPhonetic,
                                                precalculatedLayouts = layoutCache[index],
                                                karaokeProgressEnabled = karaokeProgressEnabled,
                                                centerHorizontally = centerLines,
                                            )
                                        }
                                    }
                                }

                                is SyncedLine -> {
                                    val isLineRtl = remember(line.content) { line.content.isRtl() }
                                    LyricsLineItem(
                                        isFocused = isCurrentFocusLine,
                                        isRightAligned = isLineRtl,
                                        onLineClicked = { onLineClicked(line) },
                                        onLinePressed = { onLinePressed(line) },
                                        blurRadius = { blurRadiusState.value },
                                        blendMode = stableBlendMode,
                                        centerHorizontally = centerLines,
                                    ) {
                                        SyncedLineText(
                                            line = line,
                                            isLineRtl = isLineRtl,
                                            textStyle = stableNormalTextStyle.copy(lineHeight = 1.2.em),
                                            textColor = textColor,
                                            showTranslation = showTranslation,
                                            centerHorizontally = centerLines,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item("BottomSpacing") {
                        Spacer(
                            modifier = Modifier.fillMaxWidth().height(2000.dp)
                        )
                    }
                }
            }
        }
    }
}