package com.yueyin.tv.core.data.api

import com.yueyin.tv.core.data.server.NormalizedServer
import com.yueyin.tv.core.data.server.ConnectionAccess
import com.yueyin.tv.core.model.AppError
import com.yueyin.tv.core.model.AppException
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.serialization.Serializable

class TrimMusicApi(
    private val server: NormalizedServer,
    private val client: OkHttpClient,
    private val token: () -> String?,
    private val access: ConnectionAccess = ConnectionAccess(),
) {
    fun apiBase(): String = server.apiBase.toString()
    suspend fun systemConfig(): SystemConfigDto = get("sys/config", authenticated = false)

    suspend fun login(username: String, password: String, deviceId: String): LoginResultDto {
        val passwordChars = password.toCharArray()
        return try {
            login(username, PasswordHash.fromPlaintext(passwordChars), deviceId)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    internal suspend fun login(username: String, password: PasswordHash, deviceId: String): LoginResultDto =
        post(
            "user/password-login",
            PasswordLoginRequest(username, password.encoded, deviceId),
            authenticated = false,
        )

    suspend fun me(): UserDto = get("user/me")

    suspend fun logout() {
        val builder = Request.Builder().url(server.resolveApi("user/logout"))
            .post(ByteArray(0).toRequestBody())
        executeUnit(builder, authenticated = true)
    }

    suspend fun playlists(): List<PlaylistDto> = get<PageListDto<PlaylistDto>>("playlist/list").list

    suspend fun playlist(guid: String): PlaylistDetailDto = get("playlist/detail", "guid" to guid)

    suspend fun playlistTracks(guid: String, page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/playlist-detail/list", "playlistGUID" to guid, "page" to page, "size" to size, "sort" to "trackAddedAt,desc")

    suspend fun artists(page: Int, size: Int = 50): SortedPageListDto<ArtistDto> =
        get("artist/list", "page" to page, "size" to size, "sort" to "trackCount,desc")

    suspend fun artist(guid: String): ArtistDto = get("artist/detail", "guid" to guid)

    suspend fun artistTracks(guid: String, page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/artist-detail/list", "artistGUID" to guid, "page" to page, "size" to size, "sort" to "createdAt,desc")

    suspend fun artistAlbums(guid: String, page: Int, size: Int = 50): SortedPageListDto<AlbumDto> =
        get("album/artist-detail/list", "artistGUID" to guid, "page" to page, "size" to size, "sort" to "newTrackAddedAt,desc")

    suspend fun albums(page: Int, size: Int = 50): SortedPageListDto<AlbumDto> =
        get("album/list", "page" to page, "size" to size, "sort" to "newTrackAddedAt,desc")

    suspend fun album(guid: String): AlbumDto = get("album/detail", "guid" to guid)

    suspend fun albumTracks(guid: String, page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/album-detail/list", "albumGUID" to guid, "page" to page, "size" to size, "sort" to "trackNo,asc")

    suspend fun allTracks(page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/list", "page" to page, "size" to size, "sort" to "createdAt,desc")

    suspend fun favoriteTracks(page: Int, size: Int = 50): PageListDto<TrackDto> =
        get("favorite-track/list", "page" to page, "size" to size, "sort" to "favoriteAt,desc")

    suspend fun createFavorite(trackGuid: String) {
        postUnit("favorite-track/create", FavoriteTrackRequest(trackGuid))
    }

    suspend fun deleteFavorite(trackGuid: String) {
        postUnit("favorite-track/delete", FavoriteTrackRequest(trackGuid))
    }

    suspend fun sharedLibraries(): List<SharedLibraryDto> = get<ListDto<SharedLibraryDto>>("shared-library/list").list

    suspend fun metadata(guid: String): TrackMetadataDto = get("track/metadata", "guid" to guid)

    suspend fun lyrics(guid: String): LyricListDto = get("lyric/list", "trackGUID" to guid)

    suspend fun roamStart(deviceId: String): RoamStartDto? = getNullable("track/roam-start", "deviceId" to deviceId)

    suspend fun roamNext(deviceId: String, roamId: String): RoamWindowDto =
        get("track/roam-next", "deviceId" to deviceId, "relativeRoamId" to roamId)

    suspend fun roamPrevious(deviceId: String, roamId: String): RoamWindowDto =
        get("track/roam-previous", "deviceId" to deviceId, "relativeRoamId" to roamId)

    fun streamUrl(guid: String) = server.resolveApi("track/stream").newBuilder().addQueryParameter("guid", guid).build()

    /**
     * 服务端转码（Media3 没有 ASF/WMA 提取器，只能让 NAS 转成 opus 再按 HLS 播）。
     *
     * 官方链路是两步：
     * 1. `POST track/transcode`，body `{trackGUID, codec, bitrate}`，服务端登记转码会话，
     *    返回 `data.status`（ready/preparing）与可选的 `data.url`；
     * 2. `GET track/hls/{guid}/preset.m3u8` 取播放列表，分片是 fMP4 内封 Opus。
     *
     * 注意：旧实现用 `GET track/transcode?guid=...` 是错的——服务端不认这种调用，
     * 直接回 Web 首页 HTML，播放器拿到的是一段 HTML 而不是音频。
     */
    suspend fun transcodeSession(guid: String, codec: String = "opus", bitrate: Int = 192): TranscodeResultDto =
        post("track/transcode", TranscodeRequest(trackGUID = guid, codec = codec, bitrate = bitrate))

    /**
     * 转码后的 HLS 播放列表地址。优先用服务端回的 url（可能是绝对地址，也可能是
     * `/music/api/v1/...` 这种根相对路径），拿不到就退回默认预设路径。
     */
    fun transcodePlaylistUrl(guid: String, returnedUrl: String? = null): HttpUrl {
        val raw = returnedUrl?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val resolved = when {
                raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) ->
                    raw.toHttpUrlOrNull()
                raw.startsWith("/") -> server.origin.resolve(raw)
                else -> server.apiBase.resolve(raw)
            }
            if (resolved != null) return resolved
        }
        return server.resolveApi("track/hls/$guid/preset.m3u8")
    }

    /**
     * 官方文件夹视图：GET，必传共享库 GUID 与父目录在 NAS 上的绝对路径。
     * 响应 data.folder = { name, path, itemCount, folders[], tracks[] }；
     * page/size 被服务端忽略，一次返回该目录全部直接子项。
     */
    suspend fun folderViewList(
        libraryGuid: String,
        parentPath: String,
        sort: String = "name,asc",
    ): JsonElement = get(
        "folder-view/list",
        "libraryGUID" to libraryGuid,
        "parentPath" to parentPath,
        "sort" to sort,
    )

    /** 播放整个文件夹：POST JSON，folderPaths 为目录绝对路径列表。 */
    suspend fun folderViewPlayableTracks(
        libraryGuid: String,
        vararg folderPaths: String,
    ): List<TrackDto> = post<FolderPlayableTracksRequest, FolderPlayableTracksDto>(
        "folder-view/playable-tracks",
        FolderPlayableTracksRequest(libraryGuid, folderPaths.toList()),
    ).tracks

    fun coverUrl(coverId: String, size: Int?) = server.resolveApi("static/cover").newBuilder()
        .addQueryParameter("coverId", coverId)
        .apply { if (size != null) addQueryParameter("size", size.toString()) }
        .build()

    suspend fun cover(coverId: String, size: Int?): ByteArray = executeBytes(
        Request.Builder().url(coverUrl(coverId, size)),
        authenticated = true,
    )

    private suspend inline fun <reified T> get(path: String, authenticated: Boolean = true): T =
        execute(Request.Builder().url(server.resolveApi(path)), authenticated)

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, Any>): T {
        val url = server.resolveApi(path).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value.toString()) }
        }.build()
        return execute(Request.Builder().url(url), true)
    }

    private suspend inline fun <reified T> getNullable(path: String, vararg query: Pair<String, Any>): T? {
        val url = server.resolveApi(path).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value.toString()) }
        }.build()
        return executeNullable(Request.Builder().url(url), true)
    }

    private suspend inline fun <reified RequestType, reified ResponseType> post(
        path: String,
        body: RequestType,
        authenticated: Boolean = true,
    ): ResponseType {
        val json = ApiDecoder.json.encodeToString(body)
        val builder = Request.Builder().url(server.resolveApi(path))
            .post(json.toRequestBody("application/json".toMediaType()))
        return execute(builder, authenticated)
    }

    private suspend inline fun <reified RequestType> postUnit(
        path: String,
        body: RequestType,
    ) {
        val json = ApiDecoder.json.encodeToString(body)
        val builder = Request.Builder().url(server.resolveApi(path))
            .post(json.toRequestBody("application/json".toMediaType()))
        executeUnit(builder, authenticated = true)
    }

    private suspend inline fun <reified T> execute(builder: Request.Builder, authenticated: Boolean): T =
        executeResponse(builder, authenticated) { response -> ApiDecoder.decode<T>(response.body.string()) }

    private suspend inline fun <reified T> executeNullable(builder: Request.Builder, authenticated: Boolean): T? =
        executeResponse(builder, authenticated) { response -> ApiDecoder.decodeNullable<T>(response.body.string()) }

    private suspend fun executeBytes(builder: Request.Builder, authenticated: Boolean): ByteArray =
        executeResponse(builder, authenticated) { response ->
            if (response.header("Content-Type")?.contains("json", ignoreCase = true) == true) {
                ApiDecoder.decode<JsonElement>(response.body.string())
                throw AppException(AppError.Unknown("invalid_json"))
            }
            response.body.bytes().takeIf(ByteArray::isNotEmpty) ?: throw AppException(AppError.Empty)
        }

    private suspend fun executeUnit(builder: Request.Builder, authenticated: Boolean) =
        executeResponse(builder, authenticated) { response -> ApiDecoder.decodeUnit(response.body.string()) }

    private suspend fun <T> executeResponse(
        builder: Request.Builder,
        authenticated: Boolean,
        decode: (Response) -> T,
    ): T {
        val authToken = if (authenticated) token() ?: throw AppException(AppError.Unauthenticated) else null
        for ((name, value) in access.headers(authToken)) {
            builder.header(name, value)
        }
        return suspendCancellableCoroutine { continuation ->
            var activeCall: Call? = null
            fun enqueue(request: Request, redirects: Int) {
                val call = client.newCall(request)
                activeCall = call
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e.asRequestFailure())
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (access.relayMode && response.isRedirect && redirects < MAX_RELAY_REDIRECTS) {
                                val next = response.header("Location")?.let { request.url.resolve(it) }
                                response.close()
                                if (!continuation.isActive) return
                                if (next == null) {
                                    continuation.resumeWithException(AppException(AppError.NetworkUnavailable))
                                } else {
                                    enqueue(request.newBuilder().url(next).build(), redirects + 1)
                                }
                                return
                            }
                            val result = runCatching {
                                response.use {
                                    classifyStatus(it)
                                    decode(it)
                                }
                            }
                            if (!continuation.isActive) return
                            result.fold(
                                onSuccess = continuation::resume,
                                onFailure = { continuation.resumeWithException(it.asRequestFailure()) },
                            )
                        }
                    },
                )
            }
            continuation.invokeOnCancellation { activeCall?.cancel() }
            enqueue(builder.build(), redirects = 0)
        }
    }

    private fun Throwable.asRequestFailure(): Throwable = when (this) {
        is CancellationException, is AppException -> this
        is SSLException, is CertificateException -> AppException(
            AppError.CertificateUntrusted,
            ApiRequestFailure(retryable = false, cause = this),
        )
        is IOException -> AppException(
            AppError.NetworkUnavailable,
            ApiRequestFailure(retryable = true, cause = this),
        )
        else -> this
    }

    private fun classifyStatus(response: Response) {
        when {
            response.code == 401 -> throw AppException(
                AppError.Unauthenticated,
                ApiRequestFailure(retryable = false, statusCode = response.code),
            )
            response.code == 404 -> throw AppException(
                AppError.NotFound,
                ApiRequestFailure(retryable = false, statusCode = response.code),
            )
            response.isRedirect -> throw AppException(
                AppError.NetworkUnavailable,
                ApiRequestFailure(retryable = false, statusCode = response.code),
            )
            !response.isSuccessful -> throw AppException(
                AppError.NetworkUnavailable,
                ApiRequestFailure(
                    retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    statusCode = response.code,
                ),
            )
        }
    }

    companion object {
        private const val MAX_RELAY_REDIRECTS = 5

        /** 最近一次被显式信任的自签名证书指纹（SHA-256），用于首次连接后回写并固定。 */
        @Volatile
        var lastAcceptedFingerprint: String? = null

        fun client(): OkHttpClient = client(allowSelfSigned = false, host = null)

        /**
         * [allowSelfSigned] 只对 [host] 生效，绝不全局放开；[pin] 非空时只认这张证书。
         * 首次连接 pin 为空时记录指纹（TOFU），由调用方持久化后下次传入 pin。
         */
        fun client(allowSelfSigned: Boolean, host: String?, pin: String? = null): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor { chain ->
                    val request = if (chain.connection()?.protocol() == Protocol.HTTP_1_1) {
                        chain.request().newBuilder()
                            .header("Connection", "close")
                            .build()
                    } else {
                        chain.request()
                    }
                    chain.proceed(request)
                }
            if (allowSelfSigned) {
                val trust = SelfSignedTrust(host, pin)
                builder.sslSocketFactory(trust.socketFactory(), trust)
                if (!host.isNullOrBlank()) {
                    builder.hostnameVerifier { name, _ -> name.equals(host, ignoreCase = true) }
                }
            }
            return builder.build()
        }

        internal fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { "%02X".format(it) }

        /**
         * Media3 的 DefaultHttpDataSource 走 HttpsURLConnection，不经过上面的 OkHttp client。
         * 只有用户显式开启自签名信任时才改进程默认 TLS，并限定主机名；关闭时恢复系统默认。
         */
        @Synchronized
        fun applyPlaybackTls(allowSelfSigned: Boolean, host: String?, pin: String?) {
            if (!allowSelfSigned) {
                originalSslFactory?.let(HttpsURLConnection::setDefaultSSLSocketFactory)
                originalHostnameVerifier?.let(HttpsURLConnection::setDefaultHostnameVerifier)
                return
            }
            if (originalSslFactory == null) {
                originalSslFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
                originalHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            }
            val trust = SelfSignedTrust(host, pin)
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trust), null)
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { name, _ ->
                host?.let { name.equals(it, ignoreCase = true) } ?: false
            }
        }

        @Volatile
        private var originalSslFactory: javax.net.ssl.SSLSocketFactory? = null

        @Volatile
        private var originalHostnameVerifier: HostnameVerifier? = null
    }
}

/** 系统 CA 照常校验；只在该主机被显式授权时补一条自签名信任路径。 */
private class SelfSignedTrust(
    private val host: String?,
    private val pin: String?,
) : X509TrustManager {
    private val systemManagers: List<X509TrustManager> =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()

    @Volatile
    var recordedFingerprint: String? = null
        private set

    fun socketFactory() = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(this@SelfSignedTrust), null)
    }.socketFactory

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        systemManagers.firstOrNull()?.checkClientTrusted(chain, authType)
            ?: throw CertificateException("no system trust manager")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        for (manager in systemManagers) {
            runCatching { manager.checkServerTrusted(chain, authType) }
                .onSuccess { return }
        }
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val fingerprint = TrimMusicApi.fingerprint(leaf)
        if (pin != null) {
            if (!fingerprint.equals(pin, ignoreCase = true)) {
                throw CertificateException("certificate fingerprint mismatch")
            }
        } else if (host.isNullOrBlank()) {
            throw CertificateException("self-signed trust requires a host")
        }
        recordedFingerprint = fingerprint
        TrimMusicApi.lastAcceptedFingerprint = fingerprint
    }

    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
        systemManagers.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
}

@JvmInline
internal value class PasswordHash private constructor(val encoded: String) {
    companion object {
        fun fromPlaintext(password: CharArray): PasswordHash {
            val bytes = password.concatToString().toByteArray(Charsets.UTF_8)
            return try {
                PasswordHash(MessageDigest.getInstance("SHA-256").digest(bytes).toHex())
            } finally {
                bytes.fill(0)
            }
        }

        fun parse(encoded: String): PasswordHash? = encoded
            .takeIf { it.length == SHA256_HEX_LENGTH && it.all(::isLowercaseHex) }
            ?.let(::PasswordHash)

        private const val SHA256_HEX_LENGTH = 64
    }
}

private fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    return buildString(size * 2) {
        this@toHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private fun isLowercaseHex(value: Char): Boolean = value in '0'..'9' || value in 'a'..'f'

@Serializable
private data class TranscodeRequest(
    val trackGUID: String,
    val codec: String,
    val bitrate: Int,
)

@Serializable
private data class FolderPlayableTracksRequest(
    val libraryGUID: String,
    val folderPaths: List<String>,
)

@Serializable
private data class FolderPlayableTracksDto(
    val tracks: List<TrackDto> = emptyList(),
)
