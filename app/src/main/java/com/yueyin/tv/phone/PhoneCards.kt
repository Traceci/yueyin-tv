package com.yueyin.tv.phone

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yueyin.tv.AppUiDependencies
import com.yueyin.tv.core.model.Album
import com.yueyin.tv.core.model.AppError
import com.yueyin.tv.core.model.Artist
import com.yueyin.tv.core.model.CoverVariant
import com.yueyin.tv.core.model.Playlist
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.InitialArtworkPlaceholder
import com.yueyin.tv.ui.appErrorMessage
import com.yueyin.tv.ui.formatDuration

/**
 * 手机端统一的点按反馈。
 *
 * 不用涟漪：手机壳没有套 material3 的 Theme，`LocalIndication` 取到的波纹样式与深色底、
 * 无阴影的卡片风格对不上（青紫波纹压在封面上很脏）。这里用「按下整体压暗」，
 * 与底部导航、迷你条同一套反馈语言，卡片多的地方也不会满屏波纹。
 */
@Composable
internal fun Modifier.phoneTap(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .graphicsLayer { alpha = if (pressed) 0.62f else 1f }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * 占位封面的配色轮转。
 *
 * 没有封面时全部用同一个青绿会很单调（一个列表里 20 张一样的方块），
 * 按标题散列取一个强调色，同一个条目每次进入都是同一个颜色，不会闪。
 */
/** 封面显示像素的分桶步长：同桶内复用同一档封面，避免缓存 key 无限膨胀。 */
private const val ARTWORK_BUCKET_STEP = 160

private fun accentFor(title: String): Color {
    val palette = listOf(FnColors.Teal, FnColors.Coral, FnColors.Amber, FnColors.Cyan, FnColors.Violet)
    if (title.isEmpty()) return palette[0]
    var hash = 0
    for (ch in title) hash = hash * 31 + ch.code
    return palette[Math.floorMod(hash, palette.size)]
}

/**
 * 封面。
 *
 * 变体默认下限是 [CoverVariant.Grid]（400px）：手机两列网格的卡片在 3x 屏上约 510px，
 * 400 略小但看不出差别；用 `Player`（800px）会让列表滚动时解码压力翻倍。
 * 兜底用 `Compact`（200px），先出小图再换大图，滚动时不会一片空白。
 *
 * 播放页封面在竖屏要铺到 ~1080px 宽，400px 放大后会明显糊，所以那里显式传
 * [CoverVariant.Player]（800px）并把兜底降到 Grid；黑胶盘心只占盘体一半，仍用 Grid 即可。
 *
 * 现在会实测这张封面最终渲染的像素并自动升档，车机大屏 / 高分屏不会再拿 400px 的图放大；
 * 档位离散（160px 分桶），缓存 key 不会膨胀，显示仍用 ContentScale.Crop 不做拉伸。
 */
@Composable
internal fun PhoneArtwork(
    coverId: String?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(PhoneRadius.md),
    accent: Color = accentFor(title),
    contentScale: ContentScale = ContentScale.Crop,
    variant: CoverVariant = CoverVariant.Grid,
    fallback: CoverVariant? = CoverVariant.Compact,
    alignment: Alignment = Alignment.Center,
) {
    val deps = LocalPhoneDeps.current
    val displayPx = remember { mutableStateOf(0) }
    val effectiveVariant = remember(variant, displayPx.value) {
        val px = displayPx.value
        if (px <= 0) variant
        else {
            val bucket = ARTWORK_BUCKET_STEP * ((px + ARTWORK_BUCKET_STEP - 1) / ARTWORK_BUCKET_STEP)
            CoverVariant.forDisplay(bucket, variant)
        }
    }
    val bitmap = rememberPhoneArtwork(deps, coverId, effectiveVariant, fallback)
    val artworkModifier = modifier
        .clip(shape)
        .onSizeChanged { size -> displayPx.value = maxOf(size.width, size.height) }
    if (bitmap != null) {
        // 复用同一个 ImageBitmap 包装：每次重组都新建会被 Compose 当成新图片，重新上传 GPU 纹理。
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = artworkModifier,
            contentScale = contentScale,
            alignment = alignment,
            filterQuality = FilterQuality.High,
        )
    } else {
        InitialArtworkPlaceholder(
            text = title,
            accent = accent,
            modifier = modifier.onSizeChanged { size -> displayPx.value = maxOf(size.width, size.height) },
            shape = shape,
        )
    }
}

@Composable
internal fun rememberPhoneArtwork(
    deps: AppUiDependencies,
    coverId: String?,
    variant: CoverVariant,
    fallback: CoverVariant?,
): Bitmap? {
    val initial = remember(deps, coverId, variant, fallback) {
        coverId?.let { id ->
            deps.artworkBitmapCache.peek(id, variant)
                ?: fallback?.let { deps.artworkBitmapCache.peek(id, it) }
        }
    }
    val bitmap by produceState(initial, deps, coverId, variant, fallback) {
        value = coverId?.let { id ->
            deps.artworkBitmapCache.getProgressively(
                coverId = id,
                variant = variant,
                fallbackVariant = fallback,
                onIntermediate = { value = it },
            )
        }
    }
    return if (coverId == null) null else bitmap
}

// ---------------------------------------------------------------------------
// 页面骨架
// ---------------------------------------------------------------------------

/** 一级页面标题（曲库、我的、设置）。与首页问候语同一左边距。 */
@Composable
internal fun PhonePageTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(PhoneSize.TopBar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = FnColors.Text,
            fontSize = PhoneType.Title,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/** 二级页面顶部：返回键 + 标题。标题单行省略，长度不可控的集合名不会把返回键挤走。 */
@Composable
internal fun PhoneBackHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(PhoneSize.TopBar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onBack 为 null 时不画返回箭头：平板双栏下详情页固定在右栏，
        // 左边列表一直看得见，此时给箭头反而暗示「有上一页」，点了却只是把右栏清空。
        if (onBack != null) {
            Box(
                Modifier
                    .size(LocalCarProfile.current.touchTarget)
                    .phoneTap(onBack),
                contentAlignment = Alignment.Center,
            ) {
                PhoneIcon(PhoneGlyph.Back, FnColors.Text, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(PhoneSpace.xs))
        } else {
            Spacer(Modifier.width(PhoneSpace.xs))
        }
        Text(
            title,
            color = FnColors.Text,
            fontSize = PhoneType.Section,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 分组标题行：左侧标题，右侧可选的动作文字（如「全部 ›」）。 */
@Composable
internal fun PhoneSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = PhoneSpace.xl, bottom = PhoneSpace.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = FnColors.Text,
            fontSize = PhoneType.Section,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (actionLabel != null && onAction != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .phoneTap(onAction)
                    .padding(start = PhoneSpace.sm, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionLabel, color = FnColors.Muted, fontSize = PhoneType.Caption, maxLines = 1)
                PhoneIcon(
                    PhoneGlyph.ChevronRight,
                    FnColors.Muted,
                    Modifier.size(13.dp),
                    strokeWidth = 1.6.dp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 卡片
// ---------------------------------------------------------------------------

/** 专辑卡：正方形封面 + 专辑名 + 歌手。两列网格与横滑影带共用。 */
@Composable
internal fun PhoneAlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.phoneTap(onClick)) {
        PhoneArtwork(
            coverId = album.coverId,
            title = album.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(PhoneSpace.sm))
        Text(
            album.name,
            color = FnColors.Text,
            fontSize = PhoneType.Caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            // 「歌手 · 年份」；没有年份就只显示歌手（与电视端 Album.cardSubtitle 同一条规则）
            album.cardSubtitle,
            color = FnColors.Muted,
            fontSize = PhoneType.Micro,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 歌单卡：封面 + 名称 + 曲目数。 */
@Composable
internal fun PhonePlaylistCard(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.phoneTap(onClick)) {
        PhoneArtwork(
            coverId = playlist.coverId,
            title = playlist.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(PhoneSpace.sm))
        Text(
            playlist.name,
            color = FnColors.Text,
            fontSize = PhoneType.Caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        // 曲目数缺失时留空而不写「—」：在线平台的榜单歌单经常不给曲目数，
        // 一列卡片里冒出几个破折号很像加载失败。留空仍有行高，两列卡片对齐不受影响。
        Text(
            trackCountLabel(playlist.trackCount).orEmpty(),
            color = FnColors.Muted,
            fontSize = PhoneType.Micro,
            maxLines = 1,
        )
    }
}

/**
 * 歌手行：圆形头像 + 名字 + 曲目/专辑数。
 *
 * 用行而不是网格：歌手名的长度差异远大于专辑名，两列网格里长名字会被压成一行省略号，
 * 而列表行能让名字占满剩余宽度——用户找歌手看的就是那个名字。
 */
@Composable
internal fun PhoneArtistRow(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(PhoneSize.RowItem)
            .phoneTap(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneArtwork(
            coverId = artist.coverId,
            title = artist.name,
            modifier = Modifier.size(PhoneSize.RowArt),
            shape = CircleShape,
        )
        Spacer(Modifier.width(PhoneSpace.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                artist.name,
                color = FnColors.Text,
                fontSize = PhoneType.Row,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                trackCountLabel(artist.trackCount),
                artist.albumCount?.let { "${phoneCount(it)} 张专辑" },
            ).joinToString(" · ")
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    color = FnColors.Muted,
                    fontSize = PhoneType.Micro,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 曲目行。
 *
 * [current] 为真的那行左侧画一个珊瑚色竖条并让标题变色——用户从详情页连着播放时，
 * 需要一眼看出「现在放到第几首」，只靠迷你条看不到序号。
 *
 * [favorite] 给 null 表示这行不显示收藏按钮（搜索结果的混合分区里空间紧张）；
 * 非 null 时在时长右侧画心形，[onToggleFavorite] 负责真正的切换。
 * 收藏状态用「可空布尔」而不是默认 false：false 是「明确没收藏」，null 是「此处不展示」，
 * 两者混成一谈会让不该出现按钮的地方冒出空心心形。
 */
@Composable
internal fun PhoneTrackRow(
    track: Track,
    index: Int,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(PhoneSize.RowItem)
            .phoneTap(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (current) {
                Box(Modifier.size(width = 3.dp, height = 14.dp).background(FnColors.Coral, RoundedCornerShape(2.dp)))
            } else {
                Text(
                    "${index + 1}",
                    color = FnColors.Muted.copy(alpha = 0.75f),
                    fontSize = PhoneType.Micro,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(PhoneSpace.sm))
        PhoneArtwork(
            coverId = track.coverId,
            title = track.title,
            modifier = Modifier.size(PhoneSize.RowArt),
            shape = RoundedCornerShape(PhoneRadius.sm),
        )
        Spacer(Modifier.width(PhoneSpace.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                track.title,
                color = if (current) FnColors.Coral else FnColors.Text,
                fontSize = PhoneType.Row,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = track.artistName.orEmpty()
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = FnColors.Muted,
                    fontSize = PhoneType.Micro,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        track.durationMs?.let { ms ->
            Spacer(Modifier.width(PhoneSpace.sm))
            Text(formatDuration(ms), color = FnColors.Muted, fontSize = PhoneType.Micro, maxLines = 1)
        }
        if (favorite != null && onToggleFavorite != null) {
            // 心形自己占 40dp 宽的独立热区，不做成整行右侧都算点收藏：
            // 行主体的热区是「播放这首」，两者重叠会导致想播歌却改了收藏。
            Box(
                Modifier.size(40.dp).phoneTap(onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                PhoneIcon(
                    glyph = PhoneGlyph.Heart,
                    tint = if (favorite) FnColors.Coral else FnColors.Muted.copy(alpha = 0.8f),
                    modifier = Modifier.size(19.dp),
                    filled = favorite,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 按钮
// ---------------------------------------------------------------------------

/**
 * 次级按钮：描边 + 图标 + 文字。
 *
 * 与 [PhonePrimaryButton] 配成一对用在详情页操作行（播放全部 / 随机播放）。
 * 不做材料按钮的填充色：一屏里两个实心色块会打架，主行动只能有一个。
 */
@Composable
internal fun PhoneOutlineButton(
    label: String,
    glyph: PhoneGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .height(PhoneSize.PrimaryButton)
            .background(if (enabled) FnColors.Surface else FnColors.SurfaceAlt, shape)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.14f else 0.06f), shape)
            .then(if (enabled) Modifier.phoneTap(onClick) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneIcon(
            glyph = glyph,
            tint = if (enabled) FnColors.Text else FnColors.Muted,
            modifier = Modifier.size(17.dp),
            strokeWidth = 1.9.dp,
        )
        Spacer(Modifier.width(PhoneSpace.sm))
        Text(
            label,
            color = if (enabled) FnColors.Text else FnColors.Muted,
            fontSize = PhoneType.Row,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 状态提示
// ---------------------------------------------------------------------------

/**
 * 分页底部提示。
 *
 * 三种状态分开画：加载中给一行「正在加载」；没有更多就什么都不画（滚动到底自然停住，
 * 加一行「没有更多了」反而像报错）；出错给一行可点的重试。
 */
@Composable
internal fun PhoneListFooter(
    loading: Boolean,
    hasMore: Boolean,
    error: AppError?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = PhoneSpace.lg),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> Text("正在加载…", color = FnColors.Muted, fontSize = PhoneType.Caption)
            error != null -> Text(
                "${appErrorMessage(error)} · 点击重试",
                color = FnColors.Coral,
                fontSize = PhoneType.Caption,
                modifier = Modifier.phoneTap(onRetry).padding(PhoneSpace.sm),
            )
            !hasMore -> Unit
        }
    }
}

/** 空态：一句话 + 一句补充说明，居中。 */
@Composable
internal fun PhoneEmptyState(
    title: String,
    caption: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(top = 72.dp, bottom = PhoneSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(FnColors.Surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhoneIcon(PhoneGlyph.Library, FnColors.Muted.copy(alpha = 0.8f), Modifier.size(24.dp))
        }
        Spacer(Modifier.height(PhoneSpace.lg))
        Text(title, color = FnColors.Text.copy(alpha = 0.9f), fontSize = PhoneType.Row, maxLines = 1)
        if (caption != null) {
            Spacer(Modifier.height(PhoneSpace.xs))
            Text(
                caption,
                color = FnColors.Muted,
                fontSize = PhoneType.Caption,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = PhoneSpace.xxl),
            )
        }
    }
}

/** 首屏加载中：只在「一条数据都还没有」时出现，避免列表滚动时忽隐忽现。 */
@Composable
internal fun PhoneInitialLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("正在加载…", color = FnColors.Muted, fontSize = PhoneType.Caption)
    }
}
