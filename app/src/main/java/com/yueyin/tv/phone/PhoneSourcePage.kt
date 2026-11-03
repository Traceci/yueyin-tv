package com.yueyin.tv.phone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.yueyin.tv.core.data.source.MusicSource
import com.yueyin.tv.core.data.source.SourceReceiveServer
import com.yueyin.tv.core.data.source.SourceScriptValidator
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.FnQrCode
import com.yueyin.tv.ui.connect.ConnectRuntime
import com.yueyin.tv.ui.connect.ensureOnlineMusicActive
import com.yueyin.tv.ui.source.MusicSourceRuntime
import com.yueyin.tv.ui.source.checkMusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音源列表。
 *
 * 一行一个脚本：文件名 + 一句状态。没有「启用/停用」开关——电视端也没做，
 * 理由是少一个要解释的状态；不用就删。
 *
 * 删除同样用「再点一次确认」而不是弹窗：手机上弹窗成本比电视低，但这里的删除
 * 是整页里唯一的破坏性操作，就地两次点击已经足够防误触，还省掉一次遮罩绘制。
 */
@Composable
internal fun PhoneMusicSourcePage(
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by MusicSourceRuntime.state.collectAsState()
    val connectState by ConnectRuntime.store.state.collectAsState()

    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var checkingId by remember { mutableStateOf<String?>(null) }

    val onlineActive = connectState.ofType(
        com.yueyin.tv.core.model.connect.ConnectType.ONLINE,
    )?.id?.let { it == connectState.activeId } == true

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "在线音源", onBack = onBack)
        Text(
            "「在线音乐」下面可以同时挂多个脚本，各自独立生效：搜索时挨个去问，谁有结果就用谁的。" +
                "App 本身不内置任何音源。",
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = PhoneSpace.sm),
        )

        val sources = state.sources
        if (sources.isEmpty()) {
            // 空态整块可点：进到这一页的人目的只有一个（加脚本），不该再让他往下找按钮。
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.md))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(PhoneRadius.md))
                    .phoneTap(onAdd)
                    .padding(PhoneSpace.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有添加任何脚本", color = FnColors.Text, fontSize = PhoneType.Body)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "App 不内置音源。脚本由你自己提供，导入后只保存在这台设备上。",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Caption,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(PhoneSpace.md))
                    Text(
                        "点这里添加 ＋",
                        color = FnColors.Coral,
                        fontSize = PhoneType.Label,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        sources.forEach { source ->
            SourceRow(
                source = source,
                deleting = pendingDeleteId == source.id,
                checking = checkingId == source.id,
                onCheck = {
                    if (checkingId != null) return@SourceRow
                    checkingId = source.id
                    scope.launch {
                        runCatching { checkMusicSource(context, source) }
                        checkingId = null
                    }
                },
                onDelete = {
                    if (pendingDeleteId != source.id) {
                        pendingDeleteId = source.id
                    } else {
                        MusicSourceRuntime.store.remove(source.id)
                        pendingDeleteId = null
                        Toast.makeText(context, "已删除「${source.fileName}」", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.height(PhoneSpace.sm))
        }

        // 导入了但没切到在线音乐时，用户最容易卡在「脚本有了、搜不到歌」。
        // 这里直接给一键切换，而不是让他自己去平台页找那张卡片。
        if (sources.isNotEmpty() && !onlineActive) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(FnColors.Coral.copy(alpha = 0.08f), RoundedCornerShape(PhoneRadius.md))
                    .border(1.dp, FnColors.Coral.copy(alpha = 0.28f), RoundedCornerShape(PhoneRadius.md))
                    .padding(PhoneSpace.md),
            ) {
                Column {
                    Text(
                        "音源已就绪，但当前平台不是「在线音乐」",
                        color = FnColors.Coral,
                        fontSize = PhoneType.Label,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "切过去之后，搜索与在线推荐才会走音源链路。",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Caption,
                    )
                    Spacer(Modifier.height(PhoneSpace.sm))
                    PlatformActionChipInline(
                        label = "设为当前平台",
                        onClick = {
                            ensureOnlineMusicActive()
                            Toast.makeText(context, "已切换到「在线音乐」", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            Spacer(Modifier.height(PhoneSpace.sm))
        }

        AddRow(label = if (sources.isEmpty()) "＋ 添加音源脚本" else "＋ 再添加一个音源", onClick = onAdd)

        Spacer(Modifier.height(PhoneSpace.lg))
        Text(
            "检测只决定「这个脚本支持哪几家平台」，不影响能不能用；不检测也能直接播。",
            color = FnColors.Muted.copy(alpha = 0.65f),
            fontSize = PhoneType.Micro,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

@Composable
private fun SourceRow(
    source: MusicSource,
    deleting: Boolean,
    checking: Boolean,
    onCheck: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(PhoneRadius.md)
    Column(
        Modifier
            .fillMaxWidth()
            .background(FnColors.Surface, shape)
            .border(
                1.dp,
                if (deleting) FnColors.Coral.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.05f),
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        if (source.usable) FnColors.Teal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f),
                        RoundedCornerShape(10.dp),
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
            Spacer(Modifier.width(PhoneSpace.md))
            Column(Modifier.weight(1f)) {
                Text(
                    source.fileName,
                    color = FnColors.Text,
                    fontSize = PhoneType.Row,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    source.summary(),
                    color = if (deleting) FnColors.Coral else FnColors.Muted,
                    fontSize = PhoneType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(PhoneSpace.sm + 2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
            PlatformActionChipInline(
                label = if (checking) "检测中…" else "检测",
                enabled = !checking,
                onClick = onCheck,
            )
            PlatformActionChipInline(
                label = if (deleting) "确认删除" else "删除",
                danger = deleting,
                onClick = onDelete,
            )
        }
    }
}

/**
 * 行内小按钮。与平台页的 chip 用同一套尺寸与描边，
 * 只是这里不嵌在展开区里，所以单独提出来（平台页那个是 private 且绑定展开态语义）。
 */
@Composable
private fun PlatformActionChipInline(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .height(32.dp)
            .background(
                when {
                    danger -> FnColors.Coral.copy(alpha = 0.18f)
                    !enabled -> FnColors.SurfaceAlt.copy(alpha = 0.5f)
                    else -> FnColors.SurfaceAlt
                },
                shape,
            )
            .border(
                1.dp,
                when {
                    danger -> FnColors.Coral
                    enabled -> Color.White.copy(alpha = 0.08f)
                    else -> Color.White.copy(alpha = 0.04f)
                },
                shape,
            )
            .then(if (enabled) Modifier.phoneTap(onClick) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                danger -> FnColors.Coral
                !enabled -> FnColors.Muted.copy(alpha = 0.55f)
                else -> FnColors.Text
            },
            fontSize = PhoneType.Label,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 导入音源
// ---------------------------------------------------------------------------

/**
 * 添加音源。两条路同一页给全：
 *
 * 1. **本机选文件**——手机上有文件管理器，这是唯一顺手的方式（让同一台手机扫码再开浏览器上传是绕路）。
 * 2. **局域网接收**——脚本在电脑上时，用电脑浏览器打开下面的地址把 .js 拖进去。
 *
 * 接收服务只在本页存活期间监听，退出即关，不给局域网留长期入口。
 */
@Composable
internal fun PhoneSourceImportPage(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storedSources by MusicSourceRuntime.state.collectAsState()
    val received = remember { mutableStateListOf<String>() }
    var address by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun activateOnlineIfNeeded() {
        val switched = runCatching { ensureOnlineMusicActive() }.getOrDefault(false)
        if (switched) {
            Toast.makeText(context, "已切换到「在线音乐」，可以直接搜索播放了", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        val ip = SourceReceiveServer.localIpv4()
        // 接收发生在服务线程：列表是 Compose 状态，只能在主线程改，统一 post 回来。
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val server = SourceReceiveServer { fileName, script ->
            val added = MusicSourceRuntime.store.add(fileName, script) != null
            if (added) {
                main.post {
                    if (!received.contains(fileName)) received.add(fileName)
                    activateOnlineIfNeeded()
                }
            }
            added
        }
        val port = runCatching { server.start() }.getOrNull()
        address = if (port != null && ip != null) "http://$ip:$port" else null
        failed = port == null || ip == null
        onDispose { server.stop() }
    }

    val pickLocalFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        note = null
        scope.launch {
            val results = importLocalScripts(context, uris)
            results.filter { it.ok }.forEach { if (!received.contains(it.name)) received.add(it.name) }
            val okCount = results.count { it.ok }
            if (okCount > 0) activateOnlineIfNeeded()
            val bad = results.filterNot { it.ok }
            note = buildString {
                if (okCount > 0) append("已导入 $okCount 个脚本")
                if (bad.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append(bad.joinToString("；") { "${it.name}：${it.reason}" })
                }
            }
            importing = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "添加音源", onBack = onBack)
        Text(
            "两种方式都行：存好的 .js 直接选；脚本在电脑上，就用下面的地址上传。",
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = PhoneSpace.sm),
        )

        // 方式一：本机文件
        Row(
            Modifier
                .fillMaxWidth()
                .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.md))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(PhoneRadius.md))
                .phoneTap {
                    note = null
                    // 个别精简系统没有文档选择器，launch 会抛：别让它崩掉整页。
                    val ok = runCatching { pickLocalFile.launch(arrayOf("*/*")) }.isSuccess
                    if (!ok) note = "这台设备没有文件选择器，请用下面的网页方式导入"
                }
                .padding(PhoneSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(FnColors.Teal.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("JS", color = FnColors.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(PhoneSpace.md))
            Column(Modifier.weight(1f)) {
                Text(
                    if (importing) "正在读取…" else "从本机选择文件",
                    color = FnColors.Text,
                    fontSize = PhoneType.Body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "可多选；单个不超过 2 MB",
                    color = FnColors.Muted,
                    fontSize = PhoneType.Caption,
                    maxLines = 1,
                )
            }
            Text("选择 ›", color = FnColors.Coral, fontSize = PhoneType.Label, fontWeight = FontWeight.SemiBold)
        }

        if (note != null) {
            Spacer(Modifier.height(PhoneSpace.sm))
            Text(
                note!!,
                color = FnColors.Muted,
                fontSize = PhoneType.Caption,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // 方式二：局域网接收
        Spacer(Modifier.height(PhoneSpace.lg))
        Column(
            Modifier
                .fillMaxWidth()
                .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.md))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(PhoneRadius.md))
                .padding(PhoneSpace.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "用别的设备打开这个地址",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Caption,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        address ?: "拿不到局域网地址",
                        color = if (address != null) FnColors.Text else FnColors.Coral,
                        fontSize = if (address != null) 18.sp else PhoneType.Body,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (address != null) {
                    Spacer(Modifier.width(PhoneSpace.md))
                    Box(
                        Modifier
                            .size(88.dp)
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .padding(5.dp),
                    ) {
                        FnQrCode(content = address!!, size = 78.dp)
                    }
                }
            }
            Spacer(Modifier.height(PhoneSpace.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(if (address != null) FnColors.Cyan else FnColors.Coral, RoundedCornerShape(50)),
                )
                Spacer(Modifier.width(PhoneSpace.sm))
                Text(
                    when {
                        failed -> "端口被占用，或这台设备没有局域网地址"
                        address == null -> "请确认已连上 Wi-Fi"
                        storedSources.sources.isEmpty() -> "等待上传… 页面关掉就停止接收"
                        received.isNotEmpty() -> "本次添加 ${received.size} 个，共 ${storedSources.sources.size} 个"
                        else -> "已有 ${storedSources.sources.size} 个脚本"
                    },
                    color = FnColors.Muted,
                    fontSize = PhoneType.Caption,
                    maxLines = 2,
                )
            }
        }

        // 已保存清单一览：用户导入完最想知道的是「到底进去了几个」。
        if (storedSources.sources.isNotEmpty()) {
            PhoneSettingsGroup(title = "已保存") {
                storedSources.sources.forEachIndexed { index, source ->
                    if (index > 0) PhoneSettingsDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            source.fileName,
                            color = FnColors.Text,
                            fontSize = PhoneType.Row,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("✓ 已添加", color = FnColors.Teal, fontSize = PhoneType.Caption)
                    }
                }
            }
        }

        Spacer(Modifier.height(PhoneSpace.lg))
        PhonePrimaryButton(
            label = "完成",
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(PhoneSpace.sm))
        Text(
            "只接受 .js 音源脚本。导入后会自动启用并保存到本机，不上传任何服务器。",
            color = FnColors.Muted.copy(alpha = 0.65f),
            fontSize = PhoneType.Micro,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

// ---------------------------------------------------------------- 本机文件导入

private data class ImportResult(val name: String, val ok: Boolean, val reason: String = "")

/**
 * 读文件 → 静态体检 → 落库，全部在 IO 线程。
 *
 * 体检用和服务端上传同一套 [SourceScriptValidator]：两条入口的判定必须一致，
 * 否则「网页上传被拒的脚本、本机选却能进」会让人以为校验坏了。
 */
private suspend fun importLocalScripts(context: Context, uris: List<Uri>): List<ImportResult> =
    withContext(Dispatchers.IO) {
        uris.map { uri ->
            val name = displayNameOf(context, uri)
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            when {
                bytes == null -> ImportResult(name, false, "读不到文件")
                bytes.isEmpty() -> ImportResult(name, false, "文件是空的")
                bytes.size > MusicSource.MAX_SCRIPT_BYTES -> ImportResult(name, false, "超过 2 MB")
                else -> {
                    // 有些编辑器存的文件带 BOM，直接 trim 会残留 \uFEFF 让首行判断失效。
                    val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                    val reason = SourceScriptValidator.validate(text)
                    if (reason != null) {
                        ImportResult(name, false, reason)
                    } else {
                        // 列表里靠 .js 后缀识别，选来的文件没后缀就补上。
                        val finalName = if (name.lowercase().endsWith(".js")) name else "$name.js"
                        val added = MusicSourceRuntime.store.add(finalName, text)
                        ImportResult(finalName, added != null, if (added == null) "保存失败" else "")
                    }
                }
            }
        }
    }

private fun displayNameOf(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0)
            }
    }.getOrNull()
    return queried?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "source.js"
}
