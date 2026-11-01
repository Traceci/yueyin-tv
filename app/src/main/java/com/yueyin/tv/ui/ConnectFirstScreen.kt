package com.yueyin.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yueyin.tv.core.data.connect.ConnectProfile
import com.yueyin.tv.ui.connect.PlatformIcon
import kotlinx.coroutines.delay

/**
 * 一张卡都没有时的首页空态。
 *
 * 只在「三样都没有」时出现：没有登录飞牛、没有激活任何协议平台、也没有可用的音源。
 * 三者只要有一个，用户就已经能听歌，于是直接进正常首页（曲库区会显示占位）。
 *
 * 界面是**一张卡里两行**，不是两张卡：连接平台和添加音源是两条并列的入口，
 * 但都属于「先让我能听歌」这一件事，分成两张卡会让人以为要先解决其中一个。
 *
 * 刻意不做「不再提示」：空态是唯一的引导入口，允许关掉只会让人找不到路。
 */
@Composable
internal fun ConnectFirstScreen(
    savedPlatforms: List<ConnectProfile>,
    sourceSummary: String,
    hasSource: Boolean,
    onConnect: () -> Unit,
    onManageSources: () -> Unit,
    onPickSaved: (ConnectProfile) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(8) {
            if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(40)
        }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(FnColors.Surface, RoundedCornerShape(22.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp)),
            ) {
                StartRow(
                    title = "连接音乐服务",
                    subtitle = "Emby / Navidrome / Subsonic / Jellyfin，也可以登录飞牛音乐",
                    iconColor = FnColors.Coral,
                    iconText = "＋",
                    onClick = onConnect,
                    modifier = Modifier.focusRequester(firstFocus),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.07f)),
                )
                StartRow(
                    title = if (hasSource) "音乐源" else "添加音乐源",
                    subtitle = if (hasSource) {
                        sourceSummary
                    } else {
                        "没有曲库也能听：在线搜歌，直接播放，不需要账号和会员"
                    },
                    iconColor = FnColors.Teal,
                    iconText = "♪",
                    trailing = if (hasSource) "管理 ›" else "›",
                    statusDot = hasSource,
                    onClick = onManageSources,
                )
            }
            Text(
                "选一个就能开始听；两样都有，曲库 + 在线互补，是更好的组合",
                color = FnColors.Muted.copy(alpha = 0.75f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            if (savedPlatforms.isNotEmpty()) {
                SavedPlatformRow(savedPlatforms, onPickSaved)
            }
        }
    }
}

@Composable
private fun StartRow(
    title: String,
    subtitle: String,
    iconColor: Color,
    iconText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String = "›",
    statusDot: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(76.dp)
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
            .clickable(onClick = onClick),
    ) {
        // 焦点环画在行内（inset 5dp），而不是整张卡外面：
        // 这样两行各自有明确的焦点，卡本身还是一个整体容器。
        if (focused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(5.dp)
                    .border(3.dp, FnFocus.ringColor, shape),
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(iconColor.copy(alpha = 0.14f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(iconText, color = iconColor, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = FnColors.Text, fontSize = 24.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (statusDot) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(FnColors.Teal, RoundedCornerShape(50)),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(subtitle, color = FnColors.Muted, fontSize = 14.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                trailing,
                color = if (focused) FnColors.Text else FnColors.Muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SavedPlatformRow(
    savedPlatforms: List<ConnectProfile>,
    onPickSaved: (ConnectProfile) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "或者直接用之前连过的平台",
            color = FnColors.Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            savedPlatforms.take(3).forEach { profile ->
                SavedPlatformChip(profile, onPickSaved)
            }
        }
    }
}

@Composable
private fun SavedPlatformChip(profile: ConnectProfile, onPickSaved: (ConnectProfile) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .height(56.dp)
            .background(FnColors.SurfaceAlt, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                val center = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (center && event.type == KeyEventType.KeyUp) {
                    onPickSaved(profile)
                    true
                } else {
                    false
                }
            }
            .clickable { onPickSaved(profile) }
            .then(
                if (focused) Modifier.border(FnFocus.ringWidth, FnFocus.ringColor, shape)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape),
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlatformIcon(profile.type, 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(profile.displayName, color = FnColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
