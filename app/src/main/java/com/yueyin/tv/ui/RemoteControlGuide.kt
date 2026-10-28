package com.yueyin.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.yueyin.tv.R

private data class RemoteKeyHint(
    val keys: List<String>,
    val action: String,
)

/**
 * 播放页遥控器按键说明。
 *
 * 原先这段提示是在每次进入播放页时以 OSD 形式自动弹出的，会挡住封面；
 * 现在改为首次安装（首次登录成功）后展示一次，之后只在设置页手动查看。
 */
private val REMOTE_KEY_HINTS: List<RemoteKeyHint> = listOf(
    RemoteKeyHint(listOf("←", "→"), "快退 / 快进 10 秒"),
    RemoteKeyHint(listOf("←←", "→→"), "连按两次切上/下一曲"),
    RemoteKeyHint(listOf("←", "→"), "长按可连续快退 / 快进"),
    RemoteKeyHint(listOf("确定"), "播放 / 暂停"),
    RemoteKeyHint(listOf("↑", "↓"), "呼出 / 收起控制栏"),
    RemoteKeyHint(listOf("返回"), "退出播放页"),
)

/**
 * 遥控器按键说明介绍页：首次登录成功后自动展示一次，关闭后写入偏好不再弹出。
 *
 * 版式按 4K 密度补偿后的 960×540dp 画布设计：双列按键区 + 紧凑头部，
 * 内容总高约 400dp，上下各留约 70dp 呼吸空间；heightIn + verticalScroll 兜底，
 * 系统字体再放大也不会被裁切。
 */
@Composable
internal fun RemoteControlGuide(onDismiss: () -> Unit) {
    val confirmFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6070A10)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(760.dp)
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .background(FnColors.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, FnColors.Coral.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .padding(horizontal = 36.dp, vertical = 28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo_mark),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "欢迎使用悦音",
                        color = FnColors.Text,
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Android TV 自建音乐库播放器",
                        color = FnColors.Muted,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "播放页遥控器按键",
                color = FnColors.Coral,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                REMOTE_KEY_HINTS.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { hint ->
                            GuideHintCell(hint, Modifier.weight(1f))
                        }
                        if (pair.size < 2) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GuideActionButton(
                    label = "开始使用",
                    onClick = onDismiss,
                    modifier = Modifier.width(200.dp).focusRequester(confirmFocus),
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
}

/** 双列版式下的单个按键说明格：键帽在左、动作说明在右。 */
@Composable
private fun GuideHintCell(hint: RemoteKeyHint, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(52.dp)
            .background(FnColors.SurfaceAlt, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hint.keys.forEachIndexed { index, key ->
            if (index > 0) Spacer(Modifier.width(5.dp))
            GuideKeyCap(key)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            hint.action,
            color = FnColors.Text,
            fontSize = 16.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 按键胶囊：描边小方块，模拟遥控器键帽。 */
@Composable
private fun GuideKeyCap(label: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .border(1.dp, FnColors.Muted.copy(alpha = 0.30f), shape)
            .background(Color(0xFF0C1219), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = FnColors.Text,
            fontSize = 15.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun GuideActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Button(
        onClick = onClick,
        modifier = modifier.focusBringIntoView().height(48.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.03f),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.SurfaceAlt,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.Coral,
            focusedContentColor = Color(0xFF0B0F16),
            pressedContainerColor = FnColors.Coral,
            pressedContentColor = Color(0xFF0B0F16),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 启动过渡层：**唱盘起转**式退场，四段严格串行（总长约 1.2s）：
 *
 * 1. **待机慢转**（常驻）：唱片以 15°/s 慢慢转（24 秒一圈），安静待机；
 * 2. **文字退场**（0–260ms）：左侧品牌文字淡出，画面只剩唱片；
 * 3. **声波扩散**（240–1060ms）：唱片一边甩起来（15°/s→200°/s，320ms 内）
 *    一边淡出（240–560ms 淡完），把画面完全让给两圈向外扩散、边扩边淡的珊瑚色声波环；
 * 4. **首页浮现**（620–1200ms）：纯色底先化开，首页从声波后面透出来，
 *    最后整层淡出，首页完全呈现。
 *
 * 让唱片中途退场（而不是一路转到最后）是为了避免"转盘"和"声波"两个动作抢戏：
 * 声波是唱片"放出声音"的延续，唱片淡掉、声波扩散，视觉上是一次接力。
 */
@Composable
internal fun StartupSplash(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = EnterTransition.None,
        // 第 4 段：首页先浮现，这一层最后才淡出
        exit = fadeOut(tween(durationMillis = 340, delayMillis = 860, easing = LinearEasing)),
        label = "startup-splash",
    ) {
        // AnimatedVisibilityScope.transition 的目标态是 EnterExitState：
        // 变成 PostExit 就说明这层正在退场，可以继续编排子动画
        SplashStage(exiting = transition.targetState == EnterExitState.PostExit)
    }
}

@Composable
private fun SplashStage(exiting: Boolean) {
    // 第 2 段：文字退场（0–260ms）
    val textAlpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = tween(durationMillis = 260, easing = LinearEasing),
        label = "splash-text",
    )
    // 第 4 段：首页浮现 —— 纯色底化开，首页从唱片底下透出来（620–1040ms）
    val backdropAlpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = tween(durationMillis = 420, delayMillis = 620, easing = LinearEasing),
        label = "splash-backdrop",
    )
    // 第 3 段：声波扩散一开始，唱片就把"接力棒"交给声波 ——
    // 盘体在 240–560ms 内一边甩起来一边淡出，之后画面上只剩向外扩散的声波环
    val discAlpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = tween(durationMillis = 320, delayMillis = 240, easing = LinearEasing),
        label = "splash-disc-fade",
    )
    val recordScale by animateFloatAsState(
        targetValue = if (exiting) 1.06f else 1f,
        animationSpec = tween(durationMillis = 320, delayMillis = 240, easing = LinearOutSlowInEasing),
        label = "splash-record-scale",
    )
    val ringProgress by animateFloatAsState(
        targetValue = if (exiting) 1f else 0f,
        animationSpec = tween(durationMillis = 820, delayMillis = 240, easing = LinearEasing),
        label = "splash-rings",
    )

    // 第 1 段：待机慢转。转速用 Animatable 手动积分角度：只有这样才能做「逐渐加速」，
    // rememberInfiniteTransition 的时长在创建时就固定了，改不了。
    val spinSpeed = remember { Animatable(VINYL_IDLE_DEG_PER_SEC) }
    LaunchedEffect(exiting) {
        if (exiting) {
            // 等文字退完（260ms）再起转；因为盘体 560ms 就淡掉了，
            // 加速要在 320ms 内甩起来，才看得到"转飞出去"的那一下
            delay(240)
            spinSpeed.animateTo(
                targetValue = VINYL_SPINUP_DEG_PER_SEC,
                animationSpec = tween(durationMillis = 320, easing = FastOutLinearInEasing),
            )
        }
    }
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previousFrame = 0L
        while (true) {
            withFrameNanos { now ->
                val deltaSeconds = if (previousFrame == 0L) 0f else (now - previousFrame) / 1_000_000_000f
                previousFrame = now
                angle = (angle + deltaSeconds * spinSpeed.value) % 360f
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(FnColors.Background.copy(alpha = backdropAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        // 声波环：从盘沿向外扩到屏幕外。整屏 Canvas 才画得出去，
        // 圆心按「右侧 360dp 唱片 + 左右各 96dp 边距」算。
        if (ringProgress > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                val sidePadding = 96.dp.toPx()
                val recordSize = 360.dp.toPx()
                val discCenter = Offset(
                    x = size.width - sidePadding - recordSize / 2f,
                    y = size.height / 2f,
                )
                val startRadius = recordSize / 2f * 0.84f
                val maxRadius = size.width + size.height
                repeat(2) { index ->
                    val local = ((ringProgress - index * 0.14f) / 0.86f).coerceIn(0f, 1f)
                    if (local > 0f) {
                        drawCircle(
                            color = FnColors.Coral.copy(alpha = (1f - local) * 0.30f),
                            radius = startRadius + local * maxRadius,
                            center = discCenter,
                            style = Stroke(width = (2.6f - local * 1.8f).coerceAtLeast(0.7f).dp.toPx()),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 96.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.graphicsLayer { alpha = textAlpha },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "悦音",
                    color = FnColors.Text,
                    fontSize = 62.sp,
                    lineHeight = 70.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp,
                )
                Text(
                    // 接入多平台后品牌不再绑定某个服务
                    "你的曲库为你发声",
                    color = FnColors.Muted,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 2.4.sp,
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .width(56.dp)
                        .height(2.dp)
                        .background(FnGradients.Primary),
                )
            }
            // 右侧大黑胶：纯 Canvas 自绘，起转时甩一下并淡出，把画面让给声波
            Box(
                Modifier.graphicsLayer {
                    scaleX = recordScale
                    scaleY = recordScale
                    alpha = discAlpha
                },
            ) {
                VinylRecord(
                    size = 360.dp,
                    angle = angle,
                    // 转速越高高光弧越长，制造"糊成一片"的运动感
                    highlightSweep = 64f + (spinSpeed.value - VINYL_IDLE_DEG_PER_SEC) /
                        (VINYL_SPINUP_DEG_PER_SEC - VINYL_IDLE_DEG_PER_SEC) * 56f,
                )
            }
        }
    }
}

/** 待机转速：24 秒一圈（比真机 33⅓ 转慢很多，电视上看才不晃眼）。 */
private const val VINYL_IDLE_DEG_PER_SEC = 15f

/** 起转后的峰值转速：约 1.8 秒一圈。 */
private const val VINYL_SPINUP_DEG_PER_SEC = 200f

/**
 * 启动页大黑胶：把 App 图标里的那枚唱片（`ic_logo` 中的唱片部分）**按原比例放大**，
 * 纯 Canvas 重绘，不再叠 `ic_logo` 本身。
 *
 * 为什么必须重绘而不是直接放大 `ic_logo`：那个 vector 第一条 path 就是 108×108 的
 * 深灰**圆角方块**底板，放大后等于「方形色块 + 外面圆形光晕」，边界互相打架、非常割裂。
 * 这里只保留图标唱片的图形语言 —— 米白外圈、珊瑚高光弧、珊瑚内圈、米白细圈、珊瑚中心标签 ——
 * 比例全部照搬 icon（外圈 r=35.5/w=4、高光弧 w=4.6、珊瑚圈 r=27/w=2.6、
 * 细圈 r=18/w=1.8、中心 r=9），以 icon 的 42 为基准等比缩放。
 * 整盘由外部传入 [angle] 驱动旋转（而不是自己跑无限动画）：
 * 启动退场要「越转越快」，这要求转速能被外部逐步拉高，
 * 无限动画的周期在创建时就固定了，做不到加速。
 */
@Composable
private fun VinylRecord(
    size: Dp,
    angle: Float,
    highlightSweep: Float = 64f,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // 背后暖色柔光：径向渐变淡到全透明，边界不可见（只是衬托，不构成圆形硬边）
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to FnColors.Coral.copy(alpha = 0.16f),
                    0.6f to FnColors.Coral.copy(alpha = 0.05f),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = r,
                ),
                radius = r * 0.92f,
                center = center,
            )
        }
        Canvas(
            Modifier
                .fillMaxSize(0.84f)
                .graphicsLayer { rotationZ = angle },
        ) {
            val r = this.size.minDimension / 2f
            val c = center
            // 米白外圈（icon: r=35.5 / w=4 → 线宽 = 0.113r）
            drawCircle(
                color = Color(0xFFF5F1E8),
                radius = r,
                center = c,
                style = Stroke(width = r * 0.113f),
            )
            // 珊瑚高光弧：盖在外圈右上四分之一（icon: w=4.6，略粗于外圈）
            // sweepAngle 由转速决定，起转时拉长成"拖影"
            drawArc(
                color = FnColors.Coral,
                startAngle = -74f,
                sweepAngle = highlightSweep,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = r * 0.13f, cap = StrokeCap.Round),
            )
            // 珊瑚内圈（icon: r=27 / w=2.6）
            drawCircle(
                color = FnColors.Coral,
                radius = r * 0.76f,
                center = c,
                style = Stroke(width = r * 0.096f),
            )
            // 米白细圈（icon: r=18 / w=1.8，透明度 0.35）
            drawCircle(
                color = Color(0xFFF5F1E8).copy(alpha = 0.35f),
                radius = r * 0.507f,
                center = c,
                style = Stroke(width = r * 0.10f),
            )
            // 珊瑚中心标签（icon: r=9 实心）
            drawCircle(color = FnColors.Coral, radius = r * 0.254f, center = c)
        }
    }
}
