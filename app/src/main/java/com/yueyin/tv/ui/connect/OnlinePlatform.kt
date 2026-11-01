package com.yueyin.tv.ui.connect

import com.yueyin.tv.core.data.connect.ConnectProfile
import com.yueyin.tv.core.model.connect.ConnectType

/** 在线音乐平台在本地存储里的固定 id：反复导入音源不会堆出多条同类型平台。 */
internal const val ONLINE_MUSIC_PROFILE_ID = "online-music"

/**
 * 确保「在线音乐」平台存在、并且已经切过去。
 *
 * 用户导入音源脚本的意图只有一个：用在线音乐。以前这一步要自己走「平台 → 添加 → 在线音乐」
 * 才算启用，等于把「我已经想用了」这件事再确认一遍。这里直接替他做完。
 *
 * @return true 表示这次真的发生了切换（原本不在在线音乐上），调用方可据此提示一次。
 */
internal fun ensureOnlineMusicActive(): Boolean {
    val store = ConnectRuntime.store
    val before = store.state.value
    val existing = before.ofType(ConnectType.ONLINE)
    val profile = existing ?: ConnectProfile(
        id = ONLINE_MUSIC_PROFILE_ID,
        type = ConnectType.ONLINE,
        displayName = "在线音乐",
        host = "",
    ).also { store.upsert(it) }

    // 这里必须**无条件**调用 activate，不能因为「存储里的 activeId 已经是它」就跳过。
    //
    // 踩过的坑：upsert 在 activeId 为空时会顺手把新 profile 设成 active，于是
    // `activeId == profile.id` 成立，老代码直接 return —— 但那只写进了存储，
    // 没有人去通知宿主换数据源（onActiveChanged 只有 activate 会调）。
    // 结果就是：音源导进来了、平台页也显示「当前」，可首页/搜索还在用旧链路，
    // 一直「加载失败，请重试」，非得进平台页手动点一下那张卡才生效。
    // activate 本身是幂等的（落库 + 通知宿主），重复调用没有副作用。
    ConnectRuntime.activate(profile)

    // 返回「这次用户会看到变化」：新建了平台，或原本激活的不是它。
    return existing == null || before.activeId != profile.id
}
