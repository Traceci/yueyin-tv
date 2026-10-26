package com.fnmusic.tv

import android.app.Application
import com.fnmusic.tv.core.data.repository.SessionRepository
import com.fnmusic.tv.core.data.repository.MusicRepository
import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.playback.PlaybackController
import com.fnmusic.tv.core.data.local.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.fnmusic.tv.update.UpdateCoordinator

class TvMusicApplication : Application() {
    internal val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLogStore.install(this)
        com.fnmusic.tv.ui.connect.ConnectRuntime.init(this)
        container.startPlaybackRuntime()
    }
}

internal class AppContainer(private val application: Application) : AppUiDependencies {
    private val localStore = LocalStore(application)
    override val sessionRepository = SessionRepository(application)
    override val appPreferences = AppPreferences(application, localStore)
    override val musicRepository = MusicRepository(application, sessionRepository, appPreferences, localStore)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val updateController: UpdateCoordinator = UpdateCoordinator(application, applicationScope)
    internal val updateCoordinator: UpdateCoordinator get() = updateController
    override val artworkBitmapCache = ArtworkBitmapCache(
        scope = applicationScope,
        loader = { coverId, variant ->
            musicRepository.artwork(coverId, variant)?.let { bytes ->
                decodeArtwork(bytes, variant.width ?: 1_200)
            }
        },
    )
    override val playbackController = PlaybackController(
        application,
        LocalPlaybackSessionStore(localStore),
        RepositoryPlaybackContentSource(musicRepository),
    )
    override val nowPlayingPresenter = NowPlayingPresenter(
        playbackController,
        musicRepository,
        appPreferences,
        applicationScope,
    )
    init {
        // 启动即按"当前激活平台"决定数据源：飞牛 → 走原有链路（protocolSource=null）；
        // 其它平台 → 装一个协议数据源，首页/歌单/播放/搜索自动变成该平台的内容。
        com.fnmusic.tv.ui.connect.ConnectRuntime.onActiveChanged = { profile -> applyProtocolSource(profile) }
        applyProtocolSource(com.fnmusic.tv.ui.connect.ConnectRuntime.store.state.value.active)
    }

    private fun applyProtocolSource(profile: com.fnmusic.tv.core.data.connect.ConnectProfile?) {
        val source = if (profile != null && profile.type != com.fnmusic.tv.core.model.connect.ConnectType.FEINIU) {
            com.fnmusic.tv.core.data.protocol.ProtocolLibrarySource(
                profile = profile,
                protocol = com.fnmusic.tv.ui.connect.ConnectRuntime.registry.of(profile),
            )
        } else {
            null
        }
        musicRepository.setProtocolSource(source)
    }

    private val coordinator = AuthenticatedAppCoordinator(
        application = application,
        sessionRepository = sessionRepository,
        musicRepository = musicRepository,
        appPreferences = appPreferences,
        playbackController = playbackController,
        artworkBitmapCache = artworkBitmapCache,
        nowPlayingPresenter = nowPlayingPresenter,
        applicationScope = applicationScope,
    )
    override val authenticatedActions: AuthenticatedAppActions = coordinator

    fun startPlaybackRuntime() {
        coordinator.start()
    }

    suspend fun shutdownForExit() {
        updateCoordinator.shutdownForExit()
        coordinator.shutdownForExit()
    }
}
