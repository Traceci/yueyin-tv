package com.yueyin.tv.core.model.library

/**
 * 曲库能力项：把「将来可能需要整体摘除的模块」收敛成可注销的开关。
 *
 * 重要边界：能力项只控制入口与注册，**不会删除代码，也不会改变任何网络请求行为**。
 * 它解决的是工程上的「可摘除性」，不是版权合规。各音乐平台接口代码是否保留、
 * 是否继续调用，属于发布决策；本类不对此作任何判断。
 *
 * ── 摘除清单（供将来直接使用，不必重新排查）────────────────────────────
 *
 * 【只摘内置平台接口（版权敏感部分），保留用户自带脚本】
 *  1. 从 [LibraryCapabilities] 的禁用集合中加入 [LibraryCapability.BuiltinOnlineApi]
 *     （或直接从默认集合移除，一处改动）。
 *  2. 删除 core/data 的 online/OnlineMusicApi.kt、online/OnlineDiscoverApi.kt
 *     及 OnlineMusicRepository 里调用这两者的内置接口分支。
 *  3. 两端发现页 / 五平台聚合入口由 has(BuiltinOnlineApi) 守卫，摘除后自动隐藏；
 *     本地音乐、协议平台、播放器、数据库、收藏均不受影响。
 *
 * 【整体摘除在线音乐】
 *  4. 再禁用 [LibraryCapability.UserScriptRuntime]，并删除：
 *     core/data/protocol/OnlineMusicProtocol.kt、core/data/source 包、
 *     app/source/OnlineSourceRuntime.kt、OnlinePlaybackResolver.kt、OnlineScriptSearch.kt、
 *     app/ui/source 包、app/phone/PhoneSourcePage.kt、app/ui/connect/OnlinePlatform.kt。
 *  5. 注销 ConnectRuntime 注册表中的在线音乐一行；UI 入口由能力表驱动自动隐藏。
 *
 * 本地曲库实现位于 core/data/.../localmusic/，**禁止 import 任何 online / source 包**，
 * 因此上述任何摘除都不会牵连本地播放。
 */
enum class LibraryCapability {
    /** 本机 MediaStore 音乐库。 */
    LocalMusic,

    /** 飞牛 / Jellyfin / Subsonic / WebDAV / SMB 等协议平台。 */
    ProtocolPlatform,

    /** App 内置的五大平台搜索、专辑与发现页接口（版权敏感）。 */
    BuiltinOnlineApi,

    /** 用户自行导入的 LX 音源脚本 + WebView 运行时。 */
    UserScriptRuntime,
}

object LibraryCapabilities {
    private val all: Set<LibraryCapability> = LibraryCapability.entries.toSet()

    @Volatile
    private var disabled: Set<LibraryCapability> = emptySet()

    fun has(capability: LibraryCapability): Boolean = capability !in disabled

    fun disable(capability: LibraryCapability) {
        disabled = disabled + capability
    }

    fun enable(capability: LibraryCapability) {
        disabled = disabled - capability
    }

    fun enabled(): Set<LibraryCapability> = all - disabled

    /** 在线音乐入口是否整体可用：两个能力项任一存在即可。 */
    val onlineMusicAvailable: Boolean
        get() = has(LibraryCapability.BuiltinOnlineApi) || has(LibraryCapability.UserScriptRuntime)
}
