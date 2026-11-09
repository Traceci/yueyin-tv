// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.protocol

import com.yueyin.tv.core.data.connect.ConnectProfile
import com.yueyin.tv.core.data.online.OnlineCollection
import com.yueyin.tv.core.data.online.OnlineCollectionKind
import com.yueyin.tv.core.data.online.OnlineDiscoverApi
import com.yueyin.tv.core.data.online.OnlineMusicApi
import com.yueyin.tv.core.data.online.OnlineMusicRepository
import com.yueyin.tv.core.data.online.OnlinePlatform
import com.yueyin.tv.core.data.online.OnlineSong
import com.yueyin.tv.core.data.online.OnlineText
import com.yueyin.tv.core.model.connect.ConnectType
import com.yueyin.tv.core.model.connect.ServerCapabilities
import com.yueyin.tv.core.model.stream.StreamUrlCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * 在线音乐协议：把五平台官方接口（酷我/网易/QQ/酷狗/咪咕）实现成 [MusicProtocol]，
 * 让"在线内容"和飞牛、Navidrome 一样作为一个平台接入现有界面。
 *
 * 与其它协议的关键差异：取链（把一条歌换成能播的直链）是异步的，而且要跑用户导入的
 * 音源脚本。而 [MusicProtocol.streamUrl] 是同步签名。解法是**列表预取**——
 * 在 [songs]/[playlistSongs]/[albumSongs]/[search] 返回歌曲时并行把播放链取好、缓存在内存里，
 * [streamUrl] 直接同步读缓存。这样点播放秒开，取链的异步性被藏在了列表加载阶段。
 *
 * 取链器 [resolveUrl] 由 App 层注入（因为它依赖 Android WebView 的 [com.yueyin.tv.source.OnlinePlaybackResolver]），
 * 这里只持有它的函数引用，避免 core:data 反向依赖 app 模块。
 *
 * id 编码规则（跨平台唯一的列表 key，同时也是还原 OnlineSong 的凭据）：
 *  - 歌曲：`${platform.key}:${song.id}`
 *  - 榜单：`${platform.key}:rank:${rank.id}`
 *  - 专辑：`${platform.key}:album:${album.id}`
 *  - 歌单：`${platform.key}:playlist:${playlist.id}`
 */
class OnlineMusicProtocol(
    private val resolveUrl: suspend (OnlineSong) -> String?,
    private val repository: OnlineMusicRepository = OnlineMusicRepository.shared,
    private val discover: OnlineDiscoverApi = OnlineDiscoverApi(OnlineMusicApi()),
    private val lyricProvider: (suspend (OnlineSong) -> String?)? = null,
    /**
     * 是否已导入至少一个音源脚本。在线音乐只是个壳，没导入任何脚本时，
     * 榜单 / 歌单 / 新歌 / 新碟 / 搜索这些「内置」内容一律不返回，UI 自然走空态，
     * 由「导入音源脚本」提示引导用户，而不是凭空塞一堆内置歌单。
     * 由 App 层注入（core 不反向依赖 app 的 MusicSourceRuntime）。
     */
    private val hasSources: () -> Boolean = { true },
    /**
     * 导入音源脚本的聚合搜索：在线搜索在官方 5 平台之外，把用户导入的脚本也当作数据源，
     * 对 5 个平台逐一调用脚本的 search 事件，把脚本返回的歌并入结果。由 App 层注入
     * （core 不反向依赖 app 的 OnlineScriptSearch / MusicSourceRuntime）。为 null 表示不启用。
     */
    private val scriptSearch: (suspend (String) -> List<OnlineSong>)? = null,
) : MusicProtocol {

    override val type: ConnectType = ConnectType.ONLINE

    // 在线内容没有服务端收藏接口，收藏走本地兜底
    override val supportsServerFavorites: Boolean = false

    // 播放链缓存统一交给带 TTL 的 StreamUrlCache：音源直链带签名/时效，
    // 永不过期的本地 Map 会让队列后半段整批失效（见 StreamUrlCache 注释）。

    private companion object {
        /**
         * 交给播放器前只认这个时限内取到的直链。
         *
         * 比 [StreamUrlCache.ttlMs] 更严格是刻意的：缓存里留着可以给「探活/续播」复用，
         * 但真正要交给 ExoPlayer 的那一条，越新越安全——脚本链的有效期常常只有一分钟级。
         */
        const val PLAYABLE_MAX_AGE_MS = 90_000L

        /**
         * 后台预取只温热队列最前面这几首。
         *
         * 以前一次取 20 首：批量取出来的链在同一时刻起步老化，播到后半段已经全部过期，
         * 却还躺在缓存里被当成可用地址（正是「下一曲也失败、点重试却能播」的来源）。
         * 现在只提前备 3 首，其余交给「播到时现场取链」，链始终是新鲜的。
         */
        const val PREFETCH_AHEAD = 3

        /** 搜索结果的专辑分区最多几张：电视上是横向一排，再多就要一直往右按。 */
        const val SEARCH_ALBUM_LIMIT = 12

        /** 专辑搜索的总超时。给得比歌曲短：它是附加分区，宁可少显示也不要让搜索变慢。 */
        const val SEARCH_ALBUM_TIMEOUT_MS = 4_500L
    }

    // 平台 + 歌曲 id → OnlineSong 的还原表，取链/歌词时用
    private val songIndex = ConcurrentHashMap<String, OnlineSong>()

    // 榜单/歌单/专辑 → 完整曲目，detail 时预取链并喂给 songIndex
    private val collectionCache = ConcurrentHashMap<String, List<OnlineSong>>()

    // 后台预取作用域：列表先返回（UI 立即出骨架→内容），取链在后台慢慢补，
    // 绝不阻塞列表加载。SupervisorJob 保证单条取链失败不影响其它。
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 当前这一轮后台预取。音源运行时是单个隐藏 WebView，取链严格串行：
    // 用户主动点播时必须先把预取停掉，否则点播要排在预取的那二十首后面干等。
    private var prefetchJob: Job? = null

    // ---------------------------------------------------------------- 连接

    override suspend fun ping(profile: ConnectProfile): Boolean = true

    override suspend fun login(profile: ConnectProfile): ServerCapabilities = ServerCapabilities(
        lyrics = false,
        favorites = false,
        playlists = true,
        musicVideos = false,
        radio = false,
        search = true,
    )

    // ---------------------------------------------------------------- 列表

    /**
     * 在线音乐没有"列出全部歌手"的接口，这里从发现页的新歌 / 新碟把歌手聚合成一个"热门歌手"墙：
     * 按歌手名去重、取一张代表封面。组合名（"A / B"）拆成单人，保证点进去按名搜索能命中。
     */
    override suspend fun artists(profile: ConnectProfile): List<RemoteArtist> {
        if (!hasSources()) return emptyList()
        val byName = LinkedHashMap<String, RemoteArtist>()
        fun addArtist(rawName: String, cover: String?) {
            val cleaned = OnlineText.cleanArtist(rawName)
            for (token in cleaned.split(" / ")) {
                val single = token.trim()
                if (single.isEmpty() || single in ARTIST_IGNORE) continue
                byName.putIfAbsent(
                    single,
                    RemoteArtist(
                        id = artistIdOf(single),
                        name = single,
                        coverId = cover?.takeIf { it.isNotBlank() },
                    ),
                )
                if (byName.size >= MAX_AGGREGATED_ARTISTS) return
            }
        }
        runCatching { discover.newSongs(72) }.getOrNull()?.items
            ?.forEach { addArtist(it.artist, it.coverUrl) }
        runCatching { discover.newAlbums(60) }.getOrNull()?.items
            ?.forEach { addArtist(it.artist, it.coverUrl) }
        return byName.values.toList()
    }

    override suspend fun albums(profile: ConnectProfile): List<RemoteAlbum> {
        if (!hasSources()) return emptyList()
        val part = runCatching { discover.newAlbums(48) }.getOrNull() ?: return emptyList()
        return part.items.map { album ->
            RemoteAlbum(
                id = albumIdOf(album.platform, album.id),
                name = album.name,
                artistName = album.artist,
                coverId = album.coverUrl,
                songCount = album.songCount,
            )
        }
    }

    override suspend fun songs(profile: ConnectProfile): List<RemoteSong> {
        if (!hasSources()) return emptyList()
        val part = runCatching { discover.newSongs(60) }.getOrNull() ?: return emptyList()
        val songs = part.items
        indexSongs(songs)
        prefetch(songs)
        return songs.map { it.toRemoteSong() }
    }

    override suspend fun playlists(profile: ConnectProfile): List<RemotePlaylist> {
        if (!hasSources()) return emptyList()
        val ranks = runCatching { discover.rankGroups() }.getOrNull()?.items.orEmpty()
        val pls = runCatching { discover.playlists(36) }.getOrNull()?.items.orEmpty()
        val result = mutableListOf<RemotePlaylist>()
        // 榜单在前（和发现页的信息层级一致：先看"现在什么在榜上"，再逛歌单）
        ranks.forEach { group ->
            group.ranks.forEach { rank ->
                result += RemotePlaylist(
                    id = rankIdOf(rank.platform, rank.id),
                    name = rank.name,
                    songCount = 0,
                    coverId = rank.coverUrl,
                )
            }
        }
        pls.forEach { playlist ->
            result += RemotePlaylist(
                id = playlistIdOf(playlist.platform, playlist.id),
                name = playlist.name,
                songCount = playlist.trackCount,
                coverId = playlist.coverUrl,
            )
        }
        return result
    }

    // ---------------------------------------------------------------- 详情

    override suspend fun playlistSongs(profile: ConnectProfile, playlistId: String): List<RemoteSong> {
        val songs = loadCollection(playlistId) ?: return emptyList()
        indexSongs(songs)
        prefetch(songs)
        return songs.map { it.toRemoteSong() }
    }

    override suspend fun albumSongs(profile: ConnectProfile, albumId: String): List<RemoteSong> {
        val songs = loadCollection(albumId) ?: return emptyList()
        indexSongs(songs)
        prefetch(songs)
        return songs.map { it.toRemoteSong() }
    }

    override suspend fun search(profile: ConnectProfile, query: String): List<RemoteSong> {
        if (!hasSources()) return emptyList()
        // 脚本搜索和后台预取抢同一块隐藏 WebView（单实例、操作串行）：
        // 上一轮列表的预取还在跑时开搜，两边会互相切换脚本上下文。
        // 预取只是"点了秒开"的优化，搜到歌后再点开照样会现场取链，所以这里直接让路。
        prefetchJob?.cancel()
        // 官方 5 平台 + 导入音源脚本并行聚合：脚本整轮自带预算，这里再兜一层 5 秒总超时。
        val songs = coroutineScope {
            val official = async(Dispatchers.IO) {
                runCatching { repository.search(query, useCache = true) }.getOrNull()?.songs.orEmpty()
            }
            val scripted = if (scriptSearch != null) {
                async(Dispatchers.IO) {
                    runCatching { withTimeout(5_000) { scriptSearch.invoke(query) } }.getOrElse { emptyList() }
                }
            } else {
                async { emptyList<OnlineSong>() }
            }
            val officialSongs = official.await()
            val scriptSongs = scripted.await()
            val merged = LinkedHashSet<String>()
            val out = ArrayList<OnlineSong>(officialSongs.size + scriptSongs.size)
            fun push(song: OnlineSong) {
                if (merged.add(song.key)) out += song
            }
            // 官方、脚本 1:1 交错，各自内部顺序不变（仍按相关度排）。
            // 不能把脚本结果整段追加在官方后面：官方一页上百首，脚本结果会沉到看不见的地方，
            // 那样"聚合"就等于没做。同 key 去重，脚本常会返回官方已有的同一首。
            val total = maxOf(officialSongs.size, scriptSongs.size)
            for (i in 0 until total) {
                officialSongs.getOrNull(i)?.let(::push)
                scriptSongs.getOrNull(i)?.let(::push)
            }
            out
        }
        if (songs.isEmpty()) return emptyList()
        indexSongs(songs)
        prefetch(songs)
        return songs.map { it.toRemoteSong() }
    }

    /**
     * 搜索专辑。
     *
     * 以前 `ProtocolLibrarySource.search()` 里这块是硬编码 `emptyList()`，
     * 两端的「专辑」分区因此永远不显示——不是 UI 没做，是根本没人喂数据。
     * 现在按专辑搜索接口取回来，id 仍用 `platform:album:id` 编码，
     * 点进去走的是现成的专辑详情链路（[albumSongs]），不用另写页面。
     */
    override suspend fun searchAlbums(profile: ConnectProfile, query: String): List<RemoteAlbum> {
        if (!hasSources()) return emptyList()
        val albums = runCatching {
            // 专辑只是个附加分区，不能拖住整个搜索：超时就直接当没有。
            withTimeout(SEARCH_ALBUM_TIMEOUT_MS) {
                repository.searchAlbums(query, limit = SEARCH_ALBUM_LIMIT)
            }
        }.getOrDefault(emptyList())
        return albums.map { album ->
            RemoteAlbum(
                id = albumIdOf(album.platform, album.id),
                name = album.name,
                artistName = album.artist,
                coverId = album.coverUrl.takeIf { it.isNotBlank() },
                songCount = album.songCount,
                releaseDate = album.publishDate,
            )
        }
    }

    /**
     * 歌手详情：在线没有歌手详情接口，按歌手名跨平台搜索，再筛出真正属于该歌手的歌。
     * id 里编码了歌手名（见 [artistIdOf]），这里还原出来当搜索词。
     */
    override suspend fun artistSongs(profile: ConnectProfile, artistId: String): List<RemoteSong> {
        if (!hasSources()) return emptyList()
        val name = parseArtistName(artistId)
        if (name.isBlank()) return emptyList()
        val result = runCatching { repository.search(name, useCache = true) }.getOrNull()
            ?: return emptyList()
        val songs = result.songs.filter { matchesArtist(it, name) }
        indexSongs(songs)
        prefetch(songs)
        return songs.map { it.toRemoteSong() }
    }

    /** 搜索结果里只保留歌手名里含 [name] 这一位的歌，避免同名歌名混入。 */
    private fun matchesArtist(song: OnlineSong, name: String): Boolean {
        val target = OnlineText.cleanArtist(name)
        if (target.isBlank()) return false
        return song.artist.split(" / ").any { it.trim() == target }
    }

    // ---------------------------------------------------------------- 播放 / 封面 / 歌词

    override fun streamUrl(profile: ConnectProfile, songId: String): String =
        // 队列构建时会同步问一次：只认足够新的链，老链宁可不给（返回空 → 播到时现场取）。
        StreamUrlCache.get(songId, PLAYABLE_MAX_AGE_MS).orEmpty()

    /**
     * 播放前取链：缓存命中直接返回；未命中则现场取链（suspend，可阻塞等待）。
     * 这是解决"点播放即出声"的关键——预取是尽力而为，这里兜底保证能播。
     */
    override suspend fun resolveStreamUrl(profile: ConnectProfile, songId: String): String {
        // 太老的链即便还在 TTL 内也重取：宁可多花 0.3 秒，也不要让播放器去连一条死链。
        StreamUrlCache.get(songId, PLAYABLE_MAX_AGE_MS)?.let { return it }
        // 用户真的要点播了：先把后台预取停掉，别让这一首要排在二十首之后。
        if (prefetchJob != null) {
            prefetchJob?.cancel()
            prefetchJob = null
            android.util.Log.i("YueyinOnline", "resolve $songId: 预取已取消，优先取这一首")
        }
        val song = songIndex[songId] ?: return ""
        val startedAt = System.currentTimeMillis()
        val url = runCatching { resolveUrl(song) }.getOrNull()
        android.util.Log.i(
            "YueyinOnline",
            "resolve $songId ${System.currentTimeMillis() - startedAt}ms ${if (url.isNullOrBlank()) "fail" else "ok"}",
        )
        if (!url.isNullOrBlank()) StreamUrlCache.put(songId, url)
        return url.orEmpty()
    }

    /** 收藏落库时把取链元数据交给上层持久化。 */
    override fun songOrNull(songId: String): OnlineSong? = songIndex[songId]

    /**
     * 冷启动重建：只补内存里没有的，别用落库的最小对象覆盖本轮刚搜到的完整结果
     * （完整结果里有更全的 qualities / coverUrl）。
     */
    override fun restoreSongs(songs: List<OnlineSong>) {
        val missing = songs.filter { !songIndex.containsKey(songIdOf(it.platform, it.id)) }
        if (missing.isNotEmpty()) indexSongs(missing)
    }

    override fun coverUrl(profile: ConnectProfile, coverId: String, size: Int): String = coverId

    override suspend fun lyrics(
        profile: ConnectProfile,
        songId: String,
        artist: String,
        title: String,
    ): String? {
        val song = songIndex[songId] ?: return null
        return lyricProvider?.invoke(song)
    }

    override suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean): Boolean = false

    // ---------------------------------------------------------------- 内部

    /** 从缓存/接口把一个榜单/专辑/歌单的曲目拿全。 */
    private suspend fun loadCollection(key: String): List<OnlineSong>? {
        collectionCache[key]?.let { return it }
        val parsed = parseCollectionKey(key) ?: return null
        val collection = runCatching {
            discover.collection(parsed.kind, parsed.platform, parsed.id, title = "", extra = parsed.extra)
        }.getOrNull() ?: return null
        collectionCache[key] = collection.songs
        return collection.songs
    }

    private fun indexSongs(songs: List<OnlineSong>) {
        songs.forEach { song ->
            val key = songIdOf(song.platform, song.id)
            songIndex[key] = song
        }
    }

    /** 后台预取播放链：fire-and-forget，不阻塞列表返回。失败只留空。 */
    private fun prefetch(songs: List<OnlineSong>) {
        if (songs.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = prefetchScope.launch {
            // 串行取链：音源运行时是单个隐藏 WebView，并发 resolve 会在 JS 侧排队，
            // 串行反而更稳、更容易命中已加载的脚本。只温热最前面几首，剩下的播到时现取。
            for (song in songs.take(PREFETCH_AHEAD)) {
                // 被用户点播取消后要立刻收手（runCatching 会把取消异常吞掉，这里显式判活）
                if (!isActive) return@launch
                val key = songIdOf(song.platform, song.id)
                if (StreamUrlCache.get(key, PLAYABLE_MAX_AGE_MS) != null) continue
                val url = runCatching { resolveUrl(song) }.getOrNull()
                if (!url.isNullOrBlank()) StreamUrlCache.put(key, url)
            }
        }
    }

    private fun OnlineSong.toRemoteSong(): RemoteSong {
        return RemoteSong(
            id = songIdOf(platform, id),
            title = name,
            artistName = artist,
            albumName = album,
            albumId = albumId.ifBlank { null },
            durationMs = durationSeconds.toLong() * 1000L,
            coverId = coverUrl.ifBlank { null },
        )
    }

    // ---------------------------------------------------------------- key 编码

    private fun songIdOf(platform: OnlinePlatform, id: String) = "${platform.key}:$id"

    private fun rankIdOf(platform: OnlinePlatform, id: String) = "${platform.key}:rank:$id"

    private fun albumIdOf(platform: OnlinePlatform, id: String) = "${platform.key}:album:$id"

    private fun playlistIdOf(platform: OnlinePlatform, id: String) = "${platform.key}:playlist:$id"

    // 在线没有真实歌手 id，把歌手名编码进 id，详情时再还原出来用于按名搜索。
    private val ARTIST_PREFIX = "online:artist:"
    private val MAX_AGGREGATED_ARTISTS = 90
    // 合辑 / 编译类名字不是单人歌手，凑进歌手墙只会刷屏，直接跳过。
    private val ARTIST_IGNORE = setOf("群星", "Various Artists")

    private fun artistIdOf(name: String) = "$ARTIST_PREFIX$name"
    private fun parseArtistName(artistId: String) = artistId.removePrefix(ARTIST_PREFIX)

    private data class CollectionRef(
        val kind: OnlineCollectionKind,
        val platform: OnlinePlatform,
        val id: String,
        val extra: String,
    )

    private fun parseCollectionKey(key: String): CollectionRef? {
        val parts = key.split(":")
        if (parts.size < 3) return null
        val platformKey = parts[0]
        val kindToken = parts[1]
        val id = parts.drop(2).joinToString(":")
        val platform = OnlinePlatform.fromKey(platformKey) ?: return null
        return when (kindToken) {
            "rank" -> CollectionRef(OnlineCollectionKind.Rank, platform, id, "")
            "album" -> CollectionRef(OnlineCollectionKind.Album, platform, id, "")
            "playlist" -> CollectionRef(OnlineCollectionKind.Playlist, platform, id, "")
            else -> null
        }
    }
}
