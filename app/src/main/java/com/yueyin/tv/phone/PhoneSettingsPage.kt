package com.yueyin.tv.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yueyin.tv.core.model.PlayerStyle
import com.yueyin.tv.core.model.preferences.AmbientEffect
import com.yueyin.tv.core.model.preferences.CacheBudget
import com.yueyin.tv.core.model.preferences.UiScale
import com.yueyin.tv.ui.FnColors

/**
 * 设置页的二级面板。
 *
 * 为什么不给每个面板开一条 [com.yueyin.tv.ui.LibraryRoute]：那套路由是电视壳共用的，
 * 手机每加一个面板就往里塞一个 data object，电视壳的 `when` 也要跟着补分支——纯负担。
 * 平台与音源例外：它们在电视端本来就是正式路由（[LibraryRoute.PlatformManage] 等），
 * 手机直接复用，天然获得返回键语义，不需要在这里再定义一遍。
 */
internal enum class PhoneSettingsPane {
    Root,
    Player,
    Fx,
    Cache,
    About,
    CrashLog,
}

/**
 * 手机设置页。
 *
 * 电视端是「左栏 5 组 + 右栏长表单」，竖屏放不下两栏，改成**单列分组 + 二级页**：
 * 根页每行只回答一个问题（这项现在是什么状态），细节进二级页改。
 * 这样一屏能看完全部 5 组，不用滚半天才找到「缓存」。
 *
 * [entryPane] 供「我的」页直达某个面板（点「缓存与存储」直接进缓存）。
 */
@Composable
internal fun PhoneSettingsPage(
    entryPane: PhoneSettingsPane,
    onBack: () -> Unit,
) {
    var pane by remember(entryPane) { mutableStateOf(entryPane) }
    // 面板内二级（关于 → 崩溃日志）先回入口面板，再交外层 onBack 回到「我的」；
    // 不再经过一层「设置」总览，也就消除了「我的」与「设置」重复列两份的问题。
    BackHandler(enabled = pane != entryPane) {
        pane = entryPane
    }

    when (pane) {
        PhoneSettingsPane.Player -> PhonePlayerSettingsPane(onBack = onBack)
        PhoneSettingsPane.Fx -> PhoneFxSettingsPane(onBack = onBack)
        PhoneSettingsPane.Cache -> PhoneCacheSettingsPane(onBack = onBack)
        PhoneSettingsPane.About -> PhoneAboutPane(
            onBack = onBack,
            onOpenCrashLog = { pane = PhoneSettingsPane.CrashLog },
        )
        PhoneSettingsPane.CrashLog -> PhoneCrashLogPane(onBack = { pane = PhoneSettingsPane.About })
        else -> onBack()
    }
}

// ---------------------------------------------------------------------------
// 通用行控件
//
// 全放在这里而不是各面板里各写一遍：设置页最容易失控的地方是「行高、字号、
// 分隔线、右侧文案」四处漂移，一屏里出现三种行高的观感比功能缺失更显眼。
// ---------------------------------------------------------------------------

/** 分组卡片：可选标题 + 圆角容器，行之间用 [PhoneSettingsDivider] 分隔。 */
@Composable
internal fun PhoneSettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) {
        Text(
            title,
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = PhoneSpace.xl, bottom = PhoneSpace.sm),
        )
    } else {
        Spacer(Modifier.height(PhoneSpace.xl))
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.md))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(PhoneRadius.md)),
        content = content,
    )
}

/** 组内分隔线：左缩进对齐文字，和 iOS/Android 设置页的习惯一致。 */
@Composable
internal fun PhoneSettingsDivider(startInset: Int = 16) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startInset.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.05f)),
    )
}

/**
 * 可点击的设置行：标题 + 可选说明 + 右侧当前值 + 尖角。
 *
 * [value] 传 null 表示「这一行没有状态可展示」（例如「反馈与崩溃日志」），
 * 此时不画右侧文案，只留尖角——避免出现一个空的对齐坑。
 */
@Composable
internal fun PhoneSettingsRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .phoneTap(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = FnColors.Text,
                fontSize = PhoneType.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    color = FnColors.Muted,
                    fontSize = PhoneType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(PhoneSpace.md))
            Text(
                value,
                color = FnColors.Muted,
                fontSize = PhoneType.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 右侧值最多占 45%，长平台名不会把左边的标题挤成两行。
                modifier = Modifier.fillMaxWidth(0.45f),
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.width(6.dp))
        PhoneIcon(
            PhoneGlyph.ChevronRight,
            FnColors.Muted.copy(alpha = 0.6f),
            Modifier.size(14.dp),
            strokeWidth = 1.7.dp,
        )
    }
}

/** 开关行：右侧用自绘开关，与播放页音效面板同一颗控件。 */
@Composable
internal fun PhoneSettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = FnColors.Text,
                fontSize = PhoneType.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    color = FnColors.Muted,
                    fontSize = PhoneType.Caption,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(PhoneSpace.md))
        PhoneSwitch(checked = checked, onToggle = onToggle)
    }
}

/** 纯动作行（清理缓存、退出登录）：文字可标红，右侧没有尖角。 */
@Composable
internal fun PhoneSettingsActionRow(
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .phoneTap(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            title,
            color = if (danger) FnColors.Coral else FnColors.Text,
            fontSize = PhoneType.Body,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = FnColors.Muted, fontSize = PhoneType.Caption, maxLines = 2)
        }
    }
}

/** 只读信息行：左边标签，右边值，不可点。 */
@Composable
internal fun PhoneSettingsInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = FnColors.Muted, fontSize = PhoneType.Row, modifier = Modifier.weight(1f))
        Text(
            value,
            color = FnColors.Text,
            fontSize = PhoneType.Row,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.5f),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 单选组：一行标题 + 下方一排等宽选项。
 *
 * 不用下拉菜单：手机上「点开再选」是两步，而选项最多 4 个，平铺一步到位，
 * 还能同时看到所有档位——这正是设置页要回答的问题。
 */
@Composable
internal fun <T> PhoneSettingsChoiceRow(
    title: String,
    subtitle: String? = null,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            title,
            color = FnColors.Text,
            fontSize = PhoneType.Body,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = FnColors.Muted, fontSize = PhoneType.Caption, maxLines = 2)
        }
        Spacer(Modifier.height(PhoneSpace.sm + 2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
            options.forEach { option ->
                PhoneChoiceChip(
                    label = label(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 选项胶囊：选中是珊瑚描边 + 珊瑚字，未选中是发丝线 + 次文字。 */
@Composable
internal fun PhoneChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(38.dp)
            .background(
                if (selected) FnColors.Coral.copy(alpha = 0.14f) else FnColors.SurfaceAlt,
                shape,
            )
            .border(
                1.dp,
                if (selected) FnColors.Coral.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.07f),
                shape,
            )
            .phoneTap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) FnColors.Coral else FnColors.Muted,
            fontSize = PhoneType.Label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// 文案
// ---------------------------------------------------------------------------

internal fun UiScale.scaleName(): String = when (this) {
    UiScale.Compact -> "紧凑"
    UiScale.Standard -> "标准"
    UiScale.Relaxed -> "宽松"
}

internal fun AmbientEffect.effectName(): String = when (this) {
    AmbientEffect.Off -> "关闭"
    AmbientEffect.Glow -> "光晕"
    AmbientEffect.Ripple -> "涟漪"
    AmbientEffect.Auto -> "自动"
}

internal fun CacheBudget.budgetName(): String = "${megabytes} MB"

