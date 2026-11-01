package com.yueyin.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * 首页的「没有曲库」形态。
 *
 * 只有在用户导入了可用音源、但没连任何曲库平台时才会走到这里（空态条件放宽后的必然结果）。
 * 现在在线音乐已作为「平台」接入，不再有独立的在线搜索入口；
 * 这里只保留「去连接」引导，连上平台后首页自然显示在线榜单/歌单。
 */
@Composable
internal fun HomeWithoutLibrary(
    sourceSummary: String,
    onConnect: () -> Unit,
    onManageSources: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近添加", color = FnColors.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            LinkText("管理音乐源 ›", onClick = onManageSources)
        }
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color.White.copy(alpha = 0.012f), RoundedCornerShape(FnRadius.lg))
                .border(
                    1.5.dp,
                    Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(FnRadius.lg),
                )
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(FnColors.Coral.copy(alpha = 0.12f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋", color = FnColors.Coral, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("还没有连接音乐服务", color = FnColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "连上 Emby / Navidrome / 飞牛音乐 / 在线音乐后，专辑、歌单、收藏会出现在这里",
                    color = FnColors.Muted.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.width(16.dp))
            LinkText("去连接 ›", onClick = onConnect)
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .background(
                if (focused) Color.White.copy(alpha = 0.08f) else Color.Transparent,
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
            .then(
                if (focused) Modifier.border(FnFocus.ringWidth, FnFocus.ringColor, shape) else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            color = if (focused) FnColors.Text else FnColors.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
