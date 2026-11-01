package com.yueyin.tv.ui.source

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yueyin.tv.core.data.source.MusicSource
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.FnRadius
import com.yueyin.tv.ui.FnType
import com.yueyin.tv.ui.fnFocusRing
import com.yueyin.tv.ui.focusBringIntoView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 音乐源列表。
 *
 * 这里的每一行都是「用户自己导入的脚本」，所以：
 * - 不提供「启用/停用」开关（少一个要解释的状态），不用就删；
 * - 删除用「再按一次」而不是弹窗（TV 上不涉及焦点所有权，最稳）；
 * - 空态只有一个动作：添加。没有源的时候列表页没有别的意义。
 */
@Composable
internal fun MusicSourceScreen(
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val state by MusicSourceRuntime.state.collectAsState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(8) {
            if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(40)
        }
    }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    // 检测中：把脚本丢进真正的运行时跑一遍，读出它声明了哪些平台
    var checkingId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(pendingDeleteId) {
        if (pendingDeleteId != null) {
            delay(4000)
            pendingDeleteId = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Text("音乐源", color = FnColors.Text, fontSize = FnType.PageTitle, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "添加 LX 音源脚本后，搜索里能直接播在线歌曲；不依赖曲库，也不需要账号",
            color = FnColors.Muted,
            fontSize = FnType.Sub,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "导入即可用，不需要手动检测——支持哪几家平台会自动识别出来",
            color = FnColors.Muted.copy(alpha = 0.75f),
            fontSize = FnType.Caption,
        )
        Spacer(Modifier.height(20.dp))

        val sources = state.sources
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sources.forEachIndexed { index, source ->
                val deleting = pendingDeleteId == source.id
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceCard(
                        onClick = { },
                        enabled = false,
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp),
                    ) {
                        SourceRowContent(source, deleting)
                    }
                    SourceActionButton(
                        // 这一步不再是「使用前的必经步骤」：导入后会自动识别一次，
                        // 这里的按钮只是「识别失败/想再确认一次」时的手动重试用。
                        label = when {
                            checkingId == source.id -> "识别中"
                            source.lastCheckAt == 0L -> "识别"
                            else -> "重测"
                        },
                        danger = false,
                        onClick = {
                            if (checkingId != null) return@SourceActionButton
                            checkingId = source.id
                            scope.launch {
                                checkMusicSource(context, source)
                                checkingId = null
                            }
                        },
                    )
                    SourceActionButton(
                        label = if (deleting) "再按一次" else "删除",
                        danger = deleting,
                        modifier = if (index == 0 && sources.isNotEmpty()) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        },
                        onClick = {
                            if (deleting) {
                                pendingDeleteId = null
                                MusicSourceRuntime.store.remove(source.id)
                            } else {
                                pendingDeleteId = source.id
                            }
                        },
                    )
                }
            }
            SourceCard(
                onClick = onAdd,
                enabled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .then(if (sources.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(FnColors.Teal.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("＋", color = FnColors.Teal, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (sources.isEmpty()) "添加音乐源" else "再添加一个",
                            color = FnColors.Text,
                            fontSize = FnType.Body,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "本机选文件，或用同一 Wi-Fi 的设备打开地址上传",
                            color = FnColors.Muted,
                            fontSize = FnType.Sub,
                        )
                    }
                }
            }
        }

        if (sources.isEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "App 不内置任何音源。脚本由你自己提供，导入后只保存在这台电视上。",
                color = FnColors.Muted.copy(alpha = 0.8f),
                fontSize = FnType.Caption,
            )
        }
    }
}

@Composable
private fun SourceRowContent(source: MusicSource, deleting: Boolean) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(
                    if (source.usable) FnColors.Teal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f),
                    RoundedCornerShape(13.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "JS",
                color = if (source.usable) FnColors.Teal else FnColors.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                source.fileName,
                color = FnColors.Text,
                fontSize = FnType.Body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (source.usable) FnColors.Teal else FnColors.Muted.copy(alpha = 0.5f),
                            RoundedCornerShape(50),
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (deleting) "再按一次右侧「删除」即移除" else source.summary(),
                    color = if (deleting) FnColors.Coral else FnColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.lg)
    Box(
        modifier = modifier
            .focusBringIntoView()
            .background(
                if (enabled) FnColors.SurfaceAlt else FnColors.SurfaceAlt.copy(alpha = 0.6f),
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .then(
                if (enabled) {
                    Modifier
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
                } else {
                    Modifier
                },
            )
            .fnFocusRing(focused = focused && enabled, shape = shape, cornerRadius = FnRadius.lg),
    ) { content() }
}

@Composable
private fun SourceActionButton(
    label: String,
    danger: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.lg)
    Box(
        modifier
            .focusBringIntoView()
            .size(width = if (danger) 96.dp else 64.dp, height = 76.dp)
            .background(
                if (danger) FnColors.Coral.copy(alpha = 0.16f) else FnColors.SurfaceAlt,
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
                if (danger) {
                    Modifier.border(1.dp, FnColors.Coral.copy(alpha = 0.5f), shape)
                } else {
                    Modifier
                },
            )
            .fnFocusRing(focused = focused, shape = shape, cornerRadius = FnRadius.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (danger) FnColors.Coral else FnColors.Muted,
            fontSize = if (danger) 13.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
