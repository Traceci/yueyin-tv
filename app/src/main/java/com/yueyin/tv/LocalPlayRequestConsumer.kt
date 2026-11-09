package com.yueyin.tv

import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.yueyin.tv.core.data.repository.SessionState
import com.yueyin.tv.core.model.library.LibraryCapabilities
import com.yueyin.tv.core.model.library.LibraryCapability

/**
 * 外部应用用 ACTION_VIEW 加音频 MIME 把本机音频交给悦音时的统一消费点。
 *
 * 放在 YueYinApp 这一层而不是两个壳里：手机和电视共用一份逻辑，
 * 且只有会话进入 SignedIn、播放链路就绪后才起播，避免 Activity 收到 intent 时
 * MediaController 还没连上导致丢事件。本地能力被注销时不消费。
 */
@Composable
internal fun LocalPlayRequestConsumer(deps: AppUiDependencies, session: SessionState) {
    val context = LocalContext.current
    val pending by LocalPlayRequests.pending.collectAsState()

    LaunchedEffect(pending, session) {
        val uri = pending ?: return@LaunchedEffect
        if (session !is SessionState.SignedIn) return@LaunchedEffect
        if (!LibraryCapabilities.has(LibraryCapability.LocalMusic)) return@LaunchedEffect
        val consumed = LocalPlayRequests.consume() ?: return@LaunchedEffect

        val displayName = runCatching {
            context.contentResolver.query(
                consumed,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

        val library = (context.applicationContext as? TvMusicApplication)?.container?.localMusicLibrary
            ?: return@LaunchedEffect
        val track = library.externalTrack(consumed.toString(), displayName)
        deps.playbackController.playQueue(listOf(library.prepare(track)), 0)
    }
}
