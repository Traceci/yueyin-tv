package com.yueyin.tv.core.data.source

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 用户导入的音源脚本存储。
 *
 * 和 [com.yueyin.tv.core.data.connect.ConnectProfileStore] 一样的取舍：SharedPreferences + JSON。
 * 数据量小（几个脚本、几十 KB）、读多写少，为它加 Room 表要连带补迁移，不划算。
 *
 * 注意：脚本是「用户自己粘贴/上传的代码」，这里只负责原样保存，不做任何执行或改写。
 */
class MusicSourceStore(context: Context) {

    private val prefs = context.getSharedPreferences("music_sources", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val _state = MutableStateFlow(read())
    val state: StateFlow<MusicSourcesState> = _state.asStateFlow()

    /** 加入一个脚本。同名文件覆盖（按 fileName 去重），避免反复导入堆出一堆重复项。 */
    fun add(fileName: String, script: String): MusicSource? {
        if (script.isBlank()) return null
        if (script.toByteArray().size > MusicSource.MAX_SCRIPT_BYTES) return null
        val current = _state.value.sources
        val existing = current.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
        val source = MusicSource(
            id = existing?.id ?: UUID.randomUUID().toString(),
            fileName = fileName,
            script = script,
            addedAt = System.currentTimeMillis(),
        )
        write(current.filterNot { it.id == source.id } + source)
        return source
    }

    fun remove(id: String) {
        write(_state.value.sources.filterNot { it.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        write(_state.value.sources.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** 检测结果回写：platforms / 备注 / 时间。 */
    fun recordCheck(id: String, platforms: List<String>, declared: List<String>, note: String) {
        val now = System.currentTimeMillis()
        write(
            _state.value.sources.map {
                if (it.id == id) {
                    it.copy(
                        platforms = platforms.distinct(),
                        declaredPlatforms = declared.distinct(),
                        lastCheckAt = now,
                        lastCheckNote = note,
                    )
                } else {
                    it
                }
            },
        )
    }

    fun scriptOf(id: String): String? = _state.value.sources.firstOrNull { it.id == id }?.script

    private fun write(sources: List<MusicSource>) {
        val encoded = runCatching { json.encodeToString(sources) }.getOrElse { "[]" }
        prefs.edit().putString(KEY_SOURCES, encoded).apply()
        _state.value = MusicSourcesState(sources)
    }

    private fun read(): MusicSourcesState {
        val raw = prefs.getString(KEY_SOURCES, null) ?: return MusicSourcesState()
        val sources = runCatching { json.decodeFromString<List<MusicSource>>(raw) }
            .getOrElse { emptyList() }
        return MusicSourcesState(sources)
    }

    private companion object {
        const val KEY_SOURCES = "sources_json"
    }
}
