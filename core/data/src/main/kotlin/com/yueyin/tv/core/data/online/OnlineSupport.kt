// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.online

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 在线接口共用的低层设施：JSON 宽容解析、字段读取、文本清洗、HTTP 客户端。
 *
 * 这些平台接口有一个共同特点：**字段名和类型都不稳定**。
 * 同一个"歌手"字段，酷我叫 ARTIST、网易叫 ar[].name、QQ 叫 singer[].name、
 * 酷狗把它和歌名拼成一个 "歌手 - 歌名" 字符串、咪咕叫 singers[].name。
 * 类型上 QQ 的 id 有时是数字有时是字符串，酷我的时长有时带引号有时不带。
 * 所以这里的读取函数一律"尽力取值、绝不抛异常"，取不到就退化成空串或 0，
 * 让上层少写一堆 try/catch，也避免一个脏字段把整页结果吞掉。
 */

internal object OnlineJson {
    val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 解析"不太标准"的 JSON。
     *
     * 酷我返回的是单引号 JSON（`{'TOTAL':'3513'}`），但它和标准 JSON 一样以 `{` 开头，
     * **不能靠首字符判断格式**——先按原样解析，失败且正文里确实有单引号时再整体换引号重试。
     * QQ 等接口偶尔会在 JSON 前面挂一点前缀（JSONP / 注释），所以最后还会尝试
     * 从第一个 `{` 或 `[` 截到最后一个 `}` / `]` 再解析一次。
     */
    fun parseObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return null

        runCatching { parser.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()?.let { return it }

        if (trimmed.contains('\'')) {
            runCatching { parser.parseToJsonElement(trimmed.replace('\'', '"')) as? JsonObject }
                .getOrNull()
                ?.let { return it }
        }

        val start = trimmed.indexOfFirst { it == '{' || it == '[' }
        val end = trimmed.lastIndexOfAny(charArrayOf('}', ']'))
        if (start >= 0 && end > start) {
            val sliced = trimmed.substring(start, end + 1)
            runCatching { parser.parseToJsonElement(sliced) as? JsonObject }.getOrNull()?.let { return it }
            if (sliced.contains('\'')) {
                runCatching { parser.parseToJsonElement(sliced.replace('\'', '"')) as? JsonObject }
                    .getOrNull()
                    ?.let { return it }
            }
        }
        return null
    }
}

// ---------------------------------------------------------------- 字段读取

internal fun JsonObject.str(key: String): String = when (val value = this[key]) {
    null -> ""
    is JsonPrimitive -> value.content
    else -> ""
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.int(key: String): Int = str(key).trim().let {
    it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() ?: 0
}

internal fun JsonObject.long(key: String): Long = str(key).trim().let {
    it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() ?: 0L
}

internal fun JsonObject.bool(key: String): Boolean = str(key).equals("true", ignoreCase = true) ||
    str(key) == "1"

/** 按顺序取第一个非空字段，省掉一长串 `a.ifEmpty { b }.ifEmpty { c }` */
internal fun JsonObject.firstNonEmpty(vararg keys: String): String {
    for (key in keys) {
        val value = str(key).trim()
        if (value.isNotEmpty() && value != "null") return value
    }
    return ""
}

/** 数组里每个元素都是对象时，取出对象本身并丢掉非对象元素 */
internal fun JsonArray?.orEmptyArray(): List<JsonObject> =
    this?.mapNotNull { it as? JsonObject }.orEmpty()

/** 数组里每个元素都是对象时，取出其中某个字段并丢掉空值 */
internal fun JsonArray?.stringsOf(key: String): List<String> = this
    ?.mapNotNull { (it as? JsonObject)?.str(key)?.trim() }
    ?.filter { it.isNotEmpty() && it != "null" }
    .orEmpty()

// ---------------------------------------------------------------- 文本处理

internal object OnlineText {
    /**
     * 匹配被重复转义的 `\u0026` 这类序列。
     * 酷我实际会发两个反斜杠（`\\u0026`），只按一个匹配会剩下一根反斜杠留在歌手名里，
     * 所以反斜杠必须用 `+` 兜住一个或多个。
     */
    private val LITERAL_UNICODE_RE = Regex("\\\\+u([0-9a-fA-F]{4})")
    private val HTML_TAG_RE = Regex("<[^>]+>")
    private val ENTITY_RE = Regex("&(nbsp|amp|lt|gt|quot|#39|apos);")
    private val ARTIST_SPLIT_RE = Regex("\\s*[/;|、，,]\\s*")

    private fun unescapeLiteralUnicode(input: String): String =
        LITERAL_UNICODE_RE.replace(input) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }

    /** 去掉接口里混进来的 HTML 标签与实体，酷我/网易的歌名专辑名经常带 */
    fun cleanHtml(input: String): String {
        if (input.isEmpty()) return ""
        var text = unescapeLiteralUnicode(input)
        text = HTML_TAG_RE.replace(text, "")
        text = ENTITY_RE.replace(text) { match ->
            when (match.groupValues[1].lowercase()) {
                "nbsp" -> " "
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "#39", "apos" -> "'"
                else -> ""
            }
        }
        return text.trim()
    }

    /**
     * 歌手名。各个平台的分隔约定不一样：
     *  - 酷我：`&nbsp;` 只是名字内部的空格，真正分隔多个歌手的是 `&`（常被双重转义）
     *  - 酷狗：`filename` 里是 `歌手 - 歌名`，歌手之间用 `、`
     *  - 网易/QQ/咪咕：直接给数组，先 join 成 `/` 再走这里
     *
     * 顺序不能反：先还原转义，再把 `&` 当分隔符，否则 `&nbsp;` 会被一起切碎。
     */
    fun cleanArtist(input: String): String {
        if (input.isEmpty()) return ""
        var text = unescapeLiteralUnicode(input)
        text = text.replace("&amp;", "&")
        text = text.replace("&nbsp;", " ")
        text = text.replace("&", "/")
        return cleanHtml(text)
            .split(ARTIST_SPLIT_RE)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" / ")
    }

    /** 数组型歌手字段（网易 ar[]、QQ singer[]、咪咕 singers[]） */
    fun artistsFrom(array: JsonArray?, nameKey: String = "name"): String =
        cleanArtist(array.stringsOf(nameKey).joinToString("/"))

    /**
     * 酷狗这一类把歌名和歌手塞在一个字符串里的平台：`薛之谦 - 我好像在哪见过你`。
     * 返回 (歌手, 歌名)；格式不符时歌手留空、整串当歌名。
     */
    fun splitKugouTitle(raw: String): Pair<String, String> {
        val text = cleanHtml(raw)
        if (text.isEmpty()) return "" to ""
        val index = text.indexOf(" - ")
        if (index <= 0) return "" to text
        return cleanArtist(text.substring(0, index)) to text.substring(index + 3).trim()
    }

    /** 播放量：`21960010` → `2196万`，电视上没地方显示完整数字 */
    fun formatPlayCount(count: Long): String = when {
        count <= 0 -> ""
        count >= 100_000_000L -> trimZero(count / 100_000_000.0) + "亿"
        count >= 10_000L -> trimZero(count / 10_000.0) + "万"
        else -> count.toString()
    }

    private fun trimZero(value: Double): String {
        val text = String.format("%.1f", value)
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }

    /** 发布时间：接口给的是毫秒时间戳或 `2026-10-13`，统一成 `2026-10-13` */
    fun formatDate(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val millis = text.toLongOrNull()
        if (millis != null && millis > 1_000_000_000_000L) {
            val date = java.util.Date(millis)
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            return format.format(date)
        }
        if (millis != null && millis > 1_000_000_000L) {
            val date = java.util.Date(millis * 1000)
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            return format.format(date)
        }
        return text.take(10)
    }
}

// ---------------------------------------------------------------- HTTP

internal object OnlineHttp {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun get(
        client: OkHttpClient,
        url: String,
        ua: String = DESKTOP_UA,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
        referer?.let { builder.header("Referer", it) }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            // 这些接口声明 UTF-8 但 Content-Type 里常常不带 charset，
            // 直接读字节按 UTF-8 解，避免 OkHttp 退回 ISO-8859-1 把中文读成乱码。
            return response.body.bytes().toString(Charsets.UTF_8)
        }
    }

    fun postForm(
        client: OkHttpClient,
        url: String,
        body: String,
        ua: String = DESKTOP_UA,
        referer: String? = null,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .apply { referer?.let { header("Referer", it) } }
            .post(okhttp3.RequestBody.create(null, body.toByteArray(Charsets.UTF_8)))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.bytes().toString(Charsets.UTF_8)
        }
    }
}
