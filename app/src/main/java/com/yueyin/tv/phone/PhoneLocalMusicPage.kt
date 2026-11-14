package com.yueyin.tv.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Text
import com.yueyin.tv.TvMusicApplication
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.LibraryRetainedStateStore
import com.yueyin.tv.ui.formatDuration
import com.yueyin.tv.ui.retainLoadedList
import kotlinx.coroutines.launch

/** Android 13 起读音频用 READ_MEDIA_AUDIO，旧版回落到 READ_EXTERNAL_STORAGE。 */
private fun localReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * 手机端本地音乐库。
 *
 * 与在线 / 协议平台完全隔离：曲目来自 MediaStore 扫描后落库的本地表，起播不取链，
 * 直接用 LocalMusicLibrary.prepare 把 content:// URI 交给播放器。
 * 列表状态挂 [LibraryRetainedStateStore] 且 key 以 LOCAL_STATE_PREFIX 开头，
 * 因此切换平台时 clearAll 不会把它清掉。
 */
@Composable
internal fun PhoneLocalMusicPage(
    onBack: () -> Unit,
    playingTrackId: String? = null,
) {
    val context = LocalContext.current
    val deps = LocalPhoneDeps.current
    val store = LocalPhoneStore.current
    val lib = remember {
        (context.applicationContext as TvMusicApplication).container.localMusicLibrary
    }
    // 本地列表单独一份保留状态，key 以 local: 开头，切平台清库时不会被一并清掉。
    val state = store.list<Track>(LibraryRetainedStateStore.LOCAL_STATE_PREFIX + "music:tracks")

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, localReadPermission()) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var scanning by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Track>?>(null) }
    var folders by remember { mutableStateOf<List<com.yueyin.tv.core.data.localmusic.LocalFolderEntity>>(emptyList()) }
    var addingFolder by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    // 扫库 + 读全量，覆盖写进保留状态。force 用于「重新扫描」；首屏只在未完成加载时跑。
    fun load(force: Boolean) {
        if (!granted || scanning) return
        if (!force && state.snapshot.initialLoadCompleted) return
        scanning = true
        store.scope.launch {
            runCatching {
                lib.refresh()
                lib.tracks()
            }.onSuccess { list ->
                state.snapshot = retainLoadedList(state.snapshot, list)
            }
            scanning = false
        }
    }

    // 权限授予后自动刷新；已授权但首屏未加载时也补一次。
    LaunchedEffect(granted) {
        if (granted) load(force = false)
    }

    // 手动添加文件夹：SAF 目录授权解决 MediaStore 扫不到的情况（新拷入、SD/OTG、.nomedia）。
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            addingFolder = true
            store.scope.launch {
                runCatching { lib.addFolder(uri) }
                folders = runCatching { lib.addedFolders() }.getOrDefault(emptyList())
                addingFolder = false
                load(force = true)
            }
        }
    }

    LaunchedEffect(Unit) {
        folders = runCatching { lib.addedFolders() }.getOrDefault(emptyList())
    }

    // 本地检索，不发网络请求；空关键词回落到全量列表。
    LaunchedEffect(query, state.snapshot.entries) {
        searchResults = if (query.isBlank()) {
            null
        } else {
            runCatching { lib.search(query) }.getOrDefault(emptyList())
        }
    }

    val allTracks = state.snapshot.entries
    val tracks = searchResults ?: allTracks

    Column(Modifier.fillMaxSize()) {
        PhoneBackHeader(
            title = "本地音乐",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = PhoneSpace.screenHorizontal),
        )

        if (!granted) {
            LocalPermissionGuide(
                onGrant = { permissionLauncher.launch(localReadPermission()) },
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(Modifier.padding(horizontal = PhoneSpace.screenHorizontal)) {
                // 统计 + 重新扫描：数量取全量索引，不随搜索结果变化。
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已索引 ${phoneCount(allTracks.size)} 首",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Caption,
                        modifier = Modifier.weight(1f),
                    )
                    LocalRescanButton(scanning = scanning, onClick = { load(force = true) })
                }
                Spacer(Modifier.height(PhoneSpace.sm))
                LocalFolderSection(
                    folders = folders,
                    adding = addingFolder,
                    onAdd = { runCatching { folderPicker.launch(null) } },
                    onRemove = { target ->
                        store.scope.launch {
                            runCatching { lib.removeFolder(target.uri) }
                            folders = runCatching { lib.addedFolders() }.getOrDefault(emptyList())
                            load(force = true)
                        }
                    },
                )
                Spacer(Modifier.height(PhoneSpace.md))
                // 播放全部 / 随机播放：与详情页同一对按钮，主行动保持实心，随机退成描边。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
                ) {
                    PhonePrimaryButton(
                        label = "播放全部",
                        enabled = tracks.isNotEmpty() && !scanning,
                        onClick = {
                            deps.playbackController.playQueue(tracks.map { lib.prepare(it) })
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PhoneOutlineButton(
                        label = "随机",
                        glyph = PhoneGlyph.Shuffle,
                        enabled = tracks.isNotEmpty() && !scanning,
                        onClick = {
                            deps.playbackController.playQueue(
                                tracks.shuffled().map { lib.prepare(it) },
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(PhoneSpace.md))
                LocalSearchField(query = query, onQueryChange = { query = it })
            }

            // 内容区用 weight 占满剩余空间，避免 Column 里 fillMaxSize 量成父全高。
            Box(Modifier.weight(1f)) {
                when {
                    scanning && tracks.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "正在扫描本机音频…",
                                color = FnColors.Muted,
                                fontSize = PhoneType.Caption,
                            )
                        }
                    }
                    tracks.isEmpty() -> {
                        PhoneEmptyState(
                            title = if (query.isBlank()) "没有找到本机音频" else "没有匹配的曲目",
                            caption = if (query.isBlank()) {
                                "点「重新扫描」重新读取本机音频"
                            } else {
                                "换一个关键词再试"
                            },
                        )
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                top = PhoneSpace.sm,
                                bottom = PhoneSpace.xxl,
                                start = PhoneSpace.screenHorizontal,
                                end = PhoneSpace.screenHorizontal,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(tracks, key = { _, track -> track.guid.value }) { index, track ->
                                LocalTrackRow(
                                    track = track,
                                    index = index,
                                    current = playingTrackId != null &&
                                        playingTrackId == track.guid.value,
                                    onClick = {
                                        deps.playbackController.playQueue(
                                            tracks.map { lib.prepare(it) },
                                            startIndex = index,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 权限未授予时的引导卡：没有读取音频权限，扫库无从谈起。 */
@Composable
private fun LocalPermissionGuide(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        // 必须 fillMaxWidth：Column 的宽默认是「最宽子项」而不是父宽，
        // 只给 weight(1f) 时整块会缩成一行文字的宽度并靠左，里面的 CenterHorizontally
        // 变成「在一行文字里居中」，看起来就是整页文字都偏左。
        modifier
            .fillMaxWidth()
            .padding(horizontal = PhoneSpace.screenHorizontal),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.lg)),
            contentAlignment = Alignment.Center,
        ) {
            PhoneIcon(PhoneGlyph.Library, FnColors.Muted, Modifier.size(24.dp))
        }
        Spacer(Modifier.height(PhoneSpace.lg))
        Text(
            "需要读取本机音频才能建立曲库",
            color = FnColors.Text,
            fontSize = PhoneType.Row,
            maxLines = 1,
        )
        Spacer(Modifier.height(PhoneSpace.lg))
        Box(
            Modifier
                .background(FnColors.Coral, RoundedCornerShape(PhoneRadius.md))
                .phoneTap(onGrant)
                .padding(horizontal = PhoneSpace.xl, vertical = PhoneSpace.md),
        ) {
            Text(
                "授予权限",
                color = FnColors.Background,
                fontSize = PhoneType.Row,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** 「重新扫描」小胶囊：扫描中置灰并禁止重复点击。 */
@Composable
private fun LocalRescanButton(scanning: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .background(FnColors.Surface, shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .then(if (!scanning) Modifier.phoneTap(onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneIcon(
            PhoneGlyph.Repeat,
            if (scanning) FnColors.Muted else FnColors.Text,
            Modifier.size(14.dp),
            strokeWidth = 1.8.dp,
        )
        Spacer(Modifier.width(PhoneSpace.xs))
        Text(
            if (scanning) "扫描中…" else "重新扫描",
            color = if (scanning) FnColors.Muted else FnColors.Text,
            fontSize = PhoneType.Caption,
            maxLines = 1,
        )
    }
}

/** 本地检索框：只查本机表，用搜索页同一套深底 + 珊瑚光标样式。 */
@Composable
private fun LocalSearchField(query: String, onQueryChange: (String) -> Unit) {
    val shape = RoundedCornerShape(PhoneRadius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(FnColors.Surface, shape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneIcon(
            PhoneGlyph.Search,
            FnColors.Muted,
            Modifier.size(16.dp),
            strokeWidth = 1.9.dp,
        )
        Spacer(Modifier.width(PhoneSpace.sm))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = PhoneType.Row, color = FnColors.Text),
                singleLine = true,
                cursorBrush = SolidColor(FnColors.Coral),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "搜索本机曲目",
                            color = FnColors.Muted,
                            fontSize = PhoneType.Row,
                            maxLines = 1,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/** 本地曲目行：序号 + 标题/歌手 + 时长 + 「本地」徽标，风格对齐 PhoneTrackRow。 */
@Composable
private fun LocalTrackRow(
    track: Track,
    index: Int,
    current: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PhoneSize.RowItem)
            .phoneTap(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Text(
                "${index + 1}",
                color = FnColors.Muted.copy(alpha = 0.75f),
                fontSize = PhoneType.Micro,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(PhoneSpace.sm))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                track.title,
                color = if (current) FnColors.Coral else FnColors.Text,
                fontSize = PhoneType.Row,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = track.artistName.orEmpty()
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = FnColors.Muted,
                    fontSize = PhoneType.Micro,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        track.durationMs?.let { ms ->
            Spacer(Modifier.width(PhoneSpace.sm))
            Text(
                formatDuration(ms),
                color = FnColors.Muted,
                fontSize = PhoneType.Micro,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(PhoneSpace.sm))
        Box(
            Modifier
                .background(FnColors.Teal.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                "本地",
                color = FnColors.Teal,
                fontSize = PhoneType.Micro,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * 「文件夹」区块：SAF 目录授权，补 MediaStore 扫不到的那部分。
 *
 * 没有已添加目录时给一句引导（自动扫描找不到就走这里），有目录时逐条列出、
 * 右侧可移除（移除会连带清掉该目录扫出来的曲目，MediaStore 来源的不受影响）。
 */
@Composable
private fun LocalFolderSection(
    folders: List<com.yueyin.tv.core.data.localmusic.LocalFolderEntity>,
    adding: Boolean,
    onAdd: () -> Unit,
    onRemove: (com.yueyin.tv.core.data.localmusic.LocalFolderEntity) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FnColors.Surface, RoundedCornerShape(14.dp))
            .padding(horizontal = PhoneSpace.md, vertical = PhoneSpace.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "文件夹",
                    color = FnColors.Text,
                    fontSize = PhoneType.Body,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (folders.isEmpty()) {
                        "自动扫描没找到？手动添加一个文件夹"
                    } else {
                        "已添加 ${folders.size} 个文件夹 · 只收 30 秒以上的音频"
                    },
                    color = FnColors.Muted,
                    fontSize = PhoneType.Micro,
                )
            }
            Text(
                if (adding) "扫描中…" else "添加",
                color = if (adding) FnColors.Muted else FnColors.Coral,
                fontSize = PhoneType.Caption,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.phoneTap { if (!adding) onAdd() },
            )
        }
        folders.forEach { folder ->
            Row(
                Modifier.fillMaxWidth().padding(top = PhoneSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    folder.displayPath,
                    color = FnColors.Text,
                    fontSize = PhoneType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(PhoneSpace.md))
                Text(
                    "移除",
                    color = FnColors.Muted,
                    fontSize = PhoneType.Micro,
                    modifier = Modifier.phoneTap { onRemove(folder) },
                )
            }
        }
    }
}
