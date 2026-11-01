package com.yueyin.tv.ui.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yueyin.tv.core.data.source.MusicSource
import com.yueyin.tv.core.data.source.SourceReceiveServer
import com.yueyin.tv.core.data.source.SourceScriptValidator
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.connect.ensureOnlineMusicActive
import com.yueyin.tv.ui.FnQrCode
import com.yueyin.tv.ui.FnRadius
import com.yueyin.tv.ui.FnType
import com.yueyin.tv.ui.focusBringIntoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加音源的两条路，同一页给全：
 *
 * 1. **本机选文件**（手机 / 平板 / 带文件管理器的电视）：直接调系统文件选择器，
 *    支持多选。这是手机上唯一顺手的方式——总不能让用户用同一台手机扫码再开浏览器上传。
 * 2. **局域网网页**：电视上没装文件管理器时，用电脑或手机浏览器打开下面的地址把 .js 拖进去。
 *
 * 为什么不做「扫描局域网」：扫描要求对方先开文件共享或装客户端，比开个网页麻烦；
 * 接收服务只在这一页打开时监听，退出即关，不给局域网留长期入口。
 */
@Composable
internal fun SourceReceiveScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val received = remember { mutableStateListOf<String>() }
    var address by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importNote by remember { mutableStateOf<String?>(null) }
    val localFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }

    // 导入完就替用户把「在线音乐」接上：建平台条目 + 切过去。
    // 用户导入音源的意图本来就是用在线音乐，不必让他再去平台页手动选一次。
    fun activateOnlineMusicIfNeeded() {
        val switched = runCatching { ensureOnlineMusicActive() }.getOrDefault(false)
        if (switched) {
            Toast.makeText(context, "已切换到「在线音乐」，可以直接搜索播放了", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        val ip = SourceReceiveServer.localIpv4()
        // 接收发生在服务线程：列表是 Compose 状态，只能在主线程改，所以统一 post 回来。
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val server = SourceReceiveServer { fileName, script ->
            val added = MusicSourceRuntime.store.add(fileName, script) != null
            if (added) {
                main.post {
                    if (!received.contains(fileName)) received.add(fileName)
                    activateOnlineMusicIfNeeded()
                }
            }
            added
        }
        val port = runCatching { server.start() }.getOrNull()
        address = if (port != null && ip != null) "http://$ip:$port" else null
        failed = port == null || ip == null
        onDispose { server.stop() }
    }

    // 系统文件选择器：多选，mime 放宽到 */*（.js 在各家文件管理器里上报的类型五花八门，
    // 有的报 application/javascript，有的报 text/plain，还有的干脆不给类型）。
    val pickLocalFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        importNote = null
        scope.launch {
            val results = importLocalScripts(context, uris)
            results.filter { it.ok }.forEach { if (!received.contains(it.name)) received.add(it.name) }
            val okCount = results.count { it.ok }
            if (okCount > 0) activateOnlineMusicIfNeeded()
            val bad = results.filterNot { it.ok }
            importNote = buildString {
                if (okCount > 0) append("已导入 $okCount 个脚本")
                if (bad.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append(bad.joinToString("；") { "${it.name}：${it.reason}" })
                }
            }
            importing = false
        }
    }

    LaunchedEffect(Unit) {
        repeat(8) {
            if (runCatching { localFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(40)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Text("添加音乐源", color = FnColors.Text, fontSize = FnType.PageTitle, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "两种方式都行：手机/电脑上存好的 .js 直接从本机选；台机上也可以用别的设备打开下面的地址上传",
            color = FnColors.Muted,
            fontSize = FnType.Sub,
        )
        Spacer(Modifier.height(16.dp))

        // ---- 方式一：本机文件
        ActionCard(
            badge = "JS",
            badgeColor = FnColors.Teal,
            title = when {
                importing -> "正在读取…"
                else -> "从本机选择文件"
            },
            subtitle = "打开系统文件选择器，可多选；单个不超过 2 MB",
            trailing = "选择 ›",
            focusRequester = localFocus,
            enabled = !importing,
            onClick = {
                importNote = null
                // 设备上没有文档选择器（个别精简版电视系统）时 launch 会抛，别让它崩掉整页
                val ok = runCatching { pickLocalFile.launch(arrayOf("*/*")) }.isSuccess
                if (!ok) importNote = "这台设备没有文件选择器，请用下面的网页方式导入"
            },
        )

        if (importNote != null) {
            Spacer(Modifier.height(8.dp))
            Text(importNote!!, color = FnColors.Muted, fontSize = FnType.Caption)
        }

        Spacer(Modifier.height(18.dp))

        // ---- 方式二：局域网网页
        Row(
            Modifier
                .fillMaxWidth()
                .height(172.dp)
                .background(FnColors.Surface, RoundedCornerShape(FnRadius.lg))
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(FnRadius.lg))
                .padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("或者用别的设备打开这个地址", color = FnColors.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    address ?: "拿不到局域网地址",
                    color = if (address != null) FnColors.Text else FnColors.Coral,
                    fontSize = if (address != null) 30.sp else 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (address != null) FnColors.Cyan else FnColors.Coral,
                                RoundedCornerShape(50),
                            ),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        when {
                            address == null -> "请确认设备已连上 Wi-Fi，或有线网络已插好"
                            received.isEmpty() -> "等待连接… 页面关掉就停止接收"
                            else -> "已添加 ${received.size} 个脚本"
                        },
                        color = FnColors.Muted,
                        fontSize = FnType.Sub,
                    )
                }
            }
            if (address != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FnQrCode(content = address!!, size = 118.dp)
                    Spacer(Modifier.height(6.dp))
                    Text("手机扫码也行", color = FnColors.Muted, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (received.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color.White.copy(alpha = 0.012f), RoundedCornerShape(FnRadius.md))
                    .border(1.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(FnRadius.md)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有添加任何文件", color = FnColors.Muted, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "只接受 .js 音源脚本，单个文件不超过 2 MB",
                        color = FnColors.Muted.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                received.forEach { name ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(FnColors.Surface, RoundedCornerShape(FnRadius.md))
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(FnColors.Teal.copy(alpha = 0.14f), RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("JS", color = FnColors.Teal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(name, color = FnColors.Text, fontSize = FnType.Row, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("✓ 已添加", color = FnColors.Teal, fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryAction(label = "完成", focusRequester = doneFocus, onClick = onDone)
            if (received.isNotEmpty()) {
                Text(
                    "共 ${received.size} 个，已保存并自动启用，可以直接搜索播放了",
                    color = FnColors.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        if (failed) {
            Spacer(Modifier.height(10.dp))
            Text(
                "端口 8899 起都被占用，或设备没有局域网地址。用手机热点连一次通常就好了。",
                color = FnColors.Muted.copy(alpha = 0.8f),
                fontSize = FnType.Caption,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------- 本机文件导入

private data class ImportResult(val name: String, val ok: Boolean, val reason: String = "")

/**
 * 读文件 → 静态体检 → 落库，全部在 IO 线程。
 * 体检用和服务端上传同一套 [SourceScriptValidator]，两条入口的判定标准必须一致，
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
                    // 有些编辑器存的文件带 BOM，直接 trim 会残留 \uFEFF 让首行判断失效
                    val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                    val reason = SourceScriptValidator.validate(text)
                    if (reason != null) {
                        ImportResult(name, false, reason)
                    } else {
                        // 列表里靠 .js 后缀识别，选来的文件没后缀就补上
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
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
    }.getOrNull()
    return queried?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "音源.js"
}

// ---------------------------------------------------------------- 小部件

@Composable
private fun ActionCard(
    badge: String,
    badgeColor: Color,
    title: String,
    subtitle: String,
    trailing: String,
    focusRequester: FocusRequester,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.lg)
    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .focusRequester(focusRequester)
            .focusBringIntoView()
            .background(if (enabled) FnColors.SurfaceAlt else FnColors.SurfaceAlt.copy(alpha = 0.6f), shape)
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
    ) {
        if (focused && enabled) {
            Box(Modifier.fillMaxSize().padding(4.dp).border(3.dp, Color.White, shape))
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(badgeColor.copy(alpha = 0.14f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = FnColors.Text, fontSize = FnType.Body, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = FnColors.Muted, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                trailing,
                color = if (focused) FnColors.Text else FnColors.Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .focusRequester(focusRequester)
            .focusBringIntoView()
            .height(46.dp)
            .background(FnColors.Coral, shape)
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
                if (focused) Modifier.border(3.dp, Color.White, shape)
                else Modifier.border(3.dp, Color.Transparent, shape),
            )
            .padding(horizontal = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color(0xFF0C1017), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
