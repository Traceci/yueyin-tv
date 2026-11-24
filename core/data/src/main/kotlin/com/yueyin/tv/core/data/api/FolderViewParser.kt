// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.api

import com.yueyin.tv.core.model.library.FolderEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 飞牛 folder-view/list 的真实响应：
 * data.folder = { name, path, itemCount, folders[], tracks[] }。
 * folders[].path 是 NAS 绝对路径，tracks[] 是 TrackDto 结构（含 guid）。
 * 文件夹与音频混排，先文件夹后歌曲，与官方 App 一致。
 */
internal fun parseFolderViewList(root: JsonElement, parentId: String?): List<FolderEntry> {
    // TrimMusicApi 的 get<JsonElement> 已经把响应信封拆开返回 data；
    // 单测也可能直接传完整信封，这里两种形态都兼容。
    val rootObject = root as? JsonObject ?: return emptyList()
    val data = rootObject["data"] as? JsonObject ?: rootObject
    val folderObject = data["folder"] as? JsonObject ?: return emptyList()
    val folders = folderObject["folders"] as? JsonArray ?: JsonArray(emptyList())
    val tracks = folderObject["tracks"] as? JsonArray ?: JsonArray(emptyList())

    val folderEntries = folders.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (item.booleanOf("isAdminDeleted") == true) return@mapNotNull null
        val path = item.stringOf("path") ?: return@mapNotNull null
        val count = item.intOf("itemCount") ?: 0
        FolderEntry(
            id = path,
            parentId = parentId,
            name = item.stringOf("name").orEmpty().ifBlank { path.substringAfterLast('/') },
            subtitle = "$count 首",
            isFolder = true,
            childCount = count,
            path = path,
        )
    }

    val trackEntries = tracks.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (item.booleanOf("isAdminDeleted") == true) return@mapNotNull null
        val guid = item.stringOf("guid") ?: return@mapNotNull null
        val spec = item["audioSpec"] as? JsonObject
        val path = spec?.stringOf("path")
        // 飞牛偶尔不给 artists/title，但文件路径本身常是「曲名 - 歌手.ext」。
        // 只在服务端字段缺失时兜底，不覆盖服务端明确给出的值。
        val fileName = path?.substringAfterLast('/')?.substringBeforeLast('.')
        val nameParts = fileName?.split(Regex("\\s+-\\s+"))
        val artists = (item["artists"] as? JsonArray).orEmpty().mapNotNull { artist ->
            (artist as? JsonObject)?.stringOf("name")
        }
        val artist = artists.joinToString(" / ").ifBlank { nameParts?.getOrNull(1) }
        val title = item.stringOf("title").orEmpty().ifBlank { nameParts?.firstOrNull() ?: guid }
        val format = spec?.stringOf("format")?.uppercase().orEmpty()
        FolderEntry(
            id = guid,
            parentId = parentId,
            name = title,
            subtitle = artist?.takeIf(String::isNotBlank) ?: format,
            isFolder = false,
            childCount = 0,
            path = path,
            coverId = item.stringOf("coverId"),
            artistName = artist?.takeIf(String::isNotBlank),
            albumName = (item["album"] as? JsonObject)?.stringOf("name"),
            durationMs = item.longOf("duration"),
        )
    }
    return folderEntries + trackEntries
}

/**
 * 从目录自己的 folder-view/list 响应里挑一张固定封面。
 *
 * 用目录路径的哈希在候选里取模，而不是真随机：同一个目录每次进入都是同一张，
 * 避免卡片封面来回跳。候选限于该接口返回的第一页 tracks，不额外拉整目录。
 */
internal fun parseFolderCover(root: JsonElement, folderPath: String): String? =
    pickCoverId(
        parseFolderViewList(root, folderPath)
            .filterNot(FolderEntry::isFolder)
            .mapNotNull(FolderEntry::coverId)
            .distinct(),
        folderPath,
    )

/** 按 key 的哈希在候选封面里固定选一张，避免每次进入目录封面变化。 */
internal fun pickCoverId(candidates: List<String>, key: String): String? {
    if (candidates.isEmpty()) return null
    val index = (key.hashCode() and Int.MAX_VALUE) % candidates.size
    return candidates[index]
}

private fun JsonObject.stringOf(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
}

private fun JsonObject.intOf(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    runCatching { this[key]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()
}

private fun JsonObject.longOf(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
    runCatching { this[key]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()
}

private fun JsonObject.booleanOf(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key ->
    runCatching { this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull()
}
