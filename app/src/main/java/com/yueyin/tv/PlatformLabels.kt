package com.yueyin.tv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yueyin.tv.core.data.connect.ConnectProfile
import com.yueyin.tv.core.data.repository.SessionState

/**
 * 平台管理页的显示规则：**只显示登录的用户**，不再展示服务器地址或解释性文案。
 * 数据与保存逻辑不变，已接入的平台无需重新登录。
 */

/** 飞牛音乐行的账号标识，与「我的」页顶部 / 连接卡片取同一个字段。 */
private fun Context.resolveFnAccountLabel(): String {
    val state = runCatching {
        (applicationContext as? TvMusicApplication)?.container?.sessionRepository?.state?.value
    }.getOrNull() ?: return "已登录"
    val signed = state as? SessionState.SignedIn ?: return "已登录"
    return signed.server.name
        .takeIf { it.isNotBlank() && signed.server.guid.value != "local" }
        ?: signed.user.nickname?.takeIf { it.isNotBlank() }
        ?: signed.user.username.ifBlank { "已登录" }
}

@Composable
fun rememberFnAccountLabel(): String {
    val context = LocalContext.current
    return remember(context) { context.resolveFnAccountLabel() }
}

/** 协议平台（Emby / Jellyfin / Subsonic / WebDAV / SMB 等）行副标题：只留用户名。 */
fun ConnectProfile.accountSubtitle(): String = username.ifBlank { type.displayName }
