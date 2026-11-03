package com.yueyin.tv.phone

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yueyin.tv.core.data.repository.SessionState
import com.yueyin.tv.core.model.Album
import com.yueyin.tv.core.model.AppError
import com.yueyin.tv.core.model.Playlist
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.playback.PlaybackUiState
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.applyPlaylistOrder
import com.yueyin.tv.ui.appErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页。
 *
 * 三层信息，从上到下依次回答三个问题：「现在听谁的库」「今天听什么」「我的歌单在哪」：
 *
 * 1. 问候条 —— 平台名 + 曲目总数，多平台用户靠它确认当前曲库；
 * 2. 随心听大卡 —— 唯一的主行动，一整块可点，不需要精确点中某个小按钮；
 * 3. 最近添加（横滑）+ 我的歌单（两列） —— 有明确目标的用户在这里直达。
 *
 * 电视端首页是「叠卡 + 横向影带 + 焦点驱动」，手机没有遥控器，所有内容都要靠手指直接点，
 * 因此不做叠卡，也不做需要先聚焦再确认的两步操作。
 */
@Composable
internal fun PhoneHomePage(
    session: SessionState.SignedIn,
    playback: PlaybackUiState,
    onOpenSearch: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenAllPlaylists: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
) {
    val deps = LocalPhoneDeps.current
    val store = LocalPhoneStore.current
    val context = LocalContext.current
    val window = LocalPhoneWindow.current
    val preferences by deps.appPreferences.state.collectAsStateWithLifecycle()
    val playlistState = store.list<Playlist>("phone:playlists")
    val albumState = store.paged<Album>("phone:albums")
    val playlistSnapshot = playlistState.snapshot
    val albumSnapshot = albumState.snapshot
    val scope = rememberCoroutineScope()
    val platformKey = phonePlatformKey(session)

    var trackTotal by remember { mutableStateOf<Int?>(null) }
    var roamBusy by remember { mutableStateOf(false) }
    var roamError by remember { mutableStateOf<AppError?>(null) }
    var recentTracks by remember { mutableStateOf<List<Track>>(emptyList()) }

    LaunchedEffect(Unit) {
        store.loadListOnce(playlistState, deps.musicRepository::playlists)
        store.loadFirstPageOnce(
            albumState,
            { page -> deps.musicRepository.albums(page, HOME_ALBUM_PAGE_SIZE) },
        ) { it.guid.value }
    }

    // 最近播放按平台读：切平台后列表必须是新平台的，否则点下去必然取链失败。
    // key 里带上 platformKey，切平台时重读一次，不用等页面重进。
    LaunchedEffect(platformKey) {
        recentTracks = PhoneRecentlyPlayed.toTrackList(PhoneRecentlyPlayed.read(context, platformKey))
    }

    /**
     * 点最近播放的卡片。
     *
     * 先按记录的 guid 直接起播；失败再按歌名在当前平台搜一次——平台的索引可能已经重建
     * （换音源脚本、服务端重新扫描），老 guid 会失效，但歌还在。两处都不中才提示播不了。
     */
    val playRecent: (Track) -> Unit = { track ->
        scope.launch {
            var ok = PhonePlayback.start(deps, listOf(track), 0, store.scope)
            if (!ok) {
                val found = withContext(Dispatchers.IO) {
                    runCatching { deps.musicRepository.search(track.title) }
                        .getOrNull()
                        ?.tracks
                        .orEmpty()
                }
                val hit = found.firstOrNull {
                    it.title.equals(track.title, ignoreCase = true) && it.artistName == track.artistName
                } ?: found.firstOrNull { it.title.equals(track.title, ignoreCase = true) }
                if (hit != null) ok = PhonePlayback.start(deps, listOf(hit), 0, store.scope)
            }
            if (!ok) Toast.makeText(context, "这首在当前平台播不了", Toast.LENGTH_SHORT).show()
        }
    }

    // 曲目总数与随心听预热共用一次 `allTracks(1)`：
    // 在线音源首次拉这本索引要跑一遍隐藏 WebView 的榜单脚本（8~10 秒），
    // 不预热的话第一次点随心听就是「点了没反应」。这里同时把 total 拿出来喂给副标题。
    LaunchedEffect(playlistSnapshot.initialLoadCompleted) {
        if (!playlistSnapshot.initialLoadCompleted) return@LaunchedEffect
        delay(1_200)
        trackTotal = withContext(Dispatchers.IO) {
            runCatching { deps.musicRepository.allTracks(1).total }.getOrNull()
        }
        PhoneRoam.prepareInBackground(deps, scope)
    }

    val playlists = remember(playlistSnapshot.entries, preferences.playlistOrder) {
        applyPlaylistOrder(playlistSnapshot.entries, preferences.playlistOrder)
    }
    val albums = albumSnapshot.entries

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        Spacer(Modifier.height(PhoneSpace.xs))
        GreetingRow(session = session, trackTotal = trackTotal)
        Spacer(Modifier.height(PhoneSpace.lg))
        SearchEntry(onClick = onOpenSearch)
        Spacer(Modifier.height(PhoneSpace.lg))
        // 两个主行动并排：随心听（随机放）+ 我的收藏（直达收藏曲库）。
        //
        // 横屏改成四宫格横排：横屏内容区有 810dp，两张 156dp 高的卡各占 400dp 会扁得像横幅，
        // 同时纵向只剩 411dp，卡片高一点就把下面的影带彻底挤出屏幕。四张 104dp 的小卡
        // 把纵向省下的 52dp 还给内容，横向也把四个高频入口一次摆出来，不用再往下滚去找歌单。
        if (window.isLandscape) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
            ) {
                QuickTile(
                    title = "随心听",
                    caption = if (roamBusy || playback.roamBusy) "正在准备…" else "随机混排",
                    glyph = PhoneGlyph.Shuffle,
                    accent = true,
                    onClick = {
                        if (!roamBusy) {
                            roamBusy = true
                            roamError = null
                            PhoneRoam.play(
                                deps = deps,
                                scope = scope,
                                onStarted = { },
                                onFailed = { roamError = AppError.Empty },
                                onFinished = { roamBusy = false },
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                QuickTile(
                    title = "我的收藏",
                    caption = "收藏的曲目",
                    glyph = PhoneGlyph.Heart,
                    onClick = onOpenFavorites,
                    modifier = Modifier.weight(1f),
                )
                QuickTile(
                    title = "全部专辑",
                    caption = "按封面浏览",
                    glyph = PhoneGlyph.Library,
                    onClick = onOpenAlbums,
                    modifier = Modifier.weight(1f),
                )
                QuickTile(
                    title = "全部歌单",
                    caption = "按歌单浏览",
                    glyph = PhoneGlyph.Queue,
                    onClick = onOpenAllPlaylists,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
            ) {
                RoamCard(
                    busy = roamBusy || playback.roamBusy,
                    onClick = {
                        if (!roamBusy) {
                            roamBusy = true
                            roamError = null
                            PhoneRoam.play(
                                deps = deps,
                                scope = scope,
                                onStarted = { },
                                onFailed = { roamError = AppError.Empty },
                                onFinished = { roamBusy = false },
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                FavoritesCard(
                    onClick = onOpenFavorites,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (roamError != null) {
            Spacer(Modifier.height(PhoneSpace.sm))
            Text(
                appErrorMessage(roamError!!),
                color = FnColors.Coral,
                fontSize = PhoneType.Caption,
                modifier = Modifier.padding(horizontal = PhoneSpace.xs),
            )
        }

        if (albums.isNotEmpty()) {
            PhoneSectionHeader(
                title = "最近添加",
                actionLabel = "全部",
                onAction = onOpenAlbums,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(albums.take(12), key = { it.guid.value }) { album ->
                    Box(Modifier.width(PhoneSize.StripItem)) {
                        PhoneAlbumCard(album = album, onClick = { onOpenAlbum(album) })
                    }
                }
            }
        }

        // 最近播放：真实播放历史，点卡片直接再播这首。历史按平台隔离，
        // 换平台后不会出现「另一个库里的歌」，点下去也不会取链失败。
        if (recentTracks.isNotEmpty()) {
            PhoneSectionHeader(title = "最近播放")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(recentTracks, key = { it.guid.value }) { track ->
                    Box(Modifier.width(PhoneSize.StripItem)) {
                        PhoneRecentTrackCard(track = track, onClick = { playRecent(track) })
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            PhoneSectionHeader(
                title = "我的歌单",
                actionLabel = "全部",
                onAction = onOpenAllPlaylists,
            )
            // 网格手写在 Column 里：首页只展示三行歌单，
            // 用 LazyVerticalGrid 反而要在可滚动 Column 里嵌一个不可滚动的网格，没有收益。
            // 列数跟着可用宽度反算（与曲库网格同一套规则），否则横屏上两列会拉成两张巨卡。
            val columns = window.gridColumns
            playlists.take(columns * 3).chunked(columns).forEach { rowItems ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = PhoneSpace.lg),
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
                ) {
                    rowItems.forEach { playlist ->
                        Box(Modifier.weight(1f)) {
                            PhonePlaylistCard(playlist = playlist, onClick = { onOpenPlaylist(playlist) })
                        }
                    }
                    // 末行不满时补空位，避免最后一张卡被拉伸成整行宽。
                    repeat(columns - rowItems.size) { Box(Modifier.weight(1f)) }
                }
            }
        }

        if (albums.isEmpty() && playlists.isEmpty()) {
            if (playlistSnapshot.initialLoadCompleted || albumSnapshot.initialLoadCompleted) {
                if (playlistSnapshot.error != null || albumSnapshot.error != null) {
                    PhoneEmptyState(
                        title = "曲库读取失败",
                        caption = (playlistSnapshot.error ?: albumSnapshot.error)?.let(::appErrorMessage),
                    )
                } else {
                    PhoneEmptyState(
                        title = "曲库还是空的",
                        caption = "先在「我的 → 平台」连接一个音乐平台，或导入在线音源脚本",
                    )
                }
            }
        }

        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

/** 首页一次只取 12 张专辑：横滑影带最多露 3 张，「全部」跳曲库看整本。 */
private const val HOME_ALBUM_PAGE_SIZE = 12

@Composable
private fun GreetingRow(session: SessionState.SignedIn, trackTotal: Int?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                phoneGreeting(),
                color = FnColors.Text,
                fontSize = PhoneType.Display,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(PhoneSpace.xs))
            Text(
                phoneLibrarySubtitle(session, trackTotal),
                color = FnColors.Muted,
                fontSize = PhoneType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(PhoneSpace.md))
        val name = session.user.nickname?.takeIf { it.isNotBlank() } ?: session.user.username
        Box(
            Modifier
                .size(42.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFFF8A70), Color(0xFFD6A35E), Color(0xFF70C8AF)),
                        start = Offset.Zero,
                        end = Offset(200f, 200f),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(1).ifBlank { "音" }.uppercase(),
                color = FnColors.Background,
                fontSize = PhoneType.Body,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * 搜索入口。
 *
 * 不是真的输入框，是一个长得像输入框的按钮：点击进入搜索页后由搜索页持有输入焦点。
 * 首页直接放输入框会让软键盘在滚动列表上弹出来，把整个首页顶变形。
 */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(FnColors.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .phoneTap(onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneIcon(PhoneGlyph.Search, FnColors.Muted, Modifier.size(17.dp), strokeWidth = 1.9.dp)
        Spacer(Modifier.width(10.dp))
        Text("搜索歌曲、歌手、专辑", color = FnColors.Muted, fontSize = PhoneType.Row, maxLines = 1)
    }
}

/**
 * 随心听大卡。
 *
 * 整卡可点，右下角画一个播放圆钮只是「这里点了会放歌」的提示，不是独立的点击目标——
 * 把圆钮做成独立按钮会让卡片出现两个热区，用户点空白处以为没反应。
 */
@Composable
private fun RoamCard(busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(156.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(Color(0xFFFF7A5C), Color(0xFFFF9C5C), Color(0xFFFFB46B)),
                    start = Offset.Zero,
                    end = Offset(900f, 500f),
                ),
                shape = RoundedCornerShape(PhoneRadius.xl),
            )
            .phoneTap(onClick)
            .padding(PhoneSpace.lg),
    ) {
        Column(Modifier.align(Alignment.TopStart)) {
            Text("随心听", color = Color(0xFF20120C), fontSize = PhoneType.Title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(PhoneSpace.sm))
            Text(
                if (busy) "正在准备…" else "随机混排",
                color = Color(0xFF20120C).copy(alpha = 0.78f),
                fontSize = PhoneType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(44.dp)
                .background(Color(0xE0160D09), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhoneIcon(PhoneGlyph.Play, FnColors.Amber, Modifier.size(18.dp))
        }
    }
}

/**
 * 横屏用的紧凑入口卡。
 *
 * 与 [RoamCard] / [FavoritesCard] 的区别只有尺寸与对齐：那两张是竖屏的「主行动」，
 * 156dp 高、标题 22sp；横屏四张并排时若沿用同款，每张宽 190dp 却高 156dp，
 * 比例接近方块、整块占满半屏。这里压到 104dp、标题降到 17sp，
 * 四个入口在一条线上，纵向把空间留给下面的影带与歌单。
 */
@Composable
private fun QuickTile(
    title: String,
    caption: String,
    glyph: PhoneGlyph,
    accent: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PhoneRadius.lg)
    val titleColor = if (accent) Color(0xFF20120C) else FnColors.Text
    val captionColor = if (accent) Color(0xFF20120C).copy(alpha = 0.78f) else FnColors.Muted
    Box(
        modifier
            .height(104.dp)
            .then(
                if (accent) {
                    Modifier.background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFFFF7A5C), Color(0xFFFF9C5C), Color(0xFFFFB46B)),
                            start = Offset.Zero,
                            end = Offset(600f, 320f),
                        ),
                        shape = shape,
                    )
                } else {
                    Modifier
                        .background(FnColors.Surface, shape)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                },
            )
            .phoneTap(onClick)
            .padding(PhoneSpace.md),
    ) {
        Column(Modifier.align(Alignment.TopStart)) {
            Text(
                title,
                color = titleColor,
                fontSize = PhoneType.Section,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                caption,
                color = captionColor,
                fontSize = PhoneType.Micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(30.dp)
                .background(
                    if (accent) Color(0xE0160D09) else FnColors.Coral.copy(alpha = 0.16f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            PhoneIcon(
                glyph,
                if (accent) FnColors.Amber else FnColors.Coral,
                Modifier.size(15.dp),
                filled = !accent,
            )
        }
    }
}

/** 我的收藏入口卡：与随心听并排，点了直达收藏曲库分段。 */
@Composable
private fun FavoritesCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(156.dp)
            .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.xl))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(PhoneRadius.xl))
            .phoneTap(onClick)
            .padding(PhoneSpace.lg),
    ) {
        Column(Modifier.align(Alignment.TopStart)) {
            Text("我的收藏", color = FnColors.Text, fontSize = PhoneType.Title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(PhoneSpace.sm))
            Text(
                "收藏的曲目",
                color = FnColors.Muted,
                fontSize = PhoneType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(44.dp)
                .background(FnColors.Coral.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhoneIcon(PhoneGlyph.Heart, FnColors.Coral, Modifier.size(20.dp), filled = true)
        }
    }
}

/** 最近播放卡片：方形封面 + 歌名，点一下直接再播。 */
@Composable
private fun PhoneRecentTrackCard(track: Track, onClick: () -> Unit) {
    val artist = track.artistName
    Column(
        Modifier.phoneTap(onClick),
    ) {
        PhoneArtwork(
            coverId = track.coverId,
            title = track.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(PhoneRadius.md),
        )
        Spacer(Modifier.height(PhoneSpace.xs))
        Text(
            track.title,
            color = FnColors.Text,
            fontSize = PhoneType.Caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (artist?.isNotBlank() == true) {
            Text(
                artist,
                color = FnColors.Muted,
                fontSize = PhoneType.Micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
