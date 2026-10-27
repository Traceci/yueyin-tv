package com.yueyin.tv.ui

import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.tv.material3.ButtonDefaults
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
 * 它只在「没有任何可用数据源」时出现：既没有登录飞牛，也没有激活任何协议平台。
 * 连上任意一个平台后它就永久消失，正常情况下用户只会看到一次。
 *
 * 刻意不做「不再提示」：空态是唯一的引导入口，允许关掉只会让人找不到路。
 */
@Composable
internal fun ConnectFirstScreen(
    savedPlatforms: List<ConnectProfile>,
    onConnect: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ConnectFirstCard(onClick = onConnect, modifier = Modifier.focusRequester(firstFocus))
            if (savedPlatforms.isNotEmpty()) {
                SavedPlatformRow(savedPlatforms, onPickSaved)
            }
        }
    }
}

@Composable
private fun ConnectFirstCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.Surface,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.Surface,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.SurfaceAlt,
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            border = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(
                    if (focused) FnFocus.ringWidth * 2 else 1.dp,
                    if (focused) FnFocus.ringColor else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f),
                ),
                shape = shape,
            ),
            focusedBorder = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(FnFocus.ringWidth * 2, FnFocus.ringColor),
                shape = shape,
            ),
            pressedBorder = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(FnFocus.ringWidth * 2, FnFocus.ringColor),
                shape = shape,
            ),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(FnColors.Coral.copy(alpha = 0.14f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋", color = FnColors.Coral, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("连接音乐服务", fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Emby / Navidrome / Subsonic / Jellyfin，也可以登录飞牛音乐",
                    color = FnColors.Muted,
                    fontSize = 15.sp,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text("去添加 ›", color = FnColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = { onPickSaved(profile) },
        modifier = Modifier.height(56.dp).onFocusChanged { focused = it.isFocused },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.SurfaceAlt,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.SurfaceAlt,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.Surface,
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            border = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f)),
                shape = shape,
            ),
            focusedBorder = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(FnFocus.ringWidth, FnFocus.ringColor),
                shape = shape,
            ),
            pressedBorder = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(FnFocus.ringWidth, FnFocus.ringColor),
                shape = shape,
            ),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlatformIcon(profile.type, 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(profile.displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
