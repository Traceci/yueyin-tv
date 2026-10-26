package com.fnmusic.tv.core.data.protocol

import android.net.Uri
import com.fnmusic.tv.core.data.connect.ConnectProfile
import com.fnmusic.tv.core.model.connect.ConnectType
import com.fnmusic.tv.core.model.connect.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebDAV / Alist 协议。
 *
 * WebDAV 没有「媒体库」概念，只有一个文件树，所以这里的做法是：
 * 1. 用 `PROPFIND`（Depth: 1）**广度优先**扫目录，只认音频扩展名，限深度/限条数/限时长，
 *    扫完在内存里建一份索引（10 分钟有效）；
 * 2. 用目录层级推断元数据：`歌手/专辑/01 - 歌名.flac`；层级不够时退化为
 *    `专辑/歌名` 或只用文件名；
 * 3. 封面取同目录的 `cover/folder/front/albumart.jpg`；歌词取同名 `.lrc`；
 * 4. 取流与封面都走 [LocalStreamProxy]——Media3 / Coil 不能带 Basic 认证头，
 *    代理在本地转发时补上 `Authorization`。
 */
class WebDavProtocol(
    override val type: ConnectType = ConnectType.WEBDAV,
    private val client: OkHttpClient = defaultClient(),
) : MusicProtocol {

    private val indexes = ConcurrentHashMap<String, DavIndex>()

    // ---------------------------------------------------------------- 登录

    override suspend fun ping(profile: ConnectProfile): Boolean = withContext(Dispatchers.IO) {
        runCatching { propfind(profile, baseOf(profile) + "/", depth = "0") != null }.getOrDefault(false)
    }

    override suspend fun login(profile: ConnectProfile): ServerCapabilities = withContext(Dispatchers.IO) {
        val reachable = runCatching { propfind(profile, baseOf(profile) + "/", depth = "0") != null }
            .getOrDefault(false)
        if (!reachable) return@withContext unsupportedCaps()
        // 登录成功顺手把索引建起来：文件协议没有服务端搜索，索引是搜索/浏览的前提
        runCatching { indexOf(profile) }
        ServerCapabilities(
            lyrics = true,
            favorites = false,
            playlists = false,
            musicVideos = false,
            radio = false,
            search = true,
        )
    }

    // ---------------------------------------------------------------- 列表

    override suspend fun artists(profile: ConnectProfile): List<RemoteArtist> = withContext(Dispatchers.IO) {
        runCatching {
            indexOf(profile).files
                .groupBy { it.artist }
                .filterKeys { it.isNotBlank() }
                .map { (artist, files) ->
                    RemoteArtist(id = artist, name = artist, coverId = files.firstNotNullOfOrNull { it.coverPath })
                }
        }.getOrDefault(emptyList())
    }

    override suspend fun albums(profile: ConnectProfile): List<RemoteAlbum> = withContext(Dispatchers.IO) {
        runCatching {
            indexOf(profile).files
                .groupBy { albumKeyOf(it.artist, it.album) }
                .map { (key, files) ->
                    val first = files.first()
                    RemoteAlbum(
                        id = key,
                        name = first.album.ifBlank { "未知专辑" },
                        artistName = first.artist,
                        coverId = files.firstNotNullOfOrNull { it.coverPath },
                        songCount = files.size,
                    )
                }
        }.getOrDefault(emptyList())
    }

    override suspend fun songs(profile: ConnectProfile): List<RemoteSong> = withContext(Dispatchers.IO) {
        runCatching { indexOf(profile).files.map { it.toSong() } }.getOrDefault(emptyList())
    }

    override suspend fun playlists(profile: ConnectProfile): List<RemotePlaylist> = emptyList()

    override suspend fun playlistSongs(profile: ConnectProfile, playlistId: String): List<RemoteSong> = emptyList()

    override suspend fun albumSongs(profile: ConnectProfile, albumId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                indexOf(profile).files.filter { albumKeyOf(it.artist, it.album) == albumId }.map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistSongs(profile: ConnectProfile, artistId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                indexOf(profile).files.filter { it.artist == artistId }.map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistAlbums(profile: ConnectProfile, artistId: String): List<RemoteAlbum> =
        withContext(Dispatchers.IO) {
            runCatching {
                indexOf(profile).files
                    .filter { it.artist == artistId }
                    .groupBy { albumKeyOf(it.artist, it.album) }
                    .map { (key, files) ->
                        val first = files.first()
                        RemoteAlbum(
                            id = key,
                            name = first.album.ifBlank { "未知专辑" },
                            artistName = first.artist,
                            coverId = files.firstNotNullOfOrNull { it.coverPath },
                            songCount = files.size,
                        )
                    }
            }.getOrDefault(emptyList())
        }

    override suspend fun search(profile: ConnectProfile, query: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            runCatching {
                indexOf(profile).files
                    .filter {
                        it.title.contains(query, ignoreCase = true) ||
                            it.artist.contains(query, ignoreCase = true) ||
                            it.album.contains(query, ignoreCase = true)
                    }
                    .take(200)
                    .map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    // ---------------------------------------------------------------- 播放 / 封面 / 歌词

    override fun streamUrl(profile: ConnectProfile, songId: String): String {
        val base = baseOf(profile)
        return LocalStreamProxy.urlOf(
            LocalStreamProxy.Upstream.Http(
                url = "$base/${encodePath(songId)}",
                headers = authHeaders(profile),
            ),
        )
    }

    override fun coverUrl(profile: ConnectProfile, coverId: String, size: Int): String {
        if (coverId.isBlank()) return ""
        val base = baseOf(profile)
        return LocalStreamProxy.urlOf(
            LocalStreamProxy.Upstream.Http(
                url = "$base/${encodePath(coverId)}",
                headers = authHeaders(profile),
            ),
        )
    }

    /** 同目录下的同名 `.lrc`：文件协议里这是唯一可靠的歌词来源。 */
    override suspend fun lyrics(
        profile: ConnectProfile,
        songId: String,
        artist: String,
        title: String,
    ): String? = withContext(Dispatchers.IO) {
        val lrcPath = songId.substringBeforeLast('.', songId) + ".lrc"
        runCatching {
            val request = Request.Builder()
                .url("${baseOf(profile)}/${encodePath(lrcPath)}")
                .apply { authHeaders(profile).forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            client.cancellableCall(request).use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    override suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean): Boolean = false

    // ---------------------------------------------------------------- 索引

    private data class DavFile(
        val path: String,
        val title: String,
        val artist: String,
        val album: String,
        val trackNumber: Int,
        val coverPath: String?,
    ) {
        fun toSong() = RemoteSong(
            id = path,
            title = title,
            artistName = artist,
            albumName = album,
            albumId = albumKeyOf(artist, album),
            durationMs = 0L,
            coverId = coverPath,
            trackNumber = trackNumber,
        )
    }

    private data class DavIndex(val files: List<DavFile>, val builtAt: Long)

    private suspend fun indexOf(profile: ConnectProfile): DavIndex {
        val key = baseOf(profile) + "|" + profile.username
        val cached = indexes[key]
        if (cached != null && System.currentTimeMillis() - cached.builtAt < INDEX_TTL_MS) return cached
        val built = DavIndex(buildIndex(profile), System.currentTimeMillis())
        indexes[key] = built
        return built
    }

    /**
     * 广度优先扫目录。三条硬限制（深度 / 目录数 / 耗时）是为了不在超大曲库上把登录卡死：
     * 宁可索引不全，也不能让用户进不去。
     */
    private suspend fun buildIndex(profile: ConnectProfile): List<DavFile> {
        val base = baseOf(profile)
        val startedAt = System.currentTimeMillis()
        val queue = ArrayDeque<String>()
        queue.add("")
        val visited = HashSet<String>()
        val audio = mutableListOf<RawDavFile>()
        val covers = HashMap<String, String>()
        var directoryCount = 0

        while (queue.isNotEmpty()) {
            if (directoryCount++ > MAX_DIRECTORIES) break
            if (System.currentTimeMillis() - startedAt > INDEX_BUDGET_MS) break
            val dir = queue.removeFirst()
            if (!visited.add(dir)) continue
            if (dir.count { it == '/' } >= MAX_DEPTH) continue

            val entries = runCatching { propfind(profile, "$base/${encodePath(dir)}", depth = "1") }
                .getOrNull()
                ?: continue
            for (entry in entries) {
                if (entry.path == dir) continue
                if (entry.isDirectory) {
                    queue.add(entry.path)
                } else if (entry.path.isAudioFile()) {
                    audio.add(RawDavFile(entry.path, entry.size))
                } else if (entry.path.isCoverImage()) {
                    val parent = entry.path.substringBeforeLast('/', "")
                    if (parent !in covers) covers[parent] = entry.path
                }
            }
        }

        return audio.map { file ->
            val segments = file.path.split('/')
            val fileName = segments.lastOrNull().orEmpty()
            val dirSegments = segments.dropLast(1)
            val album = dirSegments.lastOrNull().orEmpty()
            val artist = when {
                dirSegments.size >= 2 -> dirSegments[dirSegments.size - 2]
                else -> ""
            }
            val parentPath = dirSegments.joinToString("/")
            val (trackNumber, title) = parseTrackTitle(fileName)
            DavFile(
                path = file.path,
                title = title,
                artist = artist,
                album = album,
                trackNumber = trackNumber,
                coverPath = covers[parentPath],
            )
        }.sortedWith(compareBy({ it.artist }, { it.album }, { it.trackNumber }, { it.title }))
    }

    private data class RawDavFile(val path: String, val size: Long)

    // ---------------------------------------------------------------- WebDAV 请求

    private data class DavEntry(val path: String, val isDirectory: Boolean, val size: Long)

    /** 返回相对根路径的条目（含自身）；`depth` 为 "0" 时只查自身。 */
    private suspend fun propfind(profile: ConnectProfile, url: String, depth: String): List<DavEntry>? {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth)
            .apply { authHeaders(profile).forEach { (k, v) -> header(k, v) } }
            .build()
        client.cancellableCall(request).use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            return parseMultiStatus(body, rootPathOf(profile))
        }
    }

    /**
     * 解析 `multistatus`。命名空间前缀各家不同（`D:` / `d:` / `lp1:`），
     * 所以一律按「本地名」比较，不依赖前缀。
     */
    private fun parseMultiStatus(xml: String, rootPath: String): List<DavEntry> {
        if (xml.isBlank()) return emptyList()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val result = mutableListOf<DavEntry>()
        var href: String? = null
        var isCollection = false
        var length = 0L
        var insideResponse = false
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name.localName()) {
                        "response" -> {
                            insideResponse = true
                            href = null
                            isCollection = false
                            length = 0L
                        }
                        "collection" -> if (insideResponse) isCollection = true
                        "getcontentlength" -> if (insideResponse) {
                            length = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                        "href" -> if (insideResponse) href = parser.nextText()
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (parser.name.localName() == "response") {
                        insideResponse = false
                        href?.let { raw ->
                            val relative = normaliseHref(raw, rootPath)
                            if (relative != null) result.add(DavEntry(relative, isCollection, length))
                        }
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun String.localName(): String = substringAfter(':')

    /** 把服务端返回的绝对 href 归一化成「相对根路径、无首尾斜杠」的形式。 */
    private fun normaliseHref(href: String, rootPath: String): String? {
        val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        val path = runCatching { Uri.parse(decoded).path }.getOrNull() ?: decoded
        val root = rootPath.trimEnd('/')
        val withoutRoot = if (root.isNotEmpty() && path.startsWith(root)) path.removePrefix(root) else path
        return withoutRoot.trim('/')
    }

    private fun rootPathOf(profile: ConnectProfile): String =
        runCatching { Uri.parse(baseOf(profile)).path.orEmpty() }.getOrDefault("")

    private fun baseOf(profile: ConnectProfile): String {
        val raw = profile.host.trim().trimEnd('/')
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    }

    private fun authHeaders(profile: ConnectProfile): Map<String, String> {
        val header = basicAuthHeader(profile.username, profile.password)
        return if (header.isBlank()) emptyMap() else mapOf("Authorization" to header)
    }

    /** 逐段百分号编码：文件名里有空格/中文/`#` 时直接用原串会让 URL 解析出错。 */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }


    private fun unsupportedCaps() = ServerCapabilities(
        lyrics = false, favorites = false, playlists = false, musicVideos = false, radio = false, search = false,
    )

    private suspend fun OkHttpClient.cancellableCall(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }
            })
        }

    private companion object {
        const val INDEX_TTL_MS = 10 * 60 * 1000L
        const val INDEX_BUDGET_MS = 90_000L
        const val MAX_DEPTH = 6
        const val MAX_DIRECTORIES = 3_000

        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
