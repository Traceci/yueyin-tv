package com.yueyin.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.Text
import com.yueyin.tv.core.model.CollectionGuid
import com.yueyin.tv.core.model.Playlist
import com.yueyin.tv.core.model.playback.PlayMode
import com.yueyin.tv.core.playback.PlaybackUiState
import com.yueyin.tv.ui.BandEntry
import com.yueyin.tv.ui.BandKind
import com.yueyin.tv.ui.FeatureArtworkItem
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.YueYinTheme
import com.yueyin.tv.ui.HomePlaylistLockup
import com.yueyin.tv.ui.HomeScreenLayout
import com.yueyin.tv.ui.LocalAuthenticatedDependencies
import com.yueyin.tv.ui.MediaBand
import com.yueyin.tv.ui.PlayerControlOverlay
import com.yueyin.tv.ui.PlayerTransportOsd
import com.yueyin.tv.ui.TransportOsd
import com.yueyin.tv.ui.TransportOsdGlyph
import com.yueyin.tv.ui.PlaylistTile
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.serialization.json.put


/**
 * 仅 debug 变体存在的版式预览页：用假数据渲染真实的首页 Composable，
 * 便于在模拟器/真机上截图评审，不影响正式包。
 */
class UiPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YueYinTheme {
                val app = application as TvMusicApplication
                val container = remember { AppContainer(app) }
                CompositionLocalProvider(LocalAuthenticatedDependencies provides container) {
                    Box(Modifier.fillMaxSize().background(FnColors.Background)) {
                        when (intent?.getStringExtra("screen")) {
                            "player" -> PreviewPlayer()
                            "allplaylists" -> PreviewAllPlaylists()
                            "allplaylists-grid" -> PreviewAllPlaylistsGrid()
                            "my" -> PreviewMy()
                            "connect" -> PreviewConnectEmpty()
                            "sources" -> PreviewSources()
                            "receive" -> PreviewReceive()
                            "home-no-library" -> PreviewHomeNoLibrary()
                            "lxtest" -> PreviewLxTest()
                            else -> PreviewHome()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPlayer() {
    // 用真实组件摆出演播页的静态样子：沉浸态 + 呼出后的控制栏 + 快捷键 OSD。
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF173B4B), Color(0xFF0A1220), Color(0xFF070A10)),
                ),
            ),
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 96.dp, bottom = 120.dp)) {
            Box(
                Modifier
                    .height(230.dp)
                    .fillMaxWidth(0.34f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2A4A63), Color(0xFF14243A)))),
            )
            Spacer(Modifier.height(26.dp))
            Text("夜空中最亮的星", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("逃跑计划 · 世界 · FLAC 24bit", color = FnColors.Muted, fontSize = 20.sp)
            Spacer(Modifier.height(18.dp))
            Text("词：逃跑计划 · 曲：逃跑计划", color = FnColors.Muted, fontSize = 16.sp)
        }
        PlayerTransportOsd(
            osd = TransportOsd(TransportOsdGlyph.Next, "下一曲 · 周杰伦 - 晴天"),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 190.dp),
        )
        Text(
            "遥控器：←→ 快退/快进 · 连按两次切歌 · 长按连续快进 · 确定键 播放/暂停 · ↑↓ 呼出控制栏",
            color = FnColors.Muted,
            fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 40.dp),
        )
        val progressFocus = remember { FocusRequester() }
        val previousFocus = remember { FocusRequester() }
        val playFocus = remember { FocusRequester() }
        val nextFocus = remember { FocusRequester() }
        val favoriteFocus = remember { FocusRequester() }
        val modeFocus = remember { FocusRequester() }
        val queueFocus = remember { FocusRequester() }
        val fxFocus = remember { FocusRequester() }
        val exitRoamFocus = remember { FocusRequester() }
        val statusRetryFocus = remember { FocusRequester() }
        PlayerControlOverlay(
            positionMs = 83_000L,
            durationMs = 244_000L,
            isPlaying = true,
            roaming = false,
            previousEnabled = true,
            nextEnabled = true,
            progressFocus = progressFocus,
            previousFocus = previousFocus,
            playFocus = playFocus,
            nextFocus = nextFocus,
            favoriteFocus = favoriteFocus,
            modeFocus = modeFocus,
            queueFocus = queueFocus,
            fxFocus = fxFocus,
            exitRoamFocus = exitRoamFocus,
            statusRetryFocus = statusRetryFocus,
            statusRetryAvailable = false,
            playMode = PlayMode.ListRepeat,
            favorite = false,
            favoriteEnabled = true,
            queueCount = 18,
            fxLabel = "低音",
            onInteraction = {},
            onSeek = {},
            onPrevious = {},
            onPlayPause = {},
            onNext = {},
            onToggleFavorite = {},
            onCyclePlayMode = {},
            onOpenQueue = {},
            onExitRoam = {},
            onOpenFx = {},
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}

@Composable
private fun PreviewAllPlaylists() {
    // 复刻「全部歌单」页现状：顶部标题 + 横向滚动磁贴（LazyRow + PlaylistTile）
    val names = listOf(
        "粤语黄金年代" to 128, "深夜爵士" to 86, "周杰伦全集" to 214, "通勤摇滚" to 63,
        "钢琴独奏" to 47, "90 年代华语" to 156, "电影原声" to 92, "民谣现场" to 38,
        "古典入门" to 121, "女声精选" to 74, "Lo-Fi 工作" to 55, "车载必备" to 203,
        "演唱会实录" to 66, "B 面金曲" to 44, "雨夜慢歌" to 31, "电子氛围" to 58,
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Column {
                Text("全部歌单", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("共 ${names.size} 个歌单 · 长按确定键进入排序", color = FnColors.Muted, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyRow(
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(names, key = { index, _ -> "tile-$index" }) { index, (name, count) ->
                PlaylistTile(
                    title = name,
                    subtitle = if (index == 2) "←  → 移动" else "歌单",
                    coverId = null,
                    accent = FnColors.Coral,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun PreviewAllPlaylistsGrid() {
    // 改造后效果：全部歌单 4 列大网格，全部平铺、连续滚动（这里模拟 42 个已全部加载）
    val names = listOf(
        "粤语黄金年代" to 128, "深夜爵士" to 86, "周杰伦全集" to 214, "通勤摇滚" to 63,
        "钢琴独奏" to 47, "90 年代华语" to 156, "电影原声" to 92, "民谣现场" to 38,
        "古典入门" to 121, "女声精选" to 74, "Lo-Fi 工作" to 55, "车载必备" to 203,
        "演唱会实录" to 66, "B 面金曲" to 44, "雨夜慢歌" to 31, "电子氛围" to 58,
        "华语情歌" to 187, "午后咖啡" to 42, "跑步节奏" to 77, "深夜电台" to 29,
        "发烧人声" to 96, "动漫游戏" to 133, "嘻哈精选" to 64, "乡村民谣" to 51,
        "新年歌单" to 23, "怀旧粤语" to 145, "睡前轻音乐" to 36, "专注学习" to 68,
        "派对舞曲" to 89, "经典老歌" to 212, "治愈钢琴" to 57, "公路旅行" to 79,
        "独立摇滚" to 46, "R&B 慢摇" to 71, "国风古韵" to 104, "圣诞特辑" to 26,
        "早晨唤醒" to 34, "瑜伽冥想" to 19, "爵士女声" to 82, "日系纯音乐" to 39,
        "KTV 必点" to 168, "睡眠白噪音" to 15, "运动电音" to 61,
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 28.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Column {
                Text("全部歌单", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("共 ${names.size} 个歌单 · 全部铺开 · 方向键走位 · 滚动自动加载", color = FnColors.Muted, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(names, key = { name -> name.first }) { (name, count) ->
                HomePlaylistLockup(
                    title = name,
                    subtitle = "$count 首",
                    coverId = null,
                    onClick = {},
                )
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "footer") {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun PreviewMy() {
    // 复刻「我的」页现状：歌手 / 专辑 / 曲库横带（MediaBand + BandLockup）
    val artists = listOf(
        "周杰伦" to 214, "陈奕迅" to 156, "林俊杰" to 121, "五月天" to 92,
        "邓紫棋" to 74, "李荣浩" to 55, "毛不易" to 38, "薛之谦" to 66,
    ).map { (name, count) ->
        BandEntry(name, "$count 首歌曲", null, BandKind.Artist, "artist-$name") {}
    }
    val albums = listOf(
        "叶惠美" to "周杰伦", "摩天动物园" to "邓紫棋", "和自己对话" to "林俊杰",
        "后青春期的诗" to "五月天", "模特" to "李荣浩", "平凡的一天" to "毛不易",
    ).map { (name, artist) ->
        BandEntry(name, artist, null, BandKind.Album, "album-$name") {}
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp)
            .padding(top = 24.dp),
    ) {
        LibraryTopBarPreview()
        Spacer(Modifier.height(10.dp))
        // ProfileStrip 简版
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(FnColors.Coral.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("fn_user", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("飞牛 NAS · 已连接", color = FnColors.Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        MediaBand(
            title = "歌手",
            entries = artists,
            terminalEntry = BandEntry("全部歌手", "浏览完整列表", null, BandKind.Artist, "all-artists") {},
            focusedKey = null,
            focusRequester = remember { FocusRequester() },
            rowFocusRequester = remember { FocusRequester() },
            upFocusRequester = remember { FocusRequester() },
            downFocusRequester = remember { FocusRequester() },
            onFocused = {},
        )
        Spacer(Modifier.height(22.dp))
        MediaBand(
            title = "专辑",
            entries = albums,
            terminalEntry = BandEntry("全部专辑", "浏览完整列表", null, BandKind.Album, "all-albums") {},
            focusedKey = null,
            focusRequester = remember { FocusRequester() },
            rowFocusRequester = remember { FocusRequester() },
            upFocusRequester = remember { FocusRequester() },
            downFocusRequester = remember { FocusRequester() },
            onFocused = {},
        )
    }
}

@Composable
private fun LibraryTopBarPreview() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("悦音", color = FnColors.Coral, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(28.dp))
        Text("首页", color = FnColors.Muted, fontSize = 16.sp)
        Spacer(Modifier.width(22.dp))
        Text("我的", color = FnColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("搜索", color = FnColors.Muted, fontSize = 16.sp)
        Spacer(Modifier.width(22.dp))
        Text("正在播放", color = FnColors.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun PreviewHome() {
    val names = listOf(
        "粤语黄金年代" to 128,
        "深夜爵士" to 86,
        "周杰伦全集" to 214,
        "通勤摇滚" to 63,
        "钢琴独奏" to 47,
        "90 年代华语" to 156,
        "电影原声" to 92,
        "民谣现场" to 38,
        "古典入门" to 121,
        "女声精选" to 74,
        "Lo-Fi 工作" to 55,
        "车载必备" to 203,
        "演唱会实录" to 66,
        "B 面金曲" to 44,
        "雨夜慢歌" to 31,
        "电子氛围" to 58,
    )
    val playlists = names.mapIndexed { index, (name, count) ->
        Playlist(
            guid = CollectionGuid("preview-playlist-$index"),
            name = name,
            coverId = null,
            trackCount = count,
        )
    }
    val playback = PlaybackUiState(
        connected = true,
        hasMedia = true,
        isPlaying = true,
        title = "夜空中最亮的星",
        artist = "逃跑计划",
        mediaId = "preview-track",
    )
    HomeScreenLayout(
        playlists = playlists,
        playlistsLoaded = true,
        playback = playback,
        roamArtwork = emptyList<FeatureArtworkItem>(),
        favoriteArtwork = emptyList<FeatureArtworkItem>(),
        actionError = null,
        playlistError = null,
        roamBusy = false,
        onRoam = {},
        onFavorites = {},
        onPlaylist = {},
        onAll = {},
        onAlbums = {},
        onMy = {},
        onSearch = {},
        onPlayer = {},
    )
}

@Composable
private fun PreviewConnectEmpty() {
    com.yueyin.tv.ui.ConnectFirstScreen(
        savedPlatforms = emptyList(),
        sourceSummary = "还没添加",
        hasSource = false,
        onConnect = {},
        onManageSources = {},
        onPickSaved = {},
    )
}

@Composable
private fun PreviewSources() {
    val store = com.yueyin.tv.ui.source.MusicSourceRuntime.store
    remember {
        if (store.state.value.count == 0) {
            store.add(
                "lx-source.js",
                "// 预览用假脚本\nlx.on('request', () => 'http://example.com/a.mp3')",
            )
            store.add(
                "长青SVIP音源 v1.2.0.js",
                "// 预览用假脚本\nlx.on('request', () => 'http://example.com/b.mp3')",
            )
            store.state.value.sources.firstOrNull()?.let {
                store.recordCheck(it.id, listOf("kw", "tx"), listOf("kw", "tx", "wy"), "两平台可播")
            }
        }
    }
    com.yueyin.tv.ui.source.MusicSourceScreen(onAdd = {}, onBack = {})
}

@Composable
private fun PreviewReceive() {
    com.yueyin.tv.ui.source.SourceReceiveScreen(onDone = {})
}

@Composable
private fun PreviewHomeNoLibrary() {
    com.yueyin.tv.ui.HomeWithoutLibrary(
        sourceSummary = "已添加 2 个 · 酷我 / QQ 可用",
        onConnect = {},
        onManageSources = {},
    )
}

/**
 * 音源运行时自检（仅 debug）：读 filesDir/lx-test.js，加载脚本、问一次取链。
 * 用来在模拟器上确认 WebView 宿主 + 加密/请求桥是否真的跑得通。
 */
@Composable
private fun PreviewLxTest() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(listOf("准备中…")) }

    LaunchedEffect(Unit) {
        val out = mutableListOf<String>()
        val file = File(context.filesDir, "lx-test.js")
        if (!file.exists()) {
            lines = listOf("没找到 ${file.absolutePath}", "先把脚本 adb push 到应用私有目录再试")
            return@LaunchedEffect
        }
        val script = runCatching { file.readText() }.getOrElse {
            lines = listOf("读取失败: ${it.message}")
            return@LaunchedEffect
        }
        out += "脚本 ${script.length} 字节"
        val runtime = com.yueyin.tv.source.OnlineSourceRuntime.get(context)
        val loaded = runCatching { runtime.loadScript(file.name, script) }.getOrElse { false }
        out += "loadScript = $loaded"
        val declared = runCatching { runtime.declaredPlatforms() }.getOrElse { emptySet() }
        out += "声明平台: ${declared.joinToString(",").ifBlank { "（无）" }}"

        val cases = listOf(
            Triple("kw", "51685512", "晴天"),
            Triple("wy", "3440441479", "晴天"),
        )
        for ((platform, id, name) in cases) {
            if (declared.isNotEmpty() && !declared.contains(platform)) {
                out += "[$platform] 脚本未声明该平台，跳过"
                continue
            }
            val info = kotlinx.serialization.json.buildJsonObject {
                put("type", "320k")
                put(
                    "musicInfo",
                    kotlinx.serialization.json.buildJsonObject {
                        put("songmid", id); put("songId", id); put("id", id); put("rid", id)
                        put("hash", id); put("strMediaMid", id); put("copyrightId", id)
                        put("name", name); put("singer", "周杰伦"); put("albumName", "叶惠美")
                        put("interval", "269"); put("duration", 269)
                    },
                )
            }
            val value = runCatching { runtime.call(platform, "musicUrl", info) }.getOrNull()
            out += "[$platform] -> ${value?.take(120) ?: "null"}"
        }
        out += "--- 最近日志 ---"
        out += com.yueyin.tv.source.LxLog.recent().takeLast(12)
        lines = out
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        lines.forEach { line ->
            Text(line, color = FnColors.Text, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}

