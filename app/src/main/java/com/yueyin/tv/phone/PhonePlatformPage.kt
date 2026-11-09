package com.yueyin.tv.phone

import com.yueyin.tv.TvMusicApplication
import com.yueyin.tv.accountSubtitle
import com.yueyin.tv.rememberFnAccountLabel

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.tv.material3.Text
import com.yueyin.tv.core.data.connect.ConnectProfile
import com.yueyin.tv.core.model.connect.ConnectType
import com.yueyin.tv.core.model.library.LibraryCapabilities
import com.yueyin.tv.core.model.library.LibraryCapability
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.connect.ConnectRuntime
import com.yueyin.tv.ui.connect.PlatformIcon
import com.yueyin.tv.ui.connect.ensureOnlineMusicActive
import com.yueyin.tv.ui.source.MusicSourceRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 手机端平台管理。
 *
 * 与电视端的差异：
 * - 电视是「一张大卡片 + 右侧两个独立按钮（编辑/删除）」，手机上宽按钮会挤掉平台名，
 *   所以改成**点卡片切换 + 右侧紧凑的文字按钮**，不弹层、不就地展开——
 *   平台管理是个低频但要求准确的操作，弹层里点错比一行内点错更容易误触。
 * - 飞牛账号是登录账号本身，不是一条 ConnectProfile：登录后才出现这一行，
 *   右侧与电视端平权地提供「编辑」（换账号 / 改地址）和「删除」（断开账号，二次点击确认）。
 *
 * 列表只放**用户自己添加过的东西**。这里曾经把「飞牛音乐」和「在线音源」两节做成了常驻项：
 * 飞牛那行在没登录时是「未登录 · 用 NAS 账号接入」，音源那节固定写「已添加 0 个」——
 * 对新用户来说，一进平台管理就看见两个既不是自己加的、又都点不出结果的条目，
 * 会以为 App 预置了一堆东西。现在两者都收进「＋ 新增平台」：添加之后才占一行。
 */
@Composable
internal fun PhonePlatformManagePage(
    fnSessionActive: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenSources: () -> Unit,
    /** 编辑飞牛：无条件进登录页，可换账号 / 改服务器地址（与电视端 onRelogin 同语义）。 */
    onRelogin: () -> Unit,
) {
    val context = LocalContext.current
    val deps = LocalPhoneDeps.current
    val scope = rememberCoroutineScope()
    val store = ConnectRuntime.store
    val state by store.state.collectAsState()
    val sources by MusicSourceRuntime.state.collectAsState()
    val fnAccountLabel = rememberFnAccountLabel()

    // 本地音乐是本机曲库，不是可连接平台：只读展示索引数量，不进 profiles，不给编辑/删除。
    val localEnabled = LibraryCapabilities.has(LibraryCapability.LocalMusic)
    val lib = remember {
        (context.applicationContext as TvMusicApplication).container.localMusicLibrary
    }
    var localCount by remember { mutableStateOf(0) }
    var rescanning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        localCount = runCatching { lib.count() }.getOrDefault(0)
    }
    fun rescan() {
        if (rescanning) return
        rescanning = true
        scope.launch {
            localCount = runCatching { lib.refresh() }.getOrDefault(localCount)
            rescanning = false
        }
    }

    // 断开飞牛账号二次点击确认，4 秒后自动复位（与电视端同一套防误触）。
    var pendingDisconnect by remember { mutableStateOf(false) }
    LaunchedEffect(pendingDisconnect) {
        if (pendingDisconnect) {
            delay(4000)
            pendingDisconnect = false
        }
    }

    fun activate(profile: ConnectProfile?, label: String) {
        ConnectRuntime.activate(profile)
        Toast.makeText(context, "已切换到「$label」", Toast.LENGTH_SHORT).show()
    }

    fun disconnectFn() {
        if (!pendingDisconnect) {
            pendingDisconnect = true
            return
        }
        pendingDisconnect = false
        scope.launch {
            deps.sessionRepository.signOutFnSession()
            // 飞牛链路的 activeId 是 null；退出后它已不存在，必须回落到仍在的协议平台，
            // 否则当前平台悬空，首页会停在「没有曲库」的状态。
            if (state.activeId == null) {
                ConnectRuntime.activate(store.state.value.profiles.firstOrNull())
            }
            Toast.makeText(context, "已断开飞牛账号", Toast.LENGTH_SHORT).show()
        }
    }

    fun delete(profile: ConnectProfile) {
        val wasActive = state.activeId == profile.id
        store.remove(profile.id)
        if (wasActive) {
            val next = store.state.value.profiles.firstOrNull()
            ConnectRuntime.activate(next)
        }
        val keepSources = profile.type == ConnectType.ONLINE && sources.count > 0
        Toast.makeText(
            context,
            if (keepSources) {
                "已删除「${profile.displayName}」，${sources.count} 个音源脚本仍保留"
            } else {
                "已删除「${profile.displayName}」"
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "平台管理", onBack = onBack)
        Text(
            "可添加多个音乐服务随时切换；当前生效的平台在「我的」页顶部显示。",
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = PhoneSpace.sm),
        )

        // 本地音乐状态条：只读，独立于下面的可连接平台列表。
        if (localEnabled) {
            LocalMusicStatusBar(
                count = localCount,
                rescanning = rescanning,
                onRescan = { rescan() },
            )
            Spacer(Modifier.height(PhoneSpace.sm))
        }

        // 飞牛不是一条 ConnectProfile，它挂在登录账号上（登录成功时 activate(null)）。
        // 只有真正登录过 fnOS 才出现这一行；没登录时不占位，避免看起来像预置的空平台。
        // 与电视端平权：右侧「编辑」进登录页换账号/改地址，「删除」二次点击断开账号。
        if (fnSessionActive) {
            PlatformRow(
                icon = { PlatformIcon(ConnectType.FEINIU, 34.dp) },
                title = "飞牛音乐",
                subtitle = if (pendingDisconnect) {
                    "再点一次右侧「确认删除」即断开飞牛账号"
                } else {
                    fnAccountLabel
                },
                subtitleColor = if (pendingDisconnect) FnColors.Coral else FnColors.Muted,
                active = state.activeId == null,
                onActivate = { activate(null, "飞牛音乐") },
                onDelete = { disconnectFn() },
                deleteLabel = if (pendingDisconnect) "确认删除" else "删除",
                trailing = {
                    Box(
                        Modifier
                            .phoneTap {
                                pendingDisconnect = false
                                onRelogin()
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                            .background(
                                FnColors.Coral.copy(alpha = 0.14f),
                                RoundedCornerShape(7.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "编辑",
                            color = FnColors.Coral,
                            fontSize = PhoneType.Micro,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                },
            )
            Spacer(Modifier.height(PhoneSpace.sm))
        }

        state.profiles.forEach { profile ->
            PlatformRow(
                icon = { PlatformIcon(profile.type, 34.dp) },
                title = profile.type.displayName,
                subtitle = when {
                    profile.type == ConnectType.ONLINE -> if (sources.count == 0) {
                        "还没有音源脚本 · 点「音源」添加"
                    } else {
                        "${sources.count} 个音源脚本 · 五平台聚合"
                    }
                    else -> profile.accountSubtitle()
                },
                active = profile.id == state.activeId,
                onActivate = { activate(profile, profile.displayName) },
                onDelete = { delete(profile) },
                trailing = if (profile.type == ConnectType.ONLINE) {
                    {
                        Box(
                            Modifier
                                .phoneTap { onOpenSources() }
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                                .background(
                                    FnColors.Coral.copy(alpha = 0.14f),
                                    RoundedCornerShape(7.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                if (sources.count == 0) "＋音源" else "音源 ${sources.count}",
                                color = FnColors.Coral,
                                fontSize = PhoneType.Micro,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(PhoneSpace.sm))
        }

        AddRow(label = "＋ 新增平台", onClick = onAdd)

        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

/**
 * 一行平台。
 *
 * [actions] 只在这一行被展开时组合：收起状态下不挂任何可点区域，
 * 避免「看着是空的却点到删除」。
 *
 * [trailing] 是收起状态下也常驻的快捷动作（目前只有「在线音乐」用：行尾一个「音源 N」）。
 * 音源入口原来藏在「管理 → 展开 → 音源脚本」里，等于三层，用户找不到；现在一步就到。
 */
/**
 * 一行平台。点整行 = 切换为该平台（选那个直接生效）；行尾「删除」直接删除。
 * 旧的「管理 / 设为当前 / 收起」展开交互已去掉，平台管理回归「列表 + 即时切换 + 删除」。
 */
@Composable
private fun PlatformRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    subtitleColor: Color = FnColors.Muted,
    active: Boolean,
    onActivate: () -> Unit,
    /** 传 null 即不渲染行尾按钮。 */
    onDelete: (() -> Unit)?,
    deleteLabel: String = "删除",
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(PhoneRadius.md)
    Column(
        Modifier
            .fillMaxWidth()
            .background(FnColors.Surface, shape)
            .border(
                1.dp,
                if (active) FnColors.Coral.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.05f),
                shape,
            )
            .phoneTap(onActivate),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(PhoneSpace.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = FnColors.Text,
                        fontSize = PhoneType.Body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) {
                        Spacer(Modifier.width(PhoneSpace.sm))
                        Box(
                            Modifier
                                .background(FnColors.Coral.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "当前",
                                color = FnColors.Coral,
                                fontSize = PhoneType.Micro,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    color = subtitleColor,
                    fontSize = PhoneType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
            onDelete?.let { delete ->
                Box(
                    Modifier
                        .size(LocalCarProfile.current.touchTarget)
                        .phoneTap(delete),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        deleteLabel,
                        color = FnColors.Coral,
                        fontSize = PhoneType.Label,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}


@Composable
internal fun AddRow(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(PhoneRadius.md)
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(FnColors.Surface.copy(alpha = 0.6f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .phoneTap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = FnColors.Text,
            fontSize = PhoneType.Row,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 平台选择
// ---------------------------------------------------------------------------

/**
 * 新增平台：9 类纵向列表。
 *
 * 电视端是 4 列方格（一屏放下，遥控器上下左右都顺），竖屏 4 列会把名字压成两行，
 * 改成单列后每行还能带一句说明——第一次接入的用户需要知道「Subsonic 兼容」是什么意思。
 */
@Composable
internal fun PhonePlatformPickerPage(
    onBack: () -> Unit,
    onPick: (ConnectType) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "新增平台", onBack = onBack)
        Text(
            "选择要接入的音乐服务",
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            modifier = Modifier.padding(start = 4.dp, top = PhoneSpace.xs, bottom = PhoneSpace.md),
        )
        ConnectType.entries.forEach { type ->
            PlatformPickRow(type = type, onClick = { onPick(type) })
            Spacer(Modifier.height(PhoneSpace.sm))
        }
        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

@Composable
private fun PlatformPickRow(type: ConnectType, onClick: () -> Unit) {
    val shape = RoundedCornerShape(PhoneRadius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .background(FnColors.Surface, shape)
            .border(1.dp, Color.White.copy(alpha = 0.05f), shape)
            .phoneTap(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformIcon(type, 34.dp, dimmed = !type.supported)
        Spacer(Modifier.width(PhoneSpace.md))
        Column(Modifier.weight(1f)) {
            Text(
                type.displayName,
                color = if (type.supported) FnColors.Text else FnColors.Muted,
                fontSize = PhoneType.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (type.supported) type.subtitle else "即将支持",
                color = FnColors.Muted,
                fontSize = PhoneType.Caption,
                maxLines = 1,
            )
        }
        PhoneIcon(
            PhoneGlyph.ChevronRight,
            FnColors.Muted.copy(alpha = 0.55f),
            Modifier.size(14.dp),
            strokeWidth = 1.7.dp,
        )
    }
}

// ---------------------------------------------------------------------------
// 连接表单
// ---------------------------------------------------------------------------

/** 新建平台时生成的 id。与电视端一致：随机 UUID，编辑时复用原 id 覆盖。 */
private fun newProfileId(): String = UUID.randomUUID().toString()

private fun defaultHostPrefix(type: ConnectType): String = when (type) {
    ConnectType.SMB -> "smb://"
    else -> "http://"
}

private fun hostLabelOf(type: ConnectType): String = when (type) {
    ConnectType.PLEX -> "服务器地址（如 http://192.168.1.10:32400）"
    ConnectType.WEBDAV -> "WebDAV 地址（含音乐目录）"
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

/**
 * 连接表单。
 *
 * 校验顺序与电视端一致：先 ping（内含一次真实鉴权）再 enrich（拿 token），
 * 都通过才落库。手机多做的只有两件：输入框按内容切键盘类型（地址 / 密码），
 * 以及键盘弹起时整页上推，避免「保存」被键盘盖住。
 */
@Composable
internal fun PhonePlatformFormPage(
    type: ConnectType,
    existing: ConnectProfile?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = ConnectRuntime.store
    val registry = ConnectRuntime.registry

    var host by remember(existing?.id) { mutableStateOf(existing?.host ?: defaultHostPrefix(type)) }
    var username by remember(existing?.id) { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember(existing?.id) { mutableStateOf(existing?.password.orEmpty()) }
    // 测试与保存各自一个忙碌标记：共用的话，点「测试连接」时保存按钮会一起变成
    // 「处理中…」，看起来像两个请求在同时跑。
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }

    fun buildProfile(): ConnectProfile = ConnectProfile(
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = if (existing == null) "接入 ${type.displayName}" else "编辑平台", onBack = onBack)

        Row(
            Modifier.fillMaxWidth().padding(vertical = PhoneSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformIcon(type, 42.dp)
            Spacer(Modifier.width(PhoneSpace.md))
            Column(Modifier.weight(1f)) {
                Text(
                    type.displayName,
                    color = FnColors.Text,
                    fontSize = PhoneType.Headline,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(type.subtitle, color = FnColors.Muted, fontSize = PhoneType.Caption, maxLines = 1)
            }
        }

        PhoneInputField(
            label = hostLabelOf(type),
            value = host,
            onValueChange = { host = it },
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(PhoneSpace.md))
        PhoneInputField(
            label = userLabelOf(type),
            value = username,
            onValueChange = { username = it },
        )
        Spacer(Modifier.height(PhoneSpace.md))
        PhoneInputField(
            label = passLabelOf(type),
            value = password,
            onValueChange = { password = it },
            keyboardType = KeyboardType.Password,
            password = true,
        )

        if (message != null) {
            Spacer(Modifier.height(PhoneSpace.md))
            Text(
                message!!,
                color = if (messageOk) FnColors.Teal else FnColors.Coral,
                fontSize = PhoneType.Label,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(PhoneSpace.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md)) {
            PhoneOutlineButton(
                label = if (testing) "测试中…" else "测试连接",
                glyph = PhoneGlyph.Search,
                onClick = {
                    if (testing || saving) return@PhoneOutlineButton
                    testing = true
                    message = null
                    scope.launch {
                        val ok = runCatching { registry.of(type).ping(buildProfile()) }.getOrDefault(false)
                        messageOk = ok
                        message = if (ok) "连接成功" else "连接失败：请检查地址、账号密码与服务是否可访问"
                        testing = false
                    }
                },
                enabled = !testing && !saving,
                modifier = Modifier.weight(1f),
            )
            PhonePrimaryButton(
                label = if (saving) "保存中…" else "连接并保存",
                onClick = {
                    if (testing || saving || host.trim().length <= 7) return@PhonePrimaryButton
                    saving = true
                    message = null
                    scope.launch {
                        val profile = buildProfile()
                        val reachable = runCatching { registry.of(type).ping(profile) }.getOrDefault(false)
                        if (reachable) {
                            // token 必须一并落库（Jellyfin/Emby 取流时要带 api_key），
                            // 少了它就会出现「测试通过、播放 403」。
                            val enriched = runCatching { registry.of(type).enrich(profile) }.getOrDefault(profile)
                            val saved = enriched.copy(
                                displayName = "${type.displayName} · " +
                                    host.trim().substringAfter("//").substringBefore("/"),
                            )
                            store.upsert(saved)
                            ConnectRuntime.activate(saved)
                            saving = false
                            Toast.makeText(context, "已接入「${saved.displayName}」", Toast.LENGTH_SHORT).show()
                            onSaved()
                        } else {
                            messageOk = false
                            message = "登录失败：凭据未被服务端接受"
                            saving = false
                        }
                    }
                },
                enabled = !testing && !saving && host.trim().length > 7,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(PhoneSpace.md))
        Text(
            "地址示例：HTTP 服务填 http://192.168.1.10:4533；SMB 填 smb://192.168.1.10/music",
            color = FnColors.Muted.copy(alpha = 0.65f),
            fontSize = PhoneType.Micro,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

/** 手机输入框：深底 + 聚焦珊瑚描边 + 空态占位。登录页（[PhoneFnLoginPage]）共用同一颗。 */
@Composable
internal fun PhoneInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column {
        Text(
            label,
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = FnColors.Text, fontSize = PhoneType.Body),
            cursorBrush = SolidColor(FnColors.Coral),
            visualTransformation = if (password) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(FnColors.SurfaceAlt, shape)
                .border(
                    1.dp,
                    if (focused) FnColors.Coral.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.07f),
                    shape,
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            "必填",
                            color = FnColors.Muted.copy(alpha = 0.45f),
                            fontSize = PhoneType.Body,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * 飞牛音乐这一行在展开状态下的稳定 key。
 *
 * 它是会话而不是 profile，没有 id 可用；用固定串而不是 `null`，
 * 是为了让「展开哪一行」这件事只有一个可比较的值。
 */
private const val FN_OS_ROW_ID = "__fn_os_session__"

/** 在线音乐：无需表单，点即激活。与电视端同一个固定 id，重复点不会重复落库。 */
internal fun activateOnlineMusic() {
    ensureOnlineMusicActive()
}

/**
 * 「在线音乐」是否已经在平台列表里。
 *
 * 它是固定 id 的单条平台，不是每点一次新增就多一条：多条同类型平台共用同一份数据源，
 * 除了让列表变长没有任何作用。所以重复添加时提示「已经在列表里」，而不是假装又加了一条。
 */
internal fun onlineMusicAdded(): Boolean =
    ConnectRuntime.store.state.value.ofType(ConnectType.ONLINE) != null

/** 平台管理里的本地音乐只读状态条：不是可连接平台，不进 profiles，无编辑/删除。 */
@Composable
private fun LocalMusicStatusBar(count: Int, rescanning: Boolean, onRescan: () -> Unit) {
    val shape = RoundedCornerShape(PhoneRadius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .background(FnColors.Surface, shape)
            .border(1.dp, Color.White.copy(alpha = 0.05f), shape)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneIcon(PhoneGlyph.Library, FnColors.Muted, Modifier.size(24.dp))
        Spacer(Modifier.width(PhoneSpace.md))
        Text(
            "本地音乐 · 已索引 ${phoneCount(count)} 首",
            color = FnColors.Text,
            fontSize = PhoneType.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 唯一动作是重新扫描；不给编辑/删除，也不把整行做成可切换平台。
        Box(
            Modifier
                .phoneTap(onRescan)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (rescanning) "扫描中…" else "重新扫描",
                color = if (rescanning) FnColors.Muted else FnColors.Coral,
                fontSize = PhoneType.Label,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
