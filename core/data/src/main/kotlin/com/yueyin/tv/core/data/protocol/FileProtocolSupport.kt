package com.yueyin.tv.core.data.protocol

import android.util.Base64

/**
 * 文件类协议（WebDAV / SMB）共用的工具：
 * 这两类协议拿不到媒体服务器的元数据，只能靠**目录结构 + 文件名**推断
 * 歌手 / 专辑 / 曲序 / 歌名，所以把推断规则集中在这里，两边行为保持一致。
 */

/** 认作音频的扩展名（全部小写，不带点）。 */
internal val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "ape", "dsf", "dff", "alac", "aiff", "aif",
)

/** 认作封面的文件名（不含扩展名，全部小写）。 */
internal val COVER_BASENAMES = setOf(
    "cover", "folder", "front", "albumart", "albumartsmall", "album", "poster", "artwork",
)

internal fun String.extensionOf(): String = substringAfterLast('.', "").lowercase()

internal fun String.isAudioFile(): Boolean = extensionOf() in AUDIO_EXTENSIONS

internal fun String.isLrcFile(): Boolean = extensionOf() == "lrc"

internal fun String.isCoverImage(): Boolean {
    val ext = extensionOf()
    if (ext !in setOf("jpg", "jpeg", "png", "webp")) return false
    return substringBeforeLast('.', "").lowercase() in COVER_BASENAMES
}

/**
 * 从文件名解析「曲序 + 歌名」。
 * 覆盖常见命名：`01 - 歌名.flac`、`01.歌名.mp3`、`01_歌名.m4a`、`1-01 歌名.flac`、`歌名.flac`。
 */
internal fun parseTrackTitle(fileName: String): Pair<Int, String> {
    val name = fileName.substringBeforeLast('.', fileName).trim()
    val match = TRACK_PREFIX.matchEntire(name)
        ?: return 0 to name.ifBlank { fileName }
    val number = match.groupValues[1].trimStart('0').ifEmpty { "0" }.toIntOrNull() ?: 0
    val rest = match.groupValues[2].trim()
    return if (rest.isBlank()) 0 to name else number to rest
}

private val TRACK_PREFIX = Regex("""^(\d{1,3})\s*[-._、)]\s*(.+)$""")

/** HTTP Basic 认证头（Android 上没有 java.util.Base64 的 API 26 限制，用 android.util）。 */
internal fun basicAuthHeader(username: String, password: String): String {
    if (username.isEmpty() && password.isEmpty()) return ""
    val raw = "$username:$password".toByteArray(Charsets.UTF_8)
    return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
}

/**
 * 文件协议的「歌手+专辑」联合键。
 * 用 `\u0001` 分隔而不是 `-`/`|`：专辑名里这些字符都很常见。
 */
internal fun albumKeyOf(artist: String, album: String): String = "$artist\u0001$album"
