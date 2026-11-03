package com.yueyin.tv.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.yueyin.tv.ui.FnColors

/**
 * 手机版首次启动引导。
 *
 * 为什么手机需要单独一份：电视端那次引导讲的是「遥控器怎么按」（方向键、确定键），
 * 手机上一条都用不上；手机真正需要先知道的是「音乐从哪来」和「播放页怎么点」这两件事，
 * 而这两件事在空状态下都没有入口提示——新用户打开只看到一句「还没有接入任何平台」。
 *
 * 做成盖在壳上的一层，而不是一条路由：引导不属于任何 Tab，压栈会让返回键行为变奇怪
 * （用户按返回会退回一个「不存在」的上一页）。看完或跳过后写一次性标记，不再出现。
 */
@Composable
internal fun PhoneOnboarding(
    onFinish: () -> Unit,
    onOpenPlatforms: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    val last = page == ONBOARDING_PAGES - 1

    Box(
        Modifier
            .fillMaxSize()
            .background(FnColors.Background),
    ) {
        // 背景吞触摸层：只吃「按钮以外」的点击，不挡按钮本身。
        // 之前把 clickable 放在根 Box 上，会连「下一步」一起吞掉，导致引导卡在第一页进不去。
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        )
        // 顶部一团珊瑚光：整屏纯黑会让三页引导看起来像三张错误页。
        Canvas(Modifier.fillMaxWidth().height(360.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        FnColors.Coral.copy(alpha = 0.20f),
                        FnColors.Coral.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.78f, 40f),
                    radius = size.width * 0.72f,
                ),
                radius = size.width * 0.72f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.78f, 40f),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = PhoneSpace.screenHorizontal),
        ) {
            // 跳过放在最上：三页不算长，但用户没有义务读完。
            Row(Modifier.fillMaxWidth().height(PhoneSize.TopBar), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(
                    "跳过",
                    color = FnColors.Muted,
                    fontSize = PhoneType.Row,
                    modifier = Modifier
                        .phoneTap(onFinish)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (page) {
                    0 -> WelcomePage()
                    1 -> ConnectPage()
                    else -> GesturePage()
                }
            }

            PageDots(page)
            Spacer(Modifier.height(PhoneSpace.lg))
            PhonePrimaryButton(
                // 主按钮只负责「往下走」：第 2 页如果直接跳去接入，第 3 页的手势说明
                // 就永远看不到了（没有返回上一页的入口）。想马上接入的走下面那条珊瑚字链接。
                label = if (last) "开始使用" else "下一步",
                onClick = { if (last) onFinish() else page += 1 },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(PhoneSpace.sm))
            Text(
                when (page) {
                    1 -> "也可以稍后在「我的 → 平台管理」里慢慢设置"
                    2 -> "听歌不需要账号：本地曲库与在线音源都能直接用"
                    else -> "随时可以从「我的」里回到这些设置"
                },
                color = FnColors.Muted.copy(alpha = 0.7f),
                fontSize = PhoneType.Caption,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (page == 1) {
                // 次要入口放在说明下面，并且只包住文字本身：贴着主按钮铺满整行的话，
                // 想点「下一步」会很容易误触到这里，直接被带去平台页、跳过第三页。
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "现在就接入",
                        color = FnColors.Coral,
                        fontSize = PhoneType.Row,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .phoneTap(onOpenPlatforms)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(PhoneSpace.lg))
        }
    }
}

// ---------------------------------------------------------------------------
// 三页
// ---------------------------------------------------------------------------

@Composable
private fun WelcomePage() {
    // 大圆 + 播放三角：不放真实截图，是因为第一次启动时手机里还没有任何封面可截。
    Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        FnColors.Coral.copy(alpha = 0.34f),
                        FnColors.Coral.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = radius * 0.62f,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
            )
        }
        Box(
            Modifier
                .size(92.dp)
                .background(FnColors.Coral, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhoneIcon(PhoneGlyph.Play, FnColors.Background, Modifier.size(34.dp), filled = true)
        }
    }
    Spacer(Modifier.height(PhoneSpace.xl))
    OnboardingTitle("欢迎使用悦音")
    Spacer(Modifier.height(PhoneSpace.sm))
    OnboardingBody("把 NAS 上的曲库、在线音源和你的收藏，装进同一台手机。\n第一次打开，先认识两件事。")
}

@Composable
private fun ConnectPage() {
    OnboardingBadge(PhoneGlyph.Library)
    Spacer(Modifier.height(PhoneSpace.lg))
    OnboardingTitle("音乐从哪来")
    Spacer(Modifier.height(PhoneSpace.sm))
    OnboardingBody("三种来源可以同时存在，随时在「平台管理」里切换。")
    Spacer(Modifier.height(PhoneSpace.lg))
    OnboardingItem(
        title = "飞牛音乐",
        body = "用 NAS 账号登录，曲库与电视端完全同步",
    )
    OnboardingItem(
        title = "在线音乐",
        body = "导入音源脚本，接上官方音乐平台的接口",
    )
    OnboardingItem(
        title = "其它平台",
        body = "Navidrome、Jellyfin、Emby、Plex 等，填地址即可",
    )
}

@Composable
private fun GesturePage() {
    OnboardingBadge(PhoneGlyph.Play)
    Spacer(Modifier.height(PhoneSpace.lg))
    OnboardingTitle("播放页怎么点")
    Spacer(Modifier.height(PhoneSpace.sm))
    OnboardingBody("播放页把控制都收在一屏里，没有隐藏菜单。")
    Spacer(Modifier.height(PhoneSpace.lg))
    OnboardingItem(title = "轻点封面", body = "在封面与黑胶唱片之间切换")
    OnboardingItem(title = "左右滑动封面", body = "切上一首 / 下一首")
    OnboardingItem(title = "点两行歌词", body = "展开全屏歌词，再点左上角收起")
    OnboardingItem(title = "左上角 ⌄", body = "收起播放页，回到刚才浏览的位置")
}

// ---------------------------------------------------------------------------
// 零件
// ---------------------------------------------------------------------------

@Composable
private fun OnboardingTitle(text: String) {
    Text(
        text,
        color = FnColors.Text,
        fontSize = PhoneType.Display,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun OnboardingBody(text: String) {
    Text(
        text,
        color = FnColors.Muted,
        fontSize = PhoneType.Row,
        lineHeight = 21.sp,
        textAlign = TextAlign.Center,
    )
}

/** 小圆图标：第二、三页的页眉。 */
@Composable
private fun OnboardingBadge(glyph: PhoneGlyph) {
    Box(
        Modifier
            .size(76.dp)
            .background(FnColors.Coral.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PhoneIcon(glyph, FnColors.Coral, Modifier.size(30.dp))
    }
}

/** 一条说明：左侧珊瑚圆点 + 标题 + 一句解释。 */
@Composable
private fun OnboardingItem(title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .background(FnColors.Coral, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = FnColors.Text,
                fontSize = PhoneType.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                body,
                color = FnColors.Muted,
                fontSize = PhoneType.Caption,
                lineHeight = 18.sp,
            )
        }
    }
}

/** 页码点：当前页拉长成短横，比纯圆点更容易看出「走到第几页」。 */
@Composable
private fun PageDots(page: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(ONBOARDING_PAGES) { index ->
            val selected = index == page
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .height(6.dp)
                    .width(if (selected) 18.dp else 6.dp)
                    .background(
                        if (selected) FnColors.Coral else Color.White.copy(alpha = 0.18f),
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

private const val ONBOARDING_PAGES = 3
