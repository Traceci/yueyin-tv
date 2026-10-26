package com.fnmusic.tv.core.data.protocol

import com.fnmusic.tv.core.data.connect.ConnectProfile
import com.fnmusic.tv.core.model.connect.ConnectType
import com.fnmusic.tv.core.model.connect.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Jellyfin / Emby 协议实现（两者接口同源，差异只在个别参数名，用 [type] 分支处理）。
 *
 * 要点：
 * - 登录 `POST /Users/AuthenticateByName`，成功后把 `AccessToken` / `User.Id` 回填到 profile；
 * - 后续请求统一带 `api_key=<token>`（比每个请求拼 Header 简单，且两家都支持）；
 * - 播放地址用 `/Audio/{id}/universal?...&Static=true`：**Static=true 才是直连原文件**，
 *   这样服务端会返回 Accept-Ranges，快进/拖动才能生效（不加 Static 会走转码，拖不动）。
 */
class JellyfinLikeProtocol(
    override val type: ConnectType,
    private val client: OkHttpClient = defaultClient(),
) : MusicProtocol {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 可取消的 HTTP 调用：用 `enqueue` + 回调代替阻塞的 `execute()`，
     * 这样协程的 `withTimeout` / `cancel` 能通过 `call.cancel()` 真正中断在途请求
     * （`execute()` 阻塞 IO 线程时协程无法抢占，超时形同虚设，会让搜索界面永久转圈）。
     */
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

    override suspend fun ping(profile: ConnectProfile): Boolean = withContext(Dispatchers.IO) {
        // 只需一次轻量查询：能拿到结果就说明地址与凭据都可用
        val auth = resolveAuth(profile) ?: return@withContext false
        runCatching { items(profile, auth, "IncludeItemTypes" to "MusicAlbum", "Limit" to "1") }.isSuccess
    }

    override suspend fun login(profile: ConnectProfile): ServerCapabilities {
        val auth = resolveAuth(profile)
        return if (auth == null) {
            ServerCapabilities(false, false, false, false, false, false)
        } else {
            ServerCapabilities(
                lyrics = false, // Jellyfin/Emby 没有统一的歌词接口，界面据此隐藏歌词入口
                favorites = true,
                playlists = true,
                musicVideos = true,
                radio = false,
                search = true,
            )
        }
    }

    override suspend fun artists(profile: ConnectProfile): List<RemoteArtist> = withContext(Dispatchers.IO) {
        val auth = resolveAuth(profile) ?: return@withContext emptyList()
        runCatching {
            items(
                profile, auth,
                "IncludeItemTypes" to "MusicArtist",
                "Recursive" to "true",
                "SortBy" to "SortName",
                "Limit" to "500",
            ).mapNotNull { item ->
                val id = item.str("Id") ?: return@mapNotNull null
                RemoteArtist(id = id, name = item.str("Name").orEmpty(), coverId = item.str("Id"))
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun albums(profile: ConnectProfile): List<RemoteAlbum> = withContext(Dispatchers.IO) {
        val auth = resolveAuth(profile) ?: return@withContext emptyList()
        runCatching {
            items(
                profile, auth,
                "IncludeItemTypes" to "MusicAlbum",
                "Recursive" to "true",
                "SortBy" to "SortName",
                "Limit" to "500",
            ).mapNotNull { item ->
                val id = item.str("Id") ?: return@mapNotNull null
                RemoteAlbum(
                    id = id,
                    name = item.str("Name").orEmpty(),
                    artistName = item.list("Artists")?.firstOrNull() ?: item.str("AlbumArtist").orEmpty(),
                    coverId = id,
                    songCount = item.long("ChildCount")?.toInt() ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun songs(profile: ConnectProfile): List<RemoteSong> = withContext(Dispatchers.IO) {
        val auth = resolveAuth(profile) ?: return@withContext emptyList()
        runCatching {
            items(
                profile, auth,
                "IncludeItemTypes" to "Audio",
                "Recursive" to "true",
                "SortBy" to "SortName",
                "Limit" to "500",
            ).mapNotNull { it.toRemoteSong() }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlists(profile: ConnectProfile): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        val auth = resolveAuth(profile) ?: return@withContext emptyList()
        runCatching {
            items(
                profile, auth,
                "IncludeItemTypes" to "Playlist",
                "Recursive" to "true",
                "Limit" to "200",
            ).mapNotNull { item ->
                val id = item.str("Id") ?: return@mapNotNull null
                RemotePlaylist(
                    id = id,
                    name = item.str("Name").orEmpty(),
                    songCount = item.long("ChildCount")?.toInt() ?: 0,
                    coverId = id,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlistSongs(profile: ConnectProfile, playlistId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            val auth = resolveAuth(profile) ?: return@withContext emptyList()
            runCatching {
                val url = buildString {
                    append(base(profile)).append("/Playlists/").append(playlistId).append("/Items")
                    append("?UserId=").append(auth.userId)
                    append("&IncludeItemTypes=Audio&Fields=Artists,AlbumId,Album,RunTimeTicks")
                    append("&api_key=").append(auth.token)
                }
                val root = json.parseToJsonElement(getText(url)).jsonObject
                root.arr("Items").mapNotNull { it.jsonObject.toRemoteSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun search(profile: ConnectProfile, query: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val auth = resolveAuth(profile) ?: return@withContext emptyList()
            runCatching {
                items(
                    profile, auth,
                    "IncludeItemTypes" to "Audio",
                    "SearchTerm" to query,
                    "Recursive" to "true",
                    "Limit" to "50",
                ).mapNotNull { it.toRemoteSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun albumSongs(profile: ConnectProfile, albumId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            val auth = resolveAuth(profile) ?: return@withContext emptyList()
            runCatching {
                items(
                    profile, auth,
                    "IncludeItemTypes" to "Audio",
                    "ParentId" to albumId,
                    "Recursive" to "true",
                    "SortBy" to "ParentIndexNumber,IndexNumber",
                    "Limit" to "500",
                ).mapNotNull { it.toRemoteSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistSongs(profile: ConnectProfile, artistId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            val auth = resolveAuth(profile) ?: return@withContext emptyList()
            runCatching {
                items(
                    profile, auth,
                    "IncludeItemTypes" to "Audio",
                    "ArtistIds" to artistId,
                    "Recursive" to "true",
                    "SortBy" to "SortName",
                    "Limit" to "500",
                ).mapNotNull { it.toRemoteSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistAlbums(profile: ConnectProfile, artistId: String): List<RemoteAlbum> =
        withContext(Dispatchers.IO) {
            val auth = resolveAuth(profile) ?: return@withContext emptyList()
            runCatching {
                items(
                    profile, auth,
                    "IncludeItemTypes" to "MusicAlbum",
                    "AlbumArtistIds" to artistId,
                    "Recursive" to "true",
                    "SortBy" to "ProductionYear,SortName",
                    "Limit" to "500",
                ).mapNotNull { item ->
                    val id = item.str("Id") ?: return@mapNotNull null
                    RemoteAlbum(
                        id = id,
                        name = item.str("Name").orEmpty(),
                        artistName = item.list("Artists")?.firstOrNull() ?: item.str("AlbumArtist").orEmpty(),
                        coverId = id,
                        songCount = item.long("ChildCount")?.toInt() ?: 0,
                    )
                }
            }.getOrDefault(emptyList())
        }

    override fun streamUrl(profile: ConnectProfile, songId: String): String {
        val auth = profile.token.takeIf { it.isNotBlank() } ?: return ""
        val deviceId = profile.deviceId()
        // Static=true：直连原文件，保留 Accept-Ranges（决定能否快进/拖动）
        return buildString {
            append(base(profile)).append("/Audio/").append(songId).append("/universal")
            append("?UserId=").append(profile.userId)
            append("&DeviceId=").append(deviceId)
            append("&Static=true&api_key=").append(auth)
        }
    }

    override fun coverUrl(profile: ConnectProfile, coverId: String, size: Int): String {
        val token = profile.token.takeIf { it.isNotBlank() } ?: return ""
        return buildString {
            append(base(profile)).append("/Items/").append(coverId).append("/Images/Primary")
            append("?maxHeight=").append(size).append("&quality=90&api_key=").append(token)
        }
    }

    override suspend fun lyrics(
        profile: ConnectProfile,
        songId: String,
        artist: String,
        title: String,
    ): String? = null // 两家都没有标准歌词接口，交给上层降级处理

    override suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                if (favorite) {
                    val url = buildString {
                        append(base(profile)).append("/Users/").append(profile.userId)
                        append("/FavoriteItems/").append(songId).append("?api_key=").append(profile.token)
                    }
                    post(url, "")
                } else {
                    val url = buildString {
                        append(base(profile)).append("/Users/").append(profile.userId)
                        append("/FavoriteItems/").append(songId).append("?api_key=").append(profile.token)
                    }
                    delete(url)
                }
            }.getOrDefault(false)
        }

    override suspend fun enrich(profile: ConnectProfile): ConnectProfile = withContext(Dispatchers.IO) {
        val auth = resolveAuth(profile) ?: return@withContext profile
        profile.copy(token = auth.token, userId = auth.userId)
    }

    // ---------------------------------------------------------------- 内部

    private data class Auth(val token: String, val userId: String)

    /** 已有 token 直接复用；否则用账号密码登录换 token。 */
    private suspend fun resolveAuth(profile: ConnectProfile): Auth? {
        if (profile.token.isNotBlank() && profile.userId.isNotBlank()) {
            return Auth(profile.token, profile.userId)
        }
        if (profile.username.isBlank()) return null
        return runCatching {
            val url = base(profile) + "/Users/AuthenticateByName"
            val body = """{"Username":"${profile.username.escape()}","Pw":"${profile.password.escape()}"}"""
            val payload = body.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Emby-Authorization", embyAuthHeader(profile))
                .post(payload)
                .build()
            val text = client.cancellableCall(request).use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body.string()
            }
            val root = json.parseToJsonElement(text).jsonObject
            val token = root.str("AccessToken") ?: return@runCatching null
            val userId = root["User"]?.jsonObject?.str("Id") ?: return@runCatching null
            Auth(token, userId)
        }.getOrNull()
    }

    private fun embyAuthHeader(profile: ConnectProfile): String =
        "MediaBrowser Client=\"YueYin\", Device=\"AndroidTV\", DeviceId=\"" +
            profile.deviceId() + "\", Version=\"1.0\""

    private suspend fun items(profile: ConnectProfile, auth: Auth, vararg params: Pair<String, String>): List<JsonObject> {
        val url = buildString {
            append(base(profile)).append("/Items?UserId=").append(auth.userId)
            append("&Fields=Artists,AlbumId,Album,RunTimeTicks,ChildCount")
            params.forEach { (k, v) -> append("&").append(k).append("=").append(java.net.URLEncoder.encode(v, "UTF-8")) }
            append("&api_key=").append(auth.token)
        }
        val root = json.parseToJsonElement(getText(url)).jsonObject
        return root.arr("Items")
    }

    private suspend fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .get()
            .build()
        return client.cancellableCall(request).use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body.string()
        }
    }

    private suspend fun post(url: String, body: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.cancellableCall(request).use { it.isSuccessful }
    }

    private suspend fun delete(url: String): Boolean {
        val request = Request.Builder().url(url).delete().build()
        return client.cancellableCall(request).use { it.isSuccessful }
    }

    private fun base(profile: ConnectProfile): String = profile.host.trim().trimEnd('/')

    private fun ConnectProfile.deviceId(): String =
        "yueyin-tv-" + (id.hashCode().toUInt().toString(16))

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun JsonObject.list(key: String): List<String>? =
        runCatching { this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } }.getOrNull()

    private fun JsonObject.arr(key: String): List<JsonObject> =
        runCatching { this[key]?.jsonArray?.map { it.jsonObject } }.getOrNull().orEmpty()

    private fun JsonObject.toRemoteSong(): RemoteSong? {
        val id = str("Id") ?: return null
        // Jellyfin 的时长单位是 100ns tick
        val ticks = long("RunTimeTicks") ?: 0L
        return RemoteSong(
            id = id,
            title = str("Name").orEmpty(),
            artistName = list("Artists")?.firstOrNull() ?: str("AlbumArtist").orEmpty(),
            albumName = str("Album").orEmpty(),
            albumId = str("AlbumId"),
            durationMs = ticks / 10_000L,
            coverId = str("AlbumId") ?: id,
            trackNumber = long("IndexNumber")?.toInt() ?: 0,
        )
    }

    private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
