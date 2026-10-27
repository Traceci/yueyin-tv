package com.yueyin.tv.core.data.playback

import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.model.playback.HlsReason
import com.yueyin.tv.core.model.playback.PlaybackSource

class ResolvePlaybackSource {
    operator fun invoke(track: Track, directDecoderFailed: Boolean = false): PlaybackSource = when {
        track.isCue -> PlaybackSource.Hls(track, HlsReason.CueTrack)
        directDecoderFailed -> PlaybackSource.Hls(track, HlsReason.DecoderFallback)
        else -> PlaybackSource.Direct(track)
    }
}
