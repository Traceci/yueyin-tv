package com.yueyin.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * 「午夜流光」配色体系：深蓝黑底 + 珊瑚金主色 + 青绿辅助色。
 * 全局统一使用本调色板，保证各页面颜色一致、焦点明确、明暗层次清晰。
 */
object FnColors {
    /** 最深底色：近黑蓝，作为所有页面的基底。 */
    val Background = Color(0xFF070A10)

    /** 卡片/容器表面。 */
    val Surface = Color(0xFF10161F)

    /** 更高一级的表面（弹层、输入框、选中容器）。 */
    val SurfaceAlt = Color(0xFF161D29)

    /** 主文字。 */
    val Text = Color(0xFFF2F4F8)

    /** 次要文字。 */
    val Muted = Color(0xFF98A2B3)

    /** 主色：珊瑚橙，用于焦点、选中与品牌强调。 */
    val Coral = Color(0xFFFF7A5C)

    /** 主色渐变伴侣：暖金。 */
    val Amber = Color(0xFFFFB46B)

    /** 辅助色：青绿，用于随机漫游/播放相关。 */
    val Teal = Color(0xFF4FD1A5)

    /** 辅助色：湖蓝。 */
    val Cyan = Color(0xFF5FC8FF)

    /** 辅助色：紫罗兰，用于收藏/浪漫氛围。 */
    val Violet = Color(0xFF9B8CFF)

    /** 警示/歌单图标等。 */
    val Warning = Color(0xFFE8C36A)

    /** 焦点填充：主色向表面融合的柔和底。 */
    // 聚焦蒙版改为中性微提亮：珊瑚色只留给描边与光晕（见 fnFocusRing / focusGlow），
    // 封面/卡片聚焦时不再被染成橙色，层次更干净。
    val FocusFill = Color.White.copy(alpha = 0.06f)
}

/** 常用渐变笔刷：横向统一的“流光”渐变，用于强调与氛围。 */
object FnGradients {
    /** 珊瑚→暖金：主品牌/选中强调。 */
    val Primary = Brush.linearGradient(listOf(FnColors.Coral, FnColors.Amber))

    /** 青绿→湖蓝：随机漫游、播放氛围。 */
    val Calm = Brush.linearGradient(listOf(FnColors.Teal, FnColors.Cyan))

    /** 紫罗兰→珊瑚：收藏、浪漫氛围。 */
    val Twilight = Brush.linearGradient(listOf(FnColors.Violet, FnColors.Coral))

    /** 深空：表面→更深的背景。 */
    val Deep = Brush.verticalGradient(listOf(FnColors.Surface, FnColors.Background))
}

/**
 * 全局字号阶梯（按 4K 密度补偿后的 960×540dp 画布校准）。
 * 所有界面文字按层级从这里取号，不随手写字号——
 * 既保证视觉层级一致，也从根上避免“固定容器装不下变大文字”的溢出问题。
 */
object FnType {
    /** 启动页 / 欢迎页主标题。 */
    val Display = 34.sp

    /** 页面标题（设置、搜索、媒体带标题）。 */
    val PageTitle = 30.sp

    /** 强调文字（用户名、重要数值）。 */
    val Emphasis = 24.sp

    /** 小节标题（搜索结果分区等）。 */
    val Section = 23.sp

    /** 卡片 / 导航项标题。 */
    val CardTitle = 21.sp

    /** 正文与列表主文字。 */
    val Body = 19.sp

    /** 列表行标题（歌手卡、专辑卡）。 */
    val Row = 18.sp

    /** 标签与按键说明文字。 */
    val Label = 16.sp

    /** 副标题 / 元信息。 */
    val Sub = 14.sp

    /** 弱化说明（设置页标签等）。 */
    val Caption = 12.sp

    /** 正在播放胶囊内的极小文字。 */
    val Micro = 10.sp
}

/**
 * TV 设计画布（依据官方《TV 布局》规范）。
 *
 * 官方要求：**始终以 MDPI 960×540（1px = 1dp）为基准设计**，
 * 这样所有元素在 HD / 4K 屏幕上都能按比例缩放。
 * 运行时由 [FnAdaptive.canvasScale] 把任意分辨率与密度的设备折算回这块画布，
 * 保证 55"/65"/75"/85"/98" 以及更小屏幕上的**视觉比例完全一致**——
 * 屏幕越大仅仅是整体等比放大，而不会出现"元素相对变小/放大"的失调。
 */
object FnCanvas {
    /** 设计画布宽度（dp）。 */
    const val WidthDp = 960

    /** 设计画布高度（dp）。 */
    const val HeightDp = 540

    /** 过扫描保护最小边距：左右 48dp = 960×5%。低于此值内容可能被电视裁掉。 */
    val MinSafeHorizontal = 48.dp

    /** 过扫描保护最小边距：上下 27dp = 540×5%。 */
    val MinSafeVertical = 27.dp

    /** 推荐内容边距（左右）：官方 12 栅格体系 58dp（52dp×12 + 20dp×11 + 58dp×2 = 960）。 */
    val SafeHorizontal = 58.dp

    /** 推荐内容边距（上下）：官方推荐 28dp。 */
    val SafeVertical = 28.dp
}

/**
 * 自适应折算。把「设备实际 dp 画布」连续折算回 960×540 设计画布，
 * 取代原先 `screenWidthDp >= 1400` 的一刀切判断。
 *
 * 为什么必须连续折算：
 * 旧逻辑只有 0 倍与 2 倍两档。设备宽度 dp 落在 1400~1920 之间时会被强行 ×2，
 * 有效画布被压到 700~960dp（元素偏小）；而低于 1400 的设备完全不折算，
 * 有效画布最宽可达 1400dp（元素偏大）。同分辨率不同厂商上报的 dpi 差异会直接翻车，
 * 这正是"全局比例不协调"的根因。
 */
object FnAdaptive {
    /**
     * 计算密度倍率：使折算后的有效画布恰好等于设计画布。
     * 1dp 实际渲染 density×s 像素，故有效画布宽度 = widthDp / s，令其等于 960 得 s = widthDp / 960。
     * 宽高同时约束（取较小者），避免非 16:9 设备溢出。
     */
    fun canvasScale(widthDp: Int, heightDp: Int): Float {
        if (widthDp <= 0 || heightDp <= 0) return 1f
        val byWidth = widthDp.toFloat() / FnCanvas.WidthDp
        val byHeight = heightDp.toFloat() / FnCanvas.HeightDp
        // 极端兜底：限制在 0.5~3.0 倍，防止异常 dpi 上报导致界面不可用。
        return byWidth.coerceAtMost(byHeight).coerceIn(0.5f, 3.0f)
    }
}

/**
 * 自适应网格参数（对齐竞品口径：最小卡宽 150dp、间距 18dp）。
 *
 * 为什么要用「算」而不是用 `GridCells.Adaptive`：全部歌单页的排序模式要按
 * `行位移 × 列数 + 列位移` 计算目标下标，必须拿到**确定的列数**；
 * `GridCells.Adaptive` 的列数在网格内部算，外面取不到，焦点跳转就会错位。
 * 因此统一由 [columnsFor] 按可用宽度算出列数，GridCells 与焦点数学共用同一个值。
 */
object FnGrid {
    /** 卡片最小宽度：低于它不再增列。 */
    val MinCardWidth = 150.dp

    /**
     * 「全部专辑」海报卡的目标宽度。
     *
     * 960 画布 − 48×2 边距 − 8 内边距 = 856dp，(856+14)/(128+14) = 6.1 → 6 列，
     * 实际每列宽 (856−5×14)/6 = 131dp。
     *
     * 这里取 128 而不是实际的 131：131 时 (856+14)/(131+14) 正好等于 6.00，
     * 电视上报的可用宽度差 1dp 就会掉成 5 列。留 3dp 余量，列数才稳定。
     *
     * 卡片宽度由网格决定（`fillMaxWidth`），所以列数与卡片设计必须共用这一组数：
     * 之前列数按 150dp 算（5 列 → 每格 160dp）、卡片却写死 232dp，
     * 压缩后留给专辑名的只有 26dp，一个汉字都放不下。
     */
    val PosterCardWidth = 128.dp

    /** 网格水平间距：与 GridPage / MediaBand 实际绘制使用的间距保持一致。 */
    val Gutter = 14.dp

    /** 网格自身 contentPadding 左右合计（各 4dp）。 */
    const val ContentInsetDp = 8f

    /**
     * 可用宽度（dp）→ 列数。取 3~6 列兜底，避免极端宽高比下退化成 1~2 列或无限增列。
     * 例：960dp 画布、左右各 48dp 边距、8dp 内边距 → 可用 856dp → (856+14)/(150+14)=5.3 → 5 列。
     *
     * [targetCardWidth] 传该网格卡片的设计宽度，列数与卡片宽度才不会各自为政。
     */
    fun columnsFor(availableWidthDp: Float, targetCardWidth: Dp = MinCardWidth): Int {
        if (availableWidthDp <= 0f) return 4
        val cardWidth = targetCardWidth.value.coerceAtLeast(1f)
        val count = ((availableWidthDp + Gutter.value) / (cardWidth + Gutter.value)).toInt()
        return count.coerceIn(3, 6)
    }
}

/**
 * 折算后的**有效设计画布宽度（dp）**。
 * YueYinTheme 已把密度按 [FnAdaptive.canvasScale] 放大，所以 16:9 电视上恒为 960dp；
 * 这里反算出来，供网格计算列数时减去边距使用（不能用 Configuration.screenWidthDp，那是缩放前的值）。
 */
@Composable
fun rememberCanvasWidthDp(): Float {
    val configuration = LocalConfiguration.current
    val scale = FnAdaptive.canvasScale(configuration.screenWidthDp, configuration.screenHeightDp)
    return configuration.screenWidthDp / scale
}

/**
 * 全局圆角阶梯：**只用这 4 档**。
 * 之前全项目散落 17 种圆角（1.25 / 2 / 3 / 4 / 5 / 7 / 8 / 9 / 11 / 18 / 22 …），
 * 同一屏里就能看到 4/8/10/14/22 并存，这是"看起来不协调"的主要来源之一。
 * 圆形与胶囊用 `CircleShape` / `RoundedCornerShape(50)`，不要写 99/999 这类魔数。
 */
object FnRadius {
    /** 6dp：缩略图、复选框、小标签。 */
    val sm = 6.dp

    /** 12dp：卡片、列表行、搜索框。 */
    val md = 12.dp

    /** 16dp：面板、弹窗、大按钮。 */
    val lg = 16.dp

    /** 20dp：全屏弹层、详情页头图区。 */
    val xl = 20.dp
}

/** 全局间距阶梯。 */
object FnSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** 全局动效时长（毫秒）：统一节奏，避免各处 tween 数值漂移。 */
object FnMotion {
    const val Fast = 150
    const val Normal = 240
    const val Slow = 320
}

/**
 * 焦点体系统一规范。
 * 所有可聚焦表面共用同一套「主色描边 + 外扩光晕 + 轻微放大」语言；
 * 未聚焦时保留一道极淡描边，让卡片轮廓在黑底上依然清晰可辨。
 */
object FnFocus {
    /** 聚焦描边宽度（白色细描边，克制但足够醒目）。 */
    val ringWidth = 2.5.dp

    /**
     * 聚焦描边颜色：近白。
     * 参考成熟 TV 客户端的做法——**只用白色描边表达焦点，不加任何蒙层/光晕**；
     * 形状完全跟随控件本身（圆的就是圆、方的就是方），视觉最干净。
     */
    val ringColor = Color(0xFFF4F6FA)

    /** 未聚焦时的淡描边宽度。 */
    val hairlineWidth = 1.dp

    /** 淡描边颜色。 */
    val hairlineColor = Color.White.copy(alpha = 0.10f)

    /** 聚焦不再放大：避免卡片变大后压到相邻元素，也避免圆角/描边被拉伸。 */
    const val focusedScale = 1f

    /** 不再绘制外扩光晕（保留常量供旧调用点引用，值为 0 即完全不绘制）。 */
    const val glowAlpha = 0f

    /** 聚焦缩放规格：保留定义以兼容既有调用，倍率为 1 时无动画效果。 */
    val scaleSpring = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
}

/**
 * 焦点光晕内核：现已停用（改为纯白色描边）。
 *
 * 旧实现会在控件外侧画多圈珊瑚色描边形成"光晕"，虽然不覆盖内容，
 * 但视觉上仍然是"一层颜色"。按用户要求改成最干脆的表达方式：
 * 只有描边，没有任何附加颜色，因此这里保留为空实现（调用点无需改动）。
 */
@Suppress("Unused")
fun Modifier.fnFocusHalo(
    cornerRadius: Dp,
    glowColor: Color = FnColors.Coral,
): Modifier = this

/**
 * 统一焦点外观：聚焦 = 白色描边；未聚焦 = 一道极淡描边。
 * 形状完全由传入的 [shape] 决定（圆角矩形就是圆角矩形、圆形就是圆形），不做放大与叠加蒙层。
 * @param cornerRadius 保留参数以兼容既有调用点，当前不再使用。
 */
fun Modifier.fnFocusRing(
    focused: Boolean,
    shape: Shape,
    cornerRadius: Dp,
    ringColor: Color = FnFocus.ringColor,
): Modifier = this.then(
    if (focused) {
        Modifier.border(FnFocus.ringWidth, ringColor, shape)
    } else {
        Modifier.border(FnFocus.hairlineWidth, FnFocus.hairlineColor, shape)
    },
)

/** 统一焦点缩放：聚焦时轻微放大，用带回弹的 spring，松手后有“吸附”质感。 */
@Composable
fun Modifier.fnFocusScale(focused: Boolean, scale: Float = FnFocus.focusedScale): Modifier {
    val animated by animateFloatAsState(
        targetValue = if (focused) scale else 1f,
        animationSpec = FnFocus.scaleSpring,
        label = "fnFocusScale",
    )
    return this.graphicsLayer {
        scaleX = animated
        scaleY = animated
    }
}

/**
 * 慢速循环相位（0~1）：按固定低帧率推进，而不是每帧都给新值。
 *
 * 为什么不用 `rememberInfiniteTransition`：无限动画**每帧**都会写入新值，
 * 订阅它的绘制节点就会每帧重绘。对于 20s 以上才走完一圈的缓慢漂移，
 * 24fps 与 60fps 肉眼完全看不出差别，但整屏绘制次数直接降到 40%。
 *
 * 相位值只在绘制阶段被读取（`.value`），所以写入它只会让绘制失效，不会触发重组。
 */
@Composable
fun rememberSlowPhase(periodMillis: Int, fps: Int = 24): State<Float> {
    val phase = remember(periodMillis, fps) { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMillis, fps) {
        val periodNanos = periodMillis * 1_000_000L
        val stepNanos = 1_000_000_000L / fps.coerceAtLeast(1)
        var startNanos = 0L
        var lastEmit = Long.MIN_VALUE
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) startNanos = now
                if (lastEmit == Long.MIN_VALUE || now - lastEmit >= stepNanos) {
                    lastEmit = now
                    phase.floatValue = ((now - startNanos) % periodNanos).toFloat() / periodNanos
                }
            }
        }
    }
    return phase
}

/**
 * 全局氛围背景：纯黑蓝基底上叠加两团低透明度大型光斑，并让它们极缓慢地反向漂移，
 * 避免大面积纯色显得沉闷。光斑透明度压在 10% 以内，不影响任何文字对比度。
 * 漂移值只在 draw 阶段读取，且按 24fps 推进（见 [rememberSlowPhase]），不触发重组。
 */
@Composable
fun FnAmbientBackdrop(modifier: Modifier = Modifier) {
    val drift = rememberSlowPhase(periodMillis = 26_000)
    Canvas(modifier) {
        drawRect(FnColors.Background)
        val w = size.width
        val h = size.height
        val t = drift.value
        // 光斑一：左上珊瑚（主色）
        val c1 = Offset(w * (0.18f + 0.05f * t), h * (0.10f + 0.04f * t))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(FnColors.Coral.copy(alpha = 0.10f), Color.Transparent),
                center = c1,
                radius = w * 0.55f,
            ),
            center = c1,
            radius = w * 0.55f,
        )
        // 光斑二：右下湖蓝，与光斑一反相漂移，形成错位“呼吸”
        val c2 = Offset(w * (0.86f - 0.05f * t), h * (0.88f - 0.04f * t))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(FnColors.Cyan.copy(alpha = 0.07f), Color.Transparent),
                center = c2,
                radius = w * 0.50f,
            ),
            center = c2,
            radius = w * 0.50f,
        )
    }
}

/**
 * 骨架屏占位块：内部有一束高光持续扫过。
 * 用于列表/网格首屏加载，比“转圈 + 文字”更贴近最终布局，减少等待焦虑。
 */
@Composable
fun FnSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(FnRadius.md),
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    // 持有 State、在绘制阶段取值：高光每帧移动，但不产生重组（首屏 8 个骨架同时存在，这点很关键）
    val sweep = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeletonSweep",
    )
    Box(
        modifier
            .clip(shape)
            .background(FnColors.SurfaceAlt)
            .drawWithCache {
                // 高光带宽约 45% 宽度，从左外侧扫到右外侧。
                // 注意：sweep 必须在 onDrawBehind 内读取——缓存块只在尺寸/状态变化时重跑，
                // 若在缓存块里算 startX，高光位置会被冻在第一次的值上不再移动。
                val bandWidth = size.width * 0.45f
                onDrawBehind {
                    val startX = -bandWidth + (size.width + bandWidth * 2) * sweep.value
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.07f),
                                Color.Transparent,
                            ),
                            // linearGradient 用起止 Offset 定位（不像 vertical/horizontalGradient 那样接受 startY/endY）
                            start = Offset(startX, 0f),
                            end = Offset(startX + bandWidth, size.height),
                        ),
                    )
                }
            },
    )
}

@Composable
fun YueYinTheme(uiScale: Float = 1f, content: @Composable () -> Unit) {
    // 4K 电视（如 TCK65K）画布为 1920×1080dp，若按原密度渲染所有元素会显得只有
    // 1080p 电视的一半。这里做全局密度补偿：宽画布按 2× 折算，有效画布回到
    // 960×540dp，与 1080p 电视视觉完全一致；1080p 及以下设备不受影响。
    val original = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 例：4K 电视若上报 320dpi → 画布 1920×1080dp → 倍率 2.0（有效画布回到 960×540）；
    // 1080p 电视上报 320dpi → 画布 960×540dp → 倍率 1.0（不缩放）；
    // 若厂商上报 213dpi → 画布 1442dp → 倍率 1.5（旧逻辑会因 <1400…> 判断失真）。
    val scale = FnAdaptive.canvasScale(configuration.screenWidthDp, configuration.screenHeightDp)
    // uiScale 是用户在设置页选的整体缩放档位（紧凑 0.92 / 标准 1.0 / 宽松 1.08）。
    // 先按分辨率把设备折算回 960×540 设计画布，再套用用户的个性化偏好。
    val density = Density(original.density * scale * uiScale, original.fontScale)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FnColors.Coral,
            secondary = FnColors.Teal,
            tertiary = FnColors.Violet,
            background = FnColors.Background,
            surface = FnColors.Surface,
            onPrimary = FnColors.Background,
            onSecondary = FnColors.Background,
            onTertiary = FnColors.Background,
            onBackground = FnColors.Text,
            onSurface = FnColors.Text,
        ),
    ) {
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalContentColor provides FnColors.Text,
            content = content,
        )
    }
}
