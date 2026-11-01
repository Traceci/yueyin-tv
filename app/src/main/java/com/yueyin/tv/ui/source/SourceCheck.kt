package com.yueyin.tv.ui.source

import android.content.Context
import com.yueyin.tv.core.data.source.MusicSource
import com.yueyin.tv.source.OnlineSourceRuntime

/**
 * 音源脚本「检测」的唯一实现。
 *
 * 检测做的事只有一件：把脚本丢进 WebView 运行时加载一次，读出它声明了支持哪些平台。
 * 结论回写到 [MusicSourceStore.recordCheck]，供列表显示和取链排序使用。
 *
 * **它不是使用前提。** 取链器只按 `enabled` 挑脚本，不检测也能播；
 * 所以这个函数只负责「把能显示的信息补上」，任何时候都可以跑，跑失败也不影响可用性。
 *
 * 必须在主线程调用（内部要创建 WebView）。
 */
internal suspend fun checkMusicSource(context: Context, source: MusicSource): List<String> {
    val runtime = runCatching { OnlineSourceRuntime.get(context.applicationContext) }.getOrNull()
        ?: return emptyList()
    val ok = runCatching { runtime.loadScript(source.fileName, source.script) }.getOrDefault(false)
    val declared = runCatching { runtime.declaredPlatforms().toList() }.getOrDefault(emptyList())
    MusicSourceRuntime.store.recordCheck(
        id = source.id,
        platforms = if (ok) declared else emptyList(),
        declared = declared,
        note = when {
            !ok -> "脚本加载失败，可能内容不完整"
            declared.isEmpty() -> "能加载，但没声明任何平台"
            else -> "可加载，声明 " + declared.joinToString(" / ") { MusicSource.sourceLabel(it) }
        },
    )
    return if (ok) declared else emptyList()
}

/** 批量检测（进「音源」页时补齐所有还没检测过的脚本）。 */
internal suspend fun checkPendingMusicSources(context: Context, sources: List<MusicSource>): List<String> {
    val detected = mutableListOf<String>()
    sources.filter { it.lastCheckAt == 0L }.forEach { source ->
        detected += checkMusicSource(context, source)
    }
    return detected
}
