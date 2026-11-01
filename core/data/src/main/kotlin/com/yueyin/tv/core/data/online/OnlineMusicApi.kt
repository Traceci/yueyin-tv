// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.online

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * 在线搜索：直接对平台官方接口发请求，不经过音源脚本。
 *
 * 五家全部是明文 JSON + 零签名，实现照搬 `lemon-music` 的 `server/musicSdk.js`。
 * 每家的"脾气"都记在对应的函数头上——它们不像同一个 API 的五个实现，
 * 更像五个人各自按自己的习惯写的接口。
 *
 * 注意：这里只负责"拿到有哪些歌"。真正把歌变成一条能播的地址，是音源脚本
 * （`action=musicUrl`）的活儿，两者是两条独立链路。
 */
class OnlineMusicApi(
    internal val client: OkHttpClient = OnlineHttp.defaultClient(),
) {
    /** 单平台搜索。失败抛 [IOException]，由上层聚合成 [OnlineSourceError]。 */
    suspend fun search(
        platform: OnlinePlatform,
        keyword: String,
        page: Int = 1,
        limit: Int = 30,
    ): OnlineSearchPage = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@withContext OnlineSearchPage(platform, emptyList(), 0)
        when (platform) {
            OnlinePlatform.Kuwo -> searchKuwo(kw, page, limit)
            OnlinePlatform.NetEase -> searchNetEase(kw, page, limit)
            OnlinePlatform.QQ -> searchQQ(kw, page, limit)
            OnlinePlatform.Kugou -> searchKugou(kw, page, limit)
            OnlinePlatform.Migu -> searchMigu(kw, page, limit)
        }
    }

    /**
     * 多平台并发搜索。某个平台失败时只记错误，不影响其他平台的结果。
     *
     * 结果按平台交错合并：五家轮流插入，避免"前 24 条全是同一家、其余沉底"。
     * 这也是"在线不分平台"这个决定的直接后果——既然不提供按平台过滤，
     * 就必须保证每家都能在前排露出，否则等于变相按平台排序。
     */
    suspend fun searchAll(
        keyword: String,
        platforms: List<OnlinePlatform> = OnlinePlatform.entries,
        page: Int = 1,
        limit: Int = 24,
    ): OnlineSearchResult = coroutineScope {
        val jobs = platforms.map { platform ->
            platform to async { runCatching { search(platform, keyword, page, limit) } }
        }
        val songs = mutableListOf<OnlineSong>()
        val errors = mutableListOf<OnlineSourceError>()
        for ((platform, job) in jobs) {
            job.await().let { outcome ->
                outcome.getOrNull()?.let { songs += it.songs }
                outcome.exceptionOrNull()?.let {
                    errors += OnlineSourceError(platform, it.message ?: "请求失败")
                }
            }
        }
        OnlineSearchResult(songs = interleave(songs), errors = errors)
    }

    // ---------------------------------------------------------------- 酷我

    /**
     * 酷我：`search.kuwo.cn/r.s`。
     * 返回的是**单引号 JSON**，且和标准 JSON 一样以 `{` 开头——不能靠首字符判断格式，
     * 所以统一交给 [OnlineJson.parseObject] 去"先原样、再换引号"地试。
     */
    private fun searchKuwo(keyword: String, page: Int, limit: Int): OnlineSearchPage {
        val url = "https://search.kuwo.cn/r.s".toHttpUrl().newBuilder()
            .addQueryParameter("client", "kt")
            .addQueryParameter("all", keyword)
            .addQueryParameter("pn", (page - 1).coerceAtLeast(0).toString())
            .addQueryParameter("rn", limit.toString())
            .addQueryParameter("uid", "0")
            .addQueryParameter("ver", "kwplayer_ar_9.2.2.1")
            .addQueryParameter("vipver", "1")
            .addQueryParameter("show_copyright_off", "1")
            .addQueryParameter("newver", "1")
            .addQueryParameter("ft", "music")
            .addQueryParameter("cluster", "0")
            .addQueryParameter("strategy", "2012")
            .addQueryParameter("encoding", "utf8")
            .addQueryParameter("rformat", "json")
            .addQueryParameter("vermerge", "1")
            .addQueryParameter("moession", "")
            .build()

        val root = OnlineJson.parseObject(get(url.toString()))
            ?: throw IOException("酷我返回的不是可解析的 JSON")
        val list = root["abslist"] as? JsonArray
            ?: return OnlineSearchPage(OnlinePlatform.Kuwo, emptyList(), 0)

        val songs = list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val rid = item.str("MUSICRID").substringAfter("MUSIC_").ifEmpty {
                item.str("DC_TARGETID")
            }
            if (rid.isEmpty()) return@mapNotNull null
            OnlineSong(
                platform = OnlinePlatform.Kuwo,
                id = rid,
                name = OnlineText.cleanHtml(item.str("SONGNAME")),
                artist = OnlineText.cleanArtist(item.str("ARTIST")),
                album = OnlineText.cleanHtml(item.str("ALBUM")),
                albumId = item.str("ALBUMID"),
                durationSeconds = item.int("DURATION"),
                coverUrl = kuwoCover(item),
                qualities = OnlineQuality.kuwo(item.str("N_MINFO")),
            )
        }
        return OnlineSearchPage(
            platform = OnlinePlatform.Kuwo,
            songs = songs,
            total = root.int("TOTAL").takeIf { it > 0 } ?: songs.size,
        )
    }

    /** 酷我封面：搜索接口给的是 `web_albumpic_short` 短路径，拼上 CDN 前缀 */
    private fun kuwoCover(item: JsonObject): String {
        val album = item.str("web_albumpic_short").trim()
        if (album.isNotEmpty()) {
            return "https://img4.kuwo.cn/star/albumcover/" + album.replace("120", "500")
        }
        val artist = item.str("web_artistpic_short").trim()
        if (artist.isNotEmpty()) {
            return "https://img1.kuwo.cn/star/starheads/" + artist.replace("120", "500")
        }
        return OnlineQuality.firstImage(
            item.str("albumpic"),
            item.str("pic"),
            item.str("pic120"),
            item.str("musicPic"),
            item.str("artistPic"),
            item.str("img"),
        ).replace("/120/", "/500/")
    }

    // -------------------------------------------------------------- 网易云

    private fun searchNetEase(keyword: String, page: Int, limit: Int): OnlineSearchPage {
        val offset = (page - 1).coerceAtLeast(0) * limit
        val url = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder()
            .addQueryParameter("s", keyword)
            .addQueryParameter("type", "1")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()

        val root = OnlineJson.parseObject(
            OnlineHttp.get(client, url.toString(), referer = "https://music.163.com"),
        ) ?: return OnlineSearchPage(OnlinePlatform.NetEase, emptyList(), 0)
        val result = root.obj("result") ?: return OnlineSearchPage(OnlinePlatform.NetEase, emptyList(), 0)
        val list = result["songs"] as? JsonArray
            ?: return OnlineSearchPage(OnlinePlatform.NetEase, emptyList(), 0)

        val songs = list.mapNotNull { netEaseSong(it as? JsonObject ?: return@mapNotNull null) }
        return OnlineSearchPage(
            platform = OnlinePlatform.NetEase,
            songs = songs,
            total = result.int("songCount").takeIf { it > 0 } ?: songs.size,
        )
    }

    /** 网易的歌曲字段在搜索/榜单/专辑三个接口里是同一套，抽出来复用 */
    internal fun netEaseSong(item: JsonObject): OnlineSong? {
        val id = item.str("id")
        if (id.isEmpty()) return null
        val album = item.obj("al") ?: item.obj("album")
        val artists = item.arr("ar") ?: item.arr("artists")
        // 搜索接口给 dt（毫秒），榜单接口给 duration，两个都要认
        val millis = item.long("dt").takeIf { it > 0 } ?: item.long("duration")
        return OnlineSong(
            platform = OnlinePlatform.NetEase,
            id = id,
            name = OnlineText.cleanHtml(item.str("name")),
            artist = OnlineText.artistsFrom(artists),
            album = OnlineText.cleanHtml(album?.str("name").orEmpty()),
            albumId = album?.str("id").orEmpty(),
            durationSeconds = (millis / 1000).toInt(),
            coverUrl = album?.str("picUrl").orEmpty(),
            qualities = OnlineQuality.netEase(item),
        )
    }

    // ------------------------------------------------------------- QQ 音乐

    /**
     * QQ：H5 搜索 `c.y.qq.com/soso/fcgi-bin/search_for_qq_cp`。
     *
     * 三个坑：① 必须用 iPhone UA + `y.qq.com/m` 的 Referer，桌面 UA 会返回空；
     * ② 返回的是**扁平字段**（`songname / songmid / albummid`），没有 `file` 对象；
     * ③ 分页参数是 `p`（从 1 开始）而不是页码偏移。
     */
    private fun searchQQ(keyword: String, page: Int, limit: Int): OnlineSearchPage {
        val url = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp".toHttpUrl().newBuilder()
            .addQueryParameter("g_tk", "5381")
            .addQueryParameter("uin", "0")
            .addQueryParameter("format", "json")
            .addQueryParameter("inCharset", "utf-8")
            .addQueryParameter("outCharset", "utf-8")
            .addQueryParameter("notice", "0")
            .addQueryParameter("platform", "h5")
            .addQueryParameter("needNewCode", "1")
            .addQueryParameter("w", keyword)
            .addQueryParameter("zhidaqu", "1")
            .addQueryParameter("catZhida", "1")
            .addQueryParameter("t", "0")
            .addQueryParameter("flag", "1")
            .addQueryParameter("ie", "utf-8")
            .addQueryParameter("sem", "1")
            .addQueryParameter("aggr", "0")
            .addQueryParameter("perpage", limit.toString())
            .addQueryParameter("n", limit.toString())
            .addQueryParameter("p", page.coerceAtLeast(1).toString())
            .addQueryParameter("remoteplace", "txt.mqq.all")
            .build()

        val raw = OnlineHttp.get(
            client,
            url.toString(),
            ua = OnlineHttp.MOBILE_UA,
            referer = "https://y.qq.com/m/index.html",
        )
        val root = OnlineJson.parseObject(raw) ?: throw IOException("QQ音乐搜索返回异常")
        val code = root.int("code")
        if (code != 0) throw IOException("QQ音乐搜索失败($code)")
        val song = root.obj("data")?.obj("song")
            ?: return OnlineSearchPage(OnlinePlatform.QQ, emptyList(), 0)
        val list = song["list"] as? JsonArray
            ?: return OnlineSearchPage(OnlinePlatform.QQ, emptyList(), 0)
        val songs = list.mapNotNull { qqSong(it as? JsonObject ?: return@mapNotNull null) }
        return OnlineSearchPage(
            platform = OnlinePlatform.QQ,
            songs = songs,
            total = song.int("totalnum").takeIf { it > 0 } ?: songs.size,
        )
    }

    /** QQ 歌曲字段：H5 搜索是扁平、musicu 详情是嵌套，两种都认 */
    internal fun qqSong(item: JsonObject): OnlineSong? {
        val album = item.obj("album")
        val albumMid = album?.str("mid")?.ifEmpty { item.str("albummid") } ?: item.str("albummid")
        val songMid = item.firstNonEmpty("mid", "songmid")
        val songId = item.str("id").ifEmpty { item.str("songid") }.ifEmpty { songMid }
        if (songMid.isEmpty() && songId.isEmpty()) return null
        val cover = OnlineQuality.firstImage(
            item.str("img"),
            item.str("picUrl"),
            album?.str("pic"),
            if (albumMid.isNotEmpty() && albumMid != "0") {
                "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
            } else {
                ""
            },
        )
        return OnlineSong(
            platform = OnlinePlatform.QQ,
            id = songMid.ifEmpty { songId },
            name = OnlineText.cleanHtml(item.firstNonEmpty("title", "songname", "name")),
            artist = OnlineText.cleanArtist(
                if (item.arr("singer") != null) item.arr("singer").stringsOf("name").joinToString("/")
                else item.str("singername"),
            ),
            album = OnlineText.cleanHtml(album?.str("name")?.ifEmpty { item.str("albumname") }
                ?: item.str("albumname")),
            albumId = albumMid,
            durationSeconds = item.int("interval"),
            coverUrl = cover,
            qualities = OnlineQuality.qq(item),
            // QQ 的 media_mid 和 songmid 通常相同，但少数老歌不同，取链时要原样回传
            mediaId = item.obj("file")?.str("media_mid")?.ifEmpty { item.str("strMediaMid") }
                ?: item.str("strMediaMid"),
        )
    }

    // --------------------------------------------------------------- 酷狗

    /**
     * 酷狗：`songsearch.kugou.com/song_search_v2`。
     *
     * id 用的是 `FileHash`（取链也用它），歌名和歌手是分开的字段，
     * 但**在榜单/歌单接口里它们会被拼成 `歌手 - 歌名`**，那种情况走 [kugouSongFromRank]。
     */
    private fun searchKugou(keyword: String, page: Int, limit: Int): OnlineSearchPage {
        val url = "https://songsearch.kugou.com/song_search_v2".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("pagesize", limit.toString())
            .addQueryParameter("userid", "-1")
            .addQueryParameter("clientver", "2000")
            .addQueryParameter("platform", "WebFilter")
            .addQueryParameter("filter", "2")
            .addQueryParameter("iscorrection", "1")
            .addQueryParameter("privilege_filter", "0")
            .addQueryParameter("area_code", "1")
            .build()

        val root = OnlineJson.parseObject(
            OnlineHttp.get(client, url.toString(), referer = "https://www.kugou.com/"),
        ) ?: throw IOException("酷狗搜索返回异常")
        val data = root.obj("data")
            ?: return OnlineSearchPage(OnlinePlatform.Kugou, emptyList(), 0)
        val list = (data["lists"] as? JsonArray)?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val hash = item.firstNonEmpty("FileHash", "hash")
            if (hash.isEmpty()) return@mapNotNull null
            OnlineSong(
                platform = OnlinePlatform.Kugou,
                id = hash,
                name = OnlineText.cleanHtml(item.firstNonEmpty("SongName", "songname")),
                artist = OnlineText.cleanArtist(item.firstNonEmpty("SingerName", "singername")),
                album = OnlineText.cleanHtml(item.firstNonEmpty("AlbumName", "albumname")),
                albumId = item.firstNonEmpty("AlbumID", "album_id"),
                durationSeconds = item.int("Duration").takeIf { it > 0 } ?: item.int("duration"),
                coverUrl = kugouCover(item),
                qualities = OnlineQuality.kugou(item),
                mediaId = hash,
            )
        }.orEmpty()
        return OnlineSearchPage(
            platform = OnlinePlatform.Kugou,
            songs = list,
            total = data.int("total").takeIf { it > 0 } ?: list.size,
        )
    }

    /**
     * 酷狗榜单/歌单里的一条。这类接口没有独立的歌名与歌手，只有一个
     * `filename` = `歌手 - 歌名`（少数是 `songname`）。拆错了会显示成
     * "某首歌的歌手是整串标题"，所以拆分失败时宁可歌手留空。
     */
    internal fun kugouSongFromRank(item: JsonObject): OnlineSong? {
        val hash = item.firstNonEmpty("hash", "FileHash", "HASH")
        if (hash.isEmpty()) return null
        val combined = item.firstNonEmpty("filename", "songname", "name", "title")
        val (splitArtist, splitName) = OnlineText.splitKugouTitle(combined)
        val name = item.firstNonEmpty("name", "title", "songname").ifEmpty { splitName }
        val artist = item.str("author").ifEmpty { item.str("singername") }.ifEmpty { splitArtist }
        return OnlineSong(
            platform = OnlinePlatform.Kugou,
            id = hash,
            name = OnlineText.cleanHtml(name),
            artist = OnlineText.cleanArtist(artist),
            album = OnlineText.cleanHtml(
                item.firstNonEmpty("album_name", "albumname", "remark"),
            ),
            albumId = item.firstNonEmpty("album_id", "albumid"),
            durationSeconds = item.int("duration").takeIf { it > 0 } ?: item.int("timelength") / 1000,
            coverUrl = OnlineQuality.firstImage(
                item.str("album_sizable_cover"),
                item.str("imgurl"),
                item.str("album_img"),
            ).replace("{size}", "400"),
            qualities = OnlineQuality.kugou(item),
            mediaId = hash,
        )
    }

    private fun kugouCover(item: JsonObject): String = OnlineQuality.firstImage(
        item.str("Image"),
        item.str("AlbumImage"),
        item.str("album_img"),
        item.str("imgurl"),
        item.str("cover"),
        item.str("img"),
        item.str("pic"),
    ).replace("{size}", "400")

    // --------------------------------------------------------------- 咪咕

    /**
     * 咪咕：`app.c.nf.migu.cn` 的 search_all。
     *
     * 两个坑：① 必须带 `channel: 0146951` 头；
     * ② **单页硬上限 20 条**，`pageSize` 传多大都只给 20 条，分页要靠 pageNo。
     */
    private fun searchMigu(keyword: String, page: Int, limit: Int): OnlineSearchPage {
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do".toHttpUrl().newBuilder()
            .addQueryParameter("text", keyword)
            .addQueryParameter("pageNo", page.coerceAtLeast(1).toString())
            .addQueryParameter("pageSize", limit.toString())
            .addQueryParameter("searchSwitch", """{"song":1}""")
            .build()

        val root = OnlineJson.parseObject(
            OnlineHttp.get(
                client,
                url.toString(),
                referer = "https://music.migu.cn",
                extraHeaders = mapOf("channel" to "0146951"),
            ),
        ) ?: throw IOException("咪咕搜索返回异常")
        val data = root.obj("songResultData")
            ?: return OnlineSearchPage(OnlinePlatform.Migu, emptyList(), 0)
        val list = (data["result"] as? JsonArray)?.mapNotNull { element ->
            miguSong(element as? JsonObject ?: return@mapNotNull null, fallbackAlbum = "")
        }.orEmpty()
        // 咪咕的 totalCount 是字符串，有时还是空
        return OnlineSearchPage(
            platform = OnlinePlatform.Migu,
            songs = list,
            total = data.int("totalCount").takeIf { it > 0 } ?: list.size,
        )
    }

    /**
     * 咪咕一条歌。搜索接口叫 `name / singers / albums[0]`，
     * 专辑详情接口叫 `songName / singerList / album`——两套字段同时认。
     */
    internal fun miguSong(item: JsonObject, fallbackAlbum: String): OnlineSong? {
        val id = item.firstNonEmpty("copyrightId", "songId", "id")
        if (id.isEmpty()) return null
        val artists = item.arr("singers") ?: item.arr("singerList")
        // 咪咕把专辑放在 albums[0] 里，不在顶层——漏读会让整列专辑名空白
        val albumNode = item.arr("albums").orEmptyArray().firstOrNull()
        return OnlineSong(
            platform = OnlinePlatform.Migu,
            id = id,
            name = OnlineText.cleanHtml(item.firstNonEmpty("name", "songName")),
            artist = OnlineText.artistsFrom(artists),
            album = OnlineText.cleanHtml(
                item.firstNonEmpty("album", "albumName").ifEmpty {
                    albumNode?.firstNonEmpty("name", "albumName").orEmpty()
                },
            ).ifEmpty { fallbackAlbum },
            albumId = albumNode?.firstNonEmpty("id", "albumId").orEmpty(),
            durationSeconds = item.int("duration"),
            coverUrl = miguCover(item),
            qualities = OnlineQuality.migu(item),
            mediaId = item.str("copyrightId"),
        )
    }

    private fun miguCover(item: JsonObject): String {
        val direct = OnlineQuality.firstImage(
            item.str("img3"),
            item.str("img2"),
            item.str("img1"),
            item.str("imgUrl"),
            item.str("cover"),
        )
        if (direct.isNotEmpty()) return direct
        val images = item.arr("imgItems") ?: item.arr("imgList") ?: return ""
        val best = images.mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("imgSizeType") == "03" || it.str("imgSizeType") == "02" }
            ?: images.firstOrNull() as? JsonObject
        return best?.let { OnlineQuality.firstImage(it.str("img"), it.str("url")) }.orEmpty()
    }

    // ---------------------------------------------------------------- HTTP

    private fun get(url: String): String = OnlineHttp.get(client, url)

    private fun interleave(songs: List<OnlineSong>): List<OnlineSong> {
        if (songs.isEmpty()) return songs
        val byPlatform = songs.groupBy { it.platform }
        if (byPlatform.size <= 1) return songs
        val queues = byPlatform.values.map { it.toMutableList() }
        val merged = ArrayList<OnlineSong>(songs.size)
        var guard = 0
        while (queues.any { it.isNotEmpty() }) {
            queues.forEach { queue -> if (queue.isNotEmpty()) merged += queue.removeAt(0) }
            if (++guard > 10_000) break
        }
        return merged
    }

    companion object {
        /** 保留这两个门面，单元测试与旧调用点都指向这里 */
        fun cleanHtml(input: String): String = OnlineText.cleanHtml(input)

        fun cleanArtist(input: String): String = OnlineText.cleanArtist(input)
    }
}
