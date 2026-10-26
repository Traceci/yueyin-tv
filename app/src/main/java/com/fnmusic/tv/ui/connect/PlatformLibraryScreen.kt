package com.fnmusic.tv.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fnmusic.tv.core.data.connect.ConnectProfile
import com.fnmusic.tv.core.data.protocol.RemotePlaylist
import com.fnmusic.tv.core.data.protocol.RemoteSong
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.playback.PlaybackController
import com.fnmusic.tv.ui.FnColors
import com.fnmusic.tv.ui.FnFocus
import com.fnmusic.tv.ui.FnRadius
import com.fnmusic.tv.ui.fnFocusRing
import kotlinx.coroutines.launch

/**
 * 平台曲库（非飞牛平台）：歌单 + 歌曲，选中即播。
 *
 * 这一页刻意做得薄：它验证的是"新平台的凭据能真的取到内容并播放"，
 * 而不是复制一套首页。飞牛仍然走原有的完整界面。
 */
@Composable
fun PlatformLibraryScreen(
    profile: ConnectProfile,
    playbackController: PlaybackController,
    onBack: () -> Unit,
) {
    val registry = ConnectRuntime.registry
    val protocol = remember(profile) { registry.of(profile) }
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<RemoteSong>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<RemotePlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile.id) {
        loading = true
        error = null
        runCatching {
            val loadedSongs = protocol.songs(profile)
            val loadedPlaylists = protocol.playlists(profile)
            songs = loadedSongs
            playlists = loadedPlaylists
        }.onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    fun playAt(index: Int, list: List<RemoteSong>) {
        if (list.isEmpty()) return
        val queued = list.map { remote ->
            PlaybackTrack(
                track = Track(
                    guid = TrackGuid(remote.id),
                    title = remote.title,
                    artistName = remote.artistName.takeIf { it.isNotBlank() },
                    albumName = remote.albumName.takeIf { it.isNotBlank() },
                    coverId = remote.coverId,
                    durationMs = remote.durationMs.takeIf { it > 0 },
                    isCue = false,
                ),
                streamUrl = protocol.streamUrl(profile, remote.id),
                artworkUrl = remote.coverId?.let { protocol.coverUrl(profile, it) },
            )
        }
        playbackController.playQueue(queued, index)
        hint = "正在播放：${list[index].title}"
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text(profile.displayName, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(profile.host, color = FnColors.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        when {
            loading -> Text("正在读取曲库…", color = FnColors.Muted, fontSize = 17.sp)
            error != null -> Text("读取失败：$error", color = FnColors.Coral, fontSize = 16.sp)
            else -> {
                if (playlists.isNotEmpty()) {
                    Text("歌单", color = FnColors.Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        playlists.take(4).forEach { playlist ->
                            RowItem(
                                enabled = true,
                                onClick = {
                                    scope.launch {
                                        runCatching { protocol.playlistSongs(profile, playlist.id) }
                                            .onSuccess { playAt(0, it) }
                                            .onFailure { error = "歌单读取失败" }
                                    }
                                },
                                modifier = Modifier.width(180.dp).height(56.dp),
                            ) { Text(playlist.name, fontSize = 16.sp, maxLines = 1) }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
                Text(
                    if (songs.isEmpty()) "该平台没有返回歌曲列表" else "歌曲 · ${songs.size} 首（选中即播）",
                    color = FnColors.Muted,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    songs.take(4).forEachIndexed { index, song ->
                        RowItem(
                            enabled = true,
                            onClick = { playAt(index, songs) },
                            modifier = Modifier.width(210.dp).height(72.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp)) {
                                Text(song.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    song.artistName.ifBlank { "未知歌手" },
                                    color = FnColors.Muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (hint != null) {
            Spacer(Modifier.height(16.dp))
            Text(hint!!, color = FnColors.Teal, fontSize = 15.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("按返回键退出 · 新平台的封面与歌词暂由各服务自身提供", color = FnColors.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun RowItem(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.md)
    Box(
        modifier = modifier
            .background(FnColors.SurfaceAlt, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .onKeyEvent { event ->
                val center = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (enabled && center && event.type == KeyEventType.KeyUp) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .fnFocusRing(focused = focused && enabled, shape = shape, cornerRadius = FnRadius.md),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}
