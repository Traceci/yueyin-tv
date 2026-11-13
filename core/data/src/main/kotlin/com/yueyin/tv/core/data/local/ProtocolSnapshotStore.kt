package com.yueyin.tv.core.data.local

import com.yueyin.tv.core.data.protocol.RemoteSong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 协议平台「集合曲目」的磁盘快照。
 *
 * 为什么需要它：飞牛链路的列表走 `MusicRepository.cachedPage`，本来就有磁盘缓存；
 * 而协议平台（WebDAV / SMB / Emby / Navidrome / 在线音源…）在 `MusicRepository` 里是
 * `protocolSource?.playlistTracks(...)` 直接 return，**完全绕过**那层缓存，
 * 只剩 `ProtocolLibrarySource` 里的几个内存 Map。进程一退出就空，下次进歌单必须重拉；
 * 在线音源还要跑一遍隐藏 WebView 的脚本（实测 8~10 秒），表现就是每次启动进歌单
 * 都卡在「正在加载…」。这里补的就是缺失的那层落盘。
 *
 * 落在既有的 `cache_index` 表上（`namespace + indexKey + payload + accessedAt`）：
 * 容量预算、LRU 淘汰、设置页的「清理缓存」全部现成可用，不需要加表、不需要迁移。
 *
 * namespace 由调用方带上平台维度（见 `MusicRepository.newProtocolSource`），
 * 不同平台的数据天然隔离——切平台后读不到上一个平台的列表，也就不会出现
 * 「点 A 平台的歌去 B 平台取链」这种必然失败的组合。
 *
 * 注意：**不缓存空结果**。空列表落在盘上会让人以为歌单真的空了，而且永远不会自我纠正。
 */
class ProtocolSnapshotStore(private val localStore: LocalStore) {

    /**
     * 落盘载荷自带写入时刻。
     *
     * 不能用 `cache_index.accessedAt` 判过期：`LocalStore.index()` 每次读都会
     * `touchIndex` 刷新它，读得越勤越快「永不过期」，TTL 形同虚设。
     */
    @Serializable
    private data class Payload(val savedAtMs: Long, val songs: List<RemoteSong>)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 读快照。[maxAgeMs] 之外视为过期，返回 null 让调用方回落到网络。 */
    suspend fun readSongs(
        namespace: String,
        kind: String,
        id: String,
        maxAgeMs: Long,
    ): List<RemoteSong>? {
        val entity = runCatching { localStore.index(namespace, keyOf(kind, id)) }.getOrNull() ?: return null
        val payload = runCatching { json.decodeFromString<Payload>(entity.payload) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - payload.savedAtMs > maxAgeMs) return null
        return payload.songs.takeIf(List<RemoteSong>::isNotEmpty)
    }

    /** 写快照。空列表直接跳过（见类注释）；任何异常都吞掉，缓存失败不该影响正常浏览。 */
    suspend fun writeSongs(
        namespace: String,
        kind: String,
        id: String,
        songs: List<RemoteSong>,
    ) {
        if (songs.isEmpty()) return
        val payload = runCatching {
            json.encodeToString(
                Payload.serializer(),
                Payload(savedAtMs = System.currentTimeMillis(), songs = songs),
            )
        }.getOrNull() ?: return
        runCatching {
            localStore.saveIndex(
                CachedIndexEntity(
                    namespace = namespace,
                    indexKey = keyOf(kind, id),
                    payload = payload,
                    accessedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun keyOf(kind: String, id: String) = "$kind|$id"
}
