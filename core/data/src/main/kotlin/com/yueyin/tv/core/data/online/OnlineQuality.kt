// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.online

import kotlinx.serialization.json.JsonObject

/**
 * 音质分档推断。
 *
 * 各平台都用"某档有没有体积"来表示这一档能不能下：有 size 就是能下，没有就是平台没有。
 * 这套判断只用于**列表上的徽标**，不代表我们的音源真能取到那一档——
 * 取链时音源脚本会再校验一次，所以这里错判的代价只是徽标高一点，不会导致播放失败。
 *
 * 顺序约定：从高到低，和界面上的展示顺序一致。
 */
internal object OnlineQuality {
    private val ORDER = listOf("master", "atmos", "hires", "flac24bit", "flac", "320k", "128k")

    private val BITRATE_RE = Regex("bitrate:(\\d+)", RegexOption.IGNORE_CASE)
    private val FORMAT_RE = Regex("format:([^,]+)", RegexOption.IGNORE_CASE)
    private val SIZE_RE = Regex("size:([^,;]+)", RegexOption.IGNORE_CASE)

    fun sort(types: Collection<String>): List<String> = types
        .distinct()
        .sortedByDescending { type -> ORDER.indexOf(type).let { if (it < 0) 99 else it } }

    /**
     * 酷我：`N_MINFO` 长这样
     * `level:zpga201,bitrate:320,format:mp3,size:3.4M;level:zply,bitrate:2000,format:flac,size:28M`
     * size 为 `zpMb` 之类的占位表示该档拿不到，要跳过。
     */
    fun kuwo(minfo: String): List<String> {
        if (minfo.isBlank()) return emptyList()
        val found = linkedSetOf<String>()
        for (part in minfo.split(';')) {
            val bitrate = BITRATE_RE.find(part)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val format = FORMAT_RE.find(part)?.groupValues?.getOrNull(1)?.trim()?.lowercase().orEmpty()
            val size = SIZE_RE.find(part)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (size.isEmpty() || size.startsWith("0") || size.contains("zpmb", ignoreCase = true)) continue
            when {
                format.contains("flac") -> found += "flac"
                bitrate >= 320 -> found += "320k"
                bitrate >= 128 -> found += "128k"
            }
        }
        return sort(found)
    }

    /** 网易：优先看分档体积（hr/sq/h/l），没有就退回 privilege.maxbr */
    fun netEase(item: JsonObject): List<String> {
        val found = linkedSetOf<String>()
        fun hasSize(key: String): Boolean =
            (item[key] as? JsonObject)?.long("size") ?: 0L > 0L
        if (hasSize("hr")) found += "flac24bit"
        if (hasSize("sq")) found += "flac"
        if (hasSize("h")) found += "320k"
        if (hasSize("l")) found += "128k"
        if (found.isEmpty()) {
            val maxBr = item.obj("privilege")?.long("maxbr") ?: 0L
            when {
                maxBr >= 999_000L -> found += "flac"
                maxBr >= 320_000L -> found += "320k"
                maxBr >= 128_000L -> found += "128k"
            }
        }
        return sort(found)
    }

    /**
     * QQ：搜索接口给的是扁平的 `size128 / size320 / sizeflac`，
     * 详情接口给的是嵌套的 `file.size_128mp3`，两种都要认。
     */
    fun qq(item: JsonObject): List<String> {
        val file = item.obj("file")
        fun size(flat: String, nested: String): Long {
            val value = item.long(flat)
            if (value > 0L) return value
            return file?.long(nested) ?: 0L
        }
        val found = linkedSetOf<String>()
        if (size("sizehires", "size_hires") > 0L) found += "flac24bit"
        if (size("sizeflac", "size_flac") > 0L) found += "flac"
        if (size("size320", "size_320mp3") > 0L) found += "320k"
        if (size("size128", "size_128mp3") > 0L) found += "128k"
        return sort(found)
    }

    /**
     * 酷狗：搜索接口是 `FileSize / HQFileSize / SQFileSize / ResFileSize`，
     * 榜单/歌单接口把它们塞在 `audio_info` 里且换了名字。
     */
    fun kugou(item: JsonObject): List<String> {
        val audio = item.obj("audio_info")
        fun pick(flat: String, nested: String): Long {
            val value = item.long(flat)
            if (value > 0L) return value
            return audio?.long(nested) ?: 0L
        }
        val found = linkedSetOf<String>()
        if (pick("ResFileSize", "filesize_high") > 0L) found += "flac24bit"
        if (pick("SQFileSize", "filesize_flac") > 0L) found += "flac"
        if (pick("HQFileSize", "filesize_320") > 0L) found += "320k"
        if (pick("FileSize", "filesize") > 0L) found += "128k"
        return sort(found)
    }

    /** 咪咕：`rateFormats[]` 里 `formatType` = PQ/HQ/SQ/ZQ24 */
    fun migu(item: JsonObject): List<String> {
        val formats = item.arr("newRateFormats") ?: item.arr("rateFormats") ?: return emptyList()
        val found = linkedSetOf<String>()
        for (element in formats) {
            val node = element as? JsonObject ?: continue
            val size = node.long("asize").takeIf { it > 0L }
                ?: node.long("isize").takeIf { it > 0L }
                ?: node.long("fileSize").takeIf { it > 0L }
                ?: node.long("size")
            if (size <= 0L) continue
            when (node.firstNonEmpty("formatType", "format").uppercase()) {
                "ZQ24", "ZQ" -> found += "flac24bit"
                "SQ" -> found += "flac"
                "HQ" -> found += "320k"
                "PQ" -> found += "128k"
            }
        }
        return sort(found)
    }

    /** 一个平台一个入口，省得调用点写 when */
    fun of(platform: OnlinePlatform, item: JsonObject): List<String> = when (platform) {
        OnlinePlatform.Kuwo -> kuwo(item.firstNonEmpty("N_MINFO", "n_minfo"))
        OnlinePlatform.NetEase -> netEase(item)
        OnlinePlatform.QQ -> qq(item)
        OnlinePlatform.Kugou -> kugou(item)
        OnlinePlatform.Migu -> migu(item)
    }

    /** 封面兜底：接口一个图都不给时，列表上留空比塞一张假图好 */
    fun firstImage(vararg candidates: String?): String = candidates
        .asSequence()
        .mapNotNull { it?.trim() }
        .firstOrNull { it.startsWith("http") }
        .orEmpty()
}
