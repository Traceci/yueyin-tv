package com.yueyin.tv.phone

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.yueyin.tv.ui.device.FormFactor
import com.yueyin.tv.ui.device.rememberFormFactor

/**
 * 手机壳的宽度档位。
 *
 * 三档而不是五档：手机版真正需要区分的只有「竖着拿」「宽了但还不够双栏」「够双栏」三种。
 * 再细分下去每多一档就要多一套预览和回归，收益不成比例。
 */
internal enum class PhoneWidthClass {
    /** 手机竖屏，< 600dp。底栏 + 2 列。 */
    Compact,

    /** 手机横屏、折叠展开、小平板竖屏，600~899dp。侧栏 + 3 列。 */
    Medium,

    /** 平板，≥ 900dp。侧栏 + 双栏详情（列数另按最小卡宽反算，见 [gridColumns]）。 */
    Expanded,
}

/**
 * 当前窗口的尺寸事实与由此推出的布局决策。
 *
 * 为什么用 `BoxWithConstraints` 实测而不是读 `Configuration.screenWidthDp`：
 * 分屏和自由窗口下屏幕宽度不等于窗口宽度，读 Configuration 会在分屏时按整屏宽度排 4 列网格，
 * 每列只剩几十 dp，卡片挤成一团。约束实测拿到的就是这块画布真实可用宽度。
 *
 * @param widthDp 可用宽度（已扣掉系统栏）
 * @param heightDp 可用高度
 */
internal data class PhoneWindow(
    val widthDp: Int,
    val heightDp: Int,
    /** 车机档：仅影响网格密度与侧栏宽度，不改变手机/平板任何既有断点。 */
    val isCar: Boolean = false,
) {
    val widthClass: PhoneWidthClass = when {
        widthDp >= 900 -> PhoneWidthClass.Expanded
        widthDp >= 600 -> PhoneWidthClass.Medium
        else -> PhoneWidthClass.Compact
    }

    /** 横屏：宽大于高。播放页据此改左右分栏。 */
    val isLandscape: Boolean = widthDp > heightDp

    /**
     * 内容区真实可用宽度。
     *
     * [widthDp] 是整块画布（含左侧图标栏）。网格的列数必须按扣掉图标栏与页面左右留白
     * 之后的宽度算，否则横屏手机上会按 914dp 去排，实际只剩 810dp，每列都被压窄 12%。
     *
     * 用 getter 而不是初始化赋值：它依赖声明在后面的 [usesNavRail]，
     * 放在构造期赋值会取到默认值 false，横屏永远算多 72dp。
     */
    val contentWidthDp: Int
        get() = widthDp -
            (if (usesNavRail) (if (isCar) CAR_NAV_RAIL_DP else PhoneSize.NavRail.value.toInt()) else 0) -
            2 * PhoneSpace.screenHorizontal.value.toInt()

    /**
     * 网格列数：按「最小卡宽 150dp + 12dp 间距」反算，2~6 列封顶。
     *
     * 为什么不再按宽度档位写死 2/3/4：档位是给「布局形态」用的（有没有侧栏、开不开双栏），
     * 拿它推列数会在临界点上跳变——横屏手机 914dp 刚跨过 900 就固定 4 列，
     * 每张卡 193dp，横向铺满、内容却只有 4 张，看起来又空又大。
     *
     * 为什么最小 150dp：卡片下面还有标题和歌手两行 12sp/11sp 文字，再小就只剩
     * 8 个中文字的宽度，截断会比列数少更难看。按 150dp 算，910dp 横屏得 5 列（卡 152dp）。
     */
    val gridColumns: Int
        get() {
            // 车机用更大的最小卡宽：卡更大 → 封面显示像素更大 → 配合 P3 按显示像素取档，
            // 不会拿小图放大；同时超宽屏也不会密密麻麻排 6 列。
            val minItem = if (isCar) CAR_GRID_MIN_ITEM_DP else GRID_MIN_ITEM_DP
            return ((contentWidthDp + GRID_GAP_DP) / (minItem + GRID_GAP_DP)).coerceIn(2, 6)
        }

    /**
     * 歌手格子的列数上限。
     *
     * 歌手格子是「圆形头像 + 名字 + 曲目数」的横向条，比专辑卡更吃宽度，
     * 所以单独封顶：竖屏仍 2 列（与原行为一致），横屏/平板放宽到 3 列（每个 ≥ 230dp）。
     */
    val artistColumns: Int
        get() = if (contentWidthDp >= 700) 3 else 2

    /**
     * 用左侧图标栏代替底部导航。
     *
     * 到 600dp 就换：横屏手机高度只有 390dp 左右，底部 54dp 的导航条要吃掉近 1/7 的纵向空间，
     * 换成左侧 72dp 竖条后纵向全部还给内容，横向还有 700dp 以上，怎么算都划算。
     */
    val usesNavRail: Boolean = widthClass != PhoneWidthClass.Compact

    /**
     * 列表与详情并列。
     *
     * 门槛取 900 而不是 Material 常用的 840：840~900 这一段（典型是 7 寸平板竖屏 860dp）
     * 左栏固定 380dp 后右栏只剩 460dp，网格放 2 列将就、放 3 列挤，反而不如单栏 4 列好用。
     *
     * 但光看宽度会误伤横屏手机：1080×2400 @420dpi 横过来是 ~914×411dp，宽度已跨过 900 的
     * Expanded 线，却只有 ~410dp 高——这种「够宽不够高」的窗口塞双栏只会把列表和详情复制成
     * 两半，谁都看不全。所以再加一道高度门槛：只有「够宽且够高」（典型是平板，而非横屏手机）
     * 才开双栏。
     */
    // 高度门槛对车机尤其关键：1920x720 的超宽屏约 1200x450dp，宽度早已跨过 Expanded 线，
    // 但高度不足 600dp，开双栏只会把列表和详情挤成两半，所以这里继续禁用。
    val usesSplitPane: Boolean = widthClass == PhoneWidthClass.Expanded && heightDp >= 600
}

/**
 * 当前窗口。默认值取 Compact：任何页面在没被 [ProvidePhoneWindow] 包住时都按手机竖屏渲染，
 * 与 P0~P4 的行为一致，不会因为漏包一层就变成平板布局。
 */
internal val LocalPhoneWindow = staticCompositionLocalOf { PhoneWindow(390, 844) }

/** 网格最小卡宽（dp）。再小标题只能放下 8 个中文字，截断比列数少更难看。 */
private const val GRID_MIN_ITEM_DP = 150

/** 网格列间距（dp）。与 `PhonePagedGrid` 里 `spacedBy(PhoneSpace.md)` 的 12dp 必须一致。 */
private const val GRID_GAP_DP = 12

/**
 * 实测窗口尺寸并向下提供。
 *
 * 只在整个手机壳的最外层包一次（`PhoneApp`），页面里用 `LocalPhoneWindow.current` 读，
 * 避免每层各自 `BoxWithConstraints` —— 那样每一层都会多一次测量订阅，且各层拿到的值
 * 可能因为父容器已扣 padding 而不一致。
 */
@Composable
internal fun ProvidePhoneWindow(content: @Composable (PhoneWindow) -> Unit) {
    // LocalFormFactor 目前没有任何调用点 provide（默认值恒为 Tv），
    // 所以这里直接用纯函数判一次形态：只有车机给 Car 档，手机/平板走原逻辑。
    // 继续用 BoxWithConstraints 实测约束，不假设固定宽高比，分屏/自由窗口一样正确。
    val isCar = rememberFormFactor() == FormFactor.Car
    val carProfile = if (isCar) CarProfile.Enabled else CarProfile.Disabled
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val window = remember(maxWidth, maxHeight, isCar) {
            PhoneWindow(
                widthDp = maxWidth.value.toInt(),
                heightDp = maxHeight.value.toInt(),
                isCar = isCar,
            )
        }
        CompositionLocalProvider(
            LocalPhoneWindow provides window,
            LocalCarProfile provides carProfile,
        ) {
            content(window)
        }
    }
}
