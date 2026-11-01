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
import com.yueyin.tv.ui.YueYinApp
import com.yueyin.tv.update.UpdateEffect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var isExiting = false
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
