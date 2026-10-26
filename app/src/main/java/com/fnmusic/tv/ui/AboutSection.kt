package com.fnmusic.tv.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fnmusic.tv.BuildConfig
import com.fnmusic.tv.R
import com.fnmusic.tv.update.UpdateUiState

private val AboutPanel = Color(0xFF151A19)
private val AboutBorder = Color(0xFF303735)
private val AboutDividerColor = Color(0xFF2A302F)

/**
 * 设置页底部「关于」区：左边是版本信息与更新入口，右边是扫码反馈。
 */
@Composable
internal fun AboutSection(
    updateState: UpdateUiState,
    updateEnabled: Boolean,
    lastCheckedAt: Long?,
    updateFocus: FocusRequester,
    updateUpFocus: FocusRequester,
    updateDownFocus: FocusRequester,
    onUpdateAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        AboutCard(
            updateState = updateState,
            updateEnabled = updateEnabled,
            lastCheckedAt = lastCheckedAt,
            updateFocus = updateFocus,
            updateUpFocus = updateUpFocus,
            updateDownFocus = updateDownFocus,
            onUpdateAction = onUpdateAction,
            modifier = Modifier.weight(1f).height(152.dp),
        )
        FeedbackCard(Modifier.width(340.dp).height(152.dp))
    }
}

@Composable
private fun AboutCard(
    updateState: UpdateUiState,
    updateEnabled: Boolean,
    lastCheckedAt: Long?,
    updateFocus: FocusRequester,
    updateUpFocus: FocusRequester,
    updateDownFocus: FocusRequester,
    onUpdateAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(AboutPanel, RoundedCornerShape(9.dp))
            .border(0.75.dp, AboutBorder, RoundedCornerShape(9.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                modifier = Modifier.size(52.dp),
            )
            Column(
                Modifier.weight(1f).padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("悦音", fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Android TV 自建音乐库播放器",
                    color = FnColors.Muted,
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                AboutValue("版本", BuildConfig.VERSION_NAME)
                AboutValue("构建", BuildConfig.VERSION_CODE.toString().padStart(2, '0'))
                AboutValue("许可", "GPL-3.0")
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(AboutDividerColor))
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(updateState)
            Spacer(Modifier.width(9.dp))
            Text(
                statusText(updateState),
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor(updateState),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (updateEnabled) {
                statusDetail(updateState, lastCheckedAt)?.let { detail ->
                    Spacer(Modifier.width(10.dp))
                    Text(
                        detail,
                        color = FnColors.Muted.copy(alpha = 0.75f),
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (updateEnabled) {
                SettingsActionButton(
                    label = actionLabel(updateState),
                    leading = "↓",
                    enabled = actionEnabled(updateState),
                    emphasis = updateState is UpdateUiState.Available,
                    onClick = onUpdateAction,
                    modifier = Modifier
                        .width(128.dp)
                        .focusProperties {
                            up = updateUpFocus
                            down = updateDownFocus
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                        .focusRequester(updateFocus),
                )
            }
        }
    }
}

@Composable
private fun AboutValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = FnColors.Muted, fontSize = 11.sp, modifier = Modifier.width(34.dp))
        Text(
            value,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusDot(state: UpdateUiState) {
    Box(
        Modifier
            .size(8.dp)
            .background(statusColor(state), CircleShape),
    )
}

@Composable
private fun FeedbackCard(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(AboutPanel, RoundedCornerShape(9.dp))
            .border(0.75.dp, AboutBorder, RoundedCornerShape(9.dp))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FnQrCode(content = ISSUE_URL, size = 100.dp)
        Column(
            Modifier.padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("反馈与建议", fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                "扫码即可提交问题",
                color = FnColors.Muted,
                fontSize = 10.sp,
                lineHeight = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Column {
                Text("github.com/", color = FnColors.Muted.copy(alpha = 0.7f), fontSize = 9.sp, lineHeight = 12.sp)
                Text(
                    "Traceci/yueyin-tv",
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusText(state: UpdateUiState): String = when (state) {
    UpdateUiState.Disabled -> "当前版本不支持应用内更新"
    UpdateUiState.Idle -> "尚未检查更新"
    is UpdateUiState.Checking -> "正在检查更新…"
    is UpdateUiState.UpToDate -> "已是最新版本"
    is UpdateUiState.Available -> "发现新版本 ${state.manifest.versionName}"
    is UpdateUiState.Downloading -> "正在下载更新"
    is UpdateUiState.Verifying -> "正在校验安装包"
    is UpdateUiState.AwaitingInstallPermission -> "需要允许安装更新"
    is UpdateUiState.PreparingInstaller -> "正在准备安装"
    is UpdateUiState.AwaitingSystemConfirmation -> "请在系统页面确认安装"
    is UpdateUiState.Error -> state.message
}

private fun statusDetail(state: UpdateUiState, lastCheckedAt: Long?): String? = when (state) {
    is UpdateUiState.Available -> "${formatSize(state.manifest.apkSize)}"
    is UpdateUiState.Downloading -> {
        val total = state.manifest.apkSize.coerceAtLeast(1L)
        "${(state.downloadedBytes * 100 / total).coerceIn(0, 100)}%"
    }
    is UpdateUiState.UpToDate, UpdateUiState.Idle -> lastCheckedAt?.let(::checkedAgoText)
    else -> null
}

private fun checkedAgoText(lastCheckedAt: Long): String {
    val elapsed = (SystemClock.elapsedRealtime() - lastCheckedAt).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    return when {
        minutes < 1L -> "刚刚检查"
        minutes < 60L -> "${minutes} 分钟前检查"
        else -> "${minutes / 60L} 小时前检查"
    }
}

private fun statusColor(state: UpdateUiState): Color = when (state) {
    is UpdateUiState.Available, is UpdateUiState.Downloading -> FnColors.Coral
    is UpdateUiState.UpToDate -> FnColors.Teal
    is UpdateUiState.Error -> FnColors.Warning
    else -> FnColors.Muted.copy(alpha = 0.55f)
}

private fun actionLabel(state: UpdateUiState): String = when (state) {
    is UpdateUiState.Available -> "立即更新"
    is UpdateUiState.Checking -> "正在检查…"
    is UpdateUiState.Downloading, is UpdateUiState.Verifying -> "处理中…"
    is UpdateUiState.UpToDate -> "已是最新"
    else -> "检查更新"
}

private fun actionEnabled(state: UpdateUiState): Boolean = when (state) {
    is UpdateUiState.Checking, is UpdateUiState.Downloading, is UpdateUiState.Verifying,
    is UpdateUiState.AwaitingInstallPermission, is UpdateUiState.PreparingInstaller,
    is UpdateUiState.AwaitingSystemConfirmation,
    -> false
    else -> true
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
