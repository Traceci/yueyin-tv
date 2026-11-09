package com.yueyin.tv

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * 外部音频 intent（ACTION_VIEW 加音频 MIME）与 UI 之间的中继。
 *
 * Activity 收到 intent 时可能播放控制器还没连上，用 SharedFlow 会丢事件，
 * 因此这里用「待消费」StateFlow：UI 在播放链路就绪后再 consume()，取到即清空。
 */
object LocalPlayRequests {
    private val _pending = MutableStateFlow<Uri?>(null)
    val pending: StateFlow<Uri?> = _pending.asStateFlow()

    fun submit(uri: Uri) {
        _pending.value = uri
    }

    fun consume(): Uri? = _pending.getAndUpdate { null }
}
