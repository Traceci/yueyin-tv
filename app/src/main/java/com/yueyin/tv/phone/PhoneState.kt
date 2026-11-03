package com.yueyin.tv.phone

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.yueyin.tv.AppUiDependencies
import com.yueyin.tv.core.data.repository.SessionState
import com.yueyin.tv.core.model.connect.ConnectType
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.ui.LibraryRetainedStateStore
import com.yueyin.tv.ui.connect.ConnectRuntime
import java.util.Calendar

/**
 * 手机壳的依赖入口。
 *
 * 为什么用 CompositionLocal 而不是层层传参：手机壳的页面层级是「壳 → Tab 页 → 网格 → 卡片」，
 * 每层都要用到 `container`（封面缓存）和保留状态仓库。逐层透传会让每个中间组件都多两个与自己
 * 无关的参数，加一个页面就要改一串签名。电视壳对保留状态就是这么做的（`LocalLibraryRetainedState`），
 * 这里保持同一种写法，两边迁移时不用换脑子。
 */
internal val LocalPhoneDeps = staticCompositionLocalOf<AppUiDependencies> {
    error("手机壳的页面必须在 PhoneApp 内使用：LocalPhoneDeps 未提供")
}

/**
 * 手机壳独立的保留状态仓库。
 *
 * 为什么不复用电视壳那一个：两边的状态生命周期不同。电视壳按路由栈增删页面来回收快照，
 * 手机壳的 Tab 切换是「换栈底」而不是压栈，同一个 `grid:albums` 在两边会被不同的路由生命周期
 * 管理，共用一个仓库会出现「在手机上清掉的快照让电视上正在看的列表变空」。
 */
internal val LocalPhoneStore = staticCompositionLocalOf<LibraryRetainedStateStore> {
    error("手机壳的页面必须在 PhoneApp 内使用：LocalPhoneStore 未提供")
}

/**
 * 问候语。
 *
 * 按小时分段而不是「上午/下午」两段：手机是私人设备，一天里被打开的次数远多于电视，
 * 早晚各一句不同的问候是这类 App 的常规礼貌。边界取 5/9/12/14/18/23，
 * 与国内主流音乐 App 的分段一致。
 */
internal fun phoneGreeting(
    hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
): String = when (hour) {
    in 5..8 -> "早上好"
    in 9..11 -> "上午好"
    in 12..13 -> "中午好"
    in 14..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

/**
 * 当前平台的显示名。
 *
 * 优先用用户自己给平台起的名字（`ConnectProfile.displayName`，比如 "nas"）——
 * 用户在多平台之间切换时，认的是自己起的名字，不是 `Navidrome` 这种类型名。
 * 没有自定义名才退到类型名，飞牛统一显示 `fnOS` 与电视端资料条同一口径。
 *
 * `active == null` 有两种完全不同的含义，必须靠会话区分，不能一律当成「未连接」：
 * - **登录的是真飞牛**（`server.guid != "local"`）：这是设计约定——飞牛走的是登录账号
 *   本身，不是一条 `ConnectProfile`，切换时 `ConnectRuntime.activate(null)` 就是
 *   「切回飞牛」。此时应显示 `fnOS`，与电视端 `AuthenticatedApp` 的判断一致。
 * - **本地会话且没激活任何平台**：才是真的「未连接平台」。
 */
internal fun phonePlatformLabel(session: SessionState.SignedIn? = null): String {
    val active = ConnectRuntime.store.state.value.active
    if (active == null) {
        val onFnSession = session != null && session.server.guid.value != "local"
        return if (onFnSession) "fnOS" else "未连接平台"
    }
    val custom = active.displayName?.takeIf { it.isNotBlank() }
    if (custom != null) return custom
    return when (active.type) {
        ConnectType.ONLINE -> "在线音乐"
        ConnectType.FEINIU -> "fnOS"
        ConnectType.NAVIDROME -> "Navidrome"
        ConnectType.SUBSONIC -> "Subsonic"
        ConnectType.JELLYFIN -> "Jellyfin"
        ConnectType.EMBY -> "Emby"
        else -> active.type.displayName
    }
}

/**
 * 当前数据源的稳定标识，用来把「最近播放」这类本机历史按平台隔离。
 *
 * 两种挂载方式各给一个前缀：
 * - 已保存的协议平台（`p:<profileId>`）—— 切回同一个平台历史还在；
 * - 内置飞牛主账号（`fn:<serverGuid>`）—— activeId 为 null 时它就是当前数据源，
 *   带上 server guid 是为了换一台 NAS 登录后不会读到上一台的记录。
 *
 * 只有 key 相等才认为历史属于当前平台。早期版本没有这个字段，那些记录会被丢弃——
 * 这正是要修的问题（跨平台残留点了播不了），宁可清空也不留在列表里。
 */
internal fun phonePlatformKey(session: SessionState.SignedIn): String {
    val activeId = ConnectRuntime.store.state.value.activeId
    return if (activeId != null) "p:$activeId" else "fn:${session.server.guid.value}"
}

/**
 * 首页副标题：`nas · 飞牛 fnOS · 2,184 首`。
 *
 * 三段都是「换了曲库就一定会变」的信息，放在一起是为了让用户一眼确认现在看的是谁家的库——
 * 多平台用户最常见的困惑就是「我切了平台，怎么列表没变」。
 *
 * 切到「在线音乐」时不再拼 NAS 名：那时曲库来自音源脚本，跟这台 NAS 没有关系，
 * 拼上去会变成「FN874 · 在线音乐 · 289 首」，用户会以为还在读 NAS 的库。
 */
internal fun phoneLibrarySubtitle(session: SessionState.SignedIn, trackTotal: Int?): String {
    val active = ConnectRuntime.store.state.value.active
    val onOnline = active?.type == ConnectType.ONLINE
    val server = session.server.name
        .takeIf { !onOnline && it.isNotBlank() && session.server.guid.value != "local" }
    val platform = phonePlatformLabel(session)
    return buildList {
        server?.let(::add)
        if (platform.isNotBlank() && platform != server) add(platform)
        trackTotal?.let { add("${phoneCount(it)} 首") }
    }.joinToString(" · ").ifBlank { "未连接平台" }
}

/** 千分位整数。手写而不用 `String.format`：后者跟系统 Locale 走，某些区域会输出 `2.184`。 */
internal fun phoneCount(value: Int): String {
    val digits = value.toString()
    if (digits.length <= 3) return digits
    val out = StringBuilder()
    digits.forEachIndexed { index, ch ->
        if (index > 0 && (digits.length - index) % 3 == 0) out.append(',')
        out.append(ch)
    }
    return out.toString()
}

/** `42 首` / `共 186 张` 这类次级说明；数量未知时不显示，避免出现「null 首」。 */
internal fun trackCountLabel(count: Int?): String? = count?.let { "${phoneCount(it)} 首" }

internal fun albumCountLabel(count: Int?): String? = count?.let { "共 ${phoneCount(it)} 张" }

/**
 * 曲目的收藏状态：优先取本次会话里的乐观状态，没有才回落到曲目自带字段。
 *
 * 收藏成功后服务端返回的列表里 `isFavorite` 可能还是旧的（尤其是分页缓存），
 * 只读曲目字段会出现「点了心形、按钮没变」；反过来只读状态表，进详情页第一次又没有记录。
 * 两处取交集才是用户真正看到的那份状态。
 */
internal fun favoriteOf(track: Track, statuses: Map<String, Boolean>): Boolean =
    statuses[track.guid.value] ?: track.isFavorite

// ---------------------------------------------------------------------------
// 搜索历史
// ---------------------------------------------------------------------------

/**
 * 搜索历史落在 SharedPreferences 而不是数据库。
 *
 * 理由：它只是一串关键词（最多 8 条），没有查询需求、没有跨表关联，也不需要跟账号走——
 * 手机上的搜索历史是「这台设备最近找过什么」，换账号继续用同一份才符合直觉。
 * 为了它开一张 Room 表、写迁移，成本远大于收益。
 */
private const val SEARCH_HISTORY_PREFS = "phone_search_history"
private const val SEARCH_HISTORY_KEY = "keywords"

/** 上限 8 条：手机搜索页的 chip 区域放下 2 行正好，再多用户也不会点。 */
private const val SEARCH_HISTORY_LIMIT = 8

internal fun phoneSearchHistory(context: Context): List<String> =
    context.getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
        .getString(SEARCH_HISTORY_KEY, null)
        ?.split('\u0001')
        ?.filter { it.isNotBlank() }
        .orEmpty()

/**
 * 记一条搜索历史，返回最新列表。
 *
 * 已存在的关键词挪到最前而不是重复插入：用户反复搜同一个词时，
 * 期望它固定在第一位，而不是在列表里出现八次。
 */
internal fun phoneRememberSearch(context: Context, keyword: String): List<String> {
    val trimmed = keyword.trim()
    if (trimmed.isEmpty()) return phoneSearchHistory(context)
    val next = (listOf(trimmed) + phoneSearchHistory(context).filterNot { it == trimmed })
        .take(SEARCH_HISTORY_LIMIT)
    context.getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(SEARCH_HISTORY_KEY, next.joinToString("\u0001"))
        .apply()
    return next
}

internal fun phoneClearSearchHistory(context: Context): List<String> {
    context.getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(SEARCH_HISTORY_KEY)
        .apply()
    return emptyList()
}

// ---------------------------------------------------------------------------
// 播放页封面呈现
// ---------------------------------------------------------------------------

/**
 * 播放页中间那块图的两种呈现：封面（方形原图）/ 唱片（黑胶）。
 *
 * 只存在手机壳里，不进 core。core 的 `PlayerStyle` 是电视端那三档（封面 / 海报 / 歌词），
 * 手机端的「唱片」并不属于它；硬塞进去会让电视端多出一个点不到、也画不出的选项。
 * 海报样式已从手机端下线，历史存档里的 `PlayerStyle.Poster` 读到时回落到封面。
 */
internal enum class PhoneViewMode { Cover, Vinyl }

private const val PLAYER_VIEW_PREFS = "phone_player_view"
private const val PLAYER_VIEW_KEY = "mode"

internal fun phoneViewMode(context: Context): PhoneViewMode {
    val raw = context.getSharedPreferences(PLAYER_VIEW_PREFS, Context.MODE_PRIVATE)
        .getString(PLAYER_VIEW_KEY, null)
        ?: return PhoneViewMode.Cover
    return PhoneViewMode.entries.firstOrNull { it.name == raw } ?: PhoneViewMode.Cover
}

internal fun phoneSetViewMode(context: Context, mode: PhoneViewMode) {
    context.getSharedPreferences(PLAYER_VIEW_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PLAYER_VIEW_KEY, mode.name)
        .apply()
}

// ---------------------------------------------------------------------------
// 首次启动引导
// ---------------------------------------------------------------------------

/**
 * 引导是否已看过。
 *
 * 用自己的偏好文件而不是 `appPreferences`：它描述的是「这台设备装过没、看过没」，
 * 跟账号、跟电视端都没有关系；key 带 v1 是为了以后引导改版时能再弹一次，
 * 不用把「看过」这件事和版本号绑到一起。
 */
private const val ONBOARDING_PREFS = "phone_onboarding"
private const val ONBOARDING_KEY = "guide_seen_v1"

internal fun phoneGuideSeen(context: Context): Boolean =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .getBoolean(ONBOARDING_KEY, false)

internal fun phoneSetGuideSeen(context: Context) {
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(ONBOARDING_KEY, true)
        .apply()
}
