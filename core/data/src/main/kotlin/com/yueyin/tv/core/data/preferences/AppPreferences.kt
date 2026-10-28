package com.yueyin.tv.core.data.preferences

import android.content.Context
import com.yueyin.tv.core.data.local.LocalStore
import com.yueyin.tv.core.model.PlayerStyle
import com.yueyin.tv.core.model.preferences.AppPreferencesState
import com.yueyin.tv.core.model.preferences.CacheBudget
import com.yueyin.tv.core.model.preferences.AmbientEffect
import com.yueyin.tv.core.model.preferences.UiScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppPreferences(context: Context, private val localStore: LocalStore) {
    private val store = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppPreferencesState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var namespace: String? = null

    suspend fun bindNamespace(value: String) {
        namespace = value
        val account = localStore.account(value)
        val migratedNamespace = store.getString(MIGRATED_NAMESPACE, null)
        var initial = when {
            account != null -> AppPreferencesState(
                playerStyle = account.playerStyle?.let { runCatching { PlayerStyle.valueOf(it) }.getOrNull() } ?: PlayerStyle.Cover,
                cacheBudget = account.cacheBudget?.let { runCatching { CacheBudget.valueOf(it) }.getOrNull() } ?: CacheBudget.Default,
                onlineLyricsMatchingEnabled = account.onlineLyricsMatchingEnabled,
                playlistOrder = readPlaylistOrder(value),
                backgroundContinueOnExit = store.getBoolean(BACKGROUND_CONTINUE_ON_EXIT, false),
                uiScale = readUiScale(),
                ambientEffect = readAmbientEffect(),
                dynamicColor = readDynamicColor(),
                karaokeFill = readKaraokeFill(),
                blurredBackdrop = readBlurredBackdrop(),
            )
            migratedNamespace == null -> read().also { store.edit().putString(MIGRATED_NAMESPACE, value).apply() }
            else -> AppPreferencesState(
                playlistOrder = readPlaylistOrder(value),
                backgroundContinueOnExit = store.getBoolean(BACKGROUND_CONTINUE_ON_EXIT, false),
                uiScale = readUiScale(),
                ambientEffect = readAmbientEffect(),
                dynamicColor = readDynamicColor(),
                karaokeFill = readKaraokeFill(),
                blurredBackdrop = readBlurredBackdrop(),
            )
        }
        // 一次性迁移：早期版本默认值写错，会把用户从未选择过的“大海报模式”写进存储。
        // 从本版本起统一回默认 CD 模式；之后用户显式切换过的大海报仍然保留。
        if (!store.getBoolean(POSTER_DEFAULT_MIGRATED, false)) {
            store.edit().putBoolean(POSTER_DEFAULT_MIGRATED, true).apply()
            if (initial.playerStyle == PlayerStyle.Poster) {
                initial = initial.copy(playerStyle = PlayerStyle.Cover)
            }
        }
        _state.value = initial
        store.edit()
            .putString(PLAYER_STYLE, initial.playerStyle.name)
            .putString(CACHE_BUDGET, initial.cacheBudget.name)
            .putBoolean(ONLINE_LYRICS_MATCHING, initial.onlineLyricsMatchingEnabled)
            .apply()
        localStore.saveSettings(
            value,
            initial.playerStyle.name,
            initial.cacheBudget.name,
            initial.onlineLyricsMatchingEnabled,
        )
    }

    fun setPlayerStyle(style: PlayerStyle) {
        store.edit().putString(PLAYER_STYLE, style.name).apply()
        _state.value = _state.value.copy(playerStyle = style)
        persist()
    }

    fun setCacheBudget(budget: CacheBudget) {
        store.edit().putString(CACHE_BUDGET, budget.name).apply()
        _state.value = _state.value.copy(cacheBudget = budget)
        persist()
    }

    fun setOnlineLyricsMatchingEnabled(enabled: Boolean) {
        store.edit().putBoolean(ONLINE_LYRICS_MATCHING, enabled).apply()
        _state.value = _state.value.copy(onlineLyricsMatchingEnabled = enabled)
        persist()
    }

    /** 当前账号自定义的歌单排序；空列表表示沿用服务器默认顺序。 */
    fun setPlaylistOrder(order: List<String>) {
        val currentNamespace = namespace ?: return
        val key = playlistOrderKey(currentNamespace)
        store.edit().putString(key, order.joinToString(PROFILE_SEPARATOR)).apply()
        _state.value = _state.value.copy(playlistOrder = order)
        persist()
    }

    fun backgroundContinueOnExit(): Boolean = store.getBoolean(BACKGROUND_CONTINUE_ON_EXIT, false)

    fun setBackgroundContinueOnExit(enabled: Boolean) {
        store.edit().putBoolean(BACKGROUND_CONTINUE_ON_EXIT, enabled).apply()
        _state.value = _state.value.copy(backgroundContinueOnExit = enabled)
    }

    fun setUiScale(scale: UiScale) {
        store.edit().putString(UI_SCALE, scale.name).apply()
        _state.value = _state.value.copy(uiScale = scale)
    }

    fun setAmbientEffect(value: AmbientEffect) {
        store.edit().putString(AMBIENT_EFFECT, value.name).apply()
        _state.value = _state.value.copy(ambientEffect = value)
    }

    fun setDynamicColor(enabled: Boolean) {
        store.edit().putBoolean(DYNAMIC_COLOR, enabled).apply()
        _state.value = _state.value.copy(dynamicColor = enabled)
    }

    fun setKaraokeFill(enabled: Boolean) {
        store.edit().putBoolean(KARAOKE_FILL, enabled).apply()
        _state.value = _state.value.copy(karaokeFill = enabled)
    }

    fun setBlurredBackdrop(enabled: Boolean) {
        store.edit().putBoolean(BLURRED_BACKDROP, enabled).apply()
        _state.value = _state.value.copy(blurredBackdrop = enabled)
    }

    /** 是否已经看过遥控器按键说明（首次安装后展示一次，之后不再自动弹出）。 */
    fun remoteControlGuideSeen(): Boolean = store.getBoolean(REMOTE_CONTROL_GUIDE_SEEN, false)

    fun setRemoteControlGuideSeen() {
        store.edit().putBoolean(REMOTE_CONTROL_GUIDE_SEEN, true).apply()
    }

    private fun readPlaylistOrder(currentNamespace: String): List<String> {
        val raw = store.getString(playlistOrderKey(currentNamespace), null) ?: return emptyList()
        return raw.split(PROFILE_SEPARATOR).filter(String::isNotBlank)
    }

    private fun persist() {
        val currentNamespace = namespace ?: return
        val current = _state.value
        scope.launch {
            localStore.saveSettings(
                currentNamespace,
                current.playerStyle.name,
                current.cacheBudget.name,
                current.onlineLyricsMatchingEnabled,
            )
        }
    }

    private fun readUiScale(): UiScale = UiScale.of(store.getString(UI_SCALE, null))
    private fun readAmbientEffect(): AmbientEffect = AmbientEffect.of(store.getString(AMBIENT_EFFECT, null))
    private fun readDynamicColor(): Boolean = store.getBoolean(DYNAMIC_COLOR, true)
    private fun readKaraokeFill(): Boolean = store.getBoolean(KARAOKE_FILL, true)
    private fun readBlurredBackdrop(): Boolean = store.getBoolean(BLURRED_BACKDROP, true)

    private fun read() = AppPreferencesState(
        playerStyle = store.getString(PLAYER_STYLE, null)?.let { runCatching { PlayerStyle.valueOf(it) }.getOrNull() }
            ?: PlayerStyle.Cover,
        cacheBudget = store.getString(CACHE_BUDGET, null)?.let { runCatching { CacheBudget.valueOf(it) }.getOrNull() }
            ?: CacheBudget.Default,
        onlineLyricsMatchingEnabled = store.getBoolean(ONLINE_LYRICS_MATCHING, true),
        playlistOrder = namespace?.let(::readPlaylistOrder).orEmpty(),
        backgroundContinueOnExit = store.getBoolean(BACKGROUND_CONTINUE_ON_EXIT, false),
        uiScale = readUiScale(),
        ambientEffect = readAmbientEffect(),
        dynamicColor = readDynamicColor(),
        karaokeFill = readKaraokeFill(),
        blurredBackdrop = readBlurredBackdrop(),
    )

    private companion object {
        const val PLAYER_STYLE = "player_style"
        const val CACHE_BUDGET = "cache_budget"
        const val ONLINE_LYRICS_MATCHING = "online_lyrics_matching"
        const val MIGRATED_NAMESPACE = "room_migrated_namespace"
        const val BACKGROUND_CONTINUE_ON_EXIT = "background_continue_on_exit"
        const val REMOTE_CONTROL_GUIDE_SEEN = "remote_control_guide_seen"
        const val UI_SCALE = "ui_scale"
        const val AMBIENT_EFFECT = "ambient_effect"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val KARAOKE_FILL = "karaoke_fill"
        const val BLURRED_BACKDROP = "blurred_backdrop"
        const val POSTER_DEFAULT_MIGRATED = "poster_default_migrated_v2"
        const val PLAY_LIST_ORDER_PREFIX = "playlist_order_"
        const val PROFILE_SEPARATOR = "\u0001"

        private fun playlistOrderKey(namespace: String) = "$PLAY_LIST_ORDER_PREFIX$namespace"
    }
}
