package com.yueyin.tv.phone

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.yueyin.tv.core.data.server.ConnectionResolver
import com.yueyin.tv.core.data.server.ServerUrlNormalizer
import com.yueyin.tv.core.data.server.ServerUrlResult
import com.yueyin.tv.core.model.AppError
import com.yueyin.tv.core.model.AppException
import com.yueyin.tv.core.model.connect.ConnectType
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.connect.ConnectRuntime
import com.yueyin.tv.ui.connect.PlatformIcon
import kotlinx.coroutines.launch

/**
 * 登录飞牛音乐（手机版）。
 *
 * 为什么需要这一页：飞牛音乐不是一个可以「填个地址就新增」的协议平台，它就是**登录账号本身**。
 * 冷启动时应用故意不进登录页（先进本地/在线音乐模式，不让登录挡路），于是「新增平台 → 飞牛音乐」
 * 就成了用户唯一的入口——之前这里只弹一句「请先在登录页登录」然后什么都不发生，等于死路。
 *
 * 与电视端的差异：电视端直接复用 `LoginScreen`（遥控器焦点版，带历史服务器、安全码、密码记忆）。
 * 手机上那套 10 英尺布局输不进去也点不准，所以按手机表单重写一遍，只保留三条关键输入：
 * 地址 / 账号 / 密码，外加可留空的安全码与 HTTPS 开关。登录逻辑仍然只调
 * `SessionRepository.login(...)`——校验、重试、凭据落盘全在 core，不在界面里重复一份。
 */
@Composable
internal fun PhoneFnLoginPage(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = LocalPhoneDeps.current.sessionRepository

    // 上次登录过的地址直接填好：换账号重登时用户只需要补账号密码。
    val saved = remember(repository.savedServer) {
        ServerUrlNormalizer.editableInput(repository.savedServer, false)
    }
    var server by remember { mutableStateOf(saved.address) }
    var https by remember { mutableStateOf(saved.useHttps) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var accessCode by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 地址要么是局域网地址（含端口），要么是 FNID（纯字母数字串，走官方中转查询）。
    val serverReady = ConnectionResolver.isFnId(server.trim()) ||
        ServerUrlNormalizer.normalize(server, https) is ServerUrlResult.Valid
    val canSubmit = !submitting && serverReady && username.isNotBlank() && password.isNotBlank()

    fun submit() {
        if (!canSubmit) return
        submitting = true
        error = null
        scope.launch {
            // login() 内部完成地址解析、鉴权、凭据保存；失败抛 AppException。
            val result = runCatching {
                repository.login(
                    serverInput = server.trim(),
                    useHttps = https,
                    username = username.trim(),
                    password = password.toCharArray(),
                    // 手机是随身设备，默认记住登录：下次启动直接进曲库。
                    remember = true,
                    accessCode = accessCode.toCharArray(),
                )
            }
            submitting = false
            result.onSuccess {
                // 飞牛的「当前平台」就是主账号链路（activeId = null）；不清掉的话，
                // 之前选着的在线音乐还会继续生效，用户会看到「登录了但列表没变」。
                ConnectRuntime.activate(null)
                Toast.makeText(context, "已接入「飞牛音乐」", Toast.LENGTH_SHORT).show()
                onLoggedIn()
            }.onFailure { cause ->
                error = fnLoginFailureText(cause)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        PhoneBackHeader(title = "登录飞牛音乐", onBack = onBack)

        Row(
            Modifier.fillMaxWidth().padding(vertical = PhoneSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformIcon(ConnectType.FEINIU, 42.dp)
            Spacer(Modifier.width(PhoneSpace.md))
            Column(Modifier.weight(1f)) {
                Text(
                    "飞牛音乐",
                    color = FnColors.Text,
                    fontSize = PhoneType.Headline,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    "用飞牛 NAS 的账号登录",
                    color = FnColors.Muted,
                    fontSize = PhoneType.Caption,
                    maxLines = 1,
                )
            }
        }

        Text(
            "登录后「平台管理」里的飞牛音乐就是这台 NAS 的曲库；账号密码只用于向你的 NAS 鉴权，"
                + "不会上传到别处。",
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            lineHeight = 18.sp,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = PhoneSpace.md),
        )

        PhoneInputField(
            label = "NAS 地址或 FNID（如 192.168.1.10:5666）",
            value = server,
            onValueChange = { input ->
                server = input
                // 用户直接把 https:// 贴进来时自动打开下面的开关，省一次手动切换。
                val scheme = Regex("^(https?)://", RegexOption.IGNORE_CASE).find(input.trim())
                scheme?.groupValues?.get(1)?.let { https = it.equals("https", ignoreCase = true) }
            },
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(PhoneSpace.md))
        PhoneInputField(
            label = "账号",
            value = username,
            onValueChange = { username = it },
        )
        Spacer(Modifier.height(PhoneSpace.md))
        PhoneInputField(
            label = "密码",
            value = password,
            onValueChange = { password = it },
            keyboardType = KeyboardType.Password,
            password = true,
        )
        Spacer(Modifier.height(PhoneSpace.md))
        PhoneInputField(
            label = "安全码（服务器未启用可留空）",
            value = accessCode,
            onValueChange = { accessCode = it },
        )

        Spacer(Modifier.height(PhoneSpace.sm))
        PhoneSettingsGroup {
            PhoneSettingsSwitchRow(
                title = "使用 HTTPS",
                subtitle = "NAS 配了证书、或走 FNID 中转时才需要打开",
                checked = https,
                onToggle = { https = it },
            )
        }

        if (error != null) {
            Spacer(Modifier.height(PhoneSpace.md))
            Text(
                error!!,
                color = FnColors.Coral,
                fontSize = PhoneType.Label,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(PhoneSpace.lg))
        PhonePrimaryButton(
            label = if (submitting) "登录中…" else "登录并接入",
            onClick = ::submit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(PhoneSpace.md))
        Text(
            "地址填 NAS 的局域网地址（默认 5666 端口）或飞牛的 FNID；"
                + "登录成功后会自动切到飞牛音乐，原来的在线音乐仍保留在平台列表里。",
            color = FnColors.Muted.copy(alpha = 0.65f),
            fontSize = PhoneType.Micro,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(PhoneSpace.xxl))
    }
}

/**
 * 登录失败的文案。
 *
 * 直接把 `AppException` 的错误码翻译成人话：网络类错误和「密码错」在用户看来完全是两件事，
 * 前者要改地址，后者要改密码，混成一句「登录失败」只会让人反复重试。
 */
private fun fnLoginFailureText(cause: Throwable): String {
    val error = (cause as? AppException)?.error ?: AppError.Unknown()
    return when (error) {
        AppError.Unauthenticated -> "账号或密码不正确"
        AppError.AccountDisabled -> "这个账号已被禁用"
        AppError.AccessCodeRequired -> "这台服务器需要安全码，请填写后再登录"
        AppError.InvalidAccessCode -> "安全码不正确"
        AppError.NetworkUnavailable -> "连不上 NAS：检查地址、端口与当前网络"
        AppError.NotFound -> "地址通了，但没找到音乐接口，确认 NAS 上已安装「音乐」应用"
        AppError.FnIdUnavailable -> "FNID 没有可用连接，改用局域网地址试试"
        else -> "登录失败，请重试"
    }
}
