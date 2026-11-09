package com.yueyin.tv.phone

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手机端图标集。
 *
 * 为什么自己用 Canvas 画而不是引图标库：电视端已有的 `HistoryIcon` / `VisibilityIcon` / `DeleteHistoryIcon`
 * 全是 Canvas 自绘，加一个图标库等于同时维护两套视觉语言（线宽、圆头、留白都对不上）。
 * 这里沿用同一套画法——1.8dp 圆头描边、24dp 视觉框——保持全 App 一致，也不增加依赖体积。
 *
 * 所有坐标都按 `size` 的百分比写，不写死 px，因此同一份代码可以放到 22dp（导航）和 18dp（行内）都清晰。
 */
internal enum class PhoneGlyph {
    /** 首页：房子。 */
    Home,

    /** 曲库：2×2 网格。 */
    Library,

    /** 我的：人像。 */
    My,

    /** 设置：三条滑块。 */
    Settings,

    /** 播放：实心三角。 */
    Play,

    /** 暂停：两条实心竖条。 */
    Pause,

    /** 下一首：三角 + 竖线。 */
    Next,

    /** 上一首：竖线 + 三角。 */
    Previous,

    /** 搜索：圆 + 斜柄。 */
    Search,

    /** 返回：左箭头。 */
    Back,

    /** 更多入口：右尖角。 */
    ChevronRight,

    /** 收藏：心形描边。 */
    Heart,

    /** 随机播放：两条交叉箭头。 */
    Shuffle,

    /** 列表循环：带缺口的圆角框 + 两个小箭头。 */
    Repeat,

    /** 单曲循环：列表循环 + 中间一道竖笔（数字 1 的极简画法）。 */
    RepeatOne,

    /** 播放队列：三条横线 + 左侧小三角。 */
    Queue,

    /** 音效：五条高低不同的均衡器竖条。 */
    Fx,

    /** 更多：三个实心圆点。 */
    More,

    /** 收起：向下箭头。 */
    ChevronDown,

    /** 投播：屏幕 + 左下角两道信号弧。 */
    Cast,

    /** 复制：两张错位的圆角纸。 */
    Copy,

    /** 删除：垃圾桶（桶身 + 盖子 + 提手）。 */
    Trash,

    /** 顺序播放：一个向右箭头 + 末端一道竖线（「播到底就停」）。 */
    Sequence,

    /** 最近播放：圆角时钟。 */
    History,
}

@Composable
internal fun PhoneIcon(
    glyph: PhoneGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.8.dp,
    filled: Boolean = false,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = strokeWidth.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (glyph) {
            PhoneGlyph.Home -> {
                // 屋顶折线
                drawPath(
                    Path().apply {
                        moveTo(w * 0.13f, h * 0.47f)
                        lineTo(w * 0.50f, h * 0.15f)
                        lineTo(w * 0.87f, h * 0.47f)
                    },
                    color = tint,
                    style = stroke,
                )
                // 墙体：与屋顶留出一点缝隙，两条线不会糊成一块
                drawPath(
                    Path().apply {
                        moveTo(w * 0.23f, h * 0.43f)
                        lineTo(w * 0.23f, h * 0.85f)
                        lineTo(w * 0.77f, h * 0.85f)
                        lineTo(w * 0.77f, h * 0.43f)
                    },
                    color = tint,
                    style = stroke,
                )
            }

            PhoneGlyph.Library -> {
                val cell = w * 0.32f
                val start = w * 0.12f
                val step = cell + w * 0.12f
                for (row in 0..1) {
                    for (col in 0..1) {
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(start + col * step, start + row * step),
                            size = Size(cell, cell),
                            cornerRadius = CornerRadius(w * 0.07f),
                            style = stroke,
                        )
                    }
                }
            }

            PhoneGlyph.My -> {
                drawCircle(
                    color = tint,
                    radius = w * 0.155f,
                    center = Offset(w * 0.5f, h * 0.32f),
                    style = stroke,
                )
                // 肩：上半椭圆弧，压在下沿避免超出视觉框
                drawArc(
                    color = tint,
                    startAngle = 190f,
                    sweepAngle = 160f,
                    useCenter = false,
                    topLeft = Offset(w * 0.21f, h * 0.53f),
                    size = Size(w * 0.58f, h * 0.44f),
                    style = stroke,
                )
            }

            PhoneGlyph.Settings -> {
                // 三条滑块：线在圆点处断开，再套一个圆环，避免实心圆盖住线
                val rows = listOf(
                    h * 0.28f to w * 0.36f,
                    h * 0.50f to w * 0.64f,
                    h * 0.72f to w * 0.44f,
                )
                val knob = w * 0.085f
                rows.forEach { (y, x) ->
                    drawLine(tint, Offset(w * 0.12f, y), Offset(x - knob, y), sw, StrokeCap.Round)
                    drawLine(tint, Offset(x + knob, y), Offset(w * 0.88f, y), sw, StrokeCap.Round)
                    drawCircle(tint, radius = knob, center = Offset(x, y), style = stroke)
                }
            }

            PhoneGlyph.Play -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.31f, h * 0.19f)
                        lineTo(w * 0.81f, h * 0.50f)
                        lineTo(w * 0.31f, h * 0.81f)
                        close()
                    },
                    color = tint,
                )
            }

            PhoneGlyph.Pause -> {
                val barRadius = CornerRadius(w * 0.05f)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.31f, h * 0.20f),
                    size = Size(w * 0.13f, h * 0.60f),
                    cornerRadius = barRadius,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.56f, h * 0.20f),
                    size = Size(w * 0.13f, h * 0.60f),
                    cornerRadius = barRadius,
                )
            }

            PhoneGlyph.Next -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.26f, h * 0.20f)
                        lineTo(w * 0.66f, h * 0.50f)
                        lineTo(w * 0.26f, h * 0.80f)
                        close()
                    },
                    color = tint,
                )
                drawLine(tint, Offset(w * 0.74f, h * 0.20f), Offset(w * 0.74f, h * 0.80f), sw, StrokeCap.Round)
            }

            PhoneGlyph.Previous -> {
                drawLine(tint, Offset(w * 0.26f, h * 0.20f), Offset(w * 0.26f, h * 0.80f), sw, StrokeCap.Round)
                drawPath(
                    Path().apply {
                        moveTo(w * 0.74f, h * 0.20f)
                        lineTo(w * 0.34f, h * 0.50f)
                        lineTo(w * 0.74f, h * 0.80f)
                        close()
                    },
                    color = tint,
                )
            }

            PhoneGlyph.Search -> {
                // 镜片半径 0.29w、圆心略偏左上，给右下的柄留出长度
                drawCircle(
                    color = tint,
                    radius = w * 0.29f,
                    center = Offset(w * 0.44f, h * 0.44f),
                    style = stroke,
                )
                drawLine(
                    tint,
                    Offset(w * 0.655f, h * 0.655f),
                    Offset(w * 0.85f, h * 0.85f),
                    sw,
                    StrokeCap.Round,
                )
            }

            PhoneGlyph.Back -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.58f, h * 0.20f)
                        lineTo(w * 0.32f, h * 0.50f)
                        lineTo(w * 0.58f, h * 0.80f)
                    },
                    color = tint,
                    style = stroke,
                )
            }

            PhoneGlyph.ChevronRight -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.42f, h * 0.24f)
                        lineTo(w * 0.64f, h * 0.50f)
                        lineTo(w * 0.42f, h * 0.76f)
                    },
                    color = tint,
                    style = stroke,
                )
            }

            PhoneGlyph.Heart -> {
                // 两个半圆 + 两条收到底部的斜线，闭合后即心形；用 cubic 保证肩部圆润
                val heart = Path().apply {
                    moveTo(w * 0.50f, h * 0.80f)
                    cubicTo(w * 0.16f, h * 0.56f, w * 0.14f, h * 0.30f, w * 0.32f, h * 0.24f)
                    cubicTo(w * 0.42f, h * 0.21f, w * 0.48f, h * 0.27f, w * 0.50f, h * 0.33f)
                    cubicTo(w * 0.52f, h * 0.27f, w * 0.58f, h * 0.21f, w * 0.68f, h * 0.24f)
                    cubicTo(w * 0.86f, h * 0.30f, w * 0.84f, h * 0.56f, w * 0.50f, h * 0.80f)
                    close()
                }
                // 已收藏态：实心填充 + 描边。只加粗描边在 19dp 上几乎看不出变化，
                // 而「收没收藏」是这个按钮唯一要传达的信息，必须一眼可辨。
                if (filled) drawPath(heart, color = tint)
                drawPath(heart, color = tint, style = stroke)
            }

            PhoneGlyph.Shuffle -> {
                // 两条箭头交叉：左端合并、右端分开，是各家播放器通用画法。
                // 折线而不是曲线，19dp 下曲线会糊成一团。
                drawPath(
                    Path().apply {
                        moveTo(w * 0.16f, h * 0.28f)
                        lineTo(w * 0.40f, h * 0.28f)
                        lineTo(w * 0.72f, h * 0.72f)
                        lineTo(w * 0.84f, h * 0.72f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.16f, h * 0.72f)
                        lineTo(w * 0.40f, h * 0.72f)
                        lineTo(w * 0.72f, h * 0.28f)
                        lineTo(w * 0.84f, h * 0.28f)
                    },
                    color = tint,
                    style = stroke,
                )
                // 右端两个箭头
                drawPath(
                    Path().apply {
                        moveTo(w * 0.70f, h * 0.17f)
                        lineTo(w * 0.84f, h * 0.28f)
                        lineTo(w * 0.70f, h * 0.39f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.70f, h * 0.61f)
                        lineTo(w * 0.84f, h * 0.72f)
                        lineTo(w * 0.70f, h * 0.83f)
                    },
                    color = tint,
                    style = stroke,
                )
            }

            PhoneGlyph.Repeat -> {
                // 循环 = 一个断了两个口的圆角框 + 两个逆向箭头。
                // 画整框再叠箭头会糊在一起，所以左右两侧各留 0.12w 的缺口给箭头让位。
                drawPath(
                    Path().apply {
                        moveTo(w * 0.30f, h * 0.24f)
                        lineTo(w * 0.76f, h * 0.24f)
                        quadraticTo(w * 0.86f, h * 0.24f, w * 0.86f, h * 0.34f)
                        lineTo(w * 0.86f, h * 0.42f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.70f, h * 0.76f)
                        lineTo(w * 0.24f, h * 0.76f)
                        quadraticTo(w * 0.14f, h * 0.76f, w * 0.14f, h * 0.66f)
                        lineTo(w * 0.14f, h * 0.58f)
                    },
                    color = tint,
                    style = stroke,
                )
                // 右上向下的箭头、左下向上的箭头：表示「回到开头重来」
                drawPath(
                    Path().apply {
                        moveTo(w * 0.76f, h * 0.15f)
                        lineTo(w * 0.86f, h * 0.31f)
                        lineTo(w * 0.72f, h * 0.31f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.24f, h * 0.85f)
                        lineTo(w * 0.14f, h * 0.69f)
                        lineTo(w * 0.28f, h * 0.69f)
                    },
                    color = tint,
                    style = stroke,
                )
            }

            PhoneGlyph.RepeatOne -> {
                // 复用列表循环的框与箭头，中间加一道竖笔。
                // 不做「圆角方框里写数字 1」那种画法：14~18dp 下数字会糊成一个点。
                drawPath(
                    Path().apply {
                        moveTo(w * 0.30f, h * 0.24f)
                        lineTo(w * 0.76f, h * 0.24f)
                        quadraticTo(w * 0.86f, h * 0.24f, w * 0.86f, h * 0.34f)
                        lineTo(w * 0.86f, h * 0.42f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.70f, h * 0.76f)
                        lineTo(w * 0.24f, h * 0.76f)
                        quadraticTo(w * 0.14f, h * 0.76f, w * 0.14f, h * 0.66f)
                        lineTo(w * 0.14f, h * 0.58f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.76f, h * 0.15f)
                        lineTo(w * 0.86f, h * 0.31f)
                        lineTo(w * 0.72f, h * 0.31f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.24f, h * 0.85f)
                        lineTo(w * 0.14f, h * 0.69f)
                        lineTo(w * 0.28f, h * 0.69f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawLine(
                    tint,
                    Offset(w * 0.50f, h * 0.40f),
                    Offset(w * 0.50f, h * 0.60f),
                    sw,
                    StrokeCap.Round,
                )
            }

            PhoneGlyph.Queue -> {
                // 左侧播放三角 + 右侧三条横线：比「三条线 + 音符」更容易在小尺寸上认出是队列。
                drawPath(
                    Path().apply {
                        moveTo(w * 0.14f, h * 0.34f)
                        lineTo(w * 0.30f, h * 0.44f)
                        lineTo(w * 0.14f, h * 0.54f)
                        close()
                    },
                    color = tint,
                )
                listOf(0.36f, 0.50f, 0.64f).forEachIndexed { index, y ->
                    // 三条线右端错开：等长会像「菜单」，错开才像「一串曲目」
                    val endX = if (index == 2) w * 0.72f else w * 0.86f
                    drawLine(tint, Offset(w * 0.38f, h * y), Offset(endX, h * y), sw, StrokeCap.Round)
                }
            }

            PhoneGlyph.Fx -> {
                // 五条均衡器竖条，高低错落。用竖线而不是矩形，圆头天然有「推子」的观感。
                val bars = listOf(
                    w * 0.14f to h * 0.46f,
                    w * 0.32f to h * 0.26f,
                    w * 0.50f to h * 0.62f,
                    w * 0.68f to h * 0.32f,
                    w * 0.86f to h * 0.52f,
                )
                bars.forEach { (x, halfHeight) ->
                    drawLine(
                        tint,
                        Offset(x, h * 0.5f - halfHeight),
                        Offset(x, h * 0.5f + halfHeight),
                        sw,
                        StrokeCap.Round,
                    )
                }
            }

            PhoneGlyph.More -> {
                // 三个实心点。半径取 0.055w：再小在 18dp 上会消失，再大会连成一坨。
                listOf(0.22f, 0.50f, 0.78f).forEach { x ->
                    drawCircle(tint, radius = w * 0.055f, center = Offset(w * x, h * 0.5f))
                }
            }

            PhoneGlyph.Cast -> {
                // 屏幕外框画成「缺角矩形」：右下角要留给两道信号弧，不画满。
                drawPath(
                    Path().apply {
                        moveTo(w * 0.14f, h * 0.22f)
                        lineTo(w * 0.88f, h * 0.22f)
                        lineTo(w * 0.88f, h * 0.72f)
                        lineTo(w * 0.58f, h * 0.72f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.14f, h * 0.72f)
                        lineTo(w * 0.14f, h * 0.22f)
                    },
                    color = tint,
                    style = stroke,
                )
                // 左下角两道同心弧 + 实心点：投播的通用造型，缩小到 18dp 也认得出。
                listOf(0.26f, 0.46f).forEach { ratio ->
                    val r = w * ratio
                    drawArc(
                        color = tint,
                        startAngle = 180f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(w * 0.12f - r, h * 0.74f - r),
                        size = Size(r * 2, r * 2),
                        style = stroke,
                    )
                }
                drawCircle(tint, radius = w * 0.055f, center = Offset(w * 0.12f, h * 0.74f))
            }

            PhoneGlyph.ChevronDown -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.24f, h * 0.42f)
                        lineTo(w * 0.50f, h * 0.64f)
                        lineTo(w * 0.76f, h * 0.42f)
                    },
                    color = tint,
                    style = stroke,
                )
            }

            PhoneGlyph.Copy -> {
                // 前面那张纸留出右下角，后面那张只画左上两条边——
                // 两张都画整框会在 17dp 上糊成一团。
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.14f, h * 0.14f),
                    size = Size(w * 0.52f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.10f),
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.38f, h * 0.80f)
                        lineTo(w * 0.76f, h * 0.80f)
                        lineTo(w * 0.86f, h * 0.70f)
                        lineTo(w * 0.86f, h * 0.34f)
                    },
                    color = tint,
                    style = stroke,
                )
            }

            PhoneGlyph.Trash -> {
                // 盖子
                drawLine(
                    color = tint,
                    start = Offset(w * 0.18f, h * 0.28f),
                    end = Offset(w * 0.82f, h * 0.28f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
                // 提手
                drawPath(
                    Path().apply {
                        moveTo(w * 0.40f, h * 0.28f)
                        lineTo(w * 0.40f, h * 0.18f)
                        lineTo(w * 0.60f, h * 0.18f)
                        lineTo(w * 0.60f, h * 0.28f)
                    },
                    color = tint,
                    style = stroke,
                )
                // 桶身
                drawPath(
                    Path().apply {
                        moveTo(w * 0.26f, h * 0.28f)
                        lineTo(w * 0.32f, h * 0.84f)
                        lineTo(w * 0.68f, h * 0.84f)
                        lineTo(w * 0.74f, h * 0.28f)
                    },
                    color = tint,
                    style = stroke,
                )
            }

            PhoneGlyph.Sequence -> {
                // 顺序播放：一个向右的箭头，末端一条竖线。
                // 与「下一首」（三角 + 竖线）刻意区分：这里箭头是**描边**、竖线贴着箭头尖，
                // 表示「一首接一首往下走，走到队尾就停」。
                drawPath(
                    Path().apply {
                        moveTo(w * 0.22f, h * 0.22f)
                        lineTo(w * 0.62f, h * 0.50f)
                        lineTo(w * 0.22f, h * 0.78f)
                    },
                    color = tint,
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.70f, h * 0.26f), Offset(w * 0.70f, h * 0.74f), sw, StrokeCap.Round)
            }

            PhoneGlyph.History -> {
                // 圆角时钟：圆 + 中心点 + 分针与时针。时针短、分针长，指向右上方。
                drawCircle(
                    color = tint,
                    radius = w * 0.34f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.28f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.68f, h * 0.58f), sw, StrokeCap.Round)
            }
        }
    }
}
