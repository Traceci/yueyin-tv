package com.yueyin.tv.core.data.protocol

import com.yueyin.tv.core.data.connect.ConnectProfile
import com.yueyin.tv.core.model.connect.ConnectType
import com.yueyin.tv.core.model.connect.ServerCapabilities
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * SMB 共享协议。
 *
 * Media3 完全不认识 `smb://`，所以取流必须经过 [LocalStreamProxy]：
 * 协议层把「UNC 地址 + 账号密码」登记给代理，播放入口拿到的是一个
 * `http://127.0.0.1:port/...` 的普通地址，代理再用 jcifs 读文件并支持 Range seek。
 *
 * 元数据同样只能靠目录结构推断（与 WebDAV 一致）：`歌手/专辑/01 - 歌名.flac`。
 */
class SmbProtocol(
    override val type: ConnectType = ConnectType.SMB,
) : MusicProtocol {

    private val indexes = ConcurrentHashMap<String, SmbIndex>()

    // ---------------------------------------------------------------- 登录

    override suspend fun ping(profile: ConnectProfile): Boolean = withContext(Dispatchers.IO) {
        runCatching { SmbSupport.root(profile).exists() }.getOrDefault(false)
    }

    override suspend fun login(profile: ConnectProfile): ServerCapabilities = withContext(Dispatchers.IO) {
        val reachable = runCatching { SmbSupport.root(profile).exists() }.getOrDefault(false)
        if (!reachable) return@withContext unsupportedCaps()
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
                    RemoteArtist(id = artist, name = artist, coverId = files.firstNotNullOfOrNull { it.coverUrl })
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
                        coverId = files.firstNotNullOfOrNull { it.coverUrl },
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
                            coverId = files.firstNotNullOfOrNull { it.coverUrl },
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

    override fun streamUrl(profile: ConnectProfile, songId: String): String =
        LocalStreamProxy.urlOf(
            LocalStreamProxy.Upstream.Smb(songId, profile.username, profile.password),
        )

    override fun coverUrl(profile: ConnectProfile, coverId: String, size: Int): String {
        if (coverId.isBlank()) return ""
        // coverId 存的是纯路径，需要补回 smb://前缀
        val absolute = if (coverId.startsWith("smb://")) coverId else profile.host.trimEnd('/') + "/" + coverId
        return LocalStreamProxy.urlOf(
            LocalStreamProxy.Upstream.Smb(absolute, profile.username, profile.password),
        )
    }

    override suspend fun lyrics(
        profile: ConnectProfile,
        songId: String,
        artist: String,
        title: String,
    ): String? = withContext(Dispatchers.IO) {
        val lrcPath = if (songId.endsWith(".lrc", ignoreCase = true)) {
            songId
        } else {
            songId.substringBeforeLast('.', songId) + ".lrc"
        }
        runCatching {
            val file = SmbSupport.file(lrcPath, profile.username, profile.password)
            if (!file.exists()) return@runCatching null
            file.getInputStream().use { input -> input.readBytes().toString(Charsets.UTF_8) }
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean): Boolean = false

    // ---------------------------------------------------------------- 索引

    private data class SmbFileEntry(
        val url: String,
        val title: String,
        val artist: String,
        val album: String,
        val trackNumber: Int,
        val coverUrl: String?,
    ) {
        fun toSong() = RemoteSong(
            id = url,
            title = title,
            artistName = artist,
            albumName = album,
            albumId = albumKeyOf(artist, album),
            durationMs = 0L,
            coverId = coverUrl,
            trackNumber = trackNumber,
        )
    }

    private data class SmbIndex(val files: List<SmbFileEntry>, val builtAt: Long)

    private suspend fun indexOf(profile: ConnectProfile): SmbIndex {
        val key = profile.host + "|" + profile.username
        val cached = indexes[key]
        if (cached != null && System.currentTimeMillis() - cached.builtAt < INDEX_TTL_MS) return cached
        val built = SmbIndex(buildIndex(profile), System.currentTimeMillis())
        indexes[key] = built
        return built
    }

    private fun buildIndex(profile: ConnectProfile): List<SmbFileEntry> {
        val root = SmbSupport.root(profile)
        val rootPath = profile.host.trimEnd('/')
        val startedAt = System.currentTimeMillis()
        val queue = ArrayDeque<SmbFile>()
        queue.add(root)
        val audio = mutableListOf<Triple<SmbFile, String, String>>()
        val covers = HashMap<String, String>()
        var directoryCount = 0

        while (queue.isNotEmpty()) {
            if (directoryCount++ > MAX_DIRECTORIES) break
            if (System.currentTimeMillis() - startedAt > INDEX_BUDGET_MS) break
            val dir = queue.removeFirst()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                val absolute = child.uncPathOrName()
                val relative = absolute.removePrefix(rootPath).trim('/')
                if (relative.isBlank()) continue
                val isDirectory = runCatching { child.isDirectory }.getOrDefault(false)
                when {
                    isDirectory -> {
                        val depth = relative.count { it == '/' }
                        if (depth < MAX_DEPTH) queue.add(child)
                    }
                    relative.isAudioFile() -> audio.add(Triple(child, relative, absolute))
                    relative.isCoverImage() -> {
                        val parent = relative.substringBeforeLast('/', "")
                        if (parent !in covers) covers[parent] = absolute
                    }
                }
            }
        }

        return audio.map { (_, relative, absolute) ->
            val segments = relative.split('/')
            val fileName = segments.lastOrNull().orEmpty()
            val dirSegments = segments.dropLast(1)
            val album = dirSegments.lastOrNull().orEmpty()
            val artist = if (dirSegments.size >= 2) dirSegments[dirSegments.size - 2] else ""
            val parentPath = dirSegments.joinToString("/")
            val (trackNumber, title) = parseTrackTitle(fileName)
            SmbFileEntry(
                url = absolute,
                title = title,
                artist = artist,
                album = album,
                trackNumber = trackNumber,
                coverUrl = covers[parentPath],
            )
        }.sortedWith(compareBy({ it.artist }, { it.album }, { it.trackNumber }, { it.title }))
    }

    private fun SmbFile.uncPathOrName(): String {
        val path = runCatching { uncPath }.getOrNull()
        if (!path.isNullOrBlank()) return path.trimEnd('/')
        return runCatching { canonicalUncPath }.getOrNull().orEmpty().trimEnd('/')
    }


    private fun unsupportedCaps() = ServerCapabilities(
        lyrics = false, favorites = false, playlists = false, musicVideos = false, radio = false, search = false,
    )

    private companion object {
        const val INDEX_TTL_MS = 10 * 60 * 1000L
        const val INDEX_BUDGET_MS = 90_000L
        const val MAX_DEPTH = 5
        const val MAX_DIRECTORIES = 2_000
    }
}

/**
 * jcifs 的薄封装：上下文（含账号）按「用户名+密码」缓存复用，
 * 避免每次请求都新建一个 SMB 会话（服务端会因此拒绝或很慢）。
 */
internal object SmbSupport {

    private val contexts = ConcurrentHashMap<String, CIFSContext>()

    /** 根目录：一定是目录，需要以 "/" 结尾，否则 jcifs 会当成文件名。 */
    fun root(profile: ConnectProfile): SmbFile =
        SmbFile(normalise(profile.host).trimEnd('/') + "/", contextOf(profile.username, profile.password))

    fun file(url: String, username: String, password: String): SmbFile =
        SmbFile(normalise(url), contextOf(username, password))

    fun openFile(url: String, username: String, password: String): SmbFile =
        SmbFile(normalise(url), contextOf(username, password))

    /** 只补协议头，不动尾部斜杠 —— 文件地址被加上 "/" 会被当成目录而打不开。 */
    private fun normalise(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("smb://", ignoreCase = true)) trimmed else "smb://$trimmed"
    }

    private fun contextOf(username: String, password: String): CIFSContext {
        val key = "$username\u0001$password"
        return contexts.getOrPut(key) {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.connTimeout", "10000")
                setProperty("jcifs.smb.client.soTimeout", "20000")
                setProperty("jcifs.smb.client.responseTimeout", "20000")
                setProperty("jcifs.smb.client.dfs.disabled", "true")
                setProperty("jcifs.smb.client.listSize", "65435")
                setProperty("jcifs.smb.client.listCount", "200")
                setProperty("jcifs.resolveOrder", "DNS,BCAST")
            }
            val base = BaseContext(PropertyConfiguration(props))
            if (username.isBlank()) {
                base.withAnonymousCredentials()
            } else {
                val (domain, user) = splitDomain(username)
                base.withCredentials(NtlmPasswordAuthenticator(domain, user, password))
            }
        }
    }

    /** 允许用户按 `域\\账号` 或 `账号@域` 填写。 */
    private fun splitDomain(username: String): Pair<String, String> {
        val trimmed = username.trim()
        return when {
            trimmed.contains('\\') -> trimmed.substringBefore('\\') to trimmed.substringAfter('\\')
            else -> "" to trimmed
        }
    }
}
