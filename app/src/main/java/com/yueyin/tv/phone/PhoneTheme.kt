package com.yueyin.tv.phone

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 手机端字号阶梯。
 *
 * 为什么不复用 `FnType`：那套号码（30/23/19）是按 960×540 电视画布 + 3 米观看距离校准的。
 * 电视画布折算后 19sp 在 1080p 电视上物理高度约 3.8mm，而手机 390dp 宽屏上 19sp 已经接近
 * 正文上限——两者数值相同、视觉重量完全不同。所以手机独立一套，取常规移动端区间。
 *
 * 命名与 `FnType` 保持一一对应，方便两边对照迁移时不出错。
 */
object PhoneType {
    /** 首页问候 / 空态主标题。 */
    val Display = 28.sp

    /** 页面标题（曲库、我的、设置）。 */
    val Title = 22.sp

    /** 屏幕内的大标题（详情页名称、播放页曲名）。 */
    val Headline = 20.sp

    /** 分组标题（设置分组、搜索结果分区）。 */
    val Section = 17.sp

    /** 正文与列表主文字。 */
    val Body = 15.sp

    /** 列表副标题 / 歌手名。 */
    val Row = 14.sp

    /** 标签、按钮文字。 */
    val Label = 13.sp

    /** 副标题、元信息。 */
    val Caption = 12.sp

    /** 极小角标（导航文字、时长）。 */
    val Micro = 11.sp
}

/**
 * 手机端间距阶梯。与 `FnSpace` 同档位名，数值按移动端密度收敛。
 */
object PhoneSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp

    /** 屏幕左右安全边距：手机竖屏的通用留白，所有页面统一用它，不要各自写 16/18/20。 */
    val screenHorizontal = 16.dp
}

/**
 * 手机端关键尺寸。
 *
 * 全部按「单手可达」标定：底部导航与迷你条压在拇指自然落点上，
 * 主操作按钮（播放/暂停）不高于屏幕下 1/3。
 */
object PhoneSize {
    /** 页面顶部标题区高度。 */
    val TopBar = 52.dp

    /** 迷你播放条高度（不含进度线）。 */
    val MiniPlayer = 58.dp

    /** 底部导航条内容区高度（不含系统手势区）。 */
    val BottomBar = 54.dp

    /** 宽屏左侧图标栏宽度。72dp 刚好放下 22dp 图标 + 11sp 两字标签，再窄「设置」会换行。 */
    val NavRail = 72.dp

    /** 侧栏单项高度。四个 tab 竖排共 256dp，1080p 平板竖屏（约 800dp 高）还有富余。 */
    val NavRailItem = 64.dp

    /** 迷你播放条封面边长。 */
    val MiniCover = 44.dp

    /** 最小可点区域：低于 44dp 手指容易点不中，全部可点元素以此兜底。 */
    val TouchTarget = 44.dp

    /** 列表行标准高度。 */
    val RowItem = 56.dp

    /** 行内缩略图边长（曲目行、歌手行）。 */
    val RowArt = 44.dp

    /** 横滑影带的单项宽度。132dp 在 390dp 竖屏上刚好露出「两张半」，暗示还能往右滑。 */
    val StripItem = 132.dp


    /** 主按钮高度。 */
    val PrimaryButton = 48.dp
}

/**
 * 手机端圆角。四档，与 `FnRadius` 同构但数值更圆——移动端屏幕小，同样的 6dp 在手机上显得生硬。
 */
object PhoneRadius {
    /** 8dp：缩略图、小标签、输入框。 */
    val sm = 8.dp

    /** 12dp：卡片、列表行容器、搜索框。 */
    val md = 12.dp

    /** 16dp：面板、弹层、大按钮。 */
    val lg = 16.dp

    /** 20dp：底部 sheet 顶角、大封面。 */
    val xl = 20.dp
}
