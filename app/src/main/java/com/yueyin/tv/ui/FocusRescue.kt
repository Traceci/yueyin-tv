package com.yueyin.tv.ui

/**
 * 焦点急救：把「整棵视图树没有任何 View 持有焦点」的那一瞬间捞回来。
 *
 * 背景：Compose 焦点树里可能残留已经 detach 的节点（切页、播放页销毁、导航条重新挂载）。
 * 这时用户按方向键，ViewRootImpl 会走 `restoreDefaultFocus`，命中那个 detached 节点并抛
 * `IllegalStateException: visitAncestors called on an unattached node`，整个进程直接闪退
 * ——用户看到的就是「随便按一下键就退回桌面」。
 *
 * [com.yueyin.tv.MainActivity] 在按键派发前调用 [request]：能把焦点交给存活节点就照常派发，
 * 交不出去就吞掉这一次按键。少一次响应能忍，闪退不能忍。
 */
internal object FocusRescue {
    /** 由常驻的 AuthenticatedApp 注册；页面级 Composable 不参与，避免切页时来回覆盖。 */
    @Volatile
    var handler: (() -> Boolean)? = null

    /** 返回 true 表示焦点已经交给某个存活节点。 */
    fun request(): Boolean {
        val current = handler ?: return false
        return runCatching { current() }.getOrDefault(false)
    }
}
