package com.yueyin.tv

import com.yueyin.tv.core.data.preferences.AppPreferences
import com.yueyin.tv.core.data.repository.MusicRepository
import com.yueyin.tv.core.data.repository.SessionRepository
import com.yueyin.tv.core.playback.PlaybackController
import com.yueyin.tv.update.UpdateController

internal interface AuthenticatedAppDependencies {
    val appPreferences: AppPreferences
    val musicRepository: MusicRepository
    val playbackController: PlaybackController
    val nowPlayingPresenter: NowPlayingPresenter
    val artworkBitmapCache: ArtworkBitmapCache
    val authenticatedActions: AuthenticatedAppActions
    val updateController: UpdateController
}

internal interface AppUiDependencies : AuthenticatedAppDependencies {
    val sessionRepository: SessionRepository
}
