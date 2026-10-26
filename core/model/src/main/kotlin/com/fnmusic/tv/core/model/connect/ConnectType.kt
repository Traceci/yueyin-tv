package com.fnmusic.tv.core.model.connect

/**
 * 可接入的音乐服务类型。
 *
 * [supported] 为 false 的项会出现在平台选择页里但标灰（"即将支持"），
 * 绝不出现"能点但连不上"的假入口。
 */
enum class ConnectType(
    val displayName: String,
    val subtitle: String,
    val supported: Boolean,
) {
    FEINIU("飞牛 fnOS", "NAS · 官方协议", true),
    NAVIDROME("Navidrome", "Subsonic 兼容", true),
    SUBSONIC("Subsonic / Airsonic", "Subsonic 兼容", true),
    JELLYFIN("Jellyfin", "媒体服务器", true),
    EMBY("Emby", "媒体服务器", true),

    PLEX("Plex", "令牌授权 · 媒体服务器", true),
    WEBDAV("WebDAV / Alist", "文件协议", true),
    SMB("SMB 共享", "局域网共享", true),
    AUDIO_STATION("群晖 AudioStation", "NAS", true),
    ;

    companion object {
        fun of(name: String?): ConnectType = entries.firstOrNull { it.name == name } ?: FEINIU
    }
}

/**
 * 登录成功后拿到的能力集合。
 * UI 据此隐藏不支持的功能入口（例如 Subsonic 没有音乐视频）。
 */
data class ServerCapabilities(
    val lyrics: Boolean = true,
    val favorites: Boolean = true,
    val playlists: Boolean = true,
    val musicVideos: Boolean = false,
    val radio: Boolean = false,
    val search: Boolean = true,
)
