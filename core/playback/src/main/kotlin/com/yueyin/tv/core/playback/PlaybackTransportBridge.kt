package com.yueyin.tv.core.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

enum class PlaybackTransportOwnership { Restoring, Normal, Roam }

enum class PlaybackTransportDirection { Previous, Next }

object PlaybackTransportBridge {
    private data class Registration(
        val ownership: PlaybackTransportOwnership,
        val handler: ((PlaybackTransportDirection) -> Unit)?,
    )

    private val registration = AtomicReference(
        Registration(PlaybackTransportOwnership.Restoring, null),
    )
    private val ownershipListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 空直链守卫。
     *
     * 队列里除开头几首外都是以空 uri 入队的（见 ProtocolLibrarySource.queueEager），
     * 而媒体键 / 车机 / 通知栏的「下一首」是直接打到播放内核上的，不经过 PlaybackController。
     * 只要放行一次，ExoPlayer 就会拿空 uri 去加载、报「音源文件不存在」，并且连锁污染后续曲目。
     *
     * 所以切歌动作统一先问这里：返回 true 表示可以原生切过去（目标已有直链），
     * 返回 false 表示已经拦截并转交 PlaybackController 去取链，取到后再切。
     * 由 [PlaybackController] 注册；它是同进程内的回调，不跨进程。
     */
    @Volatile
    internal var skipGuard: ((targetIndex: Int) -> Boolean)? = null

    /** 返回 true 表示放行；没有注册守卫（例如服务刚起来）时一律放行。 */
    internal fun canSkipTo(targetIndex: Int): Boolean {
        if (targetIndex < 0) return true
        return skipGuard?.invoke(targetIndex) ?: true
    }

    fun register(handler: (PlaybackTransportDirection) -> Unit) {
        updateRegistration { it.copy(handler = handler) }
    }

    fun setOwnership(ownership: PlaybackTransportOwnership) {
        val previous = updateRegistration { it.copy(ownership = ownership) }
        if (previous.ownership != ownership) ownershipListeners.forEach { it() }
    }

    fun unregister() {
        skipGuard = null
        val previous = registration.getAndSet(
            Registration(PlaybackTransportOwnership.Restoring, null),
        )
        if (previous.ownership != PlaybackTransportOwnership.Restoring) {
            ownershipListeners.forEach { it() }
        }
    }

    internal fun route(direction: PlaybackTransportDirection): PlaybackTransportOwnership {
        val current = registration.get()
        if (current.ownership == PlaybackTransportOwnership.Roam) {
            val handler = current.handler ?: return PlaybackTransportOwnership.Restoring
            handler(direction)
        }
        return current.ownership
    }

    internal fun ownership(): PlaybackTransportOwnership = registration.get().ownership

    internal fun addOwnershipListener(listener: () -> Unit) {
        ownershipListeners += listener
    }

    internal fun removeOwnershipListener(listener: () -> Unit) {
        ownershipListeners -= listener
    }

    private fun updateRegistration(transform: (Registration) -> Registration): Registration {
        while (true) {
            val current = registration.get()
            if (registration.compareAndSet(current, transform(current))) return current
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class RoutingPlayer(
    private val delegate: Player,
) : ForwardingPlayer(delegate) {
    private val ownershipCallbacks = ConcurrentHashMap<Player.Listener, () -> Unit>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        val callback = { listener.onAvailableCommandsChanged(availableCommands) }
        ownershipCallbacks[listener] = callback
        PlaybackTransportBridge.addOwnershipListener(callback)
    }

    override fun removeListener(listener: Player.Listener) {
        ownershipCallbacks.remove(listener)?.let(PlaybackTransportBridge::removeOwnershipListener)
        super.removeListener(listener)
    }

    override fun getAvailableCommands(): Player.Commands {
        val commands = delegate.availableCommands
        if (PlaybackTransportBridge.ownership() != PlaybackTransportOwnership.Roam) return commands
        return Player.Commands.Builder()
            .addAll(commands)
            .addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
            )
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        if (PlaybackTransportBridge.ownership() == PlaybackTransportOwnership.Roam && command in ROAM_COMMANDS) true
        else delegate.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean =
        PlaybackTransportBridge.ownership() == PlaybackTransportOwnership.Roam || delegate.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean =
        PlaybackTransportBridge.ownership() == PlaybackTransportOwnership.Roam || delegate.hasPreviousMediaItem()

    override fun seekToNextMediaItem() = routeNext()

    override fun seekToNext() = routeNext()

    override fun seekToPreviousMediaItem() = routePrevious()

    override fun seekToPrevious() = routePrevious()

    /**
     * 队列面板点选 / 外部控制器指定某一首也走同一个守卫。
     *
     * 内部 routeNext/routePrevious 用的是 `delegate.seekTo(...)`（不走这个重载），
     * 所以这里只拦「外部直接点某一首」的调用，不会重复拦截。
     */
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (!PlaybackTransportBridge.canSkipTo(mediaItemIndex)) return
        delegate.seekTo(mediaItemIndex, positionMs)
    }

    private fun routeNext() {
        when (PlaybackTransportBridge.route(PlaybackTransportDirection.Next)) {
            PlaybackTransportOwnership.Normal -> {
                if (delegate.repeatMode == Player.REPEAT_MODE_ONE && delegate.mediaItemCount > 1) {
                    val nextIndex = (delegate.currentMediaItemIndex + 1) % delegate.mediaItemCount
                    if (!PlaybackTransportBridge.canSkipTo(nextIndex)) return
                    delegate.seekTo(nextIndex, 0L)
                } else {
                    // 下一首还没拿到直链时不能放行：空 uri 会被 ExoPlayer 当成文件路径，
                    // 直接报「音源文件不存在」，而且会连锁拖垮后面几首。
                    if (!PlaybackTransportBridge.canSkipTo(delegate.nextMediaItemIndex)) return
                    delegate.seekToNextMediaItem()
                }
            }
            PlaybackTransportOwnership.Roam,
            PlaybackTransportOwnership.Restoring,
            -> Unit
        }
    }

    private fun routePrevious() {
        when (PlaybackTransportBridge.route(PlaybackTransportDirection.Previous)) {
            PlaybackTransportOwnership.Normal -> {
                if (delegate.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
                    delegate.seekTo(0L)
                } else if (delegate.repeatMode == Player.REPEAT_MODE_ONE && delegate.mediaItemCount > 1) {
                    val previousIndex = (delegate.currentMediaItemIndex - 1 + delegate.mediaItemCount) %
                        delegate.mediaItemCount
                    if (!PlaybackTransportBridge.canSkipTo(previousIndex)) return
                    delegate.seekTo(previousIndex, 0L)
                } else {
                    if (!PlaybackTransportBridge.canSkipTo(delegate.previousMediaItemIndex)) return
                    delegate.seekToPreviousMediaItem()
                }
            }
            PlaybackTransportOwnership.Roam,
            PlaybackTransportOwnership.Restoring,
            -> Unit
        }
    }

    private companion object {
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        val ROAM_COMMANDS = setOf(
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
        )
    }
}
