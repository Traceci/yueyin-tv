package com.yueyin.tv.ui.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 预建静态渐变与颜色表。
 *
 * 项目里已有 `FnGradients` object 预建了 4 支品牌渐变（静态 val，本身不会重复创建）；
 * 本文件补齐另外两类：绘制阶段的高光色表，以及依赖尺寸/色表的 remember 缓存。
 * 页面里仍有若干内联 `Brush.*Gradient(...)`，每次重组都会新建对象，
 * 低端设备上焦点/进度引起的高频重组会因此产生大量短命对象，后续逐处迁移到这里。
 */
object FnBrushes {
    /** 骨架屏扫光高光带（固定色列，几何起止点仍由绘制阶段计算）。 */
    val SkeletonHighlight: List<Color> = listOf(
        Color.Transparent,
        Color.White.copy(alpha = 0.07f),
        Color.Transparent,
    )

    /** 卡片表面从上到下的极淡高光。 */
    val SurfaceSheen: List<Color> = listOf(
        Color.White.copy(alpha = 0.06f),
        Color.Transparent,
    )

    /** 封面/海报底部压暗，保证叠字可读。 */
    val ScrimBottom: List<Color> = listOf(
        Color.Transparent,
        Color.Black.copy(alpha = 0.55f),
    )
}

/** 依赖尺寸/颜色列表的渐变在尺寸不变时复用同一实例。 */
@Composable
fun rememberVerticalBrush(colors: List<Color>): Brush = remember(colors) {
    Brush.verticalGradient(colors)
}

@Composable
fun rememberHorizontalBrush(colors: List<Color>): Brush = remember(colors) {
    Brush.horizontalGradient(colors)
}

/**
 * 播放页氛围底色的上下压暗笔刷。
 *
 * 用相对 colorStops + `endY = Float.POSITIVE_INFINITY`，笔刷与绘制尺寸无关，
 * 因此可以只创建一次；原先在 Canvas 的每帧绘制里按 size.height 新建两支
 * `Brush.verticalGradient`，是低端设备上一笔可省的重复分配。
 */
@Composable
fun rememberScrimBrushes(): Pair<Brush, Brush> = remember {
    val bottom = Brush.verticalGradient(
        0.5f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.55f),
    )
    val top = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.25f),
        0.25f to Color.Transparent,
    )
    bottom to top
}
