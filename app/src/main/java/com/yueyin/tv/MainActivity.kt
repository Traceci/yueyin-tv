package com.yueyin.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.yueyin.tv.core.audiofx.FxPreset
import com.yueyin.tv.core.audiofx.FxSettings
import com.yueyin.tv.core.playback.AudioFxController
import com.yueyin.tv.ui.FocusRescue
import com.yueyin.tv.ui.YueYinApp
import com.yueyin.tv.update.UpdateEffect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var isExiting = false
    private var focusRescueMisses = 0
    private val appContainer: AppContainer get() = (application as TvMusicApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bindAudioFxPreferences()
        val container = appContainer
        setContent {
            YueYinApp(container) { exitApplication(container) }
        }
        lifecycleScope.launch {
            container.updateCoordinator.effects.collectLatest { effect ->
                when (effect) {
                    is UpdateEffect.LaunchIntent -> runCatching { startActivity(effect.intent) }
                        .onFailure { container.updateCoordinator.handleIntentLaunchFailure() }
                }
            }
        }
    }

    /**
     * 音效设置持久化。
     *
     * 音效状态由 `AudioFxController` 统一持有（播放条与设置页共用一份），
     * 但它活在 core:playback 里、拿不到 Context。这里在 Activity 启动时把
     * 「读」和「写」两头接上：启动时恢复上次选的档位，之后每次切换自动落盘。
     * 不接的话用户选了「低音」，杀掉进程再进来又变回「无」。
     */
    private fun bindAudioFxPreferences() {
        val store = getSharedPreferences("audio_fx", MODE_PRIVATE)
        val presetId = store.getString("preset", FxPreset.NONE.id) ?: FxPreset.NONE.id
        val enabled = store.getBoolean("enabled", false)
        AudioFxController.restore(
            FxPreset.fromId(presetId).toSettings(FxSettings()).copy(enabled = enabled),
        )
        AudioFxController.bindPersistence { settings ->
            store.edit()
                .putString("preset", settings.presetId)
                .putBoolean("enabled", settings.enabled)
                .apply()
        }
    }

    /**
     * 遥控器 / 蓝牙音响 / HDMI-CEC 上的播放媒体键：交给播放器直接处理，
     * 不依赖当前焦点停在哪个控件上。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 「整棵视图树没有任何 View 持有焦点」时按方向/确定键，ViewRootImpl 会执行
        // restoreDefaultFocus；此时 Compose 焦点树里若残留已 detach 的节点（播放页退出、
        // 切页过渡、导航条重新挂载都会造成），就会抛
        // IllegalStateException: visitAncestors called on an unattached node，直接闪退。
        // 所以派发前先把焦点抢救回一个存活节点；抢救不回来就丢掉这一次按键（并排一次重试），
        // 连续几次都救不回来才放行——那说明当前页面本来就没有导航条可落。
        if (event.action == KeyEvent.ACTION_DOWN &&
            currentFocus == null &&
            event.isFocusNavigationKey()
        ) {
            if (FocusRescue.request()) {
                focusRescueMisses = 0
            } else if (++focusRescueMisses <= MAX_FOCUS_RESCUE_MISSES) {
                window.decorView.post { FocusRescue.request() }
                return true
            } else {
                focusRescueMisses = 0
            }
        }
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    performMediaKey(event.keyCode)
                }
                true
            }

            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    private fun performMediaKey(keyCode: Int) {
        val controller = runCatching { appContainer.playbackController }.getOrNull() ?: return
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> controller.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controller.previousTrack()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (!controller.state.value.isPlaying) controller.playPause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (controller.state.value.isPlaying) controller.playPause()
            else -> controller.playPause()
        }
    }

    override fun onStart() {
        super.onStart()
        appContainer.updateCoordinator.onForeground()
    }

    override fun onResume() {
        super.onResume()
        appContainer.updateCoordinator.onResumeFromSystem()
    }

    override fun onStop() {
        appContainer.updateCoordinator.onBackground(isChangingConfigurations)
        super.onStop()
    }

    private fun exitApplication(container: AppContainer) {
        if (isExiting) return
        isExiting = true
        // “按返回键退出时在后台继续播放”开启时：不销毁进程，退到后台，让媒体前台服务继续播放。
        if (container.appPreferences.backgroundContinueOnExit()) {
            runCatching { moveTaskToBack(true) }
            isExiting = false
            return
        }
        lifecycleScope.launch {
            try {
                container.shutdownForExit()
            } finally {
                finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}

/** 连续多少次抢救不回来就放行：超过说明当前页面本来就没有可落的焦点锚点（如播放页）。 */
private const val MAX_FOCUS_RESCUE_MISSES = 3

/** 会触发 View 系统焦点导航的按键：只有这些键才会让 ViewRootImpl 走 restoreDefaultFocus。 */
private fun KeyEvent.isFocusNavigationKey(): Boolean = keyCode in setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
)
