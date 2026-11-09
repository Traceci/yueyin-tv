package com.yueyin.tv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.yueyin.tv.TvMusicApplication
import com.yueyin.tv.core.data.localmusic.LocalMusicLibrary
import com.yueyin.tv.core.model.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 电视端本地音乐库。
 *
 * 与在线 / 协议平台完全并行：起播直接走 [LocalMusicLibrary.prepare] + playQueue，
 * 不做任何联网取链，因此这里不会出现「取链失败 / 导入音源脚本」一类在线专用提示。
 * 曲目列表、扫描状态都挂在以 "local:" 开头的保留态上，切换平台时不会被清掉。
 * TV 遥控器焦点是重点：按钮与每一行都明确可聚焦，聚焦行用珊瑚底 + 白色描边高亮。
 */
@Composable
internal fun TvLocalMusicPage(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deps = LocalAuthenticatedDependencies.current
    val lib: LocalMusicLibrary = remember {
        (context.applicationContext as TvMusicApplication).container.localMusicLibrary
    }
    val retained = LocalLibraryRetainedState.current
    // 只读取本地曲库自己的保留态：切平台时 clearAll 会跳过 "local:" 前缀，列表不会闪。
    val listState = remember { retained.list<Track>("local:music:tracks") }
    val scope = rememberCoroutineScope()

    // 运行时权限：Android 13 起用 READ_MEDIA_AUDIO，以下回退到 READ_EXTERNAL_STORAGE。
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var scanning by remember { mutableStateOf(false) }
    var indexedCount by remember { mutableStateOf(0) }
    var folderCount by remember { mutableStateOf(0) }
    // 权限对话框返回后用它触发一次焦点重取，避免系统弹窗把焦点带走后无人接管。
    var focusEpoch by remember { mutableStateOf(0) }

    // 重新扫描：refresh 返回索引总数，随后把整份列表读回保留态。
    fun reload() {
        if (scanning) return
        scanning = true
        scope.launch {
            runCatching {
                val count = lib.refresh()
                val loaded = lib.tracks()
                val folders = lib.addedFolders().size
                Triple(count, loaded, folders)
            }.onSuccess { (count, loaded, folders) ->
                indexedCount = count
                folderCount = folders
                listState.snapshot = RetainedListSnapshot(entries = loaded, initialLoadCompleted = true)
            }
            scanning = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        focusEpoch++
        // 授予后自动扫一次 MediaStore，不必再让用户点「重新扫描」。
        if (granted) reload()
    }

    // 进页面时已有权限就直接刷新一次；否则等用户点「授予权限」。
    LaunchedEffect(Unit) {
        if (permissionGranted) reload()
    }

    val tracks = listState.snapshot.entries

    // 本地曲目带的是 content:// URI，prepare 后即可入队，不走 musicRepository.prepareQueue。
    fun startPlayback(items: List<Track>, startIndex: Int) {
        if (items.isEmpty()) return
        val prepared = items.map(lib::prepare)
        val index = startIndex.coerceIn(prepared.indices)
        val transition = runCatching {
            deps.playbackController.playQueue(prepared, index)
        }.getOrNull()
        if (transition == null) {
            Toast.makeText(context, "本地文件暂时无法播放", Toast.LENGTH_SHORT).show()
        } else {
            onOpenPlayer()
        }
    }

    // 初始焦点落在标题行的「重新扫描」：无论有没有权限、有没有曲目它都在，
    // 电视上一定有一个确定的落点。等组合稳定后再请求，规避 requester 未 attach 的时序问题。
    val rescanFocus = remember { FocusRequester() }
    val grantFocus = remember { FocusRequester() }
    LaunchedEffect(permissionGranted, focusEpoch) {
        val target = if (permissionGranted) rescanFocus else grantFocus
        repeat(10) {
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(60)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        // 标题行：标题 + 已索引数 / 扫描状态 + 重新扫描按钮。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本地音乐", fontSize = FnType.PageTitle, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        scanning -> "正在扫描本机音乐…"
                        // 电视端不支持新增目录（遥控器选目录体验太差），只显示手机端添加的结果。
                        folderCount > 0 -> "已索引 $indexedCount 首 · 含 $folderCount 个手动添加的文件夹"
                        else -> "已索引 $indexedCount 首"
                    },
                    color = FnColors.Muted,
                    fontSize = FnType.Sub,
                )
            }
            Spacer(Modifier.width(16.dp))
            LocalActionButton(
                label = if (scanning) "扫描中…" else "重新扫描",
                primary = true,
                modifier = Modifier.focusRequester(rescanFocus),
                onClick = { reload() },
            )
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))

        when {
            !permissionGranted -> {
                // 权限引导卡：TV 上 MediaStore 为空绝大多数是没授权，先说清楚要什么。
                Spacer(Modifier.height(24.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(FnColors.SurfaceAlt, RoundedCornerShape(FnRadius.lg))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(FnRadius.lg))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "需要读取本机音频的权限",
                        color = FnColors.Text,
                        fontSize = FnType.Section,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "授予「读取音频」权限后，才能索引电视上的本地音乐文件。",
                        color = FnColors.Muted,
                        fontSize = FnType.Body,
                    )
                    Spacer(Modifier.height(18.dp))
                    LocalActionButton(
                        label = "授予权限",
                        modifier = Modifier.focusRequester(grantFocus),
                        onClick = { permissionLauncher.launch(audioPermission) },
                    )
                }
            }
            scanning && tracks.isEmpty() -> {
                // 扫描中且还没有任何已索引内容。
                Spacer(Modifier.height(24.dp))
                Text(
                    "正在扫描本机音乐，请稍候…",
                    color = FnColors.Muted,
                    fontSize = FnType.Body,
                )
            }
            tracks.isEmpty() -> {
                // TV 上 MediaStore 本来就可能是空的，给一套明确的空态引导。
                Spacer(Modifier.height(24.dp))
                Text(
                    "没有扫描到本机音乐文件",
                    color = FnColors.Text,
                    fontSize = FnType.Section,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "电视上可能还没有存放音频文件。把音乐放到本机存储后，点「重新扫描」再试一次。",
                    color = FnColors.Muted,
                    fontSize = FnType.Body,
                )
            }
            else -> {
                // 播放全部 / 随机播放同排，与详情集合页的按钮行一致；随机用 shuffled。
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LocalActionButton(
                        label = "播放全部",
                        primary = true,
                        onClick = { startPlayback(tracks, 0) },
                    )
                    LocalActionButton(
                        label = "随机播放",
                        onClick = { startPlayback(tracks.shuffled(), 0) },
                    )
                }
                Spacer(Modifier.height(14.dp))
                LocalTrackList(
                    tracks = tracks,
                    onPlay = { index -> startPlayback(tracks, index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 本地页按钮：统一成「聚焦珊瑚实心 + 白描边」，与 TV 其它页的按钮同一套焦点语言。 */
@Composable
private fun LocalActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val shape = RoundedCornerShape(FnRadius.sm)
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) FnColors.Surface else Color.Transparent,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.FocusSolid,
            focusedContentColor = FnColors.FocusOnSolid,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (primary) FnColors.Coral.copy(alpha = 0.76f) else Color.White.copy(alpha = 0.20f),
                ),
                shape = shape,
            ),
            focusedBorder = Border(BorderStroke(FnFocus.ringWidth, FnFocus.ringColor), shape = shape),
            pressedBorder = Border(BorderStroke(FnFocus.ringWidth, FnFocus.ringColor), shape = shape),
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** 曲目列表：LazyColumn 自带焦点跟随滚动，方向键上下在相邻行之间移动。 */
@Composable
private fun LocalTrackList(
    tracks: List<Track>,
    onPlay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.guid.value }) { index, track ->
            LocalTrackRow(index = index, track = track, onClick = { onPlay(index) })
        }
    }
}

/**
 * 本地曲目行。
 *
 * 显式用 focusable() + clickable() + 确定键，保证每一行都能被遥控器聚焦并播放；
 * 聚焦时铺珊瑚底并加白色描边，选中态在 3 米外的电视上也一眼可见。
 * 无封面时用文字占位图，行尾固定带「本地」小徽标，与在线曲目区分开。
 */
@Composable
private fun LocalTrackRow(
    index: Int,
    track: Track,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.md)
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                if (focused) FnColors.Coral.copy(alpha = 0.18f) else FnColors.Surface.copy(alpha = 0.55f),
                shape,
            )
            .border(
                if (focused) FnFocus.ringWidth else 1.dp,
                if (focused) FnFocus.ringColor else Color.White.copy(alpha = 0.08f),
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                val center = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (center && event.type == KeyEventType.KeyUp) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 无封面的占位图：本地曲目没有 coverId，用一块中性小方块代替。
            Box(
                Modifier
                    .size(40.dp)
                    .background(FnColors.SurfaceAlt, RoundedCornerShape(FnRadius.sm)),
                contentAlignment = Alignment.Center,
            ) {
                Text("音", color = FnColors.Muted, fontSize = FnType.Sub, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                (index + 1).toString().padStart(2, '0'),
                color = FnColors.Muted,
                fontSize = FnType.Label,
                modifier = Modifier.width(34.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = FnColors.Text,
                    fontSize = FnType.CardTitle,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(track.artistName, track.albumName).joinToString(" · ").ifEmpty { "本机音频" },
                    color = FnColors.Muted,
                    fontSize = FnType.Sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            // 「本地」小徽标：与在线 / 协议平台曲目一眼可分。
            Box(
                Modifier
                    .background(FnColors.Teal.copy(alpha = 0.16f), RoundedCornerShape(FnRadius.sm))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("本地", color = FnColors.Teal, fontSize = FnType.Caption, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                formatLocalDuration(track.durationMs ?: 0L),
                color = FnColors.Muted,
                fontSize = FnType.Row,
                modifier = Modifier.width(64.dp),
            )
        }
    }
}

private fun formatLocalDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
