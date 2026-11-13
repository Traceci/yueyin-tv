package com.yueyin.tv.core.data.protocol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yueyin.tv.core.data.connect.ConnectProfile
import com.yueyin.tv.core.data.local.AppDatabase
import com.yueyin.tv.core.data.local.LocalStore
import com.yueyin.tv.core.data.local.ProtocolSnapshotStore
import com.yueyin.tv.core.model.connect.ConnectType
import com.yueyin.tv.core.model.connect.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 协议平台「集合曲目磁盘快照」的模拟验证。
 *
 * 场景对应用户反馈：每次启动进歌单都要「正在加载…」。这里用真实的 `LocalStore`（Robolectric
 * 里的 Room）+ 一个假协议，模拟「进程重启」——新建一个 `ProtocolLibrarySource` 实例
 * （内存缓存必然为空），网络直接抛异常，看列表是否还能立刻出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProtocolSnapshotCacheTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var localStore: LocalStore
    private lateinit var snapshots: ProtocolSnapshotStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val profile = ConnectProfile(
        id = "p1",
        type = ConnectType.NAVIDROME,
        displayName = "测试平台",
        host = "http://127.0.0.1:4533",
    )
    private val namespace = "server-guid:user-guid|p:p1"

    @Before
    fun setUp() {
        context.deleteDatabase(AppDatabase.NAME)
        localStore = LocalStore(context)
        snapshots = ProtocolSnapshotStore(localStore)
    }

    @After
    fun tearDown() {
        localStore.database.close()
        context.deleteDatabase(AppDatabase.NAME)
    }

    @Test
    fun `冷启动时先读盘，网络不可用也能立刻出列表`() = runBlocking {
        // 第一次：联网拉取并落盘（等同于用户上一次打开这个歌单）
        val online = FakeProtocol(songs = listOf(song("a"), song("b"), song("c")))
        val first = newSource(online)
        assertEquals(3, first.playlistTracks("pl1", 1).total)
        assertEquals("首次拉取后应立即落盘", 3, snapshots.readSongs(namespace, "playlist-tracks", "pl1", TTL)?.size)
        awaitSnapshot("playlist-tracks", "pl1")

        // 模拟进程重启：新实例（内存缓存为空）+ 网络直接失败
        val offline = FakeProtocol(failWith = IllegalStateException("offline"))
        val second = newSource(offline)
        val page = second.playlistTracks("pl1", 1)

        assertEquals(3, page.total)
        assertEquals(listOf("a", "b", "c"), page.items.map { it.guid.value })
        // 分页切片仍然正确：盘里存的是全量，切页在内存里做（3 首 / 每页 2 首 → 第 2 页 1 首）
        assertEquals(1, second.playlistTracks("pl1", 2, pageSize = 2).items.size)
    }

    @Test
    fun `读盘命中后会在后台刷新，下次进页面看到最新数据`() = runBlocking {
        newSource(FakeProtocol(listOf(song("a")))).playlistTracks("pl1", 1)
        awaitSnapshot("playlist-tracks", "pl1")

        // 服务端此时已经多了一首
        val refreshed = FakeProtocol(listOf(song("a"), song("b")))
        val source = newSource(refreshed)

        // 第一次读：先给磁盘里的旧列表，不阻塞
        assertEquals(1, source.playlistTracks("pl1", 1).total)

        // 后台刷新把最新列表写回内存与磁盘
        awaitCondition("后台刷新未生效") {
            refreshed.playlistCalls > 0 &&
                source.playlistTracks("pl1", 1).total == 2 &&
                snapshots.readSongs(namespace, "playlist-tracks", "pl1", TTL)?.size == 2
        }
    }

    @Test
    fun `空结果不落盘，避免把歌单永久钉成空`() = runBlocking {
        newSource(FakeProtocol(emptyList())).playlistTracks("pl1", 1)
        // 给后台写盘留出时间，再确认确实没写
        delay(300)
        assertNull(snapshots.readSongs(namespace, "playlist-tracks", "pl1", TTL))
    }

    @Test
    fun `快照过了有效期就不再用，回落到网络`() = runBlocking {
        snapshots.writeSongs(namespace, "playlist-tracks", "pl1", listOf(song("a")))
        // 有效期内的读得到
        assertEquals(1, snapshots.readSongs(namespace, "playlist-tracks", "pl1", TTL)?.size)
        // 已过期（负的有效期等价于「任何一次写入都算过期」）
        assertNull(snapshots.readSongs(namespace, "playlist-tracks", "pl1", maxAgeMs = -1L))
    }

    @Test
    fun `不同平台的命名空间互不可见`() = runBlocking {
        snapshots.writeSongs("ns|p:platform-a", "playlist-tracks", "pl1", listOf(song("a")))
        assertNull(snapshots.readSongs("ns|p:platform-b", "playlist-tracks", "pl1", TTL))
        assertEquals(
            1,
            snapshots.readSongs("ns|p:platform-a", "playlist-tracks", "pl1", TTL)?.size,
        )
    }

    @Test
    fun `专辑与歌手曲目同样走磁盘快照`() = runBlocking {
        newSource(FakeProtocol(listOf(song("x")))).albumTracks("al1", 1)
        newSource(FakeProtocol(listOf(song("y")))).artistTracks("ar1", 1)
        awaitSnapshot("album-tracks", "al1")
        awaitSnapshot("artist-tracks", "ar1")

        // 重启 + 断网：三类都能从盘里读出来
        val offline = FakeProtocol(failWith = IllegalStateException("offline"))
        assertEquals(1, newSource(offline).albumTracks("al1", 1).total)
        assertEquals(1, newSource(offline).artistTracks("ar1", 1).total)
    }

    // ---------------------------------------------------------------- 辅助

    private fun newSource(protocol: MusicProtocol) = ProtocolLibrarySource(
        profile = profile,
        protocol = protocol,
        snapshotStore = snapshots,
        snapshotNamespace = { namespace },
        refreshScope = scope,
    )

    private fun song(id: String) = RemoteSong(
        id = id,
        title = "歌 $id",
        artistName = "歌手",
        albumName = "专辑",
        albumId = "al1",
        durationMs = 180_000L,
        coverId = "cover-$id",
    )

    /** 后台落盘是异步的，这里等它真的写完再断言。 */
    private suspend fun awaitSnapshot(kind: String, id: String) = awaitCondition("快照未落盘：$kind/$id") {
        snapshots.readSongs(namespace, kind, id, TTL) != null
    }

    private suspend fun awaitCondition(message: String, block: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (block()) return
            delay(20)
        }
        throw AssertionError("$message（等待 5 秒超时）")
    }

    /** 只在测试里用的假协议：能返回固定曲目，也能模拟断网。 */
    private class FakeProtocol(
        private val songs: List<RemoteSong> = emptyList(),
        private val failWith: Exception? = null,
    ) : MusicProtocol {
        var playlistCalls = 0
            private set

        override val type = ConnectType.NAVIDROME
        override val supportsServerFavorites = false

        override suspend fun ping(profile: ConnectProfile) = true
        override suspend fun login(profile: ConnectProfile) = ServerCapabilities()
        override suspend fun artists(profile: ConnectProfile) = emptyList<RemoteArtist>()
        override suspend fun albums(profile: ConnectProfile) = emptyList<RemoteAlbum>()
        override suspend fun songs(profile: ConnectProfile) = songs
        override suspend fun playlists(profile: ConnectProfile) = emptyList<RemotePlaylist>()
        override suspend fun search(profile: ConnectProfile, query: String) = emptyList<RemoteSong>()
        override fun streamUrl(profile: ConnectProfile, songId: String) = "http://127.0.0.1/$songId"
        override fun coverUrl(profile: ConnectProfile, coverId: String, size: Int) = coverId
        override suspend fun lyrics(
            profile: ConnectProfile,
            songId: String,
            artist: String,
            title: String,
        ): String? = null

        override suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean) = false

        override suspend fun playlistSongs(profile: ConnectProfile, playlistId: String): List<RemoteSong> {
            playlistCalls += 1
            failWith?.let { throw it }
            return songs
        }

        override suspend fun albumSongs(profile: ConnectProfile, albumId: String): List<RemoteSong> {
            failWith?.let { throw it }
            return songs
        }

        override suspend fun artistSongs(profile: ConnectProfile, artistId: String): List<RemoteSong> {
            failWith?.let { throw it }
            return songs
        }
    }

    private companion object {
        const val TTL = 24L * 60 * 60 * 1000
    }
}
