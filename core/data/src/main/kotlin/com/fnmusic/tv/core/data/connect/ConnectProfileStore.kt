package com.fnmusic.tv.core.data.connect

import android.content.Context
import com.fnmusic.tv.core.model.connect.ConnectType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一个已保存的平台连接（凭据 + 地址）。
 *
 * 一套字段同时容纳各平台的鉴权差异：
 * - 飞牛 / Subsonic / Jellyfin：走 [username] + [password]（登录后再换 token）
 * - Jellyfin / Emby：登录后把 AccessToken / UserId 回填到 [token] / [userId]
 * - 自签名证书：把 [ignoreTls] 打开（仅对该 profile 生效）
 */
@Serializable
data class ConnectProfile(
    val id: String,
    val type: ConnectType,
    val displayName: String,
    val host: String,
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val userId: String = "",
    val ignoreTls: Boolean = false,
)

/** 全部已保存的平台 + 当前激活的平台 id。 */
data class ConnectProfilesState(
    val profiles: List<ConnectProfile> = emptyList(),
    val activeId: String? = null,
) {
    val active: ConnectProfile? get() = profiles.firstOrNull { it.id == activeId }
    fun ofType(type: ConnectType): ConnectProfile? = profiles.firstOrNull { it.type == type }
}

/**
 * 多平台凭据存储。
 *
 * 用 SharedPreferences + JSON 而不是 Room：这份数据读多写少、体量极小，
 * 而现有 AppDatabase 带迁移与迁移测试，为它加表要连带补迁移——不值得为此冒升级出错的风险。
 */
class ConnectProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("connect_profiles", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ConnectProfilesState> = _state.asStateFlow()

    /** 新增或覆盖一个平台（按 id 去重）。返回保存后的状态。 */
    fun upsert(profile: ConnectProfile): ConnectProfilesState {
        val current = _state.value
        val next = current.profiles.filterNot { it.id == profile.id } + profile
        return write(next, current.activeId ?: profile.id)
    }

    fun remove(id: String) {
        val current = _state.value
        val next = current.profiles.filterNot { it.id == id }
        val activeId = if (current.activeId == id) next.firstOrNull()?.id else current.activeId
        write(next, activeId)
    }

    fun setActive(id: String?) {
        write(_state.value.profiles, id)
    }

    private fun write(profiles: List<ConnectProfile>, activeId: String?): ConnectProfilesState {
        val encoded = runCatching { json.encodeToString(profiles) }.getOrElse { "[]" }
        prefs.edit().putString(KEY_PROFILES, encoded).putString(KEY_ACTIVE, activeId).apply()
        val next = ConnectProfilesState(profiles, activeId)
        _state.value = next
        return next
    }

    private fun read(): ConnectProfilesState {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return ConnectProfilesState()
        val profiles = runCatching { json.decodeFromString<List<ConnectProfile>>(raw) }
            .getOrElse { emptyList() }
        return ConnectProfilesState(
            profiles = profiles,
            activeId = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> profiles.any { it.id == id } },
        )
    }

    private companion object {
        const val KEY_PROFILES = "profiles_json"
        const val KEY_ACTIVE = "active_profile_id"
    }
}
