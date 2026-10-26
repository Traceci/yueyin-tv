package com.fnmusic.tv.core.data.protocol

import com.fnmusic.tv.core.data.connect.ConnectProfile
import com.fnmusic.tv.core.model.connect.ConnectType
import com.fnmusic.tv.core.model.connect.ServerCapabilities

/** 跨平台统一的轻量实体（刻意不复用 fnOS 的 DTO，避免把某一家服务的字段泄漏到上层）。 */
data class RemoteArtist(val id: String, val name: String, val coverId: String? = null)
data class RemoteAlbum(
    val id: String,
    val name: String,
    val artistName: String = "",
    val coverId: String? = null,
    val songCount: Int = 0,
)
data class RemoteSong(
    val id: String,
    val title: String,
    val artistName: String = "",
    val albumName: String = "",
    val albumId: String? = null,
    val durationMs: Long = 0L,
    val coverId: String? = null,
    val trackNumber: Int = 0,
)
data class RemotePlaylist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val coverId: String? = null,
)

/**
 * 音乐服务协议抽象。
 *
 * 所有平台的差异（Subsonic 的 `u/p/v/c` 鉴权、Jellyfin 的 `X-Emby-Token`、
 * 飞牛的 FnConnect 路由……）都必须在这里被吃掉，上层 UI 不感知任何平台细节。
 */
interface MusicProtocol {
    val type: ConnectType

    /** 表单里的「测试连接」：只要能连通且鉴权通过就返回 true。 */
    suspend fun ping(profile: ConnectProfile): Boolean

    /** 正式登录：校验凭据并返回该服务的能力集合。 */
    suspend fun login(profile: ConnectProfile): ServerCapabilities

    suspend fun artists(profile: ConnectProfile): List<RemoteArtist>
    suspend fun albums(profile: ConnectProfile): List<RemoteAlbum>
    suspend fun songs(profile: ConnectProfile): List<RemoteSong>
    suspend fun playlists(profile: ConnectProfile): List<RemotePlaylist>
    suspend fun playlistSongs(profile: ConnectProfile, playlistId: String): List<RemoteSong>
    suspend fun search(profile: ConnectProfile, query: String): List<RemoteSong>

    /** 给 Media3 直接播放的地址（可带鉴权参数）。 */
    fun streamUrl(profile: ConnectProfile, songId: String): String

    fun coverUrl(profile: ConnectProfile, coverId: String, size: Int = 512): String

    /** 返回 LRC 原文；没有歌词时返回 null。 */
    /** 专辑下的歌曲。Subsonic 的 getAlbum、Jellyfin 的 Items?ParentId= 都归到这里。 */
    suspend fun albumSongs(profile: ConnectProfile, albumId: String): List<RemoteSong> =
        playlistSongs(profile, albumId)

    /** 歌手的歌曲。Subsonic 用 getArtist 的 song 段，Jellyfin 用 ArtistIds 过滤。 */
    suspend fun artistSongs(profile: ConnectProfile, artistId: String): List<RemoteSong> = emptyList()

    /** 歌手的专辑。 */
    suspend fun artistAlbums(profile: ConnectProfile, artistId: String): List<RemoteAlbum> = emptyList()

    suspend fun lyrics(profile: ConnectProfile, songId: String, artist: String, title: String): String?

    suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean): Boolean

    /**
     * 保存前把凭据补全（典型场景：Jellyfin/Emby 登录后拿到的 token 必须落库，
     * 否则后续取流地址时没有 api_key，会出现"连得上但播不了"）。
     * 默认不做任何事，只有需要换 token 的协议覆盖它。
     */
    suspend fun enrich(profile: ConnectProfile): ConnectProfile = profile
}

/**
 * 尚未接入的平台：所有操作安全失败，绝不抛异常（避免把 UI 拖进崩溃）。
 * 选择页对这类平台显示「即将支持」，不会走到这里，这里只是兜底。
 */
class UnsupportedProtocol(override val type: ConnectType) : MusicProtocol {
    override suspend fun ping(profile: ConnectProfile) = false
    override suspend fun login(profile: ConnectProfile) = ServerCapabilities(
        lyrics = false, favorites = false, playlists = false, musicVideos = false, radio = false, search = false,
    )

    override suspend fun artists(profile: ConnectProfile) = emptyList<RemoteArtist>()
    override suspend fun albums(profile: ConnectProfile) = emptyList<RemoteAlbum>()
    override suspend fun songs(profile: ConnectProfile) = emptyList<RemoteSong>()
    override suspend fun playlists(profile: ConnectProfile) = emptyList<RemotePlaylist>()
    override suspend fun playlistSongs(profile: ConnectProfile, playlistId: String) = emptyList<RemoteSong>()
    override suspend fun search(profile: ConnectProfile, query: String) = emptyList<RemoteSong>()
    override fun streamUrl(profile: ConnectProfile, songId: String) = ""
    override fun coverUrl(profile: ConnectProfile, coverId: String, size: Int) = ""
    override suspend fun lyrics(profile: ConnectProfile, songId: String, artist: String, title: String): String? = null
    override suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean) = false
}

/**
 * 协议注册表：`ConnectType` → 实现。
 * 新增平台只需在这里登记一行，UI 与上层完全不用改。
 */
class MusicProtocolRegistry(private val protocols: List<MusicProtocol>) {

    fun of(type: ConnectType): MusicProtocol =
        protocols.firstOrNull { it.type == type } ?: UnsupportedProtocol(type)

    fun of(profile: ConnectProfile): MusicProtocol = of(profile.type)

    /** 已真正接入（能连）的平台类型。 */
    val supportedTypes: List<ConnectType>
        get() = protocols.filterNot { it is UnsupportedProtocol }.map { it.type }
}
