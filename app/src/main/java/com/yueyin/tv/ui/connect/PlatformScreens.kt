package com.yueyin.tv.ui.connect

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yueyin.tv.R
import com.yueyin.tv.ui.focusBringIntoView
import androidx.compose.ui.unit.sp
import com.yueyin.tv.core.data.connect.ConnectProfile
import com.yueyin.tv.core.data.connect.ConnectProfileStore
import com.yueyin.tv.core.data.protocol.MusicProtocolRegistry
import com.yueyin.tv.core.model.connect.ConnectType
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.FnFocus
import com.yueyin.tv.ui.FnRadius
import com.yueyin.tv.ui.FnType
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 多平台运行时：平台凭据 + 协议注册表。
 *
 * 刻意用单例而不是往 AppUiDependencies / AuthenticatedAppDependencies 里加成员：
 * 那两个接口被多个测试替身实现，加成员会连带改一堆测试，收益不抵风险。
 */
object ConnectRuntime {
    @Volatile private var storeRef: ConnectProfileStore? = null
    @Volatile private var registryRef: MusicProtocolRegistry? = null

    fun init(context: Context) {
        if (storeRef == null) storeRef = ConnectProfileStore(context.applicationContext)
        if (registryRef == null) {
            // 取链器只建一次：probeClient 内部有连接池，反复 new 会不断建连接。
            val playbackResolver = com.yueyin.tv.source.OnlinePlaybackResolver(context.applicationContext)
            // 脚本聚合搜索也只建一次，复用同一个 WebView 运行时。
            val scriptSearch = com.yueyin.tv.source.OnlineScriptSearch(context.applicationContext)
            registryRef = MusicProtocolRegistry(
                listOf(
                    com.yueyin.tv.core.data.protocol.SubsonicProtocol(ConnectType.NAVIDROME),
                    com.yueyin.tv.core.data.protocol.SubsonicProtocol(ConnectType.SUBSONIC),
                    com.yueyin.tv.core.data.protocol.JellyfinLikeProtocol(ConnectType.JELLYFIN),
                    com.yueyin.tv.core.data.protocol.JellyfinLikeProtocol(ConnectType.EMBY),
                    com.yueyin.tv.core.data.protocol.PlexProtocol(),
                    com.yueyin.tv.core.data.protocol.AudioStationProtocol(),
                    com.yueyin.tv.core.data.protocol.WebDavProtocol(),
                    com.yueyin.tv.core.data.protocol.SmbProtocol(),
                    com.yueyin.tv.core.data.protocol.OnlineMusicProtocol(
                        resolveUrl = { song ->
                            // 失败返回 null（协议侧只缓存成功的直链，失败留给下次取）。
                            val outcome = playbackResolver.resolve(song)
                            (outcome as? com.yueyin.tv.source.PlaybackResolution.Success)?.url
                        },
                        lyricProvider = { song ->
                            playbackResolver.resolveLyric(song)
                        },
                        // 没导入任何音源脚本时，榜单/歌单/新歌/搜索一律返回空，避免「内置音源」。
                        hasSources = {
                            com.yueyin.tv.ui.source.MusicSourceRuntime.state.value.sources.isNotEmpty()
                        },
                        // 把导入音源脚本也接入搜索聚合（双端共用这一条在线协议）。
                        scriptSearch = scriptSearch::search,
                    ),
                ),
            )
            // 本地流代理提前起好：SMB/WebDAV 的播放地址要用它，
            // 放在这里是为了避免第一次播放时才建 ServerSocket（卡一下画面）。
            Thread({ com.yueyin.tv.core.data.protocol.LocalStreamProxy.ensureReady() }, "fn-proxy-warmup")
                .apply { isDaemon = true }
                .start()
        }
    }

    val store: ConnectProfileStore
        get() = storeRef ?: error("ConnectRuntime 未初始化")

    val registry: MusicProtocolRegistry
        get() = registryRef ?: error("ConnectRuntime 未初始化")

    /** 激活平台变化时通知宿主（App 层据此切换 MusicRepository 的数据源）。 */
    @Volatile
    var onActiveChanged: ((ConnectProfile?) -> Unit)? = null

    /** 切换当前平台：落库 + 通知宿主重新指向数据源。 */
    fun activate(profile: ConnectProfile?) {
        store.setActive(profile?.id)
        onActiveChanged?.invoke(profile)
    }
}

// ---------------------------------------------------------------- 公共小部件

@Composable
private fun PlatformCard(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.lg)
    val active = focused && enabled
    Box(
        modifier = modifier
            .focusBringIntoView()
            // 大卡片不用实心：卡片里有图标和两行文字，实心会把它压掉。
            // 焦点用「珊瑚 22% 底 + 2dp 珊瑚边」，与按钮的珊瑚实心同源、同色系。
            .background(
                when {
                    active -> FnColors.Coral.copy(alpha = 0.22f)
                    enabled -> FnColors.SurfaceAlt
                    else -> FnColors.SurfaceAlt.copy(alpha = 0.5f)
                },
                shape,
            )
            .border(
                if (active) 2.dp else 1.dp,
                if (active) FnColors.Coral else Color.White.copy(alpha = 0.10f),
                shape,
            )
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
            .clickable(enabled = enabled, onClick = onClick),
    ) { content() }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    password: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = FnColors.Muted, fontSize = 14.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(FnColors.SurfaceAlt, shape)
                .then(
                    if (focused) Modifier.border(FnFocus.ringWidth, FnFocus.ringColor, shape)
                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape),
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = FnColors.Text, fontSize = 17.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(FnColors.Coral),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusBringIntoView()
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            if (password) "密码" else "必填",
                            color = Color.White.copy(alpha = 0.28f),
                            fontSize = 17.sp,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/**
 * 等组合 / 布局稳定后再请求焦点。
 *
 * 路由页首次插入时 `focusRequester` 还没 attach 到节点上，
 * 直接 `requestFocus()` 会抛 "FocusRequester is not initialized" 被吞掉，
 * 表现就是「焦点还留在别处、遥控器怎么按都没反应」。
 * 这里退让几帧重试，彻底规避 attach 时序问题。
 */
private suspend fun FocusRequester.requestFocusWhenReady(): Boolean {
    repeat(8) {
        if (runCatching { requestFocus() }.getOrDefault(false)) return true
        kotlinx.coroutines.delay(40)
    }
    return false
}

private fun newProfileId(): String = UUID.randomUUID().toString()

/** 我的页「新增平台」的弹层流程。 */
sealed interface PlatformFlowStep {
    object Manage : PlatformFlowStep
    object Picker : PlatformFlowStep
    data class Form(val type: ConnectType) : PlatformFlowStep
    data class Library(val profile: ConnectProfile) : PlatformFlowStep
}


// ---------------------------------------------------------------- 平台图标

private val ConnectType.brandColor: Color
    get() = when (this) {
        ConnectType.FEINIU -> Color(0xFF3AA6F0)
        ConnectType.NAVIDROME -> Color(0xFF7C5CFF)
        ConnectType.SUBSONIC -> Color(0xFF1F8FD8)
        ConnectType.JELLYFIN -> Color(0xFFAA5CC3)
        ConnectType.EMBY -> Color(0xFF52B54B)
        ConnectType.PLEX -> Color(0xFFE5A00D)
        ConnectType.WEBDAV -> Color(0xFF7B8794)
        ConnectType.SMB -> Color(0xFF8B95A5)
        ConnectType.AUDIO_STATION -> Color(0xFF2FA8A0)
        ConnectType.ONLINE -> Color(0xFFFF6B4A)
    }

/**
 * 平台徽章：圆形底 + 品牌色描边 + 内部标记（三角=媒体服务器，方块=文件/局域网，圆点=其余）。
 * 刻意用矢量绘制而不是引入图片资源，避免为 9 个平台各塞一张 PNG。
 */
/**
 * 平台图标：真实品牌图标（整理自箭头音乐的资源，统一圆角方底）。
 *
 * 找不到对应资源时回退到 [PlatformGlyph] 的画圈方案——这样以后新增平台类型
 * 即使忘了放图，也只是样式退化，不会白屏。
 */
@Composable
fun PlatformIcon(
    type: ConnectType,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val res = type.iconRes()
    if (res != 0) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .then(if (dimmed) Modifier.alpha(0.35f) else Modifier),
        )
    } else {
        PlatformGlyph(type, size, modifier)
    }
}

private fun ConnectType.iconRes(): Int = when (this) {
    ConnectType.FEINIU -> R.drawable.ic_platform_feiniu
    ConnectType.NAVIDROME -> R.drawable.ic_platform_navidrome
    ConnectType.SUBSONIC -> R.drawable.ic_platform_subsonic
    ConnectType.JELLYFIN -> R.drawable.ic_platform_jellyfin
    ConnectType.EMBY -> R.drawable.ic_platform_emby
    ConnectType.PLEX -> R.drawable.ic_platform_plex
    ConnectType.WEBDAV -> R.drawable.ic_platform_webdav
    ConnectType.SMB -> R.drawable.ic_platform_smb
    ConnectType.AUDIO_STATION -> R.drawable.ic_platform_audio_station
    ConnectType.ONLINE -> 0
}

@Composable
fun PlatformGlyph(type: ConnectType, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val color = type.brandColor
        drawCircle(color = color.copy(alpha = 0.16f), radius = radius)
        drawCircle(color = color, radius = radius * 0.78f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = radius * 0.12f))
        when (type) {
            ConnectType.JELLYFIN, ConnectType.EMBY, ConnectType.PLEX -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x - radius * 0.22f, center.y - radius * 0.26f)
                    lineTo(center.x + radius * 0.28f, center.y)
                    lineTo(center.x - radius * 0.22f, center.y + radius * 0.26f)
                    close()
                }
                drawPath(path, color = color)
            }
            ConnectType.WEBDAV, ConnectType.SMB -> {
                val side = radius * 0.52f
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - side / 2f, center.y - side / 2f),
                    size = androidx.compose.ui.geometry.Size(side, side),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.24f),
                )
            }
            ConnectType.ONLINE -> {
                // 在线音乐：三个并排的音符柱（高低错落），一眼区别于「服务器/文件」类平台
                val barW = radius * 0.16f
                val heights = listOf(0.28f, 0.52f, 0.36f)
                heights.forEachIndexed { i, h ->
                    val x = center.x + (i - 1) * radius * 0.34f
                    val half = radius * h
                    drawRoundRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(x - barW / 2f, center.y - half),
                        size = androidx.compose.ui.geometry.Size(barW, half * 2f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
                    )
                }
            }
            else -> drawCircle(color = color, radius = radius * 0.26f)
        }
    }
}

// ---------------------------------------------------------------- 平台管理

@Composable
fun PlatformManageScreen(
    onAdd: () -> Unit,
    onRelogin: () -> Unit,
    onEdit: (ConnectProfile) -> Unit,
    onDelete: (ConnectProfile) -> Unit,
    onSignOutFn: () -> Unit,
    fnSessionActive: Boolean,
    onAddSource: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Text("平台", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "可添加多个音乐服务，随时切换；当前生效的平台在资料条显示",
            color = FnColors.Muted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        PlatformManageContent(
            onAdd = onAdd,
            onRelogin = onRelogin,
            onEdit = onEdit,
            onDelete = onDelete,
            onSignOutFn = onSignOutFn,
            fnSessionActive = fnSessionActive,
            onAddSource = onAddSource,
            autoRequestFocus = true,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "按返回键退出",
            color = FnColors.Muted,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/**
 * 平台管理核心内容：平台列表 + 在线音源区块，不带头部标题与返回提示。
 *
 * 既被独立路由页 [PlatformManageScreen] 使用，也被设置页「平台」分组直接内联，
 * 避免「设置 → 平台 → 管理平台与音源」再多一层二级页面。
 */
@Composable
fun PlatformManageContent(
    onAdd: () -> Unit,
    onRelogin: () -> Unit,
    onEdit: (ConnectProfile) -> Unit,
    onDelete: (ConnectProfile) -> Unit,
    onSignOutFn: () -> Unit,
    fnSessionActive: Boolean,
    onAddSource: () -> Unit,
    autoRequestFocus: Boolean,
    // 设置页把「右键进右栏」的焦点请求交给它：挂到第一张平台卡上，
    // 这样设置页的 enterContent() 能直接命中，不必依赖几何搜索。
    externalFirstFocus: FocusRequester? = null,
    // 同理，这是「左键回左栏本分组」的出口：挂在每行行首控件上。
    // 不挂的话按左会走几何搜索，按垂直距离猜一个左栏项——
    // 实测「＋ 再添加一个音源」在最底部，左边最近的是「关于」，于是整页被切到关于分组。
    navExitFocus: FocusRequester? = null,
) {
    val store = ConnectRuntime.store
    val state by store.state.collectAsState()
    val firstFocus = remember { FocusRequester() }
    // 每行行首控件共用的「左键回左栏」出口。行内右侧的小按钮（编辑/删除/检测）不挂，
    // 让它们按左回到同一行左边那颗卡，而不是直接跳栏。
    val navExit = navExitFocus
    val leftExit = if (navExit != null) Modifier.focusProperties { left = navExit } else Modifier
    LaunchedEffect(autoRequestFocus) {
        if (autoRequestFocus) firstFocus.requestFocusWhenReady()
    }
    // activeId == null 表示当前走的是内置飞牛主账号链路（而不是某个已保存的协议平台）
    val fnOsActive = fnSessionActive && state.activeId == null
    var pendingDeleteFn by remember { mutableStateOf(false) }
    LaunchedEffect(pendingDeleteFn) {
        if (pendingDeleteFn) {
            kotlinx.coroutines.delay(4000)
            pendingDeleteFn = false
        }
    }
    // 删除用「二次点击」确认：TV 上比弹层更稳（不涉及焦点所有权），4 秒后自动复位
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingDeleteId) {
        if (pendingDeleteId != null) {
            kotlinx.coroutines.delay(4000)
            pendingDeleteId = null
        }
    }
    // —— 音源脚本识别 ——
    val musicSourcesState by com.yueyin.tv.ui.source.MusicSourceRuntime.state.collectAsState()
    val sourceContext = androidx.compose.ui.platform.LocalContext.current
    val sourceScope = rememberCoroutineScope()
    var checkingSourceId by remember { mutableStateOf<String?>(null) }
    fun triggerSourceCheck() {
        // 进页面时自动识别一次（见下方 LaunchedEffect），这里的按钮只用于「再确认一次」。
        if (checkingSourceId != null) return
        checkingSourceId = "__all__"
        sourceScope.launch {
            com.yueyin.tv.ui.source.MusicSourceRuntime.store.state.value.sources.forEach { source ->
                com.yueyin.tv.ui.source.checkMusicSource(sourceContext, source)
            }
            checkingSourceId = null
        }
    }

    val profiles = state.profiles
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 飞牛音乐：登录过才出现，和协议平台一样可以编辑（重新登录）/删除（断开账号）。
            // 它不是 ConnectProfile，所以走单独的"会话"语义，但界面上与其它平台平权。
            if (fnSessionActive) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformCard(
                        enabled = true,
                        onClick = { ConnectRuntime.activate(null) },
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp)
                            .then(if (fnSessionActive) Modifier.focusRequester(firstFocus) else Modifier)
                            .then(
                                if (fnSessionActive && externalFirstFocus != null) {
                                    Modifier.focusRequester(externalFirstFocus)
                                } else {
                                    Modifier
                                },
                            )
                            .then(leftExit),
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlatformIcon(ConnectType.FEINIU, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("飞牛音乐", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                    if (fnOsActive) {
                                        Spacer(Modifier.width(10.dp))
                                        Text("当前", color = FnColors.Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    if (pendingDeleteFn) "再按一次右侧红色按钮即断开飞牛账号"
                                    else "与登录账号同一个曲库",
                                    color = if (pendingDeleteFn) FnColors.Coral else FnColors.Muted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    PlatformRowAction(
                        kind = PlatformRowActionKind.Edit,
                        danger = false,
                        onClick = { onRelogin() },
                    )
                    PlatformRowAction(
                        kind = PlatformRowActionKind.Delete,
                        danger = pendingDeleteFn,
                        onClick = {
                            if (pendingDeleteFn) {
                                pendingDeleteFn = false
                                onSignOutFn()
                            } else {
                                pendingDeleteFn = true
                            }
                        },
                    )
                }
            }
            profiles.forEachIndexed { index, profile ->
                val deleting = pendingDeleteId == profile.id
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformCard(
                        enabled = true,
                        onClick = { ConnectRuntime.activate(profile) },
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp)
                            .then(
                                if (!fnSessionActive && index == 0) Modifier.focusRequester(firstFocus)
                                else Modifier,
                            )
                            .then(
                                if (!fnSessionActive && index == 0 && externalFirstFocus != null) {
                                    Modifier.focusRequester(externalFirstFocus)
                                } else {
                                    Modifier
                                },
                            )
                            .then(leftExit),
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlatformIcon(profile.type, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(profile.displayName, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                    if (profile.id == state.activeId) {
                                        Spacer(Modifier.width(10.dp))
                                        Text("当前", color = FnColors.Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    if (deleting) "再按一次右侧红色按钮即删除"
                                    else "${profile.username.ifBlank { profile.type.displayName }} · ${profile.host}",
                                    color = if (deleting) FnColors.Coral else FnColors.Muted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    if (profile.type == ConnectType.ONLINE) {
                        // 在线音乐没有地址/账号可编辑，右侧按钮改为「检测」音源脚本
                        SourceCheckAction(
                            checking = checkingSourceId != null,
                            onClick = { triggerSourceCheck() },
                        )
                    } else {
                        PlatformRowAction(
                            kind = PlatformRowActionKind.Edit,
                            danger = false,
                            onClick = { onEdit(profile) },
                        )
                    }
                    PlatformRowAction(
                        kind = PlatformRowActionKind.Delete,
                        danger = deleting,
                        onClick = {
                            if (deleting) {
                                pendingDeleteId = null
                                onDelete(profile)
                            } else {
                                pendingDeleteId = profile.id
                            }
                        },
                    )
                }
            }
            PlatformCard(
                enabled = true,
                onClick = onAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .then(
                        if (!fnSessionActive && profiles.isEmpty()) Modifier.focusRequester(firstFocus)
                        else Modifier,
                    )
                    .then(leftExit),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("＋ 新增平台", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (profiles.isEmpty() && !fnSessionActive) {
                Text("还没有连接任何平台", color = FnColors.Muted, fontSize = 15.sp)
            }

            // —— 在线音源脚本区块：平铺在平台列表下方，不再单独占一个设置分组 ——
            Spacer(Modifier.height(14.dp))
            Text(
                "在线音源",
                color = FnColors.Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "添加 LX 音源脚本即可用；导入后自动识别支持哪几家平台，不需要手动检测",
                color = FnColors.Muted,
                fontSize = 12.sp,
            )
            // 就绪状态一句话：用户不用去别处确认「到底配好了没有」
            val onlineMusicActive = state.ofType(ConnectType.ONLINE)?.id?.let { it == state.activeId } == true
            if (musicSourcesState.sources.isNotEmpty()) {
                Text(
                    if (onlineMusicActive) {
                        "已就绪：搜索里能直接播放"
                    } else {
                        "音源已就绪 · 需要把上面的「在线音乐」设为当前平台才能用"
                    },
                    color = if (onlineMusicActive) FnColors.Teal else FnColors.Coral.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            val sources = musicSourcesState.sources
            sources.forEach { source ->
                val deleting = pendingDeleteId == source.id
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .background(FnColors.SurfaceAlt.copy(alpha = 0.6f), RoundedCornerShape(FnRadius.lg))
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .background(
                                        if (source.usable) FnColors.Teal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f),
                                        RoundedCornerShape(11.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "JS",
                                    color = if (source.usable) FnColors.Teal else FnColors.Muted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    source.fileName,
                                    color = FnColors.Text,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                                Text(
                                    if (deleting) "再按一次右侧「删除」即移除" else source.summary(),
                                    color = if (deleting) FnColors.Coral else FnColors.Muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    SourceCheckAction(
                        checking = checkingSourceId == source.id,
                        onClick = {
                            if (checkingSourceId != null) return@SourceCheckAction
                            checkingSourceId = source.id
                            sourceScope.launch {
                                com.yueyin.tv.ui.source.checkMusicSource(sourceContext, source)
                                checkingSourceId = null
                            }
                        },
                    )
                    PlatformRowAction(
                        kind = PlatformRowActionKind.Delete,
                        danger = deleting,
                        onClick = {
                            if (deleting) {
                                pendingDeleteId = null
                                com.yueyin.tv.ui.source.MusicSourceRuntime.store.remove(source.id)
                            } else {
                                pendingDeleteId = source.id
                            }
                        },
                    )
                }
            }
            PlatformCard(
                enabled = true,
                onClick = onAddSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    // 一个平台都没有时，这张卡才是本分组的第一个可聚焦项，
                    // 设置页的「右键进右栏」得靠它落到这里。
                    .then(
                        if (!fnSessionActive && profiles.isEmpty() && externalFirstFocus != null) {
                            Modifier.focusRequester(externalFirstFocus)
                        } else {
                            Modifier
                        },
                    )
                    .then(leftExit),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (sources.isEmpty()) "＋ 添加音源脚本" else "＋ 再添加一个音源",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FnColors.Text,
                    )
                }
            }
            if (sources.isEmpty()) {
                Text(
                    "App 不内置任何音源。脚本由你自己提供，导入后只保存在这台设备上。",
                    color = FnColors.Muted.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                )
            }
        }
    }

// ---------------------------------------------------------------- 平台选择

/** 平台行右侧的小按钮：编辑（铅笔）/ 删除（垃圾桶），删除待确认时变红。 */
private enum class PlatformRowActionKind { Edit, Delete }

@Composable
private fun PlatformRowAction(
    kind: PlatformRowActionKind,
    danger: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    // 聚焦一律珊瑚实心 + 深色图标，与设置页其它控件同一语言；删除待确认时也是实心，只换图标含义。
    val solid = danger || focused
    val tint = when {
        solid -> FnColors.FocusOnSolid
        kind == PlatformRowActionKind.Delete -> FnColors.Coral
        else -> FnColors.Muted
    }
    val fill = if (solid) FnColors.Coral else FnColors.Surface
    val stroke = if (solid) FnColors.Coral else Color.White.copy(alpha = 0.12f)
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(fill, shape)
            .border(if (solid) 2.dp else 1.dp, stroke, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable(true)
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
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            val w = size.width
            val h = size.height
            if (kind == PlatformRowActionKind.Edit) {
                drawLine(
                    color = tint,
                    start = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.78f),
                    end = androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.22f),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.14f, h * 0.86f)
                    lineTo(w * 0.32f, h * 0.82f)
                    lineTo(w * 0.18f, h * 0.68f)
                    close()
                }
                drawPath(path, color = tint)
            } else {
                drawRoundRect(
                    color = tint,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.34f),
                    size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.48f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                drawLine(
                    color = tint,
                    start = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.26f),
                    end = androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.26f),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = androidx.compose.ui.geometry.Offset(w * 0.40f, h * 0.18f),
                    end = androidx.compose.ui.geometry.Offset(w * 0.60f, h * 0.18f),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

/** 在线音乐平台行的「检测」按钮：触发音源脚本检测，代替原本的「编辑」。 */
@Composable
private fun SourceCheckAction(
    checking: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val tint = if (focused) FnColors.FocusOnSolid else FnColors.Muted
    val fill = if (focused) FnColors.Coral else FnColors.Surface
    val stroke = if (focused) FnColors.Coral else Color.White.copy(alpha = 0.12f)
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(fill, shape)
            .border(if (focused) 2.dp else 1.dp, stroke, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable(true)
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
        contentAlignment = Alignment.Center,
    ) {
        if (checking) {
            Text("识别中", color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        } else {
            androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
                val stroke = 2.dp.toPx()
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f
                // 放大镜：圆 + 斜柄
                drawCircle(
                    color = tint,
                    radius = w * 0.26f,
                    center = androidx.compose.ui.geometry.Offset(cx - w * 0.06f, cy - h * 0.06f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                drawLine(
                    color = tint,
                    start = androidx.compose.ui.geometry.Offset(cx + w * 0.16f, cy + h * 0.16f),
                    end = androidx.compose.ui.geometry.Offset(cx + w * 0.34f, cy + h * 0.34f),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun PlatformPickerScreen(
    onPick: (ConnectType) -> Unit,
    onBack: () -> Unit,
) {
    // 所有平台一视同仁地列出来（包括飞牛音乐）——与箭头音乐一致
    val types = ConnectType.entries
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text("新增平台", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "选择要接入的音乐服务",
            color = FnColors.Muted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            types.chunked(4).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEachIndexed { colIndex, type ->
                        PlatformCard(
                            enabled = type.supported,
                            onClick = { onPick(type) },
                            modifier = Modifier
                                .weight(1f)
                                .height(96.dp)
                                .then(
                                    if (rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstFocus)
                                    else Modifier,
                                ),
                        ) {
                            Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlatformIcon(type, 40.dp, dimmed = !type.supported)
                                Spacer(Modifier.width(10.dp))
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    type.displayName,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (type.supported) FnColors.Text else FnColors.Muted,
                                )
                                Text(
                                    if (type.supported) type.subtitle else "即将支持",
                                    color = if (type.supported) FnColors.Muted else Color(0xFF6B7280),
                                    fontSize = 12.sp,
                                )
                            }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 连接表单

/** 各平台的地址默认前缀：SMB 必须以 smb:// 开头，其它是 http://。 */
private fun defaultHostPrefix(type: ConnectType): String = when (type) {
    ConnectType.SMB -> "smb://"
    else -> "http://"
}

private fun hostLabelOf(type: ConnectType): String = when (type) {
    ConnectType.PLEX -> "服务器地址（如 http://192.168.1.10:32400）"
    ConnectType.WEBDAV -> "WebDAV 地址（含音乐目录，如 http://ip:5244/dav/music）"
    ConnectType.SMB -> "共享路径（如 smb://192.168.1.10/music）"
    ConnectType.AUDIO_STATION -> "群晖地址（如 http://192.168.1.10:5000）"
    else -> "服务器地址（含端口）"
}

private fun userLabelOf(type: ConnectType): String = when (type) {
    ConnectType.PLEX -> "账号（可留空）"
    ConnectType.SMB -> "账号（可填 域\\用户名）"
    else -> "用户名"
}

private fun passLabelOf(type: ConnectType): String = when (type) {
    ConnectType.PLEX -> "Plex Token（不是密码）"
    else -> "密码"
}

@Composable
fun ConnectFormScreen(
    type: ConnectType,
    existing: ConnectProfile? = null,
    onSaved: (ConnectProfile) -> Unit,
    onBack: () -> Unit,
) {
    val store = ConnectRuntime.store
    val registry = ConnectRuntime.registry
    val scope = rememberCoroutineScope()
    // 编辑时回填原值；新建时按平台给默认地址前缀（SMB 必须是 smb://）
    var host by remember(existing?.id) {
        mutableStateOf(existing?.host ?: defaultHostPrefix(type))
    }
    var username by remember(existing?.id) { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember(existing?.id) { mutableStateOf(existing?.password.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }

    val hostFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val testFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { hostFocus.requestFocusWhenReady() }

    fun buildProfile(): ConnectProfile = ConnectProfile(
        // 编辑时复用原 id，保存即覆盖；新建时生成新 id
        id = existing?.id ?: newProfileId(),
        type = type,
        displayName = existing?.displayName ?: type.displayName,
        host = host.trim(),
        username = username.trim(),
        password = password,
        token = existing?.token.orEmpty(),
        userId = existing?.userId.orEmpty(),
        ignoreTls = existing?.ignoreTls ?: false,
    )

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 48.dp, vertical = 24.dp)
            // 键盘弹出时可滚动，避免输入框被顶到屏幕外
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            PlatformIcon(type, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(type.displayName, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                Text(type.subtitle, color = FnColors.Muted, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.width(560.dp).align(Alignment.CenterHorizontally)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 各平台要填的东西不一样：Plex 填 Token 而不是密码，
                // WebDAV/SMB 的地址必须带路径，标签里直接给示例，省得用户猜。
                FormField(hostLabelOf(type), host, { host = it }, hostFocus)
                FormField(userLabelOf(type), username, { username = it }, userFocus)
                FormField(passLabelOf(type), password, { password = it }, passFocus, password = true)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PlatformCard(
                enabled = !busy,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val ok = runCatching { registry.of(type).ping(buildProfile()) }.getOrDefault(false)
                        messageOk = ok
                        message = if (ok) "连接成功" else "连接失败：请检查地址、账号密码与服务是否可访问"
                        busy = false
                    }
                },
                modifier = Modifier.width(190.dp).height(56.dp).focusRequester(testFocus),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (busy) "测试中…" else "测试连接", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            PlatformCard(
                enabled = !busy && host.length > 7,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val profile = buildProfile()
                        // 用 ping 判定可用性：它内部完成一次真实鉴权。
                        // 之前这里又调了一次 login（第二次鉴权），Emby 上会出现"测试连接成功、保存却失败"。
                        val reachable = runCatching { registry.of(type).ping(profile) }.getOrDefault(false)
                        if (reachable) {
                            // 把 token 一并落库（Jellyfin/Emby 必须，否则取流时会缺 api_key）
                            val enriched = runCatching { registry.of(type).enrich(profile) }.getOrDefault(profile)
                            val saved = enriched.copy(
                                displayName = "${type.displayName} · ${host.trim().substringAfter("//").substringBefore("/")}",
                            )
                            store.upsert(saved)
                            ConnectRuntime.activate(saved)
                            busy = false
                            onSaved(saved)
                        } else {
                            messageOk = false
                            message = "登录失败：凭据未被服务端接受"
                            busy = false
                        }
                    }
                },
                modifier = Modifier.width(190.dp).height(56.dp).focusRequester(saveFocus),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("连接并保存", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                message!!,
                color = if (messageOk) FnColors.Teal else FnColors.Coral,
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.weight(1f))
        Text("按返回键退出", color = FnColors.Muted, fontSize = 13.sp)
    }
}
