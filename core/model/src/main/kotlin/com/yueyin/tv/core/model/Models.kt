package com.yueyin.tv.core.model

@JvmInline value class ServerGuid(val value: String)
@JvmInline value class UserGuid(val value: String)
@JvmInline value class TrackGuid(val value: String)
@JvmInline value class CollectionGuid(val value: String)

data class ServerIdentity(
    val guid: ServerGuid,
    val name: String,
    val serverVersion: String,
    val mediaServerVersion: String,
)

data class User(
    val guid: UserGuid,
    val username: String,
    val nickname: String?,
)

data class Playlist(
    val guid: CollectionGuid,
    val name: String,
    val coverId: String?,
    val trackCount: Int? = null,
)

data class Artist(
    val guid: CollectionGuid,
    val name: String,
    val coverId: String?,
    val trackCount: Int? = null,
    val albumCount: Int? = null,
)

data class Album(
    val guid: CollectionGuid,
    val name: String,
    val artistName: String?,
    val coverId: String?,
    val trackCount: Int? = null,
    val releaseDate: String? = null,
)

data class Track(
    val guid: TrackGuid,
    val title: String,
    val artistName: String?,
    val albumName: String?,
    val coverId: String?,
    val durationMs: Long?,
    val isCue: Boolean,
    val accessStatus: Int? = null,
    val audioFormat: String? = null,
    val isFavorite: Boolean = false,
)

data class SharedLibrary(
    val guid: CollectionGuid,
    val name: String,
    val accessStatus: Int,
    val updatedAt: Long,
)

data class Page<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val sort: String,
) {
    val hasNext: Boolean get() = page * pageSize < total
}

data class LyricDocument(
    val guid: String,
    val content: String,
    val isLrc: Boolean,
    val offsetMs: Long,
)

data class PlaybackCredentials(
    val apiBase: String,
    val rawAuthorization: String,
    val cacheNamespace: String,
    val accessCodeHeader: String? = null,
    val relayMode: Boolean = false,
)

data class PlaybackTrack(
    val track: Track,
    val streamUrl: String,
    val artworkUrl: String?,
)

data class RoamNode(val roamId: String, val track: Track)
data class RoamWindow(val previous: RoamNode?, val current: RoamNode, val next: RoamNode?)

enum class PlayerStyle { Cover, Poster, Lyrics }

enum class CoverVariant(val width: Int?) {
    Compact(200),
    Grid(400),
    Player(800),
    /** 车机 / 高分屏：按实际显示像素取档时用到，服务端按 width 出图。 */
    Hd(1200),
    Ultra(1600),
    Poster(null);

    companion object {
        /**
         * 按「实际显示像素」挑一个不小于目标的档位，避免小图放大导致模糊。
         * 档位是离散的，天然起到缓存分桶作用（不会因像素级尺寸差异产生无限 key）。
         * [minimum] 是调用方指定的下限（例如播放页要求 Player），不会被更小的档位覆盖。
         */
        fun forDisplay(targetPx: Int, minimum: CoverVariant = Grid): CoverVariant {
            if (minimum == Poster) return Poster
            val finite = values().filter { it.width != null }
            val picked = finite.firstOrNull { it.width!! >= targetPx } ?: finite.last()
            return if (picked.width!! >= (minimum.width ?: Int.MAX_VALUE)) picked else minimum
        }
    }
}
