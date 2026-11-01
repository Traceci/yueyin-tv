// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 一次多平台抓取的结果：拿到的条目 + 谁挂了 */
data class FeedPart<T>(
    val items: List<T>,
    val errors: List<OnlineSourceError> = emptyList(),
)

/**
 * 发现页：新歌 / 新碟 / 榜单 / 歌单，以及它们的详情。
 *
 * 这一层回答的是"有音源之后为什么还是只有搜索框"——因为搜索只覆盖了"知道歌名"的场景，
 * 而人真正会用的是"今天有什么新的"、"现在什么在榜上"。这些内容一样不经过音源脚本，
 * 全部来自平台自己的运营接口，所以即使一个音源都没有，这里也应该满满当当。
 *
 * 各平台的支持度并不整齐，这是接口本身的现实，不是漏做：
 *  - 新歌：五家全有
 *  - 新碟：酷我 / 网易 / QQ / 酷狗（咪咕只在新歌里出现）
 *  - 榜单：酷我 / 网易 / QQ / 酷狗（咪咕的榜单接口要登录态，返回 299996）
 *  - 歌单：网易 / 酷我 / 酷狗（QQ、咪咕的推荐位要登录）
 */
class OnlineDiscoverApi(
    private val api: OnlineMusicApi = OnlineMusicApi(),
) {
    private val client get() = api.client

    // ------------------------------------------------------------- 新歌

    suspend fun newSongs(limit: Int = 30): FeedPart<OnlineSong> = coroutineScope {
        val jobs = OnlinePlatform.entries.map { platform ->
            platform to async { runCatching { fetchNewSongs(platform, limit) } }
        }
        val all = mutableListOf<OnlineSong>()
        val errors = mutableListOf<OnlineSourceError>()
        for ((platform, job) in jobs) {
            val outcome = job.await()
            val songs = outcome.getOrNull()
            if (songs.isNullOrEmpty()) {
                outcome.exceptionOrNull()?.let {
                    errors += OnlineSourceError(platform, it.message ?: "请求失败")
                }
            } else {
                all += songs
            }
        }
        FeedPart(interleaveSongs(all), errors)
    }

    private suspend fun fetchNewSongs(platform: OnlinePlatform, limit: Int): List<OnlineSong> =
        withContext(Dispatchers.IO) {
            when (platform) {
                OnlinePlatform.Kuwo -> {
                    // bangId=17 是酷我新歌榜；wapi 必须按 30 条一页翻页（见 kuwoBangSongs）
                    val wapi = kuwoBangSongs(KUWO_NEW_SONG_BANG, limit)
                    if (wapi != null) return@withContext wapi

                    val url = "http://kbangserver.kuwo.cn/ksong.s".toHttpUrl().newBuilder()
                        .addQueryParameter("from", "pc")
                        .addQueryParameter("fmt", "json")
                        .addQueryParameter("pn", "0")
                        .addQueryParameter("rn", limit.toString())
                        .addQueryParameter("type", "bang")
                        .addQueryParameter("data", "content")
                        .addQueryParameter("id", KUWO_NEW_SONG_BANG)
                        .addQueryParameter("show_copyright_off", "0")
                        .addQueryParameter("pcmp4", "1")
                        .addQueryParameter("isbang", "1")
                        .addQueryParameter("userid", "0")
                        .build()
                    val root = OnlineJson.parseObject(kuwoGet(url.toString()))
                        ?: throw java.io.IOException("酷我新歌返回异常")
                    val list = root.arr("musiclist") ?: root.obj("data")?.arr("musicList")
                        ?: root.arr("list") ?: JsonArray(emptyList())
                    list.mapNotNull { kuwoSearchSong(it as? JsonObject ?: return@mapNotNull null, "") }
                }

                OnlinePlatform.NetEase -> {
                    val root = OnlineJson.parseObject(
                        OnlineHttp.get(
                            client,
                            "https://music.163.com/api/v1/discovery/new/songs?areaId=0&total=true&limit=100",
                            referer = "https://music.163.com",
                        ),
                    ) ?: throw java.io.IOException("网易新歌返回异常")
                    val list = root.arr("data") ?: root.arr("songList") ?: JsonArray(emptyList())
                    list.mapNotNull { element ->
                        api.netEaseSong(element as? JsonObject ?: return@mapNotNull null)
                    }.take(limit)
                }

                OnlinePlatform.QQ -> {
                    val root = musicu(
                        """{"comm":{"ct":24,"cv":0},"new_song":{"module":"newsong.NewSongServer",
                           "method":"get_new_song_info","param":{"type":0}}}""",
                    )
                    val data = root.obj("new_song")?.obj("data")
                    val list = data?.arr("songlist") ?: data?.arr("song_list") ?: data?.arr("list")
                        ?: JsonArray(emptyList())
                    list.mapNotNull { api.qqSong(it as? JsonObject ?: return@mapNotNull null) }
                        .take(limit)
                }

                OnlinePlatform.Kugou -> {
                    // rankid=8888 是酷狗 TOP500，同时也是它"新歌"的入口
                    val root = OnlineJson.parseObject(
                        OnlineHttp.get(
                            client,
                            "http://mobilecdn.kugou.com/api/v3/rank/song?version=9108&rankid=8888" +
                                "&page=1&pagesize=$limit&area_code=1",
                            referer = "https://www.kugou.com/",
                        ),
                    ) ?: throw java.io.IOException("酷狗新歌返回异常")
                    val data = root.obj("data") ?: throw java.io.IOException("酷狗新歌无数据")
                    (data.arr("info") ?: JsonArray(emptyList()))
                        .mapNotNull { api.kugouSongFromRank(it as? JsonObject ?: return@mapNotNull null) }
                }

                OnlinePlatform.Migu -> {
                    val root = OnlineJson.parseObject(
                        OnlineHttp.get(
                            client,
                            "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do" +
                                "?columnId=$MIGU_NEW_SONG_COLUMN&needAll=0",
                            referer = "https://m.music.migu.cn/",
                            extraHeaders = mapOf("channel" to "0146951"),
                        ),
                    ) ?: throw java.io.IOException("咪咕新歌返回异常")
                    val contents = root.obj("columnInfo")?.arr("contents")
                        ?: root.obj("data")?.arr("contents")
                        ?: JsonArray(emptyList())
                    contents.mapNotNull { element ->
                        val node = element as? JsonObject ?: return@mapNotNull null
                        api.miguSong(node.obj("objectInfo") ?: node, "")
                    }.take(limit)
                }
            }
        }

    // ------------------------------------------------------------- 新碟

    suspend fun newAlbums(limit: Int = 24): FeedPart<OnlineAlbum> = coroutineScope {
        val platforms = listOf(
            OnlinePlatform.Kuwo,
            OnlinePlatform.NetEase,
            OnlinePlatform.QQ,
            OnlinePlatform.Kugou,
        )
        val jobs = platforms.map { platform ->
            platform to async { runCatching { fetchNewAlbums(platform, limit) } }
        }
        val all = mutableListOf<OnlineAlbum>()
        val errors = mutableListOf<OnlineSourceError>()
        for ((platform, job) in jobs) {
            val outcome = job.await()
            val albums = outcome.getOrNull()
            if (albums.isNullOrEmpty()) {
                outcome.exceptionOrNull()?.let {
                    errors += OnlineSourceError(platform, it.message ?: "请求失败")
                }
            } else {
                all += albums
            }
        }
        // 同一个平台内部可能重复（酷我是从新歌榜的专辑字段去重来的）
        val seen = HashSet<String>()
        val deduped = all.filter { seen.add(it.key) }
        FeedPart(interleaveBy(deduped) { it.platform }, errors)
    }

    private suspend fun fetchNewAlbums(platform: OnlinePlatform, limit: Int): List<OnlineAlbum> =
        withContext(Dispatchers.IO) {
            when (platform) {
                OnlinePlatform.Kuwo -> {
                    // 酷我新碟的 shelves 接口只回 1 张，没法用；
                    // 改成拿新歌榜里出现过的专辑去重——用户看到的仍然是"最近在发片的专辑"
                    val songs = fetchNewSongs(OnlinePlatform.Kuwo, 60)
                    val seen = HashSet<String>()
                    songs.mapNotNull { song ->
                        if (song.albumId.isEmpty() || song.album.isEmpty()) return@mapNotNull null
                        if (!seen.add(song.albumId)) return@mapNotNull null
                        OnlineAlbum(
                            platform = OnlinePlatform.Kuwo,
                            id = song.albumId,
                            name = song.album,
                            artist = song.artist,
                            coverUrl = song.coverUrl,
                            publishDate = "",
                            songCount = 0,
                        )
                    }.take(limit)
                }

                OnlinePlatform.NetEase -> {
                    val root = OnlineJson.parseObject(
                        OnlineHttp.get(
                            client,
                            "https://music.163.com/api/album/new?area=ALL&limit=$limit&offset=0",
                            referer = "https://music.163.com",
                        ),
                    ) ?: throw java.io.IOException("网易新碟返回异常")
                    (root.arr("albums") ?: JsonArray(emptyList())).mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        OnlineAlbum(
                            platform = OnlinePlatform.NetEase,
                            id = item.str("id"),
                            name = OnlineText.cleanHtml(item.str("name")),
                            artist = OnlineText.cleanArtist(
                                item.arr("artists").stringsOf("name").joinToString("/")
                                    .ifEmpty { item.obj("artist")?.str("name").orEmpty() },
                            ),
                            coverUrl = item.str("picUrl"),
                            publishDate = OnlineText.formatDate(item.str("publishTime")),
                            songCount = item.int("size"),
                        )
                    }
                }

                OnlinePlatform.QQ -> {
                    // 新版 newalbum 模块对未登录请求返回 500001，只能用旧版 Musichall 接口
                    val root = musicu(
                        """{"comm":{"ct":24},"new_album":{"module":"QQMusic.MusichallServer",
                           "method":"GetNewAlbum","param":{"type":1,"category":"-1","genre":0,
                           "year":1,"company":-1,"sort":1,"start":0,"end":${(limit - 1).coerceAtLeast(0)}}}}""",
                    )
                    val data = root.obj("new_album")?.obj("data")
                    val list = data?.arr("album_list") ?: data?.arr("list") ?: data?.arr("albums")
                        ?: JsonArray(emptyList())
                    list.mapNotNull { element ->
                        val wrapper = element as? JsonObject ?: return@mapNotNull null
                        // 旧接口的形状是 { album: {...}, author: [...] }
                        val album = wrapper.obj("album") ?: wrapper
                        val mid = album.firstNonEmpty("mid", "album_mid", "albumMID")
                        OnlineAlbum(
                            platform = OnlinePlatform.QQ,
                            id = mid.ifEmpty { album.str("id") },
                            name = OnlineText.cleanHtml(
                                album.firstNonEmpty("name", "title", "album_name"),
                            ),
                            artist = OnlineText.cleanArtist(
                                wrapper.arr("author").stringsOf("name").joinToString("/")
                                    .ifEmpty { album.str("singer_name") },
                            ),
                            coverUrl = if (mid.isEmpty()) {
                                album.str("album_pic")
                            } else {
                                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg"
                            },
                            publishDate = OnlineText.formatDate(
                                album.firstNonEmpty("time_public", "public_time", "pub_time"),
                            ),
                            songCount = album.int("song_count"),
                        )
                    }
                }

                OnlinePlatform.Kugou -> {
                    // 乐库推荐位一般只有几张，剩下的用新歌榜里出现过的专辑补
                    val collected = mutableListOf<OnlineAlbum>()
                    runCatching {
                        val root = OnlineJson.parseObject(
                            OnlineHttp.get(
                                client,
                                "http://service.mobile.kugou.com/v1/yueku/recommend" +
                                    "?plat=0&type=8&operator=2&version=8352",
                                referer = "https://www.kugou.com/",
                            ),
                        )
                        val albums = root?.obj("data")?.obj("info")?.arr("album")
                            ?: JsonArray(emptyList())
                        albums.mapNotNullTo(collected) { element ->
                            val item = element as? JsonObject ?: return@mapNotNullTo null
                            val id = item.firstNonEmpty("albumid", "album_id")
                            if (id.isEmpty()) return@mapNotNullTo null
                            OnlineAlbum(
                                platform = OnlinePlatform.Kugou,
                                id = id,
                                name = OnlineText.cleanHtml(
                                    item.firstNonEmpty("albumname", "album_name"),
                                ),
                                artist = OnlineText.cleanArtist(
                                    item.firstNonEmpty("singername", "author_name"),
                                ),
                                coverUrl = OnlineQuality.firstImage(
                                    item.str("imgurl"),
                                    item.str("sizable_cover"),
                                ).replace("{size}", "400"),
                                publishDate = OnlineText.formatDate(item.str("publishtime")),
                                songCount = item.int("songcount"),
                            )
                        }
                    }
                    if (collected.size < limit) {
                        val songs = runCatching {
                            fetchNewSongs(OnlinePlatform.Kugou, 50)
                        }.getOrDefault(emptyList())
                        val seen = collected.map { it.id }.toHashSet()
                        for (song in songs) {
                            val id = song.albumId
                            if (id.isEmpty() || song.album.isEmpty() || !seen.add(id)) continue
                            collected += OnlineAlbum(
                                platform = OnlinePlatform.Kugou,
                                id = id,
                                name = song.album,
                                artist = song.artist,
                                coverUrl = song.coverUrl.replace("{size}", "400"),
                                publishDate = "",
                                songCount = 0,
                            )
                        }
                    }
                    collected.take(limit)
                }

                OnlinePlatform.Migu -> emptyList()
            }
        }

    // ------------------------------------------------------------- 榜单

    /**
     * 榜单列表。各平台的分组（官方榜/特色榜/地区榜）原样保留——
     * 这是平台编辑的分类，不是我们能编的，硬合并成一坨反而更难找。
     */
    suspend fun rankGroups(): FeedPart<OnlineRankGroup> = coroutineScope {
        val platforms = listOf(
            OnlinePlatform.Kuwo,
            OnlinePlatform.NetEase,
            OnlinePlatform.QQ,
            OnlinePlatform.Kugou,
        )
        val jobs = platforms.map { platform ->
            platform to async { runCatching { fetchRankGroups(platform) } }
        }
        val groups = mutableListOf<OnlineRankGroup>()
        val errors = mutableListOf<OnlineSourceError>()
        for ((platform, job) in jobs) {
            val outcome = job.await()
            val part = outcome.getOrNull()
            if (part.isNullOrEmpty()) {
                outcome.exceptionOrNull()?.let {
                    errors += OnlineSourceError(platform, it.message ?: "请求失败")
                }
            } else {
                groups += part
            }
        }
        FeedPart(groups, errors)
    }

    private suspend fun fetchRankGroups(platform: OnlinePlatform): List<OnlineRankGroup> =
        withContext(Dispatchers.IO) {
            when (platform) {
                OnlinePlatform.Kuwo -> {
                    val root = OnlineJson.parseObject(
                        kuwoGet("http://wapi.kuwo.cn/api/www/bang/bang/bangMenu?httpsStatus=1"),
                    ) ?: throw java.io.IOException("酷我榜单返回异常")
                    val groups = root.arr("data") ?: JsonArray(emptyList())
                    groups.mapNotNull { element ->
                        val group = element as? JsonObject ?: return@mapNotNull null
                        // 注意用 sourceid：内部 id（如 489927）传给 musicList 会返回 code:-1
                        val ranks = (group.arr("list") ?: JsonArray(emptyList())).mapNotNull { row ->
                            val item = row as? JsonObject ?: return@mapNotNull null
                            val id = item.firstNonEmpty("sourceid", "bangId", "bangid")
                            if (id.isEmpty()) return@mapNotNull null
                            OnlineRank(
                                platform = OnlinePlatform.Kuwo,
                                id = id,
                                name = OnlineText.cleanHtml(item.str("name")),
                                coverUrl = OnlineQuality.firstImage(
                                    item.str("pic"),
                                    item.str("img"),
                                ),
                                updateTime = item.str("pub"),
                            )
                        }
                        if (ranks.isEmpty()) return@mapNotNull null
                        OnlineRankGroup(
                            name = OnlineText.cleanHtml(group.firstNonEmpty("name", "title"))
                                .ifEmpty { "酷我榜单" },
                            platform = OnlinePlatform.Kuwo,
                            ranks = ranks,
                        )
                    }
                }

                OnlinePlatform.NetEase -> {
                    val root = OnlineJson.parseObject(
                        OnlineHttp.get(
                            client,
                            "https://music.163.com/api/toplist",
                            referer = "https://music.163.com",
                        ),
                    ) ?: throw java.io.IOException("网易榜单返回异常")
                    val list = root.arr("list") ?: JsonArray(emptyList())
                    // 网易一次给 60 多个榜，全铺出来电视上没法看，取前 24 个
                    val ranks = list.take(24).mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        val id = item.str("id")
                        if (id.isEmpty()) return@mapNotNull null
                        OnlineRank(
                            platform = OnlinePlatform.NetEase,
                            id = id,
                            name = OnlineText.cleanHtml(item.str("name")),
                            coverUrl = item.str("coverImgUrl"),
                            updateTime = item.str("updateFrequency"),
                            preview = item.arr("tracks").orEmptyArray().take(3).map { track ->
                                val first = track.str("first")
                                val second = track.str("second")
                                if (first.isNotEmpty()) {
                                    second.takeIf { it.isNotEmpty() }?.let { "$first · $it" } ?: first
                                } else {
                                    ""
                                }
                            }.filter { it.isNotEmpty() },
                        )
                    }
                    if (ranks.isEmpty()) emptyList() else listOf(
                        OnlineRankGroup("网易云榜单", OnlinePlatform.NetEase, ranks),
                    )
                }

                OnlinePlatform.QQ -> {
                    val root = musicu(
                        """{"comm":{"ct":24,"cv":0},"toplist":{"module":"musicToplist.ToplistInfoServer",
                           "method":"GetAll","param":{}}}""",
                    )
                    val groupsArray = root.obj("toplist")?.obj("data")?.arr("group")
                        ?: root.obj("toplist")?.obj("data")?.arr("list")
                        ?: JsonArray(emptyList())
                    groupsArray.mapNotNull { element ->
                        val group = element as? JsonObject ?: return@mapNotNull null
                        val ranks = (group.arr("toplist") ?: group.arr("list")
                            ?: JsonArray(emptyList())).mapNotNull { row ->
                            val item = row as? JsonObject ?: return@mapNotNull null
                            val id = item.firstNonEmpty("topId", "id", "topID")
                            if (id.isEmpty()) return@mapNotNull null
                            OnlineRank(
                                platform = OnlinePlatform.QQ,
                                id = id,
                                name = OnlineText.cleanHtml(
                                    item.firstNonEmpty("title", "name", "topTitle"),
                                ),
                                coverUrl = OnlineQuality.firstImage(
                                    item.str("headPicUrl"),
                                    item.str("frontPicUrl"),
                                    item.str("picUrl"),
                                ),
                                updateTime = item.str("updateTime"),
                                // QQ 的列表接口自带前 3 首，白拿不用再发请求
                                preview = (item.arr("song") ?: item.arr("songlist")
                                    ?: JsonArray(emptyList())).orEmptyArray().take(3).mapNotNull { song ->
                                    val name = song.firstNonEmpty("title", "songname", "name")
                                    if (name.isEmpty()) return@mapNotNull null
                                    val singer = song.arr("singer").stringsOf("name")
                                        .joinToString("/")
                                        .ifEmpty { song.str("singername") }
                                    OnlineText.cleanHtml(name) +
                                        if (singer.isEmpty()) "" else " · $singer"
                                },
                            )
                        }
                        if (ranks.isEmpty()) return@mapNotNull null
                        OnlineRankGroup(
                            name = OnlineText.cleanHtml(group.str("groupName"))
                                .ifEmpty { "QQ音乐榜单" },
                            platform = OnlinePlatform.QQ,
                            ranks = ranks,
                        )
                    }
                }

                OnlinePlatform.Kugou -> {
                    val root = OnlineJson.parseObject(
                        OnlineHttp.get(
                            client,
                            "http://mobilecdn.kugou.com/api/v3/rank/list?version=9108&apiver=6&withsong=1",
                            referer = "https://www.kugou.com/",
                        ),
                    ) ?: throw java.io.IOException("酷狗榜单返回异常")
                    val list = root.obj("data")?.arr("info") ?: JsonArray(emptyList())
                    val ranks = list.take(24).mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        val id = item.firstNonEmpty("rankid", "rank_id", "id")
                        if (id.isEmpty()) return@mapNotNull null
                        OnlineRank(
                            platform = OnlinePlatform.Kugou,
                            id = id,
                            name = OnlineText.cleanHtml(
                                item.firstNonEmpty("rankname", "rank_name"),
                            ),
                            // 酷狗封面带 {size} 占位，必须替换，否则取到的是一张 0 字节的图
                            coverUrl = OnlineQuality.firstImage(
                                item.str("imgurl"),
                                item.str("img_9"),
                                item.str("bannerurl"),
                            ).replace("{size}", "400"),
                            updateTime = item.str("update_frequency"),
                            preview = item.arr("songinfo").orEmptyArray().take(3).mapNotNull { song ->
                                val (artist, name) = OnlineText.splitKugouTitle(
                                    song.firstNonEmpty("songname", "filename"),
                                )
                                val finalName = song.str("name").ifEmpty { name }
                                if (finalName.isEmpty()) return@mapNotNull null
                                val finalArtist = song.str("author").ifEmpty { artist }
                                OnlineText.cleanHtml(finalName) +
                                    if (finalArtist.isEmpty()) "" else " · $finalArtist"
                            },
                        )
                    }
                    if (ranks.isEmpty()) emptyList() else listOf(
                        OnlineRankGroup("酷狗榜单", OnlinePlatform.Kugou, ranks),
                    )
                }

                OnlinePlatform.Migu -> emptyList()
            }
        }

    // ------------------------------------------------------------- 歌单

    suspend fun playlists(limit: Int = 24): FeedPart<OnlinePlaylist> = coroutineScope {
        val platforms = listOf(
            OnlinePlatform.NetEase,
            OnlinePlatform.Kuwo,
            OnlinePlatform.Kugou,
        )
        val jobs = platforms.map { platform ->
            platform to async { runCatching { fetchPlaylists(platform, limit) } }
        }
        val all = mutableListOf<OnlinePlaylist>()
        val errors = mutableListOf<OnlineSourceError>()
        for ((platform, job) in jobs) {
            val outcome = job.await()
            val list = outcome.getOrNull()
            if (list.isNullOrEmpty()) {
                outcome.exceptionOrNull()?.let {
                    errors += OnlineSourceError(platform, it.message ?: "请求失败")
                }
            } else {
                all += list
            }
        }
        FeedPart(interleaveBy(all) { it.platform }, errors)
    }

    private suspend fun fetchPlaylists(platform: OnlinePlatform, limit: Int): List<OnlinePlaylist> =
        withContext(Dispatchers.IO) {
            when (platform) {
                OnlinePlatform.NetEase -> {
                    val root = OnlineJson.parseObject(
                        OnlineHttp.get(
                            client,
                            "https://music.163.com/api/personalized/playlist?limit=$limit&total=true&n=1000",
                            referer = "https://music.163.com",
                        ),
                    ) ?: throw java.io.IOException("网易歌单返回异常")
                    (root.arr("result") ?: JsonArray(emptyList())).mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        val id = item.str("id")
                        if (id.isEmpty()) return@mapNotNull null
                        OnlinePlaylist(
                            platform = OnlinePlatform.NetEase,
                            id = id,
                            name = OnlineText.cleanHtml(item.str("name")),
                            author = OnlineText.cleanHtml(
                                item.obj("creator")?.str("nickname").orEmpty(),
                            ),
                            coverUrl = item.str("picUrl"),
                            playCountText = OnlineText.formatPlayCount(item.long("playCount")),
                            trackCount = item.int("trackCount"),
                        )
                    }
                }

                OnlinePlatform.Kuwo -> {
                    // 酷我的推荐歌单 id 由 digest + 数字 id 组成（`digest-8__3575766446`），
                    // 详情接口只认后半段的数字，所以这里把 digest 单独存起来
                    val root = OnlineJson.parseObject(
                        kuwoGet(
                            "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList" +
                                "?loginUid=0&loginSid=0&appUid=76039576&pn=1&rn=$limit&order=hot",
                        ),
                    ) ?: throw java.io.IOException("酷我歌单返回异常")
                    if (root.int("code") != 200) throw java.io.IOException("酷我歌单返回 code=${root.int("code")}")
                    val list = root.obj("data")?.arr("data") ?: JsonArray(emptyList())
                    list.mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        val id = item.str("id")
                        if (id.isEmpty()) return@mapNotNull null
                        OnlinePlaylist(
                            platform = OnlinePlatform.Kuwo,
                            id = id,
                            name = OnlineText.cleanHtml(item.str("name")),
                            author = OnlineText.cleanHtml(item.str("uname")),
                            coverUrl = OnlineQuality.firstImage(
                                item.str("img"),
                                item.str("img700"),
                                item.str("img300"),
                            ),
                            playCountText = OnlineText.formatPlayCount(item.long("listencnt")),
                            trackCount = item.int("total"),
                            extra = item.str("digest"),
                        )
                    }
                }

                OnlinePlatform.Kugou -> {
                    val root = OnlineJson.parseObject(
                        OnlineHttp.get(
                            client,
                            "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial" +
                                "?is_ajax=1&cdn=cdn&t=5&c=&p=1",
                            referer = "https://www.kugou.com/",
                        ),
                    ) ?: throw java.io.IOException("酷狗歌单返回异常")
                    (root.arr("special_db") ?: JsonArray(emptyList())).take(limit).mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        val id = item.firstNonEmpty("specialid", "special_id")
                        if (id.isEmpty()) return@mapNotNull null
                        OnlinePlaylist(
                            platform = OnlinePlatform.Kugou,
                            id = id,
                            name = OnlineText.cleanHtml(item.str("specialname")),
                            author = OnlineText.cleanHtml(
                                item.firstNonEmpty("nickname", "singername"),
                            ),
                            coverUrl = OnlineQuality.firstImage(
                                item.str("imgurl"),
                                item.str("img"),
                            ).replace("{size}", "400"),
                            playCountText = OnlineText.formatPlayCount(
                                item.long("play_count").takeIf { it > 0 }
                                    ?: item.long("total_play_count"),
                            ),
                            trackCount = item.int("songcount"),
                        )
                    }
                }

                else -> emptyList()
            }
        }

    // ------------------------------------------------------------- 详情

    /**
     * 打开一个榜单 / 专辑 / 歌单的完整曲目。
     *
     * 三种详情在界面上长得一模一样（封面 + 标题 + 曲目表），所以统一成 [OnlineCollection]。
     * 某一家的接口挂了就抛异常，由页面显示"这家打不开"而不是空白页。
     */
    suspend fun collection(
        kind: OnlineCollectionKind,
        platform: OnlinePlatform,
        id: String,
        title: String = "",
        extra: String = "",
        limit: Int = 200,
    ): OnlineCollection = withContext(Dispatchers.IO) {
        when (kind) {
            OnlineCollectionKind.Rank -> rankCollection(platform, id, title)
            OnlineCollectionKind.Album -> albumCollection(platform, id, title)
            OnlineCollectionKind.Playlist -> playlistCollection(platform, id, title, extra)
        }
    }

    // ------------------------------------------------------ 榜单详情

    private suspend fun rankCollection(
        platform: OnlinePlatform,
        id: String,
        fallbackTitle: String,
    ): OnlineCollection = when (platform) {
        OnlinePlatform.Kuwo -> {
            // 先问 wapi（有封面、时长正确）；拿不到再回退 kbangserver（无封面、时长不可靠）
            val wapiSongs = kuwoBangSongs(id, KUWO_RANK_SONG_LIMIT)
            if (wapiSongs != null) {
                return@rankCollection OnlineCollection(
                    title = fallbackTitle.ifEmpty { "排行榜" },
                    subtitle = "酷我",
                    coverUrl = "",
                    platform = OnlinePlatform.Kuwo,
                    songs = wapiSongs,
                    total = wapiSongs.size,
                )
            }
            val root = OnlineJson.parseObject(
                kuwoGet(
                    "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=0&rn=200" +
                        "&type=bang&data=content&id=$id&show_copyright_off=0&pcmp4=1&isbang=1&userid=0",
                ),
            ) ?: throw java.io.IOException("酷我榜单打开失败")
            val list = root.arr("musiclist") ?: root.obj("data")?.arr("musicList")
                ?: root.arr("list") ?: JsonArray(emptyList())
            val songs = list.mapNotNull {
                kuwoSearchSong(it as? JsonObject ?: return@mapNotNull null, "")
            }
            if (songs.isEmpty()) throw java.io.IOException("酷我榜单没有取到歌曲")
            OnlineCollection(
                title = OnlineText.cleanHtml(root.firstNonEmpty("name", "leader"))
                    .ifEmpty { fallbackTitle.ifEmpty { "排行榜" } },
                subtitle = "酷我",
                coverUrl = root.str("pic").ifEmpty { root.str("v9_pic2") },
                platform = OnlinePlatform.Kuwo,
                songs = songs,
                total = songs.size,
            )
        }

        OnlinePlatform.NetEase -> netEasePlaylistCollection(
            id = id,
            fallbackTitle = fallbackTitle,
            subtitle = "网易云",
        )

        OnlinePlatform.QQ -> {
            val root = musicu(
                """{"comm":{"ct":24,"cv":0},"detail":{"module":"musicToplist.ToplistInfoServer",
                   "method":"GetDetail","param":{"topId":${id.toIntOrNull() ?: 0},"offset":0,
                   "num":200,"period":""}}}""",
            )
            val detail = root.obj("detail")?.obj("data") ?: throw java.io.IOException("QQ榜单打开失败")
            val list = detail.arr("songInfoList") ?: detail.obj("data")?.arr("song")
                ?: detail.arr("songlist") ?: JsonArray(emptyList())
            val songs = list.mapNotNull { api.qqSong(it as? JsonObject ?: return@mapNotNull null) }
            if (songs.isEmpty()) throw java.io.IOException("QQ榜单没有取到歌曲")
            val info = detail.obj("data") ?: detail
            OnlineCollection(
                title = OnlineText.cleanHtml(
                    info.firstNonEmpty("title", "topTitle", "name"),
                ).ifEmpty { fallbackTitle.ifEmpty { "排行榜" } },
                subtitle = "QQ音乐",
                coverUrl = OnlineQuality.firstImage(
                    info.str("headPicUrl"),
                    info.str("frontPicUrl"),
                    info.str("picUrl"),
                ),
                platform = OnlinePlatform.QQ,
                songs = songs,
                total = detail.int("totalNum").takeIf { it > 0 } ?: songs.size,
            )
        }

        OnlinePlatform.Kugou -> {
            val root = OnlineJson.parseObject(
                OnlineHttp.get(
                    client,
                    "http://mobilecdn.kugou.com/api/v3/rank/song?version=9108&rankid=$id" +
                        "&page=1&pagesize=200&area_code=1",
                    referer = "https://www.kugou.com/",
                ),
            ) ?: throw java.io.IOException("酷狗榜单打开失败")
            val data = root.obj("data") ?: throw java.io.IOException("酷狗榜单打不开")
            val songs = (data.arr("info") ?: JsonArray(emptyList())).mapNotNull {
                api.kugouSongFromRank(it as? JsonObject ?: return@mapNotNull null)
            }
            if (songs.isEmpty()) throw java.io.IOException("酷狗榜单没有取到歌曲")
            OnlineCollection(
                title = fallbackTitle.ifEmpty { "排行榜" },
                subtitle = "酷狗",
                coverUrl = "",
                platform = OnlinePlatform.Kugou,
                songs = songs,
                total = data.int("total").takeIf { it > 0 } ?: songs.size,
            )
        }

        OnlinePlatform.Migu -> throw java.io.IOException("咪咕暂不支持榜单")
    }

    // ------------------------------------------------------ 专辑详情

    private suspend fun albumCollection(
        platform: OnlinePlatform,
        id: String,
        fallbackTitle: String,
    ): OnlineCollection = when (platform) {
        OnlinePlatform.Kuwo -> {
            // 酷我的 albumInfo 一次就把曲目一起带回来了，不用发第二个请求
            val root = OnlineJson.parseObject(
                kuwoGet("http://wapi.kuwo.cn/api/www/album/albumInfo?albumId=$id"),
            ) ?: throw java.io.IOException("酷我专辑打开失败")
            val meta = root.obj("data") ?: throw java.io.IOException("酷我专辑打不开")
            val songs = (meta.arr("musicList") ?: JsonArray(emptyList())).mapNotNull { element ->
                kuwoApiSong(element as? JsonObject ?: return@mapNotNull null, meta.str("album"))
            }
            if (songs.isEmpty()) throw java.io.IOException("酷我专辑没有曲目")
            OnlineCollection(
                title = OnlineText.cleanHtml(meta.firstNonEmpty("album", "name"))
                    .ifEmpty { fallbackTitle },
                subtitle = OnlineText.cleanArtist(meta.str("artist")),
                coverUrl = meta.str("pic"),
                platform = OnlinePlatform.Kuwo,
                songs = songs,
                total = meta.int("total").takeIf { it > 0 } ?: songs.size,
                description = OnlineText.cleanHtml(meta.str("albuminfo"))
                    .ifEmpty { OnlineText.formatDate(meta.str("releaseDate")) },
            )
        }

        OnlinePlatform.NetEase -> {
            // /api/album/{id}?ext=true 会返回 -462（反爬），v1 路径才带 songs
            val root = OnlineJson.parseObject(
                OnlineHttp.get(
                    client,
                    "https://music.163.com/api/v1/album/$id?ext=true",
                    referer = "https://music.163.com",
                ),
            ) ?: throw java.io.IOException("网易专辑打开失败")
            val album = root.obj("album") ?: throw java.io.IOException("网易专辑打不开")
            var songs = (album.arr("songs") ?: JsonArray(emptyList())).mapNotNull {
                api.netEaseSong(it as? JsonObject ?: return@mapNotNull null)
            }
            // 单曲专辑（size=1）常常 songs 为空但 size 有值——
            // 这时退回"按专辑名搜一遍再严格比对专辑名"，柠檬也是这么兜的
            if (songs.isEmpty()) {
                val albumName = OnlineText.cleanHtml(album.str("name"))
                if (albumName.isNotEmpty()) {
                    songs = runCatching {
                        api.search(OnlinePlatform.NetEase, albumName, limit = 100).songs
                            .filter { OnlineText.cleanHtml(it.album) == albumName }
                    }.getOrDefault(emptyList())
                }
            }
            if (songs.isEmpty()) throw java.io.IOException("网易专辑没有曲目")
            OnlineCollection(
                title = OnlineText.cleanHtml(album.str("name")).ifEmpty { fallbackTitle },
                subtitle = OnlineText.cleanArtist(
                    album.arr("artists").stringsOf("name").joinToString("/")
                        .ifEmpty { album.obj("artist")?.str("name").orEmpty() },
                ),
                coverUrl = album.str("picUrl"),
                platform = OnlinePlatform.NetEase,
                songs = songs,
                total = album.int("size").takeIf { it > 0 } ?: songs.size,
                description = OnlineText.cleanHtml(album.str("description"))
                    .ifEmpty { OnlineText.formatDate(album.str("publishTime")) },
            )
        }

        OnlinePlatform.QQ -> {
            val payload = buildJsonObject {
                put("comm", buildJsonObject {
                    put("cv", 1602)
                    put("ct", 24)
                })
                put("detail", buildJsonObject {
                    put("module", "music.musichallAlbum.AlbumInfoServer")
                    put("method", "GetAlbumDetail")
                    put("param", buildJsonObject { put("albumMid", id) })
                })
                put("songs", buildJsonObject {
                    put("module", "music.musichallAlbum.AlbumSongList")
                    put("method", "GetAlbumSongList")
                    put("param", buildJsonObject {
                        put("albumMid", id)
                        put("begin", 0)
                        put("num", 200)
                        put("order", 1)
                    })
                })
            }
            val root = musicu(payload.toString())
            val detail = root.obj("detail")?.obj("data") ?: JsonObject(emptyMap())
            val songData = root.obj("songs")?.obj("data") ?: JsonObject(emptyMap())
            val songs = (songData.arr("songList") ?: JsonArray(emptyList())).mapNotNull { element ->
                val wrapper = element as? JsonObject ?: return@mapNotNull null
                api.qqSong(wrapper.obj("songInfo") ?: wrapper)
            }
            if (songs.isEmpty()) throw java.io.IOException("QQ专辑没有曲目")
            val basic = detail.obj("basicInfo") ?: detail
            val singers = detail.arr("singer").stringsOf("name").joinToString("/")
                .ifEmpty { basic.arr("singer").stringsOf("name").joinToString("/") }
                .ifEmpty { basic.str("singer_name") }
                .ifEmpty { songs.firstOrNull()?.artist.orEmpty() }
            OnlineCollection(
                title = OnlineText.cleanHtml(basic.firstNonEmpty("name", "title"))
                    .ifEmpty { fallbackTitle },
                subtitle = OnlineText.cleanArtist(singers),
                coverUrl = OnlineQuality.firstImage(
                    basic.str("pic"),
                    basic.str("picUrl"),
                    "https://y.gtimg.cn/music/photo_new/T002R300x300M000$id.jpg",
                ),
                platform = OnlinePlatform.QQ,
                songs = songs,
                total = songData.int("totalNum").takeIf { it > 0 } ?: songs.size,
                description = OnlineText.cleanHtml(detail.str("desc"))
                    .ifEmpty { basic.firstNonEmpty("publishDate", "aDate", "time_public") },
            )
        }

        OnlinePlatform.Kugou -> {
            val info = runCatching {
                OnlineJson.parseObject(
                    OnlineHttp.get(
                        client,
                        "http://mobilecdn.kugou.com/api/v3/album/info?albumid=$id",
                        referer = "https://www.kugou.com/",
                    ),
                )?.obj("data")
            }.getOrNull()
            val root = OnlineJson.parseObject(
                OnlineHttp.get(
                    client,
                    "http://mobilecdn.kugou.com/api/v3/album/song?albumid=$id&page=1&pagesize=300&version=9108",
                    referer = "https://www.kugou.com/",
                ),
            ) ?: throw java.io.IOException("酷狗专辑打开失败")
            val data = root.obj("data") ?: throw java.io.IOException("酷狗专辑打不开")
            val cover = OnlineQuality.firstImage(info?.str("imgurl"), info?.str("img"))
                .replace("{size}", "400")
            val songs = (data.arr("info") ?: JsonArray(emptyList())).mapNotNull { element ->
                api.kugouSongFromRank(element as? JsonObject ?: return@mapNotNull null)
            }.map { if (it.coverUrl.isEmpty() && cover.isNotEmpty()) it.copy(coverUrl = cover) else it }
            if (songs.isEmpty()) throw java.io.IOException("酷狗专辑没有曲目")
            OnlineCollection(
                title = OnlineText.cleanHtml(
                    info?.firstNonEmpty("albumname", "name").orEmpty(),
                ).ifEmpty { fallbackTitle },
                subtitle = OnlineText.cleanArtist(
                    info?.firstNonEmpty("singername", "author_name").orEmpty(),
                ),
                coverUrl = cover,
                platform = OnlinePlatform.Kugou,
                songs = songs,
                total = data.int("total").takeIf { it > 0 } ?: songs.size,
                description = OnlineText.cleanHtml(info?.str("intro").orEmpty())
                    .ifEmpty { info?.str("publishtime").orEmpty() },
            )
        }

        OnlinePlatform.Migu -> throw java.io.IOException("咪咕暂不支持专辑详情")
    }

    // ------------------------------------------------------ 歌单详情

    private suspend fun playlistCollection(
        platform: OnlinePlatform,
        id: String,
        fallbackTitle: String,
        extra: String,
    ): OnlineCollection = when (platform) {
        OnlinePlatform.NetEase -> netEasePlaylistCollection(
            id = id,
            fallbackTitle = fallbackTitle,
            subtitle = "网易云",
            authorFallback = true,
        )

        OnlinePlatform.Kuwo -> {
            // wapi 的 playListInfo 对未登录请求返回空 musiclist，nplserver 才是可用的那个
            val root = OnlineJson.parseObject(
                kuwoGet(
                    "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$id&pn=0&rn=500&encode=utf8" +
                        "&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.1.1.2_W1&newver=1",
                ),
            ) ?: throw java.io.IOException("酷我歌单打开失败")
            val songs = (root.arr("musiclist") ?: JsonArray(emptyList())).mapNotNull {
                kuwoSearchSong(it as? JsonObject ?: return@mapNotNull null, "")
            }
            if (songs.isEmpty()) throw java.io.IOException("酷我歌单没有曲目")
            OnlineCollection(
                title = OnlineText.cleanHtml(root.str("title")).ifEmpty { fallbackTitle },
                subtitle = "酷我 · " + OnlineText.cleanHtml(root.str("uname")).ifEmpty { "歌单" },
                coverUrl = OnlineQuality.firstImage(root.str("pic"), root.str("img700")),
                platform = OnlinePlatform.Kuwo,
                songs = songs,
                total = root.int("total").takeIf { it > 0 } ?: songs.size,
                description = OnlineText.cleanHtml(root.str("info")),
            )
        }

        OnlinePlatform.Kugou -> {
            val root = OnlineJson.parseObject(
                OnlineHttp.get(
                    client,
                    "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$id" +
                        "&page=1&pagesize=300&version=9108",
                    referer = "https://www.kugou.com/",
                ),
            ) ?: throw java.io.IOException("酷狗歌单打开失败")
            val data = root.obj("data") ?: throw java.io.IOException("酷狗歌单打不开")
            val songs = (data.arr("info") ?: JsonArray(emptyList())).mapNotNull {
                api.kugouSongFromRank(it as? JsonObject ?: return@mapNotNull null)
            }
            if (songs.isEmpty()) throw java.io.IOException("酷狗歌单没有曲目")
            OnlineCollection(
                title = fallbackTitle.ifEmpty { "歌单" },
                subtitle = "酷狗",
                coverUrl = "",
                platform = OnlinePlatform.Kugou,
                songs = songs,
                total = data.int("total").takeIf { it > 0 } ?: songs.size,
            )
        }

        else -> throw java.io.IOException("${platform.label}暂不支持歌单详情")
    }

    /**
     * 网易的歌单和榜单是同一个东西（都是一个 playlist），所以合并成一个实现。
     *
     * 关键坑：`/api/v6/playlist/detail` 对未登录请求**只返回前 10 首曲目**，
     * 但 `trackIds` 是完整的、`trackCount` 也是真实的。所以要拿全量，
     * 必须再按 trackIds 分批调 `/api/song/detail` 把每一首补齐——
     * 否则用户点开一个 100 首的歌单只看到 10 首，会以为我们只抓了一页。
     */
    private suspend fun netEasePlaylistCollection(
        id: String,
        fallbackTitle: String,
        subtitle: String,
        authorFallback: Boolean = false,
    ): OnlineCollection {
        val root = OnlineJson.parseObject(
            OnlineHttp.get(
                client,
                "https://music.163.com/api/v6/playlist/detail?id=$id&n=1000&s=8",
                referer = "https://music.163.com",
            ),
        ) ?: throw java.io.IOException("网易歌单打开失败")
        val playlist = root.obj("playlist") ?: throw java.io.IOException("网易歌单打不开")

        val inline = (playlist.arr("tracks") ?: JsonArray(emptyList())).mapNotNull {
            api.netEaseSong(it as? JsonObject ?: return@mapNotNull null)
        }
        val trackIds = (playlist.arr("trackIds") ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonObject)?.str("id")?.takeIf { value -> value.isNotEmpty() } }
        val songs = if (trackIds.size > inline.size) {
            netEaseSongsByIds(trackIds).ifEmpty { inline }
        } else {
            inline
        }
        if (songs.isEmpty()) throw java.io.IOException("网易歌单没有曲目")

        val author = OnlineText.cleanHtml(
            playlist.obj("creator")?.str("nickname")
                ?: playlist.str("creatorName"),
        )
        return OnlineCollection(
            title = OnlineText.cleanHtml(playlist.str("name"))
                .ifEmpty { fallbackTitle.ifEmpty { "歌单" } },
            subtitle = if (authorFallback && author.isNotEmpty()) "$subtitle · $author" else subtitle,
            coverUrl = playlist.str("coverImgUrl"),
            platform = OnlinePlatform.NetEase,
            songs = songs,
            total = playlist.int("trackCount").takeIf { it > 0 } ?: songs.size,
            description = OnlineText.cleanHtml(playlist.str("description")),
        )
    }

    /** 按 id 批量取网易歌曲，200 首一批，尽量少打请求 */
    private suspend fun netEaseSongsByIds(ids: List<String>): List<OnlineSong> {
        val result = mutableListOf<OnlineSong>()
        for (chunk in ids.chunked(200)) {
            val query = chunk.joinToString(",", prefix = "[", postfix = "]")
            val url = "https://music.163.com/api/song/detail?ids=" +
                java.net.URLEncoder.encode(query, "UTF-8")
            val root = runCatching {
                OnlineJson.parseObject(
                    OnlineHttp.get(client, url, referer = "https://music.163.com"),
                )
            }.getOrNull() ?: continue
            val batch = (root.arr("songs") ?: JsonArray(emptyList())).mapNotNull {
                api.netEaseSong(it as? JsonObject ?: return@mapNotNull null)
            }
            result += batch
        }
        return result
    }

    // ------------------------------------------------------------- 映射

    /**
     * 酷我 www 接口（榜单 / 专辑）里的一条歌。
     * 和搜索接口的字段名完全不同：这里叫 `musicrid / name / artist / album / duration`。
     */
    private fun kuwoApiSong(item: JsonObject, fallbackAlbum: String): OnlineSong? {
        val rid = item.firstNonEmpty("musicrid", "rid", "MUSICRID")
            .substringAfter("MUSIC_")
            .ifEmpty { item.firstNonEmpty("dcTargetId", "DC_TARGETID") }
        if (rid.isEmpty()) return null
        val cover = OnlineQuality.firstImage(
            item.str("pic"),
            item.str("albumpic"),
            item.str("pic120"),
        ).kuwoCoverLarge()
        return OnlineSong(
            platform = OnlinePlatform.Kuwo,
            id = rid,
            name = OnlineText.cleanHtml(item.firstNonEmpty("name", "SONGNAME")),
            artist = OnlineText.cleanArtist(item.firstNonEmpty("artist", "ARTIST")),
            album = OnlineText.cleanHtml(item.firstNonEmpty("album", "ALBUM")).ifEmpty { fallbackAlbum },
            albumId = item.firstNonEmpty("albumid", "ALBUMID"),
            durationSeconds = item.int("duration").takeIf { it > 0 } ?: item.int("DURATION"),
            coverUrl = cover,
            qualities = OnlineQuality.kuwo(item.firstNonEmpty("N_MINFO", "n_minfo")),
        )
    }

    /** 酷我 `r.s` 风格接口（kbangserver、歌单）里的一条歌 */
    private fun kuwoSearchSong(item: JsonObject, fallbackAlbum: String): OnlineSong? {
        val rid = item.firstNonEmpty("musicrid", "MUSICRID", "rid", "id")
            .substringAfter("MUSIC_")
        if (rid.isEmpty()) return null
        return OnlineSong(
            platform = OnlinePlatform.Kuwo,
            id = rid,
            name = OnlineText.cleanHtml(item.firstNonEmpty("name", "SONGNAME")),
            artist = OnlineText.cleanArtist(item.firstNonEmpty("artist", "ARTIST")),
            album = OnlineText.cleanHtml(item.firstNonEmpty("album", "ALBUM")).ifEmpty { fallbackAlbum },
            albumId = item.firstNonEmpty("albumid", "ALBUMID"),
            durationSeconds = item.int("duration").takeIf { it > 0 } ?: item.int("DURATION"),
            coverUrl = OnlineQuality.firstImage(
                item.str("pic"),
                item.str("albumpic"),
                item.str("web_albumpic_short").let {
                    if (it.isEmpty()) "" else "https://img4.kuwo.cn/star/albumcover/" + it
                },
            ).kuwoCoverLarge(),
            qualities = OnlineQuality.kuwo(item.firstNonEmpty("N_MINfo", "N_MINFO")),
        )
    }

    /**
     * 酷我封面地址里 `/120/` 是尺寸段，换成 500 才够播放页用。
     * 只替换路径里第一段纯数字尺寸，避免误伤后面哈希里的数字。
     */
    private fun String.kuwoCoverLarge(): String =
        if (isEmpty()) this else replace(Regex("""/albumcover/\d+/"""), "/albumcover/500/")

    // ------------------------------------------------------------- 工具

    /** 酷我的请求统一带 kuwo.cn 的 Referer，缺了它会拿到空 data */
    private fun kuwoGet(url: String): String =
        OnlineHttp.get(client, url, referer = "https://www.kuwo.cn/")

    /**
     * 酷我榜单曲目（wapi）。
     *
     * 这个接口对 `rn` 有硬限制：**一页最多 30 条**，超过（实测 >= 31）直接返回
     * `{"code":-1}`，不是报错也不是空列表。以前一次性请求 `rn=200`，必然失败，
     * 于是每次都回退到 kbangserver 的 `ksong.s`——那个接口：
     *   · 没有 `pic`/`albumpic` → 所有酷我歌曲都没封面；
     *   · `duration` 是另一个含义的数字（如《我好像在哪见过你》给 107 秒，实际 279 秒）
     *     → 歌词匹配按 ±5 秒比对时长，全部判为不匹配 → 酷我歌曲永远「无歌词」。
     *
     * 所以这里必须按 30 条一页翻页抓，宁可多几个请求，也不能拿到那份残缺数据。
     */
    private suspend fun kuwoBangSongs(bangId: String, limit: Int): List<OnlineSong>? =
        withContext(Dispatchers.IO) {
        val pageSize = KUWO_WAPI_PAGE_SIZE.coerceAtMost(limit.coerceAtLeast(1))
        val songs = mutableListOf<OnlineSong>()
        var page = 1
        while (songs.size < limit && page <= KUWO_WAPI_MAX_PAGES) {
            val url = "http://wapi.kuwo.cn/api/www/bang/bang/musicList".toHttpUrl().newBuilder()
                .addQueryParameter("bangId", bangId)
                .addQueryParameter("pn", page.toString())
                .addQueryParameter("rn", pageSize.toString())
                .addQueryParameter("httpsStatus", "1")
                .build()
            val root = runCatching { OnlineJson.parseObject(kuwoGet(url.toString())) }.getOrNull() ?: break
            if (root.int("code") != 200) break
            val chunk = root.obj("data")?.arr("musicList").orEmpty()
                .mapNotNull { kuwoApiSong(it as? JsonObject ?: return@mapNotNull null, "") }
            if (chunk.isEmpty()) break
            songs += chunk
            if (chunk.size < pageSize) break
            page++
        }
        songs.take(limit).ifEmpty { null }
    }

    /** QQ 的 musicu 网关：payload 是 JSON 字符串，放在 `data` 查询参数里 */
    private fun musicu(payload: String): JsonObject {
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=" +
            java.net.URLEncoder.encode(payload, "UTF-8")
        val root = OnlineJson.parseObject(OnlineHttp.get(client, url, referer = "https://y.qq.com/"))
            ?: throw java.io.IOException("QQ音乐返回异常")
        val code = root.int("code")
        if (code != 0) throw java.io.IOException("QQ音乐返回 code=$code")
        return root
    }

    private fun interleaveSongs(songs: List<OnlineSong>): List<OnlineSong> =
        interleaveBy(songs) { it.platform }

    /** 多平台列表按平台轮流插入，保证第一家不会占满整屏 */
    private fun <T> interleaveBy(items: List<T>, platformOf: (T) -> OnlinePlatform): List<T> {
        if (items.isEmpty()) return items
        val groups = items.groupBy(platformOf)
        if (groups.size <= 1) return items
        val queues = groups.values.map { it.toMutableList() }
        val merged = ArrayList<T>(items.size)
        var guard = 0
        while (queues.any { it.isNotEmpty() }) {
            queues.forEach { queue -> if (queue.isNotEmpty()) merged += queue.removeAt(0) }
            if (++guard > 10_000) break
        }
        return merged
    }

    companion object {
        /** 酷我新歌榜 */
        private const val KUWO_NEW_SONG_BANG = "17"

        /** 酷我 wapi 单页硬上限：超过 30 条会被限流返回 code=-1。 */
        private const val KUWO_WAPI_PAGE_SIZE = 30

        /** 翻页上限，防止接口异常时无限循环（30 × 12 = 360 首，足够任何榜单）。 */
        private const val KUWO_WAPI_MAX_PAGES = 12

        /** 酷我榜单一次最多取多少首（和以前的 rn=200 对齐）。 */
        private const val KUWO_RANK_SONG_LIMIT = 200

        /** 咪咕的"新歌"运营位 */
        private const val MIGU_NEW_SONG_COLUMN = "27553319"
    }
}
