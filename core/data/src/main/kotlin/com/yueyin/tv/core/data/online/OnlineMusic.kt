// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.online

/**
 * 在线音乐平台。
 *
 * 五个平台的搜索接口都是明文 JSON、零签名、零登录，带上 UA/Referer 就能拿全字段，
 * 所以全部在客户端本地实现（移植自 lemon-music 的 server/musicSdk.js）。
 *
 * 同一套 `req()` 换成 OkHttp，每家只是"字段读哪儿"不同；真正的差异在发现页
 * （新歌 / 新碟 / 榜单 / 歌单），那部分在 [OnlineDiscoverApi] 里。
 */
enum class OnlinePlatform(val key: String, val label: String) {
    Kuwo("kw", "酷我"),
    NetEase("wy", "网易云"),
    QQ("tx", "QQ音乐"),
    Kugou("kg", "酷狗"),
    Migu("mg", "咪咕"),
    ;

    companion object {
        fun fromKey(key: String): OnlinePlatform? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 一条在线歌曲。
 *
 * 这是"搜索结果"，不是本地曲库里的 [com.yueyin.tv.core.model.Track]：
 * 它没有 guid、没有所属服务端，播放链接要靠音源脚本现取。
 */
data class OnlineSong(
    val platform: OnlinePlatform,
    /** 平台内的歌曲 id，取播放链接时要原样回传 */
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val durationSeconds: Int,
    val coverUrl: String,
    /** 由接口字段推断出的可取链档位，仅用于展示徽标；真正能不能取到要看音源 */
    val qualities: List<String>,
    /** 酷狗用 hash 取链、QQ 用 songmid，两者和 id 不一定相同，单独留一份 */
    val mediaId: String = "",
    /** 标记这条结果来自哪个导入音源脚本（文件名）；空字符串表示来自官方 5 平台接口。 */
    val sourceName: String = "",
) {
    /** 跨平台唯一的列表 key */
    val key: String get() = "${platform.key}:$id"

    val durationText: String
        get() = if (durationSeconds <= 0) "" else "%d:%02d".format(durationSeconds / 60, durationSeconds % 60)

    val qualityText: String
        get() = when {
            qualities.any { it.startsWith("flac") || it == "hires" || it.startsWith("atmos") || it == "master" } -> "无损"
            qualities.contains("320k") -> "320K"
            qualities.contains("128k") -> "128K"
            else -> ""
        }
}

/** 单平台单页结果 */
data class OnlineSearchPage(
    val platform: OnlinePlatform,
    val songs: List<OnlineSong>,
    val total: Int,
)

/** 搜索结果里的一条错误（某个平台挂了不拖垮整页，和柠檬的做法一致） */
data class OnlineSourceError(
    val platform: OnlinePlatform,
    val message: String,
)

/** 聚合后的结果：谁先回来算谁的，出错的平台单独记一笔 */
data class OnlineSearchResult(
    val songs: List<OnlineSong>,
    val errors: List<OnlineSourceError> = emptyList(),
)

// ------------------------------------------------------------ 发现页模型

/** 一张专辑（新碟列表 / 搜索结果共用） */
data class OnlineAlbum(
    val platform: OnlinePlatform,
    val id: String,
    val name: String,
    val artist: String,
    val coverUrl: String,
    val publishDate: String,
    val songCount: Int,
) {
    val key: String get() = "${platform.key}:$id"
}

/**
 * 一个榜单。
 *
 * [preview] 是榜单前几首的「歌名 · 歌手」，直接从列表接口就能拿到；
 * 有些平台（网易、酷我）列表里不带预览曲，需要额外请求，
 * 为了不让发现页打开时打几十个请求，那种情况就让它空着——点进去照样是完整列表。
 */
data class OnlineRank(
    val platform: OnlinePlatform,
    val id: String,
    val name: String,
    val coverUrl: String,
    val updateTime: String,
    val preview: List<String> = emptyList(),
) {
    val key: String get() = "${platform.key}:$id"
}

/** 榜单分组（官方榜 / 特色榜 / 地区榜…）。分组名各平台自报，不强行统一 */
data class OnlineRankGroup(
    val name: String,
    val platform: OnlinePlatform,
    val ranks: List<OnlineRank>,
)

/** 推荐歌单里的一项 */
data class OnlinePlaylist(
    val platform: OnlinePlatform,
    val id: String,
    val name: String,
    val author: String,
    val coverUrl: String,
    val playCountText: String,
    val trackCount: Int,
    /** 酷我歌单详情要带上 digest，存在 id 里不好看，单独放 */
    val extra: String = "",
) {
    val key: String get() = "${platform.key}:$id"
}

/** 发现页一次加载的全部内容 */
data class OnlineDiscoverFeed(
    val newSongs: List<OnlineSong> = emptyList(),
    val newAlbums: List<OnlineAlbum> = emptyList(),
    val rankGroups: List<OnlineRankGroup> = emptyList(),
    val playlists: List<OnlinePlaylist> = emptyList(),
    val errors: List<OnlineSourceError> = emptyList(),
)

/** 详情页（榜单 / 专辑 / 歌单）的统一结构 */
data class OnlineCollection(
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val platform: OnlinePlatform,
    val songs: List<OnlineSong>,
    val total: Int = 0,
    val description: String = "",
)

/** 详情页要打开的是哪一类东西 */
enum class OnlineCollectionKind { Rank, Album, Playlist }
