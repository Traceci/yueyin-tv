package com.yueyin.tv

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.yueyin.tv.core.audiofx.FxPreset
import com.yueyin.tv.core.audiofx.FxSettings
import com.yueyin.tv.core.playback.AudioFxController
import com.yueyin.tv.phone.PhoneSystemBars
import com.yueyin.tv.ui.FocusRescue
import com.yueyin.tv.ui.YueYinApp
import com.yueyin.tv.ui.device.FormFactor
import com.yueyin.tv.ui.device.resolveFormFactor
import com.yueyin.tv.update.UpdateEffect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var isExiting = false
    private var focusRescueMisses = 0
    // 形态只判一次并留档：转屏时要重算系统栏，而那时不该再判一遍形态
    // （判据里有 smallestScreenWidthDp，横竖屏下同一个值，但重复判定没有意义）。
    private var formFactor: FormFactor = FormFactor.Tv
    private val appContainer: AppContainer get() = (application as TvMusicApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 形态只判一次，方向、系统栏、主题密度都复用它，避免三处各判一遍出现不一致。
        formFactor = resolveFormFactor(this)
        applyDeviceOrientation(formFactor)
        // 状态栏样式固定为「深色背景 + 浅色图标」：应用整体是深蓝黑底，
        // 交给 auto 判断会跟随系统明暗，系统处于浅色模式时图标会变成深色、在深色底上看不见。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        applySystemBars(formFactor, resources.configuration)
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
     * 按设备形态决定屏幕方向。
     *
     * 原先方向写死在 Manifest 的 `screenOrientation="landscape"` 里，手机装上只能横着用，
     * 竖屏布局根本不会生效。现在改成运行时判定：
     * - **电视**维持横屏：遥控器操作 + 16:9 内容源，竖过来既没法看也没法拿；
     * - **手持设备**交还系统（`UNSPECIFIED`）：P5 起横屏与平板布局都已适配
     *   （宽屏侧栏、3~4 列网格、播放页左右分栏、平板双栏），再锁竖屏等于把做好的布局藏起来。
     *   交给系统意味着仍尊重用户的「方向锁定」开关——锁着就竖，转开就横，这是系统的约定。
     *
     * 为什么放在 `super.onCreate` 之后立刻执行：Activity 已声明
     * `configChanges=orientation|screenSize`，方向变化不会重建 Activity、只走
     * `onConfigurationChanged`，所以这里赋值不会引发第二次 `onCreate`，冷启动也不会闪一下。
     */
    private fun applyDeviceOrientation(formFactor: FormFactor) {
        requestedOrientation = when (formFactor) {
            FormFactor.Tv -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            FormFactor.Phone,
            FormFactor.Tablet,
            FormFactor.Car,
            -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /**
     * 按设备形态与屏幕方向决定系统栏。
     *
     * 主题里 `android:windowFullscreen=true` 是给电视定的（电视没有状态栏，全屏才对）。
     * 手机上这条会把状态栏一起隐藏，用户看不到时间和电量——所以手持设备要显式清掉
     * 全屏 flag 再把状态栏要回来。电视端保持原样，一行行为都不变。
     *
     * 手持设备再按方向分两种：
     * - **竖屏**：显示状态栏。竖屏是「拿在手里刷列表」的形态，时间/电量/通知都在上面。
     * - **横屏**：隐藏状态栏（沉浸）。横屏只有 411dp 高，状态栏要吃掉 24~32dp，
     *   而且播放页本来就沉浸，其它页显示、播放页隐藏，来回切会闪一下，很割裂。
     *   这里设 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`：从顶部下滑仍能临时唤出状态栏，
     *   看到时间和通知，不牺牲可读性。
     */
    private fun applySystemBars(formFactor: FormFactor, configuration: Configuration) {
        if (!formFactor.isHandheld) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.statusBars())
            return
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // 横屏沉浸、竖屏显示，规则集中在 PhoneSystemBars，播放页退出沉浸时用的是同一份。
        PhoneSystemBars.applyByOrientation(
            window = window,
            landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
    }

    /**
     * 转屏后重新应用系统栏。
     *
     * Manifest 声明了 `configChanges=orientation|screenSize`，转屏不会重建 Activity，
     * 也就不会重走 `onCreate`——不在这里补一次，横屏时状态栏会保持竖屏时的「显示」状态，
     * 要等下一次冷启动才正确。Compose 侧的 `LocalConfiguration` 会自己更新，无需通知。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars(formFactor, newConfig)
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
