package com.yueyin.tv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yueyin.tv.core.model.Album
import com.yueyin.tv.core.model.AppError
import com.yueyin.tv.core.model.AppException
import com.yueyin.tv.core.model.Page
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.appErrorMessage
import com.yueyin.tv.ui.retainLoadedPage
import com.yueyin.tv.ui.shouldLoadInitialPage
import kotlinx.coroutines.launch

/**
 * 集合详情页（专辑 / 歌单 / 歌手 / 收藏共用）。
 *
 * 一个页面覆盖四种集合，是因为它们在手机上的结构完全一致：**头部信息 + 播放全部 + 曲目列表**。
 * 电视端专辑详情带「专辑/歌手」双 Tab、歌手详情带「热门/专辑」双 Tab，那是横屏有横向空间才做的
 * 分区；竖屏一屏只放得下一列内容，分区反而增加一次点击才能听到歌。
 *
 * 差异通过参数表达：封面的形状（歌手用圆形）、副标题文案、曲目来源、底部是否挂一张关联专辑带。
 */
@Composable
internal fun PhoneTrackCollectionPage(
    stateKey: String,
    kindLabel: String,
    title: String,
    coverId: String?,
    subtitle: String,
    onBack: () -> Unit,
    loader: suspend (Int) -> Page<Track>,
    emptyTitle: String,
    emptyCaption: String?,
    playingTrackId: String?,
    onPlayTracks: (List<Track>, Int) -> Unit,
    coverShape: Shape = RoundedCornerShape(PhoneRadius.md),
    favoriteStatuses: Map<String, Boolean> = emptyMap(),
    onToggleFavorite: (Track, Boolean) -> Unit = { _, _ -> },
    relatedAlbums: PhoneRelatedAlbums? = null,
    onOpenAlbum: (Album) -> Unit = {},
    showBack: Boolean = true,
) {
    val deps = LocalPhoneDeps.current
    val store = LocalPhoneStore.current
    val state = store.paged<Track>(stateKey)
    val snapshot = state.snapshot
    val tracks = snapshot.entries
    val listState = rememberLazyListState()

    val load: (Int) -> Unit = { target ->
        if (!state.loading && (target == 1 || state.snapshot.hasNext)) {
            state.loading = true
            store.scope.launch {
                runCatching { loader(target) }
                    .onSuccess { state.snapshot = retainLoadedPage(state.snapshot, it) { track -> track.guid.value } }
                    .onFailure {
                        state.snapshot = state.snapshot.copy(
                            error = (it as? AppException)?.error ?: AppError.Unknown(it.message),
                            initialLoadCompleted = state.snapshot.initialLoadCompleted || target == 1,
                        )
                    }
                state.loading = false
            }
        }
    }

    LaunchedEffect(stateKey) {
        if (shouldLoadInitialPage(state.snapshot)) load(1)
    }
    LaunchedEffect(listState, snapshot.initialLoadCompleted, snapshot.page, snapshot.hasNext) {
        if (!snapshot.initialLoadCompleted) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 5) load(state.snapshot.page + 1)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = PhoneSpace.xxl),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.padding(horizontal = PhoneSpace.screenHorizontal)) {
                PhoneBackHeader(title = kindLabel, onBack = if (showBack) onBack else null)
                Row(
                    Modifier.fillMaxWidth().padding(top = PhoneSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhoneArtwork(
                        coverId = coverId,
                        title = title,
                        modifier = Modifier.size(96.dp),
                        shape = coverShape,
                    )
                    Spacer(Modifier.width(PhoneSpace.lg))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            color = FnColors.Text,
                            fontSize = PhoneType.Headline,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(PhoneSpace.xs))
                        Text(
                            subtitle,
                            color = FnColors.Muted,
                            fontSize = PhoneType.Caption,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(PhoneSpace.lg))
                // 两个按钮平分一行：主行动（播放全部）保持实心珊瑚色，随机播放退成描边。
                // 不做「主按钮通栏 + 随机塞进 ⋮ 菜单」——手机上「随机播放一个歌单」是高频操作，
                // 藏进二级菜单等于取消这个功能。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
                ) {
                    PhonePrimaryButton(
                        label = "播放全部",
                        enabled = tracks.any(::isPlayable),
                        onClick = {
                            val first = tracks.indexOfFirst(::isPlayable)
                            if (first >= 0) onPlayTracks(tracks, first)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PhoneOutlineButton(
                        label = "随机",
                        glyph = PhoneGlyph.Shuffle,
                        enabled = tracks.any(::isPlayable),
                        onClick = {
                            // 随机播放 = 打乱后从头播。不调用播放器的 shuffle 模式：
                            // 用户点「随机」想要的是「现在这首随机」，不是「以后一直随机」。
                            val playable = tracks.filter(::isPlayable)
                            if (playable.isNotEmpty()) onPlayTracks(playable.shuffled(), 0)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(PhoneSpace.md))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "共 ${phoneCount(snapshot.total ?: tracks.size)} 首",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Caption,
                        modifier = Modifier.weight(1f),
                    )
                    snapshot.error?.let { error ->
                        Text(
                            appErrorMessage(error),
                            color = FnColors.Coral,
                            fontSize = PhoneType.Micro,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(PhoneSpace.sm))
                // 关联专辑带挂在头部而不是列表末尾：歌手动辄上百首曲目，
                // 放在末尾等于藏起来（实测滚到第 113 首才看得见）。
                relatedAlbums?.let { spec ->
                    RelatedAlbumsSection(
                        stateKey = "$stateKey:related",
                        spec = spec,
                        onOpenAlbum = onOpenAlbum,
                    )
                }
            }
        }

        itemsIndexed(tracks, key = { _, track -> track.guid.value }) { index, track ->
            Row(Modifier.padding(horizontal = PhoneSpace.screenHorizontal)) {
                val favorite = favoriteOf(track, favoriteStatuses)
                PhoneTrackRow(
                    track = track,
                    index = index,
                    current = playingTrackId != null && playingTrackId == track.guid.value,
                    onClick = { onPlayTracks(tracks, index) },
                    favorite = favorite,
                    onToggleFavorite = { onToggleFavorite(track, favorite) },
                )
            }
        }

        if (tracks.isEmpty() && snapshot.initialLoadCompleted && snapshot.error == null) {
            item { PhoneEmptyState(title = emptyTitle, caption = emptyCaption) }
        }
        item {
            PhoneListFooter(
                loading = state.loading,
                hasMore = snapshot.hasNext,
                error = snapshot.error,
                onRetry = { load(if (tracks.isEmpty()) 1 else snapshot.page + 1) },
            )
        }
    }
}

/** 与电视端同一个可播放判定：CUE 分轨与无版权（accessStatus != 0）都不进播放队列。 */
private fun isPlayable(track: Track): Boolean =
    !track.isCue && (track.accessStatus == null || track.accessStatus == 0)

/**
 * 主行动按钮。
 *
 * 48dp 高、珊瑚色实底：手机竖屏上「播放全部」是详情页唯一的主行动，
 * 做成描边按钮会被周边细线卡片淹没。
 *
 * 宽度交给调用方（`Modifier.weight` / `fillMaxWidth`），这里不写死通栏——
 * 它现在和「随机」按钮平分一行，内部再抢一次最大宽度会和 Row 的权重分配打架。
 */
@Composable
internal fun PhonePrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(PhoneSize.PrimaryButton)
            .background(
                color = if (enabled) FnColors.Coral else FnColors.SurfaceAlt,
                shape = RoundedCornerShape(14.dp),
            )
            .then(if (enabled) Modifier.phoneTap(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) FnColors.Background else FnColors.Muted,
            fontSize = PhoneType.Body,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * 详情页底部的关联专辑带。
 *
 * 只用于「歌手详情」：歌手页光有曲目列表说不清「这个人出过哪些专辑」，
 * 而专辑详情没法反查歌手 guid（`Album` 只带 artistName，没有 artistGuid），
 * 硬要反查得先按名字搜歌手再比对，一次跳转里做两次网络请求，不如不做。
 */
internal data class PhoneRelatedAlbums(
    val title: String,
    val excludeGuid: String? = null,
    val loader: suspend (Int) -> Page<Album>,
)

@Composable
private fun RelatedAlbumsSection(
    stateKey: String,
    spec: PhoneRelatedAlbums,
    onOpenAlbum: (Album) -> Unit,
) {
    val store = LocalPhoneStore.current
    val state = store.paged<Album>(stateKey)
    LaunchedEffect(stateKey) {
        store.loadFirstPageOnce(state, spec.loader) { it.guid.value }
    }
    // 排除当前专辑：歌手的专辑列表里混进「正在看的这张」会让人以为重复了。
    val albums = state.snapshot.entries
        .filter { spec.excludeGuid == null || it.guid.value != spec.excludeGuid }
        .take(12)
    if (albums.isEmpty()) return

    // 左右边距由调用方（详情页头部）统一给，这里不再叠加，否则会变成 32dp 的双倍留白。
    Column {
        PhoneSectionHeader(title = spec.title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md)) {
            items(albums, key = { it.guid.value }) { album ->
                Box(Modifier.width(PhoneSize.StripItem)) {
                    PhoneAlbumCard(album = album, onClick = { onOpenAlbum(album) })
                }
            }
        }
        Spacer(Modifier.height(PhoneSpace.md))
    }
}
