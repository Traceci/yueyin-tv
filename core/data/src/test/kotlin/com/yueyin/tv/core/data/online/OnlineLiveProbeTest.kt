package com.yueyin.tv.core.data.online

import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 临时的联网实测（跑完就删）：确认五家搜索、四个发现板块、三种详情真的能出数据。
 * 这些断言依赖外网与平台状态，不适合留在常规测试里。
 */
class OnlineLiveProbeTest {
    @Test
    fun probe() = runBlocking {
        val repo = OnlineMusicRepository()

        println("===== 搜索 =====")
        for (platform in OnlinePlatform.entries) {
            val page = runCatching { OnlineMusicApi().search(platform, "晴天", limit = 5) }
            page.onSuccess { p ->
                println("${platform.label}: ${p.songs.size} 首, total=${p.total}")
                p.songs.take(2).forEach {
                    println("   ${it.name} | ${it.artist} | ${it.album} | ${it.durationText} | ${it.qualityText} | ${it.coverUrl.take(48)}")
                }
            }.onFailure { println("${platform.label}: FAIL ${it.message}") }
        }

        println("===== 发现页 =====")
        val feed = runCatching { repo.discoverFeed(useCache = false) }
        feed.onSuccess { f ->
            println("新歌 ${f.newSongs.size}: " + f.newSongs.groupBy { it.platform }.map { "${it.key.label}=${it.value.size}" })
            println("新碟 ${f.newAlbums.size}: " + f.newAlbums.groupBy { it.platform }.map { "${it.key.label}=${it.value.size}" })
            println("榜单组 ${f.rankGroups.size}")
            f.rankGroups.forEach { println("   组 ${it.platform.label}/${it.name} = ${it.ranks.size} 个榜") }
            f.rankGroups.flatMap { it.ranks }.take(6).forEach {
                println("   rank ${it.platform.label} ${it.id} ${it.name} preview=${it.preview}")
            }
            println("歌单 ${f.playlists.size}: " + f.playlists.groupBy { it.platform }.map { "${it.key.label}=${it.value.size}" })
            f.playlists.take(4).forEach {
                println("   pl ${it.platform.label} ${it.id} ${it.name} | ${it.author} | ${it.playCountText} | ${it.trackCount}")
            }
            f.newSongs.take(4).forEach {
                println("   新歌 ${it.platform.label} ${it.name} | ${it.artist} | ${it.album}")
            }
            f.newAlbums.take(4).forEach {
                println("   新碟 ${it.platform.label} ${it.id} ${it.name} | ${it.artist} | ${it.publishDate}")
            }
            println("错误: " + f.errors.map { "${it.platform.label}:${it.message}" })
        }.onFailure { println("FEED FAIL ${it.message}"); it.printStackTrace() }

        println("===== 详情 =====")
        val sample = feed.getOrNull()
        sample?.rankGroups?.forEach { group ->
            val rank = group.ranks.firstOrNull() ?: return@forEach
            val result = runCatching {
                repo.collection(OnlineCollectionKind.Rank, rank.platform, rank.id, rank.name)
            }
            result.onSuccess { c ->
                println("榜单 ${rank.platform.label}/${rank.name} → 《${c.title}》${c.songs.size} 首 (${c.subtitle})")
                c.songs.take(2).forEach { println("      ${it.name} | ${it.artist}") }
            }.onFailure { println("榜单 ${rank.platform.label}/${rank.name} FAIL ${it.message}") }
        }

        listOf(
            OnlineCollectionKind.Album to sample?.newAlbums?.firstOrNull(),
        ).forEach { (kind, album) ->
            album ?: return@forEach
            val result = runCatching {
                repo.collection(kind, album.platform, album.id, album.name)
            }
            result.onSuccess { c ->
                println("专辑 ${album.platform.label}/${album.name} → 《${c.title}》${c.songs.size} 首 (${c.subtitle})")
            }.onFailure { println("专辑 ${album.platform.label}/${album.name} FAIL ${it.message}") }
        }

        // 每个平台各取一张专辑验证
        for (platform in listOf(OnlinePlatform.Kuwo, OnlinePlatform.NetEase, OnlinePlatform.QQ, OnlinePlatform.Kugou)) {
            val album = sample?.newAlbums?.firstOrNull { it.platform == platform } ?: continue
            val result = runCatching { repo.collection(OnlineCollectionKind.Album, platform, album.id, album.name) }
            result.onSuccess { c ->
                println("专辑 ${platform.label} 首张 → 《${c.title}》${c.songs.size} 首 (${c.subtitle})")
            }.onFailure { println("专辑 ${platform.label} 首张 FAIL ${it.message}") }
        }

        for (playlist in sample?.playlists?.take(6).orEmpty()) {
            val result = runCatching {
                repo.collection(OnlineCollectionKind.Playlist, playlist.platform, playlist.id, playlist.name, playlist.extra)
            }
            result.onSuccess { c ->
                println("歌单 ${playlist.platform.label}/${playlist.name} → 《${c.title}》${c.songs.size} 首")
            }.onFailure { println("歌单 ${playlist.platform.label}/${playlist.name} FAIL ${it.message}") }
        }
    }
}
