package com.yueyin.tv.phone

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yueyin.tv.BuildConfig
import com.yueyin.tv.CrashLogStore
import com.yueyin.tv.R
import com.yueyin.tv.core.audiofx.FxPreset
import com.yueyin.tv.core.audiofx.FxSettings
import com.yueyin.tv.core.model.preferences.AmbientEffect
import com.yueyin.tv.core.model.preferences.CacheBudget
import com.yueyin.tv.core.model.preferences.CacheUsage
import com.yueyin.tv.core.model.preferences.UiScale
import com.yueyin.tv.core.playback.AudioFxController
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.device.DeviceLayoutOverride
import com.yueyin.tv.ui.device.LayoutOverride
import com.yueyin.tv.ui.device.findHostActivity
import com.yueyin.tv.ui.FnQrCode
import com.yueyin.tv.ui.ISSUE_URL
import com.yueyin.tv.update.UpdateUiState
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 播放界面
// ---------------------------------------------------------------------------

/**
 * 播放界面设置。
 *
 * 十个偏好项里的六个在这一页（样式 / 缩放 / 歌词匹配 / 逐字填充 / 动态取色 / 模糊背景 / 氛围特效）。
 * 一项都不折叠：手机上「改设置」本身就是低频动作，进到二级页还看不到全貌会更烦。
 */
@Composable
internal fun PhonePlayerSettingsPane(onBack: () -> Unit) {
    val deps = LocalPhoneDeps.current
    val prefs by deps.appPreferences.state.collectAsStateWithLifecycle()
    val prefsController = deps.appPreferences

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "播放界面", onBack = onBack)

        // 样式放在最上面而不是列表里：它是这一页唯一「看得见效果」的选项，
        // 缩略图比文字更直接，用户不用来回切出去对比。
        Text(
            "播放页样式",
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = PhoneSpace.md, bottom = PhoneSpace.sm),
        )
        // 样式只剩「封面 / 唱片」两种，且只作用于手机播放页，所以存手机本地偏好，
        // 不用 core 的 PlayerStyle——那一份是电视端的「封面 / 海报 / 歌词」，
        // 海报已从手机端下线，歌词也不再是样式（播放页固定带歌词条）。
        val context = LocalContext.current
        var viewMode by remember { mutableStateOf(phoneViewMode(context)) }
        var layoutOverride by remember { mutableStateOf(DeviceLayoutOverride.get(context)) }
        Row(horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm + 2.dp)) {
            PhoneViewMode.entries.forEach { mode ->
                StylePreviewCard(
                    mode = mode,
                    selected = viewMode == mode,
                    onClick = {
                        viewMode = mode
                        phoneSetViewMode(context, mode)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        PhoneSettingsGroup {
            PhoneSettingsChoiceRow(
                title = "界面缩放",
                subtitle = "整体字号与间距，小屏选紧凑",
                options = UiScale.entries,
                selected = prefs.uiScale,
                label = { it.scaleName() },
                onSelect = { prefsController.setUiScale(it) },
            )
            PhoneSettingsDivider()
            PhoneSettingsChoiceRow(
                title = "界面形态",
                subtitle = "车机未正确识别时可手动指定，切换后界面会刷新",
                options = LayoutOverride.entries,
                selected = layoutOverride,
                label = { it.label },
                onSelect = { value ->
                    DeviceLayoutOverride.set(context, value)
                    layoutOverride = value
                    Toast.makeText(context, "已切换界面形态，正在刷新", Toast.LENGTH_SHORT).show()
                    context.findHostActivity()?.recreate()
                },
            )
            PhoneSettingsDivider()
            PhoneSettingsSwitchRow(
                title = "在线歌词匹配",
                subtitle = "找不到歌词时按曲名去网上找一份",
                checked = prefs.onlineLyricsMatchingEnabled,
                onToggle = { prefsController.setOnlineLyricsMatchingEnabled(it) },
            )
            PhoneSettingsDivider()
            PhoneSettingsSwitchRow(
                title = "逐字填充",
                subtitle = "当前句按时间从左到右点亮",
                checked = prefs.karaokeFill,
                onToggle = { prefsController.setKaraokeFill(it) },
            )
        }

        PhoneSettingsGroup("视觉") {
            PhoneSettingsSwitchRow(
                title = "动态取色",
                subtitle = "从封面提取主色给播放页染色",
                checked = prefs.dynamicColor,
                onToggle = { prefsController.setDynamicColor(it) },
            )
            PhoneSettingsDivider()
            PhoneSettingsSwitchRow(
                title = "模糊背景",
                subtitle = "播放页背景用封面虚化成整屏",
                checked = prefs.blurredBackdrop,
                onToggle = { prefsController.setBlurredBackdrop(it) },
            )
            PhoneSettingsDivider()
            PhoneSettingsChoiceRow(
                title = "氛围特效",
                subtitle = "播放页的呼吸光晕与唱片涟漪",
                options = AmbientEffect.entries,
                selected = prefs.ambientEffect,
                label = { it.effectName() },
                onSelect = { prefsController.setAmbientEffect(it) },
            )
        }

        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

/**
 * 样式缩略图。
 *
 * 自绘而不放真实截图：两张截图加起来几百 KB，还会在深色/浅色封面上各露一次马脚；
 * 这里要传达的只是「中间那块是什么」——一张方图，或者一张唱盘。
 */
@Composable
private fun StylePreviewCard(
    mode: PhoneViewMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PhoneRadius.md)
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    BoxWithConstraints(
        modifier.phoneTap(onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        // 竖屏：高度仍按「宽度 / 0.82」推导，保持原来的竖向预览比例。
        // 横屏：这一页是 verticalScroll、高度不受限，按宽度算会把两张卡撑到近 500dp 高，
        // 下面的「界面缩放 / 界面形态 / 视觉」全被推出屏外。所以按可视高度封顶，
        // 再由 0.82 反推宽度并居中——卡片变窄但不掉比例，也不再占满整屏。
        val naturalHeight = maxWidth / 0.82f
        val cappedHeight = (configuration.screenHeightDp * 0.46f).dp
        val previewHeight = if (landscape) minOf(naturalHeight, cappedHeight) else naturalHeight
        val previewWidth = previewHeight * 0.82f
        val tint = if (selected) FnColors.Coral else FnColors.Muted
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .width(previewWidth)
                    .height(previewHeight)
                    .background(
                        if (selected) FnColors.Coral.copy(alpha = 0.10f) else FnColors.SurfaceAlt,
                        shape,
                    )
                    .border(
                        if (selected) 1.5.dp else 1.dp,
                        if (selected) FnColors.Coral else Color.White.copy(alpha = 0.06f),
                        shape,
                    )
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val line = size.minDimension * 0.055f
                    // 视图缩略图下方的两条线：上粗下细，代表曲名与歌手，两种样式共用。
                    fun drawTitleLines() {
                        drawLine(
                            color = tint.copy(alpha = 0.5f),
                            start = Offset(w * 0.26f, h * 0.80f),
                            end = Offset(w * 0.74f, h * 0.80f),
                            strokeWidth = line,
                        )
                        drawLine(
                            color = tint.copy(alpha = 0.28f),
                            start = Offset(w * 0.36f, h * 0.90f),
                            end = Offset(w * 0.64f, h * 0.90f),
                            strokeWidth = line,
                        )
                    }
                    when (mode) {
                        PhoneViewMode.Cover -> {
                            // 封面：一个圆角方块（方形封面原图）+ 画面里的取景点。
                            val side = size.minDimension * 0.54f
                            val left = w / 2 - side / 2
                            val top = h * 0.17f
                            drawRoundRect(
                                color = tint.copy(alpha = 0.12f),
                                topLeft = Offset(left, top),
                                size = Size(side, side),
                                cornerRadius = CornerRadius(side * 0.14f),
                            )
                            drawRoundRect(
                                color = tint,
                                topLeft = Offset(left, top),
                                size = Size(side, side),
                                cornerRadius = CornerRadius(side * 0.14f),
                                style = Stroke(width = line),
                            )
                            drawCircle(
                                color = tint.copy(alpha = 0.75f),
                                radius = side * 0.13f,
                                center = Offset(w / 2, top + side * 0.40f),
                            )
                            drawTitleLines()
                        }

                        PhoneViewMode.Vinyl -> {
                            // 唱片：一个圆环 + 圆心点（盘心 label），下面同样是曲名与歌手。
                            val radius = size.minDimension * 0.30f
                            val center = Offset(w / 2, h * 0.38f)
                            drawCircle(color = tint, radius = radius, center = center, style = Stroke(width = line))
                            drawCircle(color = tint.copy(alpha = 0.7f), radius = radius * 0.22f, center = center)
                            drawTitleLines()
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (mode == PhoneViewMode.Cover) "封面" else "唱片",
                color = if (selected) FnColors.Coral else FnColors.Muted,
                fontSize = PhoneType.Caption,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 音效
// ---------------------------------------------------------------------------

/**
 * 音效设置。
 *
 * 与播放页的音效 sheet 是同一份数据（`FxPreset.selectable` + `AudioFxController`），
 * 任一处改动另一处立刻跟着变——两边各存一份状态的话，用户会看到「播放页选了低音，
 * 设置里还显示平直」，这是这类双入口最经典的 bug。
 */
@Composable
internal fun PhoneFxSettingsPane(onBack: () -> Unit) {
    val current by AudioFxController.state.collectAsStateWithLifecycle()
    val presets = FxPreset.selectable

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "音效", onBack = onBack)

        PhoneSettingsGroup {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (current.enabled) "已开启 · ${current.effectLabel}" else "未开启",
                        color = if (current.enabled) FnColors.Coral else FnColors.Text,
                        fontSize = PhoneType.Body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (current.enabled) "调整立即生效，切歌也不会回退" else "打开后下面选的档位才会生效",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Caption,
                        maxLines = 1,
                    )
                }
                PhoneSwitch(
                    checked = current.enabled,
                    onToggle = { AudioFxController.update(current.copy(enabled = it)) },
                )
            }
        }

        PhoneSettingsGroup(title = "档位") {
            Column(Modifier.padding(PhoneSpace.md)) {
                presets.chunked(2).forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) Spacer(Modifier.height(PhoneSpace.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
                        row.forEach { preset ->
                            FxPresetTile(
                                preset = preset,
                                selected = current.enabled && current.presetId == preset.id,
                                onClick = {
                                    // 与播放页一致：点档位即视为「我要用这个音效」，
                                    // 不再要求先去拨一次总开关。
                                    AudioFxController.update(current.selecting(preset))
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 奇数档补一个等宽占位，最后一格不会被拉成半屏宽。
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(PhoneSpace.md))
        Text(
            "音效只作用于本机播放链路，不会改变源文件；换设备需要重新选。",
            color = FnColors.Muted.copy(alpha = 0.7f),
            fontSize = PhoneType.Micro,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

@Composable
private fun FxPresetTile(
    preset: FxPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .height(52.dp)
            .background(
                if (selected) FnColors.Coral.copy(alpha = 0.14f) else FnColors.SurfaceAlt,
                shape,
            )
            .border(
                1.dp,
                if (selected) FnColors.Coral.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.06f),
                shape,
            )
            .phoneTap(onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            preset.displayName,
            color = if (selected) FnColors.Coral else FnColors.Text,
            fontSize = PhoneType.Label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 缓存
// ---------------------------------------------------------------------------

@Composable
internal fun PhoneCacheSettingsPane(onBack: () -> Unit) {
    val deps = LocalPhoneDeps.current
    val prefs by deps.appPreferences.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 存原始对象而不是拼好的句子：占用拆成三行显示，
    // 挤成一行「1.7 MB（图片 0 B / 资料 1.7 MB）」在 45% 宽度里必然被截断。
    var usage by remember { mutableStateOf<CacheUsage?>(null) }
    var usageReady by remember { mutableStateOf(false) }
    var songCacheText by remember { mutableStateOf("统计中…") }
    var clearing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        usage = runCatching { deps.musicRepository.cacheUsage() }.getOrNull()
        usageReady = true
        songCacheText = phoneBytes(deps.playbackController.songCacheUsageBytes())
    }

    LaunchedEffect(Unit) {
        // 进页面先按当前预算重算一次缓存上限：预算改了但没进过设置的用户，
        // 原来那份上限会一直沿用旧值，表现为「选了 32MB 还占着 200MB」。
        runCatching { deps.musicRepository.applyArtworkBudget() }
        refresh()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "缓存", onBack = onBack)

        PhoneSettingsGroup {
            PhoneSettingsChoiceRow(
                title = "封面缓存上限",
                subtitle = "超出后按最近最少使用淘汰",
                options = CacheBudget.entries,
                selected = prefs.cacheBudget,
                label = { it.budgetName() },
                onSelect = {
                    deps.appPreferences.setCacheBudget(it)
                    scope.launch {
                        deps.musicRepository.applyArtworkBudget()
                        refresh()
                    }
                },
            )
        }

        PhoneSettingsGroup("当前占用") {
            val snapshot = usage
            PhoneSettingsInfoRow(
                "封面图片",
                when {
                    !usageReady -> "统计中…"
                    snapshot == null -> "读取失败"
                    else -> phoneBytes(snapshot.artworkBytes)
                },
            )
            PhoneSettingsDivider()
            PhoneSettingsInfoRow(
                "曲库资料",
                when {
                    !usageReady -> "统计中…"
                    snapshot == null -> "读取失败"
                    else -> phoneBytes(snapshot.indexBytes)
                },
            )
            PhoneSettingsDivider()
            PhoneSettingsInfoRow(
                "合计",
                when {
                    !usageReady -> "统计中…"
                    snapshot == null -> "读取失败"
                    else -> phoneBytes(snapshot.totalBytes)
                },
            )
            PhoneSettingsDivider()
            PhoneSettingsInfoRow("歌曲磁盘缓存", songCacheText)
        }

        PhoneSettingsGroup {
            PhoneSettingsActionRow(
                title = if (clearing) "正在清理…" else "清除图片与资料缓存",
                subtitle = "不影响已下载的歌曲与账号登录状态",
                onClick = {
                    if (clearing) return@PhoneSettingsActionRow
                    clearing = true
                    scope.launch {
                        runCatching { deps.musicRepository.clearAllEvictableCaches() }
                        refresh()
                        clearing = false
                        Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            PhoneSettingsDivider()
            PhoneSettingsActionRow(
                title = "清除歌曲磁盘缓存",
                subtitle = "下次播放会重新下载，流量会重走一遍",
                onClick = {
                    deps.playbackController.clearSongCache()
                    scope.launch {
                        refresh()
                        Toast.makeText(context, "歌曲缓存已清除", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        Spacer(Modifier.height(PhoneSpace.md))
        Text(
            "缓存只占本机存储，不影响音源与曲库；清理后首屏封面会重新加载一次。",
            color = FnColors.Muted.copy(alpha = 0.7f),
            fontSize = PhoneType.Micro,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

// ---------------------------------------------------------------------------
// 关于
// ---------------------------------------------------------------------------

@Composable
internal fun PhoneAboutPane(onBack: () -> Unit, onOpenCrashLog: () -> Unit) {
    val deps = LocalPhoneDeps.current
    val context = LocalContext.current
    val updateState by deps.updateController.state.collectAsStateWithLifecycle()
    val lastCheckedAt by deps.updateController.lastCheckedAt.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "关于", onBack = onBack)

        // 品牌卡：logo + 版本三行。颜色与「关于悦音」这一行在根页的表述保持一致。
        Row(
            Modifier
                .fillMaxWidth()
                .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.md))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(PhoneRadius.md))
                .padding(PhoneSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(PhoneSpace.md))
            Column(Modifier.weight(1f)) {
                Text("悦音", color = FnColors.Text, fontSize = PhoneType.Section, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "自建音乐库播放器 · 手机版",
                    color = FnColors.Muted,
                    fontSize = PhoneType.Caption,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "版本 ${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE} · GPL-3.0",
                    color = FnColors.Muted.copy(alpha = 0.75f),
                    fontSize = PhoneType.Micro,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        PhoneSettingsGroup(title = "更新") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(updateColor(updateState), CircleShape),
                )
                Spacer(Modifier.width(PhoneSpace.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        updateText(updateState),
                        color = FnColors.Text,
                        fontSize = PhoneType.Row,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                    )
                    updateDetail(updateState, lastCheckedAt)?.let { detail ->
                        Spacer(Modifier.height(3.dp))
                        Text(detail, color = FnColors.Muted, fontSize = PhoneType.Caption, maxLines = 1)
                    }
                }
                if (deps.updateController.enabled) {
                    Spacer(Modifier.width(PhoneSpace.sm))
                    PhoneUpdateButton(
                        label = updateActionLabel(updateState),
                        enabled = updateActionEnabled(updateState),
                        emphasized = updateState is UpdateUiState.Available,
                        onClick = {
                            when (updateState) {
                                is UpdateUiState.Available -> deps.updateController.startDownload()
                                // 「需要授权」时按钮要变成「去授权」，否则用户在这一页点了没反应，
                                // 只能靠系统通知栏里那条安装提示自己找路。
                                is UpdateUiState.AwaitingInstallPermission ->
                                    deps.updateController.openInstallPermissionSettings()

                                is UpdateUiState.Error,
                                UpdateUiState.Idle,
                                is UpdateUiState.UpToDate,
                                -> deps.updateController.checkManually()

                                // 下载/校验/安装进行中重复触发会让控制器抛状态异常，什么都不做。
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }

        PhoneSettingsGroup(title = "反馈") {
            Row(
                Modifier.fillMaxWidth().padding(PhoneSpace.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(76.dp)
                        .background(Color.White, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                ) {
                    FnQrCode(content = ISSUE_URL, size = 68.dp)
                }
                Spacer(Modifier.width(PhoneSpace.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        "反馈与建议",
                        color = FnColors.Text,
                        fontSize = PhoneType.Body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "扫码或点下面这行，去 GitHub 提交问题",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Caption,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(PhoneSpace.sm))
                    Text(
                        "在浏览器打开 ›",
                        color = FnColors.Coral,
                        fontSize = PhoneType.Label,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.phoneTap {
                            // 手机上有浏览器，扫码是电视台那套绕路方式；这里直接跳，
                            // 跳不动（没装浏览器）就退回提示扫码，不让用户卡死。
                            val opened = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_URL)))
                            }.isSuccess
                            if (!opened) {
                                Toast.makeText(context, "没有可用的浏览器，请扫码反馈", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
            PhoneSettingsDivider()
            PhoneSettingsRow(
                title = "崩溃日志",
                subtitle = "最近一次的异常记录与系统退出原因",
                value = null,
                onClick = onOpenCrashLog,
            )
        }

        Spacer(Modifier.height(PhoneSpace.xl))
        Text(
            "悦音不提供任何音源内容。曲库来自你自己的服务器，音源脚本由你自己提供。",
            color = FnColors.Muted.copy(alpha = 0.6f),
            fontSize = PhoneType.Micro,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

@Composable
private fun PhoneUpdateButton(
    label: String,
    enabled: Boolean,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(36.dp)
            .background(
                when {
                    !enabled -> FnColors.SurfaceAlt
                    emphasized -> FnColors.Coral
                    else -> FnColors.SurfaceAlt
                },
                shape,
            )
            .border(
                1.dp,
                if (emphasized && enabled) Color.Transparent else Color.White.copy(alpha = 0.10f),
                shape,
            )
            .then(if (enabled) Modifier.phoneTap(onClick) else Modifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> FnColors.Muted.copy(alpha = 0.6f)
                emphasized -> FnColors.Background
                else -> FnColors.Text
            },
            fontSize = PhoneType.Label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 崩溃日志
// ---------------------------------------------------------------------------

/**
 * 崩溃日志页。
 *
 * 只做三件事：读、复制、清空。不做「上报」——没有服务端，硬做一个假的上报入口
 * 反而让用户以为问题被收走了。复制到剪贴板后贴进 issue 是这条链路唯一真实的终点。
 */
@Composable
internal fun PhoneCrashLogPane(onBack: () -> Unit) {
    val context = LocalContext.current
    var log by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 读日志要访问文件与 ActivityManager（Android 11+ 的退出原因），别放主线程同步做。
        log = runCatching { CrashLogStore.read(context) }.getOrNull()
        loaded = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "崩溃日志", onBack = onBack)

        val text = log
        when {
            !loaded -> Box(Modifier.fillMaxWidth().padding(PhoneSpace.xxl), contentAlignment = Alignment.Center) {
                Text("正在读取…", color = FnColors.Muted, fontSize = PhoneType.Caption)
            }

            text == null -> Column(Modifier.fillMaxWidth().padding(top = PhoneSpace.xl)) {
                Text("没有崩溃记录", color = FnColors.Text, fontSize = PhoneType.Body, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "这是好事。日志只在本机保留最近 512 KB，不会上传。",
                    color = FnColors.Muted,
                    fontSize = PhoneType.Caption,
                )
            }

            else -> {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = PhoneSpace.sm),
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm),
                ) {
                    PhoneOutlineButton(
                        label = "复制全部",
                        glyph = PhoneGlyph.Copy,
                        onClick = {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("崩溃日志", text),
                            )
                            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PhoneOutlineButton(
                        label = "清空",
                        glyph = PhoneGlyph.Trash,
                        onClick = {
                            CrashLogStore.clear(context)
                            log = null
                            Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 日志本身是可滚动的等宽文本块：它比整页滚动更合理，
                // 否则用户往下翻的时候标题会跟着出去，看着像「页面跑掉了」。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.md))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(PhoneRadius.md)),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(PhoneSpace.md),
                    ) {
                        Text(
                            text,
                            color = FnColors.Text.copy(alpha = 0.86f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(PhoneSpace.lg))
    }
}

// ---------------------------------------------------------------------------
// 文案工具
// ---------------------------------------------------------------------------

/** 字节数转人话。手写而非 `Formatter`：这里只需要两个量级，不值得为它引一套格式化。 */
internal fun phoneBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun updateText(state: UpdateUiState): String = when (state) {
    UpdateUiState.Disabled -> "当前版本不支持应用内更新"
    UpdateUiState.Idle -> "尚未检查更新"
    is UpdateUiState.Checking -> "正在检查更新…"
    is UpdateUiState.UpToDate -> "已是最新版本"
    is UpdateUiState.Available -> "发现新版本 ${state.manifest.versionName}"
    is UpdateUiState.Downloading -> "正在下载更新"
    is UpdateUiState.Verifying -> "正在校验安装包"
    is UpdateUiState.AwaitingInstallPermission -> "需要允许安装未知应用"
    is UpdateUiState.PreparingInstaller -> "正在准备安装"
    is UpdateUiState.AwaitingSystemConfirmation -> "请在系统页面确认安装"
    is UpdateUiState.Error -> state.message
}

private fun updateDetail(state: UpdateUiState, lastCheckedAt: Long?): String? = when (state) {
    is UpdateUiState.Available -> "安装包 ${phoneBytes(state.manifest.apkSize)}"
    is UpdateUiState.Downloading -> {
        val total = state.manifest.apkSize.coerceAtLeast(1L)
        "${(state.downloadedBytes * 100 / total).coerceIn(0, 100)}% · ${phoneBytes(state.downloadedBytes)}"
    }
    is UpdateUiState.UpToDate, UpdateUiState.Idle -> lastCheckedAt?.let(::checkedAgo)
    else -> null
}

private fun checkedAgo(lastCheckedAt: Long): String {
    val minutes = (android.os.SystemClock.elapsedRealtime() - lastCheckedAt).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1L -> "刚刚检查"
        minutes < 60L -> "$minutes 分钟前检查"
        else -> "${minutes / 60L} 小时前检查"
    }
}

private fun updateColor(state: UpdateUiState): Color = when (state) {
    is UpdateUiState.Available, is UpdateUiState.Downloading -> FnColors.Coral
    is UpdateUiState.UpToDate -> FnColors.Teal
    is UpdateUiState.Error -> FnColors.Warning
    else -> FnColors.Muted.copy(alpha = 0.5f)
}

private fun updateActionLabel(state: UpdateUiState): String = when (state) {
    is UpdateUiState.Available -> "立即更新"
    is UpdateUiState.AwaitingInstallPermission -> "去授权"
    is UpdateUiState.Checking -> "检查中…"
    is UpdateUiState.Downloading, is UpdateUiState.Verifying -> "处理中…"
    is UpdateUiState.UpToDate -> "已最新"
    else -> "检查更新"
}

private fun updateActionEnabled(state: UpdateUiState): Boolean = when (state) {
    is UpdateUiState.Checking,
    is UpdateUiState.Downloading,
    is UpdateUiState.Verifying,
    is UpdateUiState.PreparingInstaller,
    is UpdateUiState.AwaitingSystemConfirmation,
    is UpdateUiState.UpToDate,
    -> false

    else -> true
}
