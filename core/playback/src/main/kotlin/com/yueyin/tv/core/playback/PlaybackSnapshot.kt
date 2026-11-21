package com.yueyin.tv.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.yueyin.tv.core.model.RoamNode
import com.yueyin.tv.core.model.RoamWindow
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.model.TrackGuid
import com.yueyin.tv.core.model.playback.PlayMode
import com.yueyin.tv.core.model.playback.QueueKind
import com.yueyin.tv.core.model.playback.QueuePageItem
import com.yueyin.tv.core.model.playback.QueuePageSegment
import com.yueyin.tv.core.model.playback.QueueSource
import com.yueyin.tv.core.model.playback.SlidingQueueState
import org.json.JSONArray
import org.json.JSONObject

internal enum class PlaybackPlayIntent { Play, Pause }

internal data class PlaybackSnapshot(
    val generation: Long,
    val revision: Long,
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val source: QueueSource?,
    val window: SlidingQueueState?,
    val kind: QueueKind,
    val mode: PlayMode,
    val shuffleOrder: List<String>,
    val roamWindow: RoamWindow?,
    val currentRoamId: String?,
    val frozen: PlaybackSnapshot?,
    val playIntent: PlaybackPlayIntent,
    val legacy: Boolean = false,
)

internal object PlaybackSnapshotCodec {
    /**
     * 快照版本。
     *
     * v3 起**不再持久化在线直链**：音源直链带签名与时效，通常几分钟就死。
     * 旧实现把它原样写进 Room，下次启动恢复出来的是一整队死链——
     * 用户看到的就是「播放过的歌，退出再进就播放失败」。
     * 现在只存「这是哪一首」（mediaId + 标题/歌手/封面等元数据），uri 留空，
     * 恢复时按需重新取链；本地曲目的 content:// 仍然照存（那是稳定的）。
     */
    const val Version = 3
    private const val MinSupportedVersion = 2
    private const val MaxItems = 250

    fun encode(snapshot: PlaybackSnapshot): String = encodeSnapshot(snapshot, depth = 0).toString()

    fun decode(encoded: String, legacyFrozen: String? = null): PlaybackSnapshot? = runCatching {
        val active = decodeSnapshot(JSONObject(encoded), depth = 0)
        if (!active.legacy || legacyFrozen.isNullOrBlank()) return@runCatching active

        val frozen = decodeSnapshot(JSONObject(legacyFrozen), depth = 1)
        active.copy(
            kind = QueueKind.Roam,
            frozen = frozen.copy(kind = QueueKind.Normal, frozen = null),
        )
    }.getOrNull()

    private fun encodeSnapshot(snapshot: PlaybackSnapshot, depth: Int): JSONObject {
        require(depth <= 1) { "Playback snapshots may contain only one frozen queue" }
        val root = JSONObject()
            .put("version", Version)
            .put("generation", snapshot.generation)
            .put("revision", snapshot.revision)
            .put("kind", snapshot.kind.name)
            .put("mode", snapshot.mode.name)
            .put("items", JSONArray().also { items -> snapshot.items.forEach { items.put(encodeMediaItem(it)) } })
            .put("index", snapshot.index)
            .put("position", snapshot.positionMs)
            .put("playIntent", snapshot.playIntent.name)
            .put("shuffleOrder", JSONArray(snapshot.shuffleOrder))
            .put("roamId", snapshot.currentRoamId ?: JSONObject.NULL)
            .put("roamWindow", snapshot.roamWindow?.let(::encodeRoamWindow) ?: JSONObject.NULL)

        snapshot.source?.let { root.put("source", encodeSource(it)) }
        snapshot.window?.let { root.put("window", encodeWindow(it)) }
        snapshot.frozen?.let { root.put("frozen", encodeSnapshot(it.copy(frozen = null), depth + 1)) }
        return root
    }

    private fun decodeSnapshot(root: JSONObject, depth: Int): PlaybackSnapshot {
        require(depth <= 1) { "Playback snapshot nesting is too deep" }
        val legacy = !root.has("version")
        if (!legacy) {
            val version = root.getInt("version")
            require(version in MinSupportedVersion..Version) { "Unsupported playback snapshot version" }
        }

        val itemValues = root.getJSONArray("items")
        require(legacy || itemValues.length() <= MaxItems) { "Playback queue exceeds its active limit" }
        val itemCount = itemValues.length().coerceAtMost(MaxItems)
        val items = List(itemCount) { index -> decodeMediaItem(itemValues.getJSONObject(index)) }
        require(items.map(MediaItem::mediaId).distinct().size == items.size) { "Playback queue contains duplicate IDs" }

        val generation = if (legacy) root.optLong("generation", 0L).coerceAtLeast(0L) else {
            root.getLong("generation").also { require(it >= 0L) { "Playback generation must not be negative" } }
        }
        val revision = if (legacy) root.optLong("revision", 0L).coerceAtLeast(0L) else {
            root.getLong("revision").also { require(it >= 0L) { "Playback revision must not be negative" } }
        }
        val index = if (legacy) root.optInt("index", 0) else root.getInt("index")
        require(items.isNotEmpty() || index == 0) { "Empty playback queue has a non-zero index" }
        require(items.isEmpty() || index in items.indices) { "Playback index is outside the active queue" }
        val positionMs = if (legacy) root.optLong("position", 0L).coerceAtLeast(0L) else {
            root.getLong("position").also { require(it >= 0L) { "Playback position must not be negative" } }
        }

        val source = root.optJSONObject("source")?.let(::decodeSource)
        // Legacy window fields did not preserve filtered source positions. Restoring them as
        // exact segments would make subsequent previous/next paging skip or duplicate rows.
        val window = if (legacy) null else root.optJSONObject("window")?.let { value ->
            decodeWindow(value, source, items.map(MediaItem::mediaId), index)
        }
        val kind = root.enumValue<QueueKind>("kind").let { value ->
            if (legacy) value ?: QueueKind.Normal else requireNotNull(value) { "Unknown playback queue kind" }
        }
        val mode = root.enumValue<PlayMode>("mode").let { value ->
            if (legacy) value ?: PlayMode.ListRepeat else requireNotNull(value) { "Unknown playback mode" }
        }
        val shuffleOrder = if (legacy) {
            root.optJSONArray("shuffleOrder")?.strings().orEmpty()
        } else {
            root.getJSONArray("shuffleOrder").strings()
        }
        val roamWindow = if (legacy) null else root.optNullableObject("roamWindow")?.let(::decodeRoamWindow)
        val currentRoamId = root.optNullableString("roamId")
        val frozen = if (legacy) null else root.optJSONObject("frozen")?.let { decodeSnapshot(it, depth + 1) }
        val playIntent = root.enumValue<PlaybackPlayIntent>("playIntent").let { value ->
            if (legacy) value ?: PlaybackPlayIntent.Pause else requireNotNull(value) { "Unknown playback intent" }
        }

        if (!legacy) {
            if (kind == QueueKind.Normal) {
                if (mode == PlayMode.Shuffle) {
                    require(isCompleteIdOrder(items.map(MediaItem::mediaId), shuffleOrder)) {
                        "Shuffle order does not cover the active queue"
                    }
                } else {
                    require(shuffleOrder.isEmpty()) { "A non-shuffle queue contains a shuffle order" }
                }
            }
            require(kind != QueueKind.Roam || roamWindow == null || currentRoamId == roamWindow.current.roamId) {
                "Roam cursor does not match its window"
            }
            require(frozen == null || frozen.kind == QueueKind.Normal) { "Frozen queue must be normal playback" }
            require(kind == QueueKind.Roam || frozen == null) { "Only roam playback may contain a frozen queue" }
        }

        return PlaybackSnapshot(
            generation = generation,
            revision = revision,
            items = items,
            index = index,
            positionMs = positionMs,
            source = source,
            window = window,
            kind = kind,
            mode = mode,
            shuffleOrder = shuffleOrder,
            roamWindow = roamWindow,
            currentRoamId = currentRoamId,
            frozen = frozen,
            playIntent = playIntent,
            legacy = legacy,
        )
    }

    private fun encodeMediaItem(item: MediaItem): JSONObject = JSONObject()
        .put("id", item.mediaId)
        .put("uri", persistableUriOf(item))
        .put("title", item.mediaMetadata.title?.toString())
        .put("artist", item.mediaMetadata.artist?.toString())
        .put("album", item.mediaMetadata.albumTitle?.toString())
        .put("format", item.mediaMetadata.extras?.getString(AUDIO_FORMAT_KEY))
        .put("coverId", item.mediaMetadata.extras?.getString(COVER_ID_KEY))
        .put(
            "durationMs",
            item.mediaMetadata.extras
                ?.takeIf { it.containsKey(DECLARED_DURATION_MS_KEY) }
                ?.getLong(DECLARED_DURATION_MS_KEY),
        )
        .put("art", item.mediaMetadata.artworkUri?.toString())

    /**
     * 只把稳定的本地地址写进快照。
     *
     * http(s) 是在线音源现取的直链，带签名与时效（多数只有几分钟），
     * 落盘再恢复出来必然是死链，所以一律不存——恢复时留空 uri，由播放链路重新取。
     * content:// / file:// 指向本机文件，稳定，照常持久化。
     */
    private fun persistableUriOf(item: MediaItem): String {
        val uri = item.localConfiguration?.uri ?: return ""
        val scheme = uri.scheme?.lowercase().orEmpty()
        return if (scheme == "http" || scheme == "https") "" else uri.toString()
    }

    private fun decodeMediaItem(value: JSONObject): MediaItem {
        val id = value.getString("id")
        require(id.isNotBlank()) { "Playback item is missing its ID" }
        // v3 起在线曲目的 uri 允许为空：那是「还没取链」，不是坏数据。
        val uri = value.optNullableString("uri").orEmpty()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(value.optNullableString("title"))
                    .setArtist(value.optNullableString("artist"))
                    .setAlbumTitle(value.optNullableString("album"))
                    .setArtworkUri(value.optNullableString("art")?.let(Uri::parse))
                    .setExtras(
                        mediaExtras(
                            value.optNullableString("format"),
                            value.optNullableString("coverId"),
                            value.optNullableLong("durationMs"),
                        ),
                    )
                    .build(),
            )
            .build()
    }

    private fun encodeWindow(window: SlidingQueueState): JSONObject = JSONObject()
        .put(
            "segments",
            JSONArray().also { values -> window.segments.forEach { values.put(encodeSegment(it)) } },
        )

    private fun decodeWindow(
        value: JSONObject,
        source: QueueSource?,
        itemIds: List<String>,
        currentIndex: Int,
    ): SlidingQueueState {
        requireNotNull(source) { "A paging window requires a queue source" }
        val segmentValues = value.getJSONArray("segments")
        require(segmentValues.length() > 0) { "A paging window requires exact segments" }
        val segments = List(segmentValues.length()) { index -> decodeSegment(segmentValues.getJSONObject(index)) }
        require(segments.all { it.sort == source.sort }) { "Queue source and segments use different sort orders" }
        val window = SlidingQueueState.fromSegments(segments, currentIndex)
        require(window.guids == itemIds) { "Queue segments do not match the active Media3 queue" }
        return window
    }

    private fun encodeSegment(segment: QueuePageSegment): JSONObject = JSONObject()
        .put("page", segment.page)
        .put("rawRowCount", segment.rawRowCount)
        .put("sort", segment.sort)
        .put("knownTotal", segment.knownTotal ?: JSONObject.NULL)
        .put("pageSize", segment.pageSize)
        .put("sourceStartIndex", segment.sourceStartIndex)
        .put(
            "playableItems",
            JSONArray().also { values ->
                segment.playableItems.forEach { item ->
                    values.put(
                        JSONObject()
                            .put("id", item.mediaId)
                            .put("sourceAbsoluteIndex", item.sourceAbsoluteIndex),
                    )
                }
            },
        )

    private fun decodeSegment(value: JSONObject): QueuePageSegment {
        val playableValues = value.getJSONArray("playableItems")
        return QueuePageSegment(
            page = value.getInt("page"),
            rawRowCount = value.getInt("rawRowCount"),
            playableItems = List(playableValues.length()) { index ->
                playableValues.getJSONObject(index).let { item ->
                    QueuePageItem(
                        mediaId = item.getString("id"),
                        sourceAbsoluteIndex = item.getInt("sourceAbsoluteIndex"),
                    )
                }
            },
            sort = value.getString("sort"),
            knownTotal = value.optNullableInt("knownTotal"),
            pageSize = value.getInt("pageSize"),
            sourceStartIndex = value.getInt("sourceStartIndex"),
        )
    }

    private fun encodeRoamWindow(window: RoamWindow): JSONObject = JSONObject()
        .put("previous", window.previous?.let(::encodeRoamNode) ?: JSONObject.NULL)
        .put("current", encodeRoamNode(window.current))
        .put("next", window.next?.let(::encodeRoamNode) ?: JSONObject.NULL)

    private fun decodeRoamWindow(value: JSONObject): RoamWindow = RoamWindow(
        previous = value.optNullableObject("previous")?.let(::decodeRoamNode),
        current = decodeRoamNode(value.getJSONObject("current")),
        next = value.optNullableObject("next")?.let(::decodeRoamNode),
    )

    private fun encodeRoamNode(node: RoamNode): JSONObject = JSONObject()
        .put("roamId", node.roamId)
        .put("track", encodeTrack(node.track))

    private fun decodeRoamNode(value: JSONObject): RoamNode = RoamNode(
        roamId = value.getString("roamId").also { require(it.isNotBlank()) },
        track = decodeTrack(value.getJSONObject("track")),
    )

    private fun encodeTrack(track: Track): JSONObject = JSONObject()
        .put("guid", track.guid.value)
        .put("title", track.title)
        .put("artist", track.artistName ?: JSONObject.NULL)
        .put("album", track.albumName ?: JSONObject.NULL)
        .put("coverId", track.coverId ?: JSONObject.NULL)
        .put("durationMs", track.durationMs ?: JSONObject.NULL)
        .put("isCue", track.isCue)
        .put("accessStatus", track.accessStatus ?: JSONObject.NULL)
        .put("audioFormat", track.audioFormat ?: JSONObject.NULL)

    private fun decodeTrack(value: JSONObject): Track = Track(
        guid = TrackGuid(value.getString("guid").also { require(it.isNotBlank()) }),
        title = value.getString("title"),
        artistName = value.optNullableString("artist"),
        albumName = value.optNullableString("album"),
        coverId = value.optNullableString("coverId"),
        durationMs = value.optNullableLong("durationMs"),
        isCue = value.getBoolean("isCue"),
        accessStatus = value.optNullableInt("accessStatus"),
        audioFormat = value.optNullableString("audioFormat"),
    )

    private fun encodeSource(source: QueueSource): JSONObject = JSONObject()
        .put(
            "kind",
            when (source) {
                is QueueSource.Playlist -> "playlist"
                is QueueSource.Artist -> "artist"
                is QueueSource.Album -> "album"
                is QueueSource.LibraryAllTracks -> "all"
                is QueueSource.Favorites -> "favorites"
            },
        )
        .put(
            "guid",
            when (source) {
                is QueueSource.Playlist -> source.guid
                is QueueSource.Artist -> source.guid
                is QueueSource.Album -> source.guid
                is QueueSource.LibraryAllTracks -> ""
                is QueueSource.Favorites -> ""
            },
        )
        .put("sort", source.sort)

    private fun decodeSource(value: JSONObject): QueueSource {
        val sort = value.getString("sort")
        val guid = value.optString("guid")
        return when (value.getString("kind")) {
            "playlist" -> QueueSource.Playlist(guid.also { require(it.isNotBlank()) }, sort)
            "artist" -> QueueSource.Artist(guid.also { require(it.isNotBlank()) }, sort)
            "album" -> QueueSource.Album(guid.also { require(it.isNotBlank()) }, sort)
            "all" -> QueueSource.LibraryAllTracks(sort)
            "favorites" -> QueueSource.Favorites(sort)
            else -> error("Unknown playback queue source")
        }
    }

    private fun mediaExtras(audioFormat: String?, coverId: String?, durationMs: Long?): Bundle = Bundle().apply {
        audioFormat?.takeIf(String::isNotBlank)?.let { putString(AUDIO_FORMAT_KEY, it) }
        coverId?.takeIf(String::isNotBlank)?.let { putString(COVER_ID_KEY, it) }
        durationMs?.takeIf { it > 0L }?.let { putLong(DECLARED_DURATION_MS_KEY, it) }
    }

    private fun isCompleteIdOrder(canonicalIds: List<String>, orderedIds: List<String>): Boolean =
        canonicalIds.size == orderedIds.size &&
            canonicalIds.distinct().size == canonicalIds.size &&
            orderedIds.distinct().size == orderedIds.size &&
            canonicalIds.toSet() == orderedIds.toSet()

    private inline fun <reified T : Enum<T>> JSONObject.enumValue(key: String): T? =
        optNullableString(key)?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

    private fun JSONObject.optNullableObject(key: String): JSONObject? =
        takeIf { has(key) && !isNull(key) }?.getJSONObject(key)

    private fun JSONObject.optNullableString(key: String): String? =
        takeIf { has(key) && !isNull(key) }?.getString(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        takeIf { has(key) && !isNull(key) }?.getInt(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        takeIf { has(key) && !isNull(key) }?.getLong(key)

    private fun JSONArray.strings(): List<String> = List(length()) { index -> getString(index) }
}
