package com.yueyin.tv.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 后台补链任务的唯一持有者：全局只留一条在跑。
 *
 * 在线音源只有一个隐藏 WebView，取链严格串行。一次「随心听 / 播放全部」会补几十首，
 * 上一轮补链不取消，就会把下一次点播的取链堵在队列后面——表现就是「每次点都要等」。
 * 所以任何一次新的起播都要先掐掉旧的补链。
 */
internal object QueueUrlFiller {
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun start(scope: CoroutineScope, block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }
}
