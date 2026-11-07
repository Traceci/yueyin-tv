// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.online

/**
 * 在线内容的统一入口：搜索 + 发现 + 详情。
 *
 * 缓存的目的不是省流量，而是电视端"输入即搜"会连着打十几次请求，
 * 退格/改字时常回到刚搜过的同一个词，命中缓存能让列表不闪；
 * 发现页同理——从详情页返回时不该整页重新加载一遍。
 */
class OnlineMusicRepository(
    private val api: OnlineMusicApi = OnlineMusicApi(),
    private val discover: OnlineDiscoverApi = OnlineDiscoverApi(api),
) {
    private val cache = object : LinkedHashMap<String, OnlineSearchResult>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OnlineSearchResult>): Boolean =
            size > MAX_CACHED_QUERIES
    }

    private val albumCache = object : LinkedHashMap<String, List<OnlineAlbum>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OnlineAlbum>>): Boolean =
            size > MAX_CACHED_QUERIES
    }

    /** 发现页缓存：只留最近一次，够覆盖"进去看一眼就返回" */
    @Volatile
    private var feedCache: Pair<Long, OnlineDiscoverFeed?> = 0L to null

    suspend fun search(
        keyword: String,
        platforms: List<OnlinePlatform> = OnlinePlatform.entries,
        page: Int = 1,
        limit: Int = 24,
        useCache: Boolean = true,
    ): OnlineSearchResult {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return OnlineSearchResult(emptyList())
        val key = "${trimmed.lowercase()}|${platforms.joinToString(",") { it.key }}|$page|$limit"
        if (useCache) {
            synchronized(cache) { cache[key] }?.let { return it }
        }
        val result = api.searchAll(trimmed, platforms, page, limit)
        // 全部平台都失败时不写缓存，否则一次网络抖动会被记成"这个词没结果"
        if (result.songs.isNotEmpty() || result.errors.isEmpty()) {
            synchronized(cache) { cache[key] = result }
        }
        return result
    }

    /**
     * 专辑搜索。和歌曲搜索分开缓存：同一句话的歌曲结果会被「输入即搜」反复读写，
     * 而专辑只在一次搜索里取一次，混在一张 LRU 里只会互相把对方挤掉。
     */
    suspend fun searchAlbums(
        keyword: String,
        page: Int = 1,
        limit: Int = 10,
        useCache: Boolean = true,
    ): List<OnlineAlbum> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val key = "${trimmed.lowercase()}|$page|$limit"
        if (useCache) synchronized(albumCache) { albumCache[key] }?.let { return it }
        val albums = api.searchAlbumsAll(trimmed, page = page, limit = limit)
        if (albums.isNotEmpty()) synchronized(albumCache) { albumCache[key] = albums }
        return albums
    }

    /**
     * 发现页。四个板块各自独立失败：
     * 榜单挂了不该导致新歌也看不见，所以这里把四份结果合成一个 feed，
     * 错误统一收在 [OnlineDiscoverFeed.errors] 里。
     */
    suspend fun discoverFeed(useCache: Boolean = true): OnlineDiscoverFeed {
        val cached = feedCache.second
        if (useCache && cached != null && cached.newSongs.isNotEmpty()) return cached

        val songs = discover.newSongs()
        val albums = discover.newAlbums()
        val ranks = discover.rankGroups()
        val playlists = discover.playlists()
        val feed = OnlineDiscoverFeed(
            newSongs = songs.items,
            newAlbums = albums.items,
            rankGroups = ranks.items,
            playlists = playlists.items,
            errors = (songs.errors + albums.errors + ranks.errors + playlists.errors)
                // 同一个平台在多个板块都挂时只报一次
                .distinctBy { it.platform },
        )
        if (feed.newSongs.isNotEmpty() || feed.newAlbums.isNotEmpty() ||
            feed.rankGroups.isNotEmpty() || feed.playlists.isNotEmpty()
        ) {
            feedCache = System.currentTimeMillis() to feed
        }
        return feed
    }

    /** 缓存超过 10 分钟就重新拉，运营位内容变化快 */
    fun invalidateFeedIfStale() {
        val (stamp, _) = feedCache
        if (System.currentTimeMillis() - stamp > FEED_TTL_MS) feedCache = 0L to null
    }

    suspend fun collection(
        kind: OnlineCollectionKind,
        platform: OnlinePlatform,
        id: String,
        title: String = "",
        extra: String = "",
    ): OnlineCollection = discover.collection(kind, platform, id, title, extra)

    /** 页面上要给用户看的"哪家挂了"的一句话说明 */
    fun describeErrors(result: OnlineSearchResult): String? {
        if (result.errors.isEmpty()) return null
        return result.errors.joinToString("；") { "${it.platform.label}：${it.message}" }
    }

    fun describeFeedErrors(feed: OnlineDiscoverFeed): String? {
        if (feed.errors.isEmpty()) return null
        return "已跳过 " + feed.errors.joinToString("、") { it.platform.label }
    }

    companion object {
        private const val MAX_CACHED_QUERIES = 12
        private const val FEED_TTL_MS = 10 * 60 * 1000L

        /** 全进程共用一个 OkHttp 连接池，避免反复建连接 */
        val shared: OnlineMusicRepository by lazy { OnlineMusicRepository() }
    }
}
