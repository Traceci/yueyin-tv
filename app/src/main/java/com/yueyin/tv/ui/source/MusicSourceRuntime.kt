package com.yueyin.tv.ui.source

import android.content.Context
import com.yueyin.tv.core.data.source.MusicSourceStore
import kotlinx.coroutines.flow.StateFlow

/**
 * 音源运行时：和 [com.yueyin.tv.ui.connect.ConnectRuntime] 一样用单例持有，避免往依赖接口里加成员
 * （那两个接口被多个测试替身实现，加成员会连带改一堆测试）。
 */
object MusicSourceRuntime {
    @Volatile
    private var storeRef: MusicSourceStore? = null

    fun init(context: Context) {
        if (storeRef == null) {
            synchronized(this) {
                if (storeRef == null) storeRef = MusicSourceStore(context.applicationContext)
            }
        }
    }

    val store: MusicSourceStore
        get() = storeRef ?: error("MusicSourceRuntime 未初始化")

    val state: StateFlow<com.yueyin.tv.core.data.source.MusicSourcesState>
        get() = store.state
}
