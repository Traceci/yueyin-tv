package com.yueyin.tv

import com.yueyin.tv.core.data.local.LocalStore
import com.yueyin.tv.core.data.repository.MusicRepository
import com.yueyin.tv.core.model.Page
import com.yueyin.tv.core.model.PlaybackTrack
import com.yueyin.tv.core.model.RoamWindow
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.model.playback.QueueSource
import com.yueyin.tv.core.playback.PlaybackContentSource
import com.yueyin.tv.core.playback.PlaybackSessionStore
import com.yueyin.tv.core.playback.StoredPlaybackSession

internal class LocalPlaybackSessionStore(
    private val localStore: LocalStore,
) : PlaybackSessionStore {
    override suspend fun read(namespace: String): StoredPlaybackSession? =
        localStore.account(namespace)?.let { account ->
            StoredPlaybackSession(account.queueJson, account.frozenQueueJson)
        }

    override suspend fun save(namespace: String, snapshotJson: String?) {
        localStore.savePlaybackSnapshot(namespace, snapshotJson)
    }

    override suspend fun clear(namespace: String) {
        localStore.clearNamespace(namespace, includeEssential = true)
    }
}

internal class RepositoryPlaybackContentSource(
    private val repository: MusicRepository,
) : PlaybackContentSource {
    override suspend fun queuePage(source: QueueSource, page: Int): Page<Track> =
        repository.queuePage(source, page)

    override suspend fun prepare(track: Track): PlaybackTrack = repository.prepare(track)

    override fun prepareQueue(tracks: List<Track>): List<PlaybackTrack> = repository.prepareQueue(tracks)

    override suspend fun startRoam(): RoamWindow? = repository.startRoam()

    override suspend fun nextRoam(roamId: String): RoamWindow = repository.nextRoam(roamId)

    override suspend fun previousRoam(roamId: String): RoamWindow = repository.previousRoam(roamId)
}
