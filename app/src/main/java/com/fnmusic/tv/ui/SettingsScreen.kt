package com.fnmusic.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.fnmusic.tv.AuthenticatedAppDependencies
import com.fnmusic.tv.BuildConfig
import com.fnmusic.tv.R
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.preferences.CacheBudget
import com.fnmusic.tv.core.model.preferences.CacheUsage
import com.fnmusic.tv.core.model.preferences.UiScale
import com.fnmusic.tv.update.UpdateCheckSource
import com.fnmusic.tv.update.UpdateUiState
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private val SettingsPanel = Color(0xFF151A19)
private val SettingsControl = Color(0xFF292D31)
private val SettingsDividerColor = Color(0xFF2A302F)
private val SettingsBorderColor = Color(0xFF303735)

@Composable
internal fun SettingsScreen(container: AuthenticatedAppDependencies) {
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    val updateState by container.updateController.state.collectAsStateWithLifecycle()
    val lastCheckedAt by container.updateController.lastCheckedAt.collectAsStateWithLifecycle()
    val scope = LocalLibraryRetainedState.current.scope
    val coverStyleFocus = remember { FocusRequester() }
    val posterStyleFocus = remember { FocusRequester() }
    val onlineLyricsFocus = remember { FocusRequester() }
    val lyricsStyleFocus = remember { FocusRequester() }
    val compactScaleFocus = remember { FocusRequester() }
    val standardScaleFocus = remember { FocusRequester() }
    val relaxedScaleFocus = remember { FocusRequester() }
    val backgroundContinueFocus = remember { FocusRequester() }
    val cacheFocuses = remember { List(CacheBudget.entries.size) { FocusRequester() } }
    val clearCacheFocus = remember { FocusRequester() }
    val songCacheClearFocus = remember { FocusRequester() }
    val updateFocus = remember { FocusRequester() }
    val remoteGuideFocus = remember { FocusRequester() }
    var usage by remember { mutableStateOf(CacheUsage(artworkBytes = 0, indexBytes = 0)) }
    var songCacheBytes by remember { mutableStateOf(0L) }
    var showRemoteGuide by remember { mutableStateOf(false) }

    suspend fun refreshUsage() {
        usage = container.musicRepository.cacheUsage()
    }

    suspend fun refreshSongCacheUsage() {
        songCacheBytes = container.playbackController.songCacheUsageBytes()
    }

    LaunchedEffect(Unit) {
        container.musicRepository.applyArtworkBudget()
        refreshUsage()
        refreshSongCacheUsage()
    }
    LaunchedEffect(Unit) {
        yield()
        runCatching { coverStyleFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(FnColors.Background)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 84.dp, y = (-132).dp)
                .size(280.dp)
                .background(Color(0xFF141719), CircleShape),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 70.dp, y = 104.dp)
                .size(230.dp)
                .background(Color(0xFF131617), CircleShape),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 28.dp),
        ) {
            Text("设置", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("播放与歌词", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SettingsPanel, RoundedCornerShape(9.dp))
                    .border(0.75.dp, SettingsBorderColor, RoundedCornerShape(9.dp))
                    .padding(horizontal = 22.dp),
            ) {
                SettingsRow(label = "播放界面", height = 65.dp) {
                    SettingsChoiceButton(
                        label = "CD 模式",
                        selected = preferences.playerStyle == PlayerStyle.Cover,
                        onClick = { container.appPreferences.setPlayerStyle(PlayerStyle.Cover) },
                        modifier = Modifier
                            .width(116.dp)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = posterStyleFocus
                                down = compactScaleFocus
                            }
                            .focusRequester(coverStyleFocus),
                    )
                    SettingsChoiceButton(
                        label = "大海报模式",
                        selected = preferences.playerStyle == PlayerStyle.Poster,
                        onClick = { container.appPreferences.setPlayerStyle(PlayerStyle.Poster) },
                        modifier = Modifier
                            .width(116.dp)
                            .focusProperties {
                                left = coverStyleFocus
                                right = lyricsStyleFocus
                                down = compactScaleFocus
                            }
                            .focusRequester(posterStyleFocus),
                    )
                    SettingsChoiceButton(
                        label = "迷你歌词",
                        selected = preferences.playerStyle == PlayerStyle.Lyrics,
                        onClick = { container.appPreferences.setPlayerStyle(PlayerStyle.Lyrics) },
                        modifier = Modifier
                            .width(116.dp)
                            .focusProperties {
                                left = posterStyleFocus
                                right = FocusRequester.Cancel
                                down = compactScaleFocus
                            }
                            .focusRequester(lyricsStyleFocus),
                    )
                }
                SettingsDivider()
                SettingsRow(label = "界面缩放", height = 65.dp) {
                    SettingsChoiceButton(
                        label = "紧凑",
                        selected = preferences.uiScale == UiScale.Compact,
                        onClick = { container.appPreferences.setUiScale(UiScale.Compact) },
                        modifier = Modifier
                            .width(116.dp)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = standardScaleFocus
                                up = coverStyleFocus
                                down = onlineLyricsFocus
                            }
                            .focusRequester(compactScaleFocus),
                    )
                    SettingsChoiceButton(
                        label = "标准",
                        selected = preferences.uiScale == UiScale.Standard,
                        onClick = { container.appPreferences.setUiScale(UiScale.Standard) },
                        modifier = Modifier
                            .width(116.dp)
                            .focusProperties {
                                left = compactScaleFocus
                                right = relaxedScaleFocus
                                up = posterStyleFocus
                                down = onlineLyricsFocus
                            }
                            .focusRequester(standardScaleFocus),
                    )
                    SettingsChoiceButton(
                        label = "宽松",
                        selected = preferences.uiScale == UiScale.Relaxed,
                        onClick = { container.appPreferences.setUiScale(UiScale.Relaxed) },
                        modifier = Modifier
                            .width(116.dp)
                            .focusProperties {
                                left = standardScaleFocus
                                right = FocusRequester.Cancel
                                up = lyricsStyleFocus
                                down = onlineLyricsFocus
                            }
                            .focusRequester(relaxedScaleFocus),
                    )
                }
                SettingsDivider()
                SettingsRow(label = "歌词", height = 65.dp) {
                    SettingsCheckbox(
                        label = "在线歌词匹配",
                        selected = preferences.onlineLyricsMatchingEnabled,
                        onClick = {
                            container.appPreferences.setOnlineLyricsMatchingEnabled(
                                !preferences.onlineLyricsMatchingEnabled,
                            )
                        },
                        modifier = Modifier
                            .focusProperties {
                                up = compactScaleFocus
                                down = backgroundContinueFocus
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                            .focusRequester(onlineLyricsFocus),
                    )
                }
                SettingsDivider()
                SettingsRow(label = "播放行为", height = 76.dp) {
                    SettingsCheckbox(
                        label = "按返回键退出时在后台继续播放",
                        selected = preferences.backgroundContinueOnExit,
                        onClick = {
                            container.appPreferences.setBackgroundContinueOnExit(
                                !preferences.backgroundContinueOnExit,
                            )
                        },
                        modifier = Modifier
                            .focusProperties {
                                up = onlineLyricsFocus
                                down = cacheFocuses.first()
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                            .focusRequester(backgroundContinueFocus),
                    )
                }
                SettingsDivider()
                Row(
                    Modifier.fillMaxWidth().height(80.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "图片磁盘缓存上限",
                        color = FnColors.Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.width(144.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            CacheBudget.entries.forEachIndexed { index, budget ->
                                SettingsChoiceButton(
                                    label = "${budget.megabytes} MB",
                                    selected = preferences.cacheBudget == budget,
                                    onClick = {
                                        scope.launch {
                                            container.appPreferences.setCacheBudget(budget)
                                            container.musicRepository.applyArtworkBudget()
                                            refreshUsage()
                                        }
                                    },
                                    modifier = Modifier
                                        .width(104.dp)
                                        .focusProperties {
                                            up = onlineLyricsFocus
                                            down = if (container.updateController.enabled) {
                                                updateFocus
                                            } else {
                                                FocusRequester.Cancel
                                            }
                                            left = cacheFocuses.getOrNull(index - 1)
                                                ?: FocusRequester.Cancel
                                            right = cacheFocuses.getOrNull(index + 1)
                                                ?: clearCacheFocus
                                        }
                                        .focusRequester(cacheFocuses[index]),
                                )
                            }
                        }
                        Text(
                            "当前 ${formatBytes(usage.totalBytes)}（图片 ${formatBytes(usage.artworkBytes)} / 资料 ${formatBytes(usage.indexBytes)}）",
                            color = FnColors.Muted,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SettingsActionButton(
                        label = "清除图片和资料缓存",
                        onClick = {
                            scope.launch {
                                container.authenticatedActions.clearAllEvictableCaches()
                                refreshUsage()
                            }
                        },
                        modifier = Modifier
                            .width(166.dp)
                            .focusProperties {
                                up = onlineLyricsFocus
                                // 下一站是「清除歌曲缓存」，不要跨过它，否则该按钮遥控器永远到不了
                                down = songCacheClearFocus
                                left = cacheFocuses.last()
                                right = FocusRequester.Cancel
                            }
                            .focusRequester(clearCacheFocus),
                    )
                }
                SettingsDivider()
                Row(
                    Modifier.fillMaxWidth().height(80.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "歌曲磁盘缓存",
                        color = FnColors.Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.width(144.dp),
                    )
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            "当前 ${formatBytes(songCacheBytes)}",
                            color = FnColors.Muted,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        )
                        Text(
                            "已播放歌曲的本地缓存",
                            color = FnColors.Muted.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SettingsActionButton(
                        label = "清除歌曲缓存",
                        onClick = {
                            container.playbackController.clearSongCache()
                            scope.launch { refreshSongCacheUsage() }
                        },
                        modifier = Modifier
                            .width(166.dp)
                            .focusProperties {
                                up = clearCacheFocus
                                down = if (container.updateController.enabled) {
                                    updateFocus
                                } else {
                                    remoteGuideFocus
                                }
                                left = cacheFocuses.last()
                                right = FocusRequester.Cancel
                            }
                            .focusRequester(songCacheClearFocus),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("关于", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            AboutSection(
                updateState = updateState,
                updateEnabled = container.updateController.enabled,
                lastCheckedAt = lastCheckedAt,
                updateFocus = updateFocus,
                updateUpFocus = songCacheClearFocus,
                updateDownFocus = remoteGuideFocus,
                onUpdateAction = {
                    if (updateState is UpdateUiState.Available) {
                        container.updateController.startDownload()
                    } else {
                        container.updateController.checkManually()
                    }
                },
            )

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(SettingsPanel, RoundedCornerShape(9.dp))
                    .border(0.75.dp, SettingsBorderColor, RoundedCornerShape(9.dp))
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("按键说明", color = FnColors.Muted, fontSize = 12.sp, lineHeight = 14.sp)
                    Text(
                        "查看播放页的遥控器按键用法",
                        color = FnColors.Muted.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SettingsActionButton(
                    label = "查看按键说明",
                    onClick = { showRemoteGuide = true },
                    modifier = Modifier
                        .width(150.dp)
                        .focusProperties {
                            up = if (container.updateController.enabled) updateFocus else songCacheClearFocus
                            down = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                        .focusRequester(remoteGuideFocus),
                )
            }
            if (showRemoteGuide) {
                RemoteControlGuide { showRemoteGuide = false }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    height: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = FnColors.Muted, fontSize = 12.sp, modifier = Modifier.width(144.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(SettingsDividerColor))
}


@Composable
private fun SettingsChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Button(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF4B3936) else SettingsControl,
            contentColor = FnColors.Text,
            // 聚焦≠选中：未选中的聚焦用中性蓝，避免被误认为“也被选中”
            focusedContainerColor = if (selected) Color(0xFF513B37) else Color(0xFF232B36),
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            border = Border(
                BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) FnColors.Coral else Color(0xFF454B4D)),
                shape = shape,
            ),
            // 聚焦边框：选中项用珊瑚橙，未选中项用暖金，一眼可辨
            focusedBorder = Border(
                BorderStroke(1.5.dp, if (selected) FnColors.Coral else FnColors.Amber),
                shape = shape,
            ),
            pressedBorder = Border(BorderStroke(3.dp, FnFocus.ringColor), shape = shape),
        ),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
internal fun SettingsActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: String? = null,
    emphasis: Boolean = false,
) {
    val shape = RoundedCornerShape(18.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(34.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (emphasis) FnColors.Coral else SettingsControl,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.Coral,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.Coral,
            pressedContentColor = FnColors.Text,
            disabledContainerColor = if (emphasis) Color(0xFF5A3A33) else Color(0xFF24282B),
            disabledContentColor = FnColors.Muted,
        ),
        border = ButtonDefaults.border(
            border = Border(
                BorderStroke(if (emphasis) 1.5.dp else 0.5.dp, if (emphasis) FnColors.Coral else Color(0xFF454B4D)),
                shape = shape,
            ),
            focusedBorder = Border(BorderStroke(3.dp, FnFocus.ringColor), shape = shape),
            pressedBorder = Border(BorderStroke(3.dp, FnFocus.ringColor), shape = shape),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) Text(leading, fontSize = 16.sp, lineHeight = 16.sp)
                Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun SettingsCheckbox(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .width(320.dp)
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(SettingsControl, RoundedCornerShape(5.dp))
            .border(
                if (focused) 1.5.dp else 0.5.dp,
                if (focused) FnColors.Coral else Color(0xFF454B4D),
                RoundedCornerShape(5.dp),
            )
            .focusable()
            .onPreviewKeyEvent { event ->
                // 遥控器确定键（DPAD_CENTER / Enter）也能切换复选框。
                if (event.key == Key.Enter || event.key == Key.DirectionCenter) {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                } else {
                    false
                }
            }
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(if (selected) FnColors.Warning else Color.Transparent, RoundedCornerShape(2.dp))
                .border(1.5.dp, if (selected) FnColors.Warning else FnColors.Muted, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text("✓", color = FnColors.Background, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f KB".format(bytes / 1024.0)
}
