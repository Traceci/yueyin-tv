package com.yueyin.tv.core.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.yueyin.tv.core.model.stream.PlaybackHttp
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.ArrayList

internal const val FORWARD_BUFFER_DURATION_MS = 50_000
internal const val BACK_BUFFER_DURATION_MS = 15_000
internal const val SONG_CACHE_DIRECTORY = "song-cache"
internal const val SONG_CACHE_MAX_BYTES = 512L * 1024L * 1024L
private const val LEGACY_AUDIO_CACHE_DIRECTORY = "media"
private const val TAG = "PlaybackService"

// 车机浏览树只暴露当前播放队列：service 自己就有数据，不跨模块取曲库、不碰认证态。
private const val LIBRARY_ROOT_ID = "yueyin-root"
private const val LIBRARY_QUEUE_ID = "yueyin-queue"

internal fun validatedShuffleIndices(
    canonicalIds: List<String>,
    requestedIds: List<String>,
): IntArray? {
    if (
        requestedIds.size != canonicalIds.size ||
        requestedIds.any(String::isBlank) ||
        canonicalIds.any(String::isBlank) ||
        requestedIds.distinct().size != requestedIds.size ||
        canonicalIds.distinct().size != canonicalIds.size ||
        requestedIds.toSet() != canonicalIds.toSet()
    ) {
        return null
    }
    val indexById = canonicalIds.withIndex().associate { (index, id) -> id to index }
    return requestedIds.map { id -> indexById.getValue(id) }.toIntArray()
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createPlaybackLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        FORWARD_BUFFER_DURATION_MS,
        FORWARD_BUFFER_DURATION_MS,
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    )
    .setBackBuffer(BACK_BUFFER_DURATION_MS, false)
    .build()

/**
 * 构建 RenderersFactory。传入 `fxProcessor` 时，音频链路会多一段自定义 DSP。
 *
 * 关键在于**保留默认链里的两个处理器**：SilenceSkippingAudioProcessor 负责"跳过静音"，
 * SonicAudioProcessor 负责"变速不变调"。直接用 `setAudioProcessors(arrayOf(fx))` 会把这俩
 * 一起换掉——表现就是倍速播放破音、静音跳过失效。所以这里走 `setAudioProcessorChain`。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun createPlaybackRenderersFactory(
    context: Context,
    fxProcessor: FxAudioProcessor? = null,
): DefaultRenderersFactory {
    if (fxProcessor == null) {
        return DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    }
    return object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf<AudioProcessor>(fxProcessor),
                    SilenceSkippingAudioProcessor(),
                    SonicAudioProcessor(),
                ),
            )
            .build()
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createPlaybackHttpDataSourceFactory(): DefaultHttpDataSource.Factory =
    DefaultHttpDataSource.Factory()
        // 音源代理链常把 http 直链 302 到 https；禁掉跨协议重定向会直接报「连接不上音源」。
        .setAllowCrossProtocolRedirects(true)
        // 与取链探活共用同一 UA：探活能过、播放器被拒是排查里最常见的一类假成功。
        .setUserAgent(PlaybackHttp.USER_AGENT)

internal fun playbackRequestHeaders(token: String, accessCode: String?, relayMode: Boolean): Map<String, String> =
    buildMap {
        put("Authorization", token)
        if (relayMode) put("Cookie", "music-token=$token; mode=relay")
        if (!accessCode.isNullOrBlank()) {
            put("x-access-code", accessCode)
            put("x-access-source", "app")
        }
    }

internal fun deleteLegacyAudioCache(cacheDirectory: File): Boolean = runCatching {
    val canonicalCacheDirectory = cacheDirectory.canonicalFile
    val legacyCache = File(canonicalCacheDirectory, LEGACY_AUDIO_CACHE_DIRECTORY)
    if (!legacyCache.exists()) {
        legacyCache.delete()
        return@runCatching true
    }
    if (legacyCache.canonicalFile != legacyCache.absoluteFile) {
        return@runCatching legacyCache.delete()
    }
    legacyCache.deleteRecursively()
}.getOrDefault(false)

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaLibraryService.MediaLibrarySession? = null
    private var songCache: SimpleCache? = null
    private var fxProcessor: FxAudioProcessor? = null

    override fun onCreate() {
        super.onCreate()
        if (!deleteLegacyAudioCache(cacheDir)) {
            Log.w(TAG, "Unable to delete legacy audio cache")
        }
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val httpFactory = createPlaybackHttpDataSourceFactory()
        // 本地音乐与外部 intent 会带 content:// / file:// URI。DefaultDataSource 会按 scheme
        // 分发到 ContentDataSource / FileDataSource；http(s) 仍回落原来的鉴权 HTTP 工厂，
        // 在线播放链路的行为完全不变。
        val upstreamFactory = DefaultDataSource.Factory(this, httpFactory)
        // 歌曲磁盘缓存：对流式音频做本地缓存，方便断网续播，也可在设置中查看占用并一键清空。
        val cache = SimpleCache(
            File(filesDir, SONG_CACHE_DIRECTORY),
            LeastRecentlyUsedCacheEvictor(SONG_CACHE_MAX_BYTES),
        )
        songCache = cache
        val cachingSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
        // 音效处理器跟着服务生命周期走：服务在，链路在；服务销毁时从全局注册表摘掉。
        // 处理器恒在音频链上（FxAudioProcessor.isActive 恒 true），参数改动即时生效，
        // 不再需要「切开关时 seek 一次触发 flush」——那既会顿一下，又因为 flush 不会补
        // configure，导致处理器始终没被初始化、滤波全 bypass。
        val fx = AudioFxController.createProcessor().also { fxProcessor = it }
        val exoPlayer = ExoPlayer.Builder(
            this,
            createPlaybackRenderersFactory(this, fx),
        )
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachingSourceFactory))
            .setLoadControl(createPlaybackLoadControl())
            .build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
        player = exoPlayer
        val libraryCallback = object : MediaLibraryService.MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(PlaybackCommands.ConfigureAuthCommand)
                    .add(PlaybackCommands.ClearAuthCommand)
                    .add(PlaybackCommands.SetShuffleOrderCommand)
                    .add(PlaybackCommands.ClearSongCacheCommand)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(available)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: android.os.Bundle,
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == PlaybackCommands.ClearAuth) {
                    httpFactory.setDefaultRequestProperties(emptyMap())
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand.customAction == PlaybackCommands.ClearSongCache) {
                    // 逐条移除缓存的资源；正在播放中的片段可能无法移除，但不影响当前播放。
                    runCatching {
                        songCache?.keys?.forEach { key -> runCatching { songCache?.removeResource(key) } }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand.customAction == PlaybackCommands.ConfigureAuth) {
                    val token = args.getString(PlaybackCommands.Token)
                    val namespace = args.getString(PlaybackCommands.CacheNamespace)
                    if (token.isNullOrBlank() || namespace.isNullOrBlank()) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                    val relayMode = args.getBoolean(PlaybackCommands.RelayMode, false)
                    val accessCode = args.getString(PlaybackCommands.AccessCode)
                    val headers = playbackRequestHeaders(token, accessCode, relayMode)
                    httpFactory.setDefaultRequestProperties(headers)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand.customAction == PlaybackCommands.SetShuffleOrder) {
                    val requested = args.getStringArrayList(PlaybackCommands.MediaIds).orEmpty()
                    val revision = args.getLong(PlaybackCommands.SnapshotRevision, -1L)
                    val canonical = List(exoPlayer.mediaItemCount) { index ->
                        exoPlayer.getMediaItemAt(index).mediaId
                    }
                    val order = validatedShuffleIndices(canonical, requested)
                    if (revision < 0L || order == null) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                    exoPlayer.setShuffleOrder(DefaultShuffleOrder(order, revision))
                    val acknowledgement = android.os.Bundle().apply {
                        putLong(PlaybackCommands.SnapshotRevision, revision)
                        putStringArrayList(PlaybackCommands.MediaIds, ArrayList(requested))
                    }
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, acknowledgement),
                    )
                }
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }

            override fun onAddMediaItems(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>,
            ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(
                mediaItems.map { requested ->
                    // App 内提交队列（setMediaItems）与后台补直链（replaceMediaItem）都会走这里，
                    // 而且请求里已经带着刚取到的直链。早期版本一律用 findQueueItem(id) 覆盖，
                    // 结果命中队列里同 mediaId 的旧 item（uri 为空或已过期），新直链被顶掉，
                    // 播到该首时 ExoPlayer 直接报 IO 网络错误——「播放失败：网络连接不上音源」。
                    // 因此：本 App 的请求、以及任何已带 uri 的请求一律原样放行；
                    // 只有外部控制器（车机浏览树点选）发来的「仅 mediaId」请求才补齐 uri。
                    val fromApp = runCatching {
                        controller.packageName == applicationContext.packageName
                    }.getOrDefault(false)
                    val hasUri = !requested.localConfiguration?.uri?.toString().isNullOrBlank()
                    val id = requested.mediaId
                    if (fromApp || hasUri || id.isBlank()) requested
                    else (findQueueItem(id) ?: requested)
                },
            )

            override fun onGetLibraryRoot(
                session: MediaLibraryService.MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
                LibraryResult.ofItem(folderItem(LIBRARY_ROOT_ID, "悦音"), params),
            )

            override fun onGetChildren(
                session: MediaLibraryService.MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(
                runCatching {
                    val children = when (parentId) {
                        LIBRARY_ROOT_ID -> listOf(folderItem(LIBRARY_QUEUE_ID, "当前队列"))
                        LIBRARY_QUEUE_ID -> pageOf(queueItems(), page, pageSize)
                        else -> emptyList()
                    }
                    LibraryResult.ofItemList(children, params)
                }.getOrDefault(
                    LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_UNKNOWN),
                ),
            )

            override fun onGetItem(
                session: MediaLibraryService.MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                mediaId: String,
            ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
                runCatching {
                    val item = when (mediaId) {
                        LIBRARY_ROOT_ID -> folderItem(LIBRARY_ROOT_ID, "悦音")
                        LIBRARY_QUEUE_ID -> folderItem(LIBRARY_QUEUE_ID, "当前队列")
                        else -> findQueueItem(mediaId)
                    }
                    if (item != null) LibraryResult.ofItem(item, null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }.getOrDefault(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)),
            )

            override fun onSearch(
                session: MediaLibraryService.MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                query: String,
                params: MediaLibraryService.LibraryParams?,
            ): ListenableFuture<LibraryResult<Void>> =
                Futures.immediateFuture(LibraryResult.ofVoid())

            override fun onGetSearchResult(
                session: MediaLibraryService.MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(
                runCatching {
                    val needle = query.trim()
                    val matched = if (needle.isEmpty()) {
                        emptyList()
                    } else {
                        queueItems().filter { item ->
                            item.mediaMetadata.title?.toString()
                                ?.contains(needle, ignoreCase = true) == true
                        }
                    }
                    LibraryResult.ofItemList(pageOf(matched, page, pageSize), params)
                }.getOrDefault(
                    LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_UNKNOWN),
                ),
            )
        }
        mediaSession = MediaLibraryService.MediaLibrarySession.Builder(
            this,
            RoutingPlayer(exoPlayer),
            libraryCallback,
        ).build()
    }

    /**
     * 车机浏览树只暴露当前播放队列：数据就在 [player] 里，
     * 不跨模块取曲库、不需要认证态，外部查询失败也只影响车机浏览，不影响 app 内播放。
     */
    private fun queueItems(): List<MediaItem> = runCatching {
        val exoPlayer = player ?: return emptyList()
        List(exoPlayer.mediaItemCount) { index -> exoPlayer.getMediaItemAt(index) }
    }.getOrDefault(emptyList())

    private fun findQueueItem(mediaId: String): MediaItem? =
        queueItems().firstOrNull { it.mediaId == mediaId }

    private fun folderItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                    .build(),
            )
            .build()

    private fun pageOf(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> = runCatching {
        val from = (page.toLong() * pageSize.coerceAtLeast(0)).coerceAtMost(items.size.toLong()).toInt()
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) items.drop(from)
        else items.drop(from).take(pageSize)
    }.getOrDefault(emptyList())

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaLibraryService.MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        fxProcessor?.let(AudioFxController::release)
        fxProcessor = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        runCatching { songCache?.release() }
        songCache = null
        super.onDestroy()
    }
}
