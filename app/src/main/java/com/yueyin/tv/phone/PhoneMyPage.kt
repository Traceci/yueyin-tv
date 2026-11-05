package com.yueyin.tv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yueyin.tv.BuildConfig
import com.yueyin.tv.core.data.repository.SessionState
import com.yueyin.tv.core.model.Album
import com.yueyin.tv.core.model.Artist
import com.yueyin.tv.core.model.Playlist
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.connect.ConnectRuntime
import kotlinx.coroutines.launch

/**
 * 我的页。
 *
 * 这一页回答的是「我在谁的曲库上、我存了多少东西、去哪改设置」。
 * 电视端同名页面用两条横向影带（歌手 / 专辑）铺满屏幕，那是横向空间富余时的做法；
 * 手机竖屏装不下两条影带还让人找得到重点，因此换成「资料条 + 四个数字 + 平台卡 + 列表」。
 *
 * 数字一律来自保留状态里**已有的**快照，进页面只补三个轻量请求（歌单 / 专辑首页 / 收藏首页），
 * 不拉全量曲库——手机上打开「我的」应该是瞬时的，不该等一本 10 万首的索引。
 */
@Composable
internal fun PhoneMyPage(
    session: SessionState.SignedIn,
    onOpenSegment: (PhoneLibrarySegment) -> Unit,
    onOpenPlatforms: () -> Unit,
    onOpenPane: (PhoneSettingsPane) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val deps = LocalPhoneDeps.current
    val store = LocalPhoneStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val playlistState = store.list<Playlist>("phone:playlists")
    val albumState = store.paged<Album>("phone:albums")
    val artistState = store.paged<Artist>("phone:grid:artists")
    val favoriteState = store.paged<Track>("phone:rows:favorites")
    val favoriteLibrary by deps.musicRepository.favoriteState.collectAsStateWithLifecycle()
    val connectState by ConnectRuntime.store.state.collectAsStateWithLifecycle()


    // 只补第一页：这里要的是「有多少」与「入口是否为空」，不是把整本目录拉下来。
    LaunchedEffect(Unit) {
        store.loadListOnce(playlistState, deps.musicRepository::playlists)
        store.loadFirstPageOnce(albumState, { page -> deps.musicRepository.albums(page, 20) }) { it.guid.value }
        store.loadFirstPageOnce(artistState, { page -> deps.musicRepository.artists(page, 20) }) { it.guid.value }
        store.loadFirstPageOnce(favoriteState, { page -> deps.musicRepository.favoriteTracks(page) }) { it.guid.value }
    }
    // 收藏数要跟着操作变：在其它页面点了心形，回到这里数字必须是新的。
    LaunchedEffect(favoriteLibrary.revision) {
        if (favoriteState.snapshot.initialLoadCompleted) {
            store.loadFirstPageForRevision(
                state = favoriteState,
                revision = favoriteLibrary.revision,
                loader = { page -> deps.musicRepository.favoriteTracks(page) },
                key = { it.guid.value },
            )
        }
    }

    val window = LocalPhoneWindow.current
    // 飞牛会话不是一条 ConnectProfile（它挂在登录账号上），所以两个地方都要算上它。
    val onFnSession = session.server.guid.value != "local"
    val platform = phonePlatformLabel(session)
    val platformType = connectState.active?.type?.displayName.orEmpty()
    val connected = connectState.active != null || onFnSession
    val displayName = session.user.nickname?.takeIf { it.isNotBlank() } ?: session.user.username
    val serverName = session.server.name
        .takeIf { it.isNotBlank() && session.server.guid.value != "local" }
    // 切到「在线音乐」后不再挂 NAS 名：那时曲库来自音源脚本，跟这台 NAS 没关系，
    // 名字还留在资料条上会让人误以为仍在读 NAS 的库（首页副标题同一套判断）。
    val shownServer = serverName
        .takeIf { connectState.active?.type != com.yueyin.tv.core.model.connect.ConnectType.ONLINE }

    /**
     * 横屏把「我是谁 / 我存了多少」和「设置入口」拆成左右两栏。
     *
     * 横屏内容区 810dp、高度只有 411dp，单列铺下去要滚三屏才能看到「关于」。
     * 两栏各 393dp 后一屏基本能看到底，且左栏（身份+数字）与右栏（设置+账号）
     * 刚好是两类信息，拆开不会打断阅读顺序。竖屏保持单列不动。
     */
    val twoColumns = window.isLandscape && window.contentWidthDp >= 700

    val identitySection: @Composable () -> Unit = {
        Column {
            ProfileCard(
                displayName = displayName,
                serverName = shownServer,
                platform = platform,
                platformType = platformType,
            )
            Spacer(Modifier.height(PhoneSpace.lg))

            QuickStats(
                favorites = favoriteState.snapshot.total
                    ?: favoriteState.snapshot.entries.size.takeIf { favoriteState.snapshot.initialLoadCompleted },
                playlists = playlistState.snapshot.entries.size.takeIf { playlistState.snapshot.initialLoadCompleted },
                albums = albumState.snapshot.total ?: albumState.snapshot.entries.size
                    .takeIf { albumState.snapshot.initialLoadCompleted },
                artists = artistState.snapshot.total ?: artistState.snapshot.entries.size
                    .takeIf { artistState.snapshot.initialLoadCompleted },
                onOpenSegment = onOpenSegment,
            )
            Spacer(Modifier.height(PhoneSpace.xl))

            PlatformCard(
                platform = platform,
                platformType = platformType,
                serverName = shownServer,
                connected = connected,
                onClick = onOpenPlatforms,
            )
        }
    }

    val settingsSection: @Composable () -> Unit = {
        Column {
            // 设置项合并进「我的」：原来独立的「设置」tab 里的内容全部作为二级入口列在这里，
            // 点进去是原来的全屏面板，返回键回到「我的」。这样底栏只剩三个 tab，不再重复。
            MyGroup(title = "设置") {
                MyRow("平台管理", "连接、编辑、切换音乐平台", PhoneGlyph.Settings, onOpenPlatforms)
                MyRow("播放界面", "封面样式、界面缩放、歌词匹配", PhoneGlyph.Play) {
                    onOpenPane(PhoneSettingsPane.Player)
                }
                MyRow("音效模式", "八档均衡器预设", PhoneGlyph.Fx) {
                    onOpenPane(PhoneSettingsPane.Fx)
                }
                MyRow("存储与缓存", "封面与索引占用、清理", PhoneGlyph.Library) {
                    onOpenPane(PhoneSettingsPane.Cache)
                }
                MyRow("关于悦音", "版本 ${BuildConfig.VERSION_NAME}、更新、崩溃日志", PhoneGlyph.My) {
                    onOpenPane(PhoneSettingsPane.About)
                }
            }
            Spacer(Modifier.height(PhoneSpace.xl))

        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhonePageTitle(
            title = "我的",
            trailing = {
                Box(
                    Modifier.size(PhoneSize.TouchTarget).phoneTap(onOpenSearch),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoneIcon(PhoneGlyph.Search, FnColors.Muted, Modifier.size(21.dp))
                }
            },
        )
        Spacer(Modifier.height(PhoneSpace.xs))

        if (twoColumns) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.xl),
            ) {
                Box(Modifier.weight(1f)) { identitySection() }
                Box(Modifier.weight(1f)) { settingsSection() }
            }
        } else {
            identitySection()
            Spacer(Modifier.height(PhoneSpace.xl))
            settingsSection()
        }

        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

// ---------------------------------------------------------------------------
// 资料条
// ---------------------------------------------------------------------------

@Composable
private fun ProfileCard(
    displayName: String,
    serverName: String?,
    platform: String,
    platformType: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = PhoneSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFFF8A70), Color(0xFFD6A35E), Color(0xFF70C8AF)),
                        start = Offset.Zero,
                        end = Offset(220f, 220f),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                displayName.trim().take(1).ifBlank { "音" }.uppercase(),
                color = FnColors.Background,
                fontSize = PhoneType.Title,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(PhoneSpace.md))
        Column(Modifier.weight(1f)) {
            Text(
                displayName,
                color = FnColors.Text,
                fontSize = PhoneType.Headline,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(PhoneSpace.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformChip(platform)
                if (serverName != null && serverName != platform) {
                    Spacer(Modifier.width(PhoneSpace.sm))
                    Text(
                        serverName,
                        color = FnColors.Muted,
                        fontSize = PhoneType.Micro,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (platformType.isNotBlank() && platformType != platform) {
                    Spacer(Modifier.width(PhoneSpace.sm))
                    Text(
                        platformType,
                        color = FnColors.Muted.copy(alpha = 0.7f),
                        fontSize = PhoneType.Micro,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 平台胶囊：珊瑚色描边的小标签，用来回答「我现在连的是谁」。 */
@Composable
private fun PlatformChip(label: String) {
    Box(
        Modifier
            .background(FnColors.Coral.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .border(1.dp, FnColors.Coral.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        Text(
            label.ifBlank { "未连接" },
            color = FnColors.Coral,
            fontSize = PhoneType.Micro,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 数字四宫格
// ---------------------------------------------------------------------------

/**
 * 四个数字入口。
 *
 * 数字为 null 时显示「—」而不是 0：0 是「确实一首都没有」，null 是「还没加载出来」，
 * 混成 0 会让用户以为收藏被清空了。点任意一格都跳到曲库的对应分段，
 * 这是比「先点我的、再点收藏」少一次点击的直达路径。
 */
@Composable
private fun QuickStats(
    favorites: Int?,
    playlists: Int?,
    albums: Int?,
    artists: Int?,
    onOpenSegment: (PhoneLibrarySegment) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm),
    ) {
        StatTile("收藏", favorites, PhoneLibrarySegment.Favorites, onOpenSegment, Modifier.weight(1f))
        StatTile("歌单", playlists, PhoneLibrarySegment.Playlists, onOpenSegment, Modifier.weight(1f))
        StatTile("专辑", albums, PhoneLibrarySegment.Albums, onOpenSegment, Modifier.weight(1f))
        StatTile("歌手", artists, PhoneLibrarySegment.Artists, onOpenSegment, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(
    label: String,
    value: Int?,
    segment: PhoneLibrarySegment,
    onOpenSegment: (PhoneLibrarySegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.md))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(PhoneRadius.md))
            .phoneTap { onOpenSegment(segment) }
            .padding(vertical = PhoneSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value?.let(::phoneCount) ?: "—",
            color = if (value == null) FnColors.Muted.copy(alpha = 0.5f) else FnColors.Text,
            fontSize = PhoneType.Section,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(label, color = FnColors.Muted, fontSize = PhoneType.Micro, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// 当前平台卡
// ---------------------------------------------------------------------------

@Composable
private fun PlatformCard(
    platform: String,
    platformType: String,
    serverName: String?,
    connected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.lg))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(PhoneRadius.lg))
            .phoneTap(onClick)
            .padding(PhoneSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 绿色小圆点表示「已连接」；没连任何平台时用灰点，避免误导成已接入。
        Box(
            Modifier.size(8.dp)
                .background(if (connected) Color(0xFF5FD39A) else Color.White.copy(alpha = 0.25f), CircleShape),
        )
        Spacer(Modifier.width(PhoneSpace.md))
        Column(Modifier.weight(1f)) {
            Text(
                platform,
                color = FnColors.Text,
                fontSize = PhoneType.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildList {
                    if (connected) {
                        if (platformType.isNotBlank() && platformType != platform) add(platformType)
                        serverName?.let(::add)
                        add("已连接")
                    } else {
                        add("新增平台以接入曲库")
                    }
                }.joinToString(" · "),
                color = FnColors.Muted,
                fontSize = PhoneType.Micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("管理", color = FnColors.Coral, fontSize = PhoneType.Caption, maxLines = 1)
        PhoneIcon(PhoneGlyph.ChevronRight, FnColors.Coral, Modifier.size(13.dp), strokeWidth = 1.6.dp)
    }
}

// ---------------------------------------------------------------------------
// 列表
// ---------------------------------------------------------------------------

@Composable
private fun MyGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = PhoneSpace.xs, bottom = PhoneSpace.sm),
            maxLines = 1,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.lg))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(PhoneRadius.lg)),
        ) {
            content()
        }
    }
}

@Composable
private fun MyRow(
    title: String,
    caption: String,
    glyph: PhoneGlyph,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PhoneSize.RowItem)
            .phoneTap(onClick)
            .padding(horizontal = PhoneSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneIcon(glyph, FnColors.Muted, Modifier.size(19.dp))
        Spacer(Modifier.width(PhoneSpace.md))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = FnColors.Text,
                fontSize = PhoneType.Row,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                caption,
                color = FnColors.Muted,
                fontSize = PhoneType.Micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhoneIcon(
            PhoneGlyph.ChevronRight,
            FnColors.Muted.copy(alpha = 0.7f),
            Modifier.size(14.dp),
            strokeWidth = 1.6.dp,
        )
    }
}


@Composable
private fun DialogButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(44.dp)
            .background(
                when {
                    !enabled -> FnColors.SurfaceAlt
                    primary -> FnColors.Coral
                    else -> FnColors.SurfaceAlt
                },
                shape,
            )
            .border(
                1.dp,
                if (primary) Color.Transparent else Color.White.copy(alpha = 0.12f),
                shape,
            )
            .then(if (enabled) Modifier.phoneTap(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> FnColors.Muted
                primary -> FnColors.Background
                else -> FnColors.Text
            },
            fontSize = PhoneType.Row,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
