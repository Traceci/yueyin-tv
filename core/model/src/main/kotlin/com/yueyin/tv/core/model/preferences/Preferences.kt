package com.yueyin.tv.core.model.preferences

import com.yueyin.tv.core.model.PlayerStyle

enum class CacheBudget(val megabytes: Int) {
    Small(32), Medium(64), Default(128), Large(256);

    val artworkBytes: Long get() = megabytes * 1024L * 1024L
}

/**
 * 界面缩放档位：在正常密度之后统一加到整体 dp 上。
 * 电视观看距离、屏幕尺寸差异很大，官方只保证"按比例缩放"，
 * 具体舒不舒服要用户自己在沙发上判断，因此提供三档现场可调（设置页 → 界面缩放），
 * 免得每次微调都要重新出包。
 */
enum class UiScale(val factor: Float) {
    /** 紧凑：全局缩小 8%，屏幕越大越合适。 */
    Compact(0.92f),

    /** 标准：完全按官方 960×540 设计画布。 */
    Standard(1f),

    /** 宽松：全局放大 8%，观看距离较远时更清晰。 */
    Relaxed(1.08f);

    companion object {
        fun of(name: String?): UiScale = entries.firstOrNull { it.name == name } ?: Compact
    }
}

/**
 * 播放页氛围特效：控制 CD 唱片 / 大海报模式下的视觉增强。
 * - Off：关闭所有特效
 * - Glow：全屏呼吸光晕（颜色跟随封面主色），适合大海报模式
 * - Ripple：网易云式 3D 唱片涟漪（扩散环 + 随机光点），适合 CD 模式
 * - Auto：按显示形态自动选择（CD→Ripple，大海报→Glow，迷你歌词→Off）
 */
enum class AmbientEffect {
    Off, Glow, Ripple, Auto;

    companion object {
        fun of(name: String?): AmbientEffect = entries.firstOrNull { it.name == name } ?: Auto
    }
}

/**
 * 画质档位：统一控制过渡动画、常驻动画、渐变/半透明层等装饰负载。
 * 这是**设备级**设置，不随账号切换；Auto 由设备能力自动判定，其余为用户手选。
 * 低档位只降装饰，焦点反馈、进度、列表、播放等功能完整保留。
 */
enum class QualityTier {
    /** 自动：按低内存标记、可用内存、系统版本与设备形态判定。 */
    Auto,

    /** 流畅：关闭常驻动画与页面转场、纯色替渐变，供低端设备使用。 */
    Smooth,

    /** 均衡：保留必要反馈，简化转场与渐变层。 */
    Balanced,

    /** 精美：完整动效与图形效果。 */
    Rich;

    companion object {
        fun of(name: String?): QualityTier = entries.firstOrNull { it.name == name } ?: Auto
    }
}

/**
 * 外观模式。电视端固定深色（暗环境 + 3 米观看距离），只有手机端开放三档。
 * 设备级设置，切换账号不重置。
 */
enum class ThemeMode {
    FollowSystem, Light, Dark;

    companion object {
        fun of(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: Dark
    }
}

data class AppPreferencesState(
    val playerStyle: PlayerStyle = PlayerStyle.Cover,
    val cacheBudget: CacheBudget = CacheBudget.Default,
    val onlineLyricsMatchingEnabled: Boolean = true,
    /** 用户自定义歌单排序（歌单 guid 列表，按展示先后排列），空表示沿用服务器默认顺序。 */
    val playlistOrder: List<String> = emptyList(),
    /** 按返回键退出时是否在后台继续播放。 */
    val backgroundContinueOnExit: Boolean = false,
    /** 界面缩放档位，默认「紧凑」（全局缩小 8%）。 */
    val uiScale: UiScale = UiScale.Compact,
    /** 播放页氛围特效模式（关闭/呼吸光晕/3D唱片涟漪/自动）。 */
    val ambientEffect: AmbientEffect = AmbientEffect.Auto,
    /** 是否从封面提取主色并给播放页整体染色。 */
    val dynamicColor: Boolean = true,
    /** 是否启用逐字卡拉OK歌词填充（当前句按时间从左到右高亮）。 */
    val karaokeFill: Boolean = true,
    /** 大海报模式是否把封面虚化为全屏背景。 */
    val blurredBackdrop: Boolean = true,
    /** 画质档位（设备级，低配可整体降级装饰负载）。 */
    val qualityTier: QualityTier = QualityTier.Auto,
    /** 外观模式；电视端忽略此值，恒为深色。默认深色，避免装包后外观突变。 */
    val themeMode: ThemeMode = ThemeMode.Dark,
)

data class CacheUsage(
    val artworkBytes: Long,
    val indexBytes: Long = 0,
) {
    val totalBytes: Long get() = artworkBytes + indexBytes
}
