package com.yueyin.tv.phone

import android.content.Context
import com.yueyin.tv.core.model.Track

/**
 * 最近播放历史。
 *
 * 落在 SharedPreferences + 单条字符串，与搜索历史同一套取舍：它就是一小串「最近听了什么」，
 * 没有查询、没有跨表关联，也不需要跟账号走。为了它开一张 Room 表写迁移，成本远大于收益。
 *
 * 手机端独立记录，不改 core：电视端没有「最近播放」这个入口，也没有可挂的触屏横滑。
 * 记录时机由手机壳订阅播放状态、在 mediaId 变化时写入（见 [PhoneApp]）。
 */
internal object PhoneRecentlyPlayed {

    private const val PREFS = "phone_recently_played"
    private const val KEY = "tracks"
    private const val LIMIT = 50

    /**
     * 一条历史：只存「点一下就能再播」所需的字段，不存曲目全量。
     *
     * [platform] 是记录时所在数据源的标识（见 [phonePlatformKey]）：多平台共用一份历史时，
     * 必须能区分「这首是哪个库里的」，否则切平台后列表里出现一堆当前平台不存在的歌，
     * 点下去必然取链失败。[durationMs] 一并存下：在线音源取链要用它算 musicInfo，
     * 只给歌名和歌手会让部分脚本返回时长错误的音频流。
     */
    internal data class Entry(
        val guid: String,
        val title: String,
        val artist: String,
        val coverId: String?,
        val durationMs: Long,
        val platform: String,
        val playedAtMs: Long,
    )

    /** 读**当前平台**的历史，新的在前。其它平台的记录留在磁盘上，切回去还在。 */
    internal fun read(context: Context, platformKey: String): List<Entry> =
        readAll(context).filter { it.platform == platformKey }

    private fun readAll(context: Context): List<Entry> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.split('\u0001')
            ?.mapNotNull(::decode)
            .orEmpty()

    /**
     * 记一首，新的在前；同一首歌重复播只把它挪到最前，不重复插入。
     *
     * 其它平台的记录原样保留，只在**本平台内**做 50 条截断——
     * 否则在 A 平台听满 50 首再切到 B，A 的历史就被挤没了。
     *
     * @return 写入后当前平台的最新列表，方便调用方直接刷新。
     */
    internal fun record(
        context: Context,
        track: Track,
        platformKey: String,
    ): List<Entry> {
        val guid = track.guid.value
        if (guid.isBlank()) return read(context, platformKey)
        val all = readAll(context)
        val entry = Entry(
            guid = guid,
            title = track.title,
            artist = track.artistName.orEmpty(),
            coverId = track.coverId,
            durationMs = track.durationMs ?: 0L,
            platform = platformKey,
            playedAtMs = System.currentTimeMillis(),
        )
        val others = all.filter { it.platform != platformKey }
        val mine = (listOf(entry) + all.filter { it.platform == platformKey && it.guid != guid })
            .take(LIMIT)
        val next = others + mine
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, next.joinToString("\u0001", transform = ::encode))
            .apply()
        return mine
    }

    /** 把历史条目还原成可播曲目。字段够 `prepareQueue` 直接取链，不用先回服务端查一次。 */
    internal fun toTrackList(entries: List<Entry>): List<Track> = entries.map { entry ->
        Track(
            guid = com.yueyin.tv.core.model.TrackGuid(entry.guid),
            title = entry.title,
            artistName = entry.artist.takeIf { it.isNotBlank() },
            albumName = null,
            coverId = entry.coverId,
            durationMs = entry.durationMs.takeIf { it > 0L },
            isCue = false,
            isFavorite = false,
        )
    }

    private fun encode(entry: Entry): String = listOf(
        entry.guid,
        entry.title.replace("\\", "\\\\").replace("|", "\\|"),
        entry.artist.replace("\\", "\\\\").replace("|", "\\|"),
        entry.coverId.orEmpty(),
        entry.durationMs.toString(),
        entry.platform,
        entry.playedAtMs.toString(),
    ).joinToString("|")

    /**
     * 七个字段才对得上。旧版只有五个（没有 durationMs / platform），
     * 这里直接判无效丢弃——那些记录正是跨平台残留的来源，留着只会点一次失败一次。
     */
    private fun decode(raw: String): Entry? {
        val parts = splitEscaped(raw)
        if (parts.size != 7) return null
        return Entry(
            guid = parts[0],
            title = parts[1],
            artist = parts[2],
            coverId = parts[3].takeIf { it.isNotBlank() },
            durationMs = parts[4].toLongOrNull() ?: 0L,
            platform = parts[5],
            playedAtMs = parts[6].toLongOrNull() ?: return null,
        )
    }

    /**
     * 按未转义的 `|` 切分。
     *
     * 不能用 `String.split("|")`：歌名里出现 `|` 时 encode 会写成 `\|`，
     * 直接 split 会把它当成两个字段的边界，整条记录解析失败（旧版的隐患）。
     */
    private fun splitEscaped(raw: String): List<String> {
        val out = ArrayList<String>(7)
        val field = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                field.append(raw[i + 1])
                i += 2
                continue
            }
            if (ch == '|') {
                out.add(field.toString())
                field.setLength(0)
            } else {
                field.append(ch)
            }
            i++
        }
        out.add(field.toString())
        return out
    }
}
