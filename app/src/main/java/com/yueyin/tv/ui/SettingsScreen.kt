package com.yueyin.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.yueyin.tv.AuthenticatedAppDependencies
import com.yueyin.tv.BuildConfig
import com.yueyin.tv.R
import com.yueyin.tv.core.model.PlayerStyle
import com.yueyin.tv.core.audiofx.FxPreset
import com.yueyin.tv.core.audiofx.FxSettings
import com.yueyin.tv.core.playback.AudioFxController
import com.yueyin.tv.core.model.preferences.CacheBudget
import com.yueyin.tv.core.model.preferences.CacheUsage
import com.yueyin.tv.core.model.preferences.QualityTier
import com.yueyin.tv.core.model.preferences.UiScale
import com.yueyin.tv.ui.designsystem.tierLabel
import com.yueyin.tv.ui.device.DeviceLayoutOverride
import com.yueyin.tv.ui.device.LayoutOverride
import com.yueyin.tv.ui.device.findHostActivity
import com.yueyin.tv.update.UpdateCheckSource
import com.yueyin.tv.update.UpdateUiState
import kotlinx.coroutines.launch
// 面板与控件统一走全局 FnColors（蓝黑系）。
// 原来这里有一套独立的绿灰配色（#151A19 / #292D31 / #2A302F / #303735），
// 色相与主界面完全不同，从首页点进设置会像换了一套皮肤——现已全部对齐。
private val SettingsPanel = FnColors.Surface
private val SettingsControl = FnColors.SurfaceAlt
private val SettingsDividerColor = Color.White.copy(alpha = 0.08f)
private val SettingsBorderColor = Color.White.copy(alpha = 0.10f)
/** 选中底色：主色 16% 压暗后的暗珊瑚，和主界面选中态同源。 */
private val SettingsSelected = FnColors.SelectedSoft
/** 未选中项的描边：中性白发丝线，不再用灰绿色。 */
private val SettingsControlBorder = Color.White.copy(alpha = 0.16f)

/** 设置页的五个侧边栏分组。 */
private enum class SettingsSection(val title: String, val subtitle: String) {
    PLAYER("播放界面", "播放页样式、缩放与歌词行为"),
    AUDIO_FX("音效", "音效模式（与播放条同步，选「无」即关闭）"),
    PLATFORMS("平台", "平台连接与在线音源管理"),
    CACHE("缓存", "图片与歌曲磁盘缓存"),
    ABOUT("关于", "版本、更新与反馈"),
}

/** 设置页统一间距，避免各分组视觉节奏不一致。 */
private val GAP_TITLE = 14.dp // 副标题到首个分组的间隔
private val GAP_GROUP = 18.dp // 分组之间的纵向间隔（与首页网格 18dp 行距一致）
private val GAP_LABEL = 10.dp // 分组标题到控件的间隔
private val GAP_DESC = 10.dp // 说明文字到控件的间隔
private val GAP_BLOCK = 14.dp // 说明块 / 操作按钮之间的间隔
private val GAP_ROW = 8.dp // 同行多控件 / 多行之间的间隔

@Composable
internal fun SettingsScreen(
    container: AuthenticatedAppDependencies,
    onAddPlatform: () -> Unit = {},
    onRelogin: () -> Unit = {},
    onEdit: (com.yueyin.tv.core.data.connect.ConnectProfile) -> Unit = {},
    onDelete: (com.yueyin.tv.core.data.connect.ConnectProfile) -> Unit = {},
    onSignOutFn: () -> Unit = {},
    onAddSource: () -> Unit = {},
    fnSessionActive: Boolean = false,
    // 顶部导航条「设置」标签按「下键」时用：焦点从导航条直接落进左栏第一项，
    // 而不是设置页一打开就自己抢走焦点（那样导航条上就永远到不了「搜索 / 播放胶囊」）。
    firstFocus: FocusRequester? = null,
    // 左栏第一项按「上键」时的出口：回到顶部导航条的「设置」标签。
    navTabFocus: FocusRequester? = null,
) {
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    val updateState by container.updateController.state.collectAsStateWithLifecycle()
    val lastCheckedAt by container.updateController.lastCheckedAt.collectAsStateWithLifecycle()
    val scope = LocalLibraryRetainedState.current.scope

    var selected by remember { mutableStateOf(SettingsSection.PLAYER) }
    // 左栏当前握着遥控器焦点的那一项。焦点移进右栏后它变回 null，
    // 此时当前分组用「选中浅底」继续标记，用户仍知道自己在哪个分组里。
    var focusedNav by remember { mutableStateOf<SettingsSection?>(null) }

    // 左栏导航项的焦点。「播放界面」这一项用外部传入的 requester（导航条下键的落点），
    // 其余自建；firstFocus 为空（独立预览等场景）时全部自建。
    val navRequesters = remember(firstFocus) {
        SettingsSection.entries.associateWith { section ->
            if (section == SettingsSection.PLAYER && firstFocus != null) firstFocus else FocusRequester()
        }
    }

    // 右栏任意分组「最左边那个控件」按左键的出口：回到左栏对应分组，而不是把左键吞掉。
    fun navFocusOf(section: SettingsSection): FocusRequester = navRequesters.getValue(section)

    // —— 播放界面分组 ——
    val coverStyleFocus = remember { FocusRequester() }
    val posterStyleFocus = remember { FocusRequester() }
    val lyricsStyleFocus = remember { FocusRequester() }
    val compactScaleFocus = remember { FocusRequester() }
    val standardScaleFocus = remember { FocusRequester() }
    val relaxedScaleFocus = remember { FocusRequester() }
    // 「界面形态」四档：自动 / 电视 / 车机 / 手机。电视盒子被固件信号误判成车机、手机时，
    // 就在这里手选回来——电视端设置页也要有这条，否则选错之后没有回头路。
    val layoutContext = LocalContext.current
    val layoutFocuses = remember { List(LayoutOverride.entries.size) { FocusRequester() } }
    var layoutOverride by remember { mutableStateOf(DeviceLayoutOverride.get(layoutContext)) }
    val onlineLyricsFocus = remember { FocusRequester() }
    val backgroundContinueFocus = remember { FocusRequester() }

    // —— 音效分组 ——
    // 直接订阅全局音效状态：播放条上的音效选择和这里共用同一份数据，改哪儿两边都同步。
    val fxSettings by AudioFxController.state.collectAsStateWithLifecycle()
    val fxPresetFocuses = remember { List(FxPreset.selectable.size) { FocusRequester() } }
    fun applyFx(next: FxSettings) {
        AudioFxController.update(next)
    }

    // —— 缓存分组 ——
    val cacheFocuses = remember { List(CacheBudget.entries.size) { FocusRequester() } }
    val clearCacheFocus = remember { FocusRequester() }
    val songCacheClearFocus = remember { FocusRequester() }

    // —— 关于分组 ——
    val updateFocus = remember { FocusRequester() }
    val remoteGuideFocus = remember { FocusRequester() }

    // —— 平台分组 ——
    // 平台列表在 PlatformManageContent 内部，首焦点（第一张平台卡）由它自己拿。
    // 这里给一个外部 requester 挂到同一张卡上，设置页才能用统一的「右键进右栏」把焦点送进去。
    val platformFirstFocus = remember { FocusRequester() }

    var usage by remember { mutableStateOf(CacheUsage(artworkBytes = 0, indexBytes = 0)) }
    var songCacheBytes by remember { mutableStateOf(0L) }
    var showRemoteGuide by remember { mutableStateOf(false) }

    suspend fun refreshUsage() {
        usage = container.musicRepository.cacheUsage()
    }

    suspend fun refreshSongCacheUsage() {
        songCacheBytes = container.playbackController.songCacheUsageBytes()
    }

    LaunchedEffect(Unit) {
        container.musicRepository.applyArtworkBudget()
        refreshUsage()
        refreshSongCacheUsage()
    }
    // 不再在进入页面时自动抢焦点：
    // 1) 焦点从导航条「设置」标签落进来时，必须留在导航条上——否则用户永远按不到右侧的
    //    「搜索 / 播放胶囊」；想进设置内容，按一下「下键」即可（见 firstFocus 的注释）。
    // 2) 经菜单键从播放页等无焦点页面呼出时，AuthenticatedApp 的保底逻辑会把焦点交给导航条。

    // 左栏上下切换分组；右键把焦点交给当前分组右栏的第一个控件。
    fun moveNav(delta: Int) {
        val all = SettingsSection.entries
        // 从「真正握着焦点的那一项」起步，而不是 selected。
        // 焦点刚进出右栏时两者可能短暂不同步，用 selected 算下标会多跳一格。
        val current = focusedNav ?: selected
        val idx = (all.indexOf(current) + delta + all.size) % all.size
        selected = all[idx]
        navRequesters.getValue(all[idx]).let { runCatching { it.requestFocus() } }
    }

    /**
     * 把焦点送进右栏。
     *
     * 不能同步调用 requestFocus：右栏是按 [selected] 组合的，刚按上/下换过分组的那一帧里
     * 控件还没挂到窗口上，`requestFocus()` 会抛 `FocusRequester is not initialized`，
     * 被包在 runCatching 里静默吞掉——表现出来就是「按右键没反应，得多按一次确认」。
     *
     * 所以这里等一帧（让重组落地）再请求，仍失败就再等一帧重试一次。
     * 右键与确认键共用这条路径，行为一致。
     */
    val enterScope = rememberCoroutineScope()
    fun firstFocusOf(section: SettingsSection): FocusRequester? = when (section) {
        SettingsSection.PLAYER -> coverStyleFocus
        SettingsSection.AUDIO_FX -> fxPresetFocuses.firstOrNull()
        // 平台分组内联了平台列表，首焦点在 PlatformManageContent 内部，用外部 requester 接进来。
        SettingsSection.PLATFORMS -> platformFirstFocus
        SettingsSection.CACHE -> cacheFocuses.firstOrNull()
        SettingsSection.ABOUT -> if (container.updateController.enabled) updateFocus else remoteGuideFocus
    }
    fun enterContent() {
        val section = selected
        val target = firstFocusOf(section) ?: return
        enterScope.launch {
            repeat(2) { attempt ->
                withFrameNanos { }
                if (runCatching { target.requestFocus() }.isSuccess) return@launch
                if (attempt == 1) return@launch
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                // 顶部留白 12dp：与首页（导航条下 8+4）、专辑页、我的页同一条基准线。
                // 原先是 28dp，比其它一级页多出 16dp，切到设置时内容会明显往下坠。
                .padding(horizontal = 48.dp)
                .padding(top = 12.dp, bottom = 28.dp),
        ) {
            Text("设置", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(GAP_BLOCK))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // —— 左侧导航栏 ——
                Column(
                    Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .background(SettingsPanel, RoundedCornerShape(FnRadius.md))
                        .border(1.dp, SettingsBorderColor, RoundedCornerShape(FnRadius.md))
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                ) {
                    SettingsSection.entries.forEach { section ->
                        val isSelected = section == selected
                        val navFocused = focusedNav == section
                        // 焦点实心 / 选中浅底 / 普通，三态统一（见 FnColors.FocusSolid 的注释）。
                        val navFill = when {
                            navFocused -> FnColors.FocusSolid
                            isSelected -> SettingsSelected
                            else -> Color.Transparent
                        }
                        val navStroke = when {
                            navFocused -> FnColors.FocusSolid
                            isSelected -> FnColors.Coral
                            else -> SettingsBorderColor
                        }
                        val navInk = when {
                            navFocused -> FnColors.FocusOnSolid
                            isSelected -> FnColors.Coral
                            else -> FnColors.Muted
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .background(navFill, RoundedCornerShape(FnRadius.md))
                                .border(
                                    if (navFocused || isSelected) 1.5.dp else 0.5.dp,
                                    navStroke,
                                    RoundedCornerShape(FnRadius.md),
                                )
                                // 焦点修饰符必须写在 clickable 之前：clickable 自己就是焦点节点，
                                // 再叠一层 focusable() 会造出第二个节点，焦点落在 clickable 那个上，
                                // 而 requestFocus / onFocusChanged / onPreviewKeyEvent 挂在另一个上——
                                // 表现就是「焦点看不见、上下列表不动、右键也不进右栏」。
                                .focusRequester(navRequesters.getValue(section))
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        // 守卫：焦点从右栏「跳」回左栏时，落点必须就是当前分组。
                                        // 落到别的项说明方向键目标失效后 Compose 退化成几何搜索，
                                        // 按垂直距离猜了一个（实测会落到「关于」「缓存」）。
                                        // 这时不切分组，直接把焦点送回右栏，避免一次误触就换了内容。
                                        // moveNav 是先改 selected 再请求焦点，所以正常上下移动不会命中这里。
                                        if (focusedNav == null && section != selected) {
                                            enterScope.launch {
                                                repeat(3) {
                                                    withFrameNanos { }
                                                    val back = firstFocusOf(selected) ?: return@launch
                                                    if (runCatching { back.requestFocus() }.isSuccess) return@launch
                                                }
                                            }
                                            return@onFocusChanged
                                        }
                                        focusedNav = section
                                        if (!isSelected) selected = section
                                    } else if (focusedNav == section) {
                                        focusedNav = null
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    // 遥控器按一下 = KeyDown + KeyUp 两个事件，两者的 event.key 相同。
                                    // 不做类型过滤的话 moveNav 会被执行两次，表现就是「按一下跳两格」。
                                    val known = event.key == Key.DirectionDown ||
                                        event.key == Key.DirectionUp ||
                                        event.key == Key.DirectionRight ||
                                        event.key == Key.DirectionLeft ||
                                        event.key == Key.Enter ||
                                        event.key == Key.DirectionCenter
                                    // 抬起事件只吞掉、不再执行动作（吞掉是为了别让下层 clickable 再补一刀）。
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent known
                                    when (event.key) {
                                        Key.DirectionDown -> { moveNav(+1); true }
                                        Key.DirectionUp -> {
                                            // 已经在第一项：上键回顶部导航条（别在五个分组里绕圈），
                                            // 保住「设置 → 右 → 搜索 / 播放胶囊」这条路。
                                            // 请求失败（导航条未挂载等）就退回原来的循环移动。
                                            val exited = section == SettingsSection.entries.first() &&
                                                navTabFocus != null &&
                                                runCatching { navTabFocus.requestFocus() }.isSuccess
                                            if (!exited) moveNav(-1)
                                            true
                                        }
                                        Key.DirectionRight -> { enterContent(); true }
                                        // 确定/OK 也能直接展开右侧内容（与右键同效），不用再按右键。
                                        Key.Enter, Key.DirectionCenter -> { enterContent(); true }
                                        // 左栏已经是最左边一列，左键就地吃掉。
                                        // 原来返回 false 会一路冒泡到顶层导航条，而导航是「焦点落到即切页」，
                                        // 表现就是「在设置页任意一项按左，整页被切回首页」。
                                        Key.DirectionLeft -> true
                                        else -> false
                                    }
                                }
                                .clickable { selected = section },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(
                                        if (navFocused || isSelected) navInk else navInk.copy(alpha = 0.4f),
                                        CircleShape,
                                    ),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                section.title,
                                fontSize = 15.sp,
                                lineHeight = 18.sp,
                                fontWeight = if (navFocused || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = navInk,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // —— 右侧内容区 ——
                // 边框/背景必须画在「视口」上，不能画在滚动内容上：
                // Modifier 按声明顺序逐层包裹，写在 verticalScroll 之后的 background / border
                // 拿到的尺寸是内容的实际高度（可能远高于可视区），内容一长，边框就伸出可视区，
                // 滚动时看起来是边框跟着内容走、还被裁掉一截。
                // 所以顺序改为：背景 + 边框（视口层，高度由 fillMaxHeight 固定）
                //   → clip（把滚动内容裁进圆角内，避免文字溢出到边框外）
                //   → verticalScroll（这一层起才滚动）
                //   → padding（内边距属于滚动内容，随内容一起走）。
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(SettingsPanel, RoundedCornerShape(FnRadius.md))
                        .border(1.dp, SettingsBorderColor, RoundedCornerShape(FnRadius.md))
                        .clip(RoundedCornerShape(FnRadius.md))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                ) {
                    Text(selected.title, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(selected.subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = FnColors.Muted)
                    Spacer(Modifier.height(GAP_TITLE))

                    when (selected) {
                        SettingsSection.PLAYER -> {
                            GroupLabel("样式")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SettingsChoiceButton(
                                    label = "CD 模式",
                                    selected = preferences.playerStyle == PlayerStyle.Cover,
                                    onClick = { container.appPreferences.setPlayerStyle(PlayerStyle.Cover) },
                                    modifier = Modifier
                                        .width(140.dp)
                                        .focusProperties { right = posterStyleFocus; left = navFocusOf(SettingsSection.PLAYER) }
                                        .focusRequester(coverStyleFocus),
                                )
                                SettingsChoiceButton(
                                    label = "大海报模式",
                                    selected = preferences.playerStyle == PlayerStyle.Poster,
                                    onClick = { container.appPreferences.setPlayerStyle(PlayerStyle.Poster) },
                                    modifier = Modifier
                                        .width(140.dp)
                                        .focusProperties { left = coverStyleFocus; right = lyricsStyleFocus }
                                        .focusRequester(posterStyleFocus),
                                )
                                SettingsChoiceButton(
                                    label = "迷你歌词",
                                    selected = preferences.playerStyle == PlayerStyle.Lyrics,
                                    onClick = { container.appPreferences.setPlayerStyle(PlayerStyle.Lyrics) },
                                    modifier = Modifier
                                        .width(140.dp)
                                        .focusProperties { left = posterStyleFocus; right = FocusRequester.Cancel }
                                        .focusRequester(lyricsStyleFocus),
                                )
                            }
                            Spacer(Modifier.height(GAP_GROUP))
                            GroupLabel("界面缩放")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SettingsChoiceButton(
                                    label = "紧凑",
                                    selected = preferences.uiScale == UiScale.Compact,
                                    onClick = { container.appPreferences.setUiScale(UiScale.Compact) },
                                    modifier = Modifier
                                        .width(140.dp)
                                        .focusProperties { right = standardScaleFocus; left = navFocusOf(SettingsSection.PLAYER) }
                                        .focusRequester(compactScaleFocus),
                                )
                                SettingsChoiceButton(
                                    label = "标准",
                                    selected = preferences.uiScale == UiScale.Standard,
                                    onClick = { container.appPreferences.setUiScale(UiScale.Standard) },
                                    modifier = Modifier
                                        .width(140.dp)
                                        .focusProperties { left = compactScaleFocus; right = relaxedScaleFocus }
                                        .focusRequester(standardScaleFocus),
                                )
                                SettingsChoiceButton(
                                    label = "宽松",
                                    selected = preferences.uiScale == UiScale.Relaxed,
                                    onClick = { container.appPreferences.setUiScale(UiScale.Relaxed) },
                                    modifier = Modifier
                                        .width(140.dp)
                                        .focusProperties { left = standardScaleFocus; right = FocusRequester.Cancel }
                                        .focusRequester(relaxedScaleFocus),
                                )
                            }
                            Spacer(Modifier.height(GAP_GROUP))
                            GroupLabel("画质档位")
                            Text(
                                "低配设备选「流畅」：关闭常驻动画与页面转场、用纯色替代渐变；焦点与播放功能不变。选「自动」按设备能力判定。",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = FnColors.Muted,
                            )
                            Spacer(Modifier.height(GAP_DESC))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                QualityTier.entries.forEach { tier ->
                                    SettingsChoiceButton(
                                        label = tier.tierLabel(),
                                        selected = preferences.qualityTier == tier,
                                        onClick = { container.appPreferences.setQualityTier(tier) },
                                        modifier = Modifier.width(120.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(GAP_GROUP))
                            GroupLabel("界面形态")
                            Text(
                                "电视盒子 / 车机被系统信号误判时可手动指定；选「自动」按系统上报判断，切换后界面会刷新。",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = FnColors.Muted,
                            )
                            Spacer(Modifier.height(GAP_DESC))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val options = LayoutOverride.entries
                                options.forEachIndexed { index, option ->
                                    SettingsChoiceButton(
                                        label = option.label,
                                        selected = layoutOverride == option,
                                        onClick = {
                                            DeviceLayoutOverride.set(layoutContext, option)
                                            layoutOverride = option
                                            layoutContext.findHostActivity()?.recreate()
                                        },
                                        modifier = Modifier
                                            .width(120.dp)
                                            .focusProperties {
                                                left = if (index == 0) {
                                                    navFocusOf(SettingsSection.PLAYER)
                                                } else {
                                                    layoutFocuses[index - 1]
                                                }
                                                right = if (index == options.lastIndex) {
                                                    FocusRequester.Cancel
                                                } else {
                                                    layoutFocuses[index + 1]
                                                }
                                            }
                                            .focusRequester(layoutFocuses[index]),
                                    )
                                }
                            }
                            Spacer(Modifier.height(GAP_GROUP))
                            GroupLabel("歌词")
                            SettingsCheckbox(
                                label = "在线歌词匹配",
                                selected = preferences.onlineLyricsMatchingEnabled,
                                onClick = {
                                    container.appPreferences.setOnlineLyricsMatchingEnabled(
                                        !preferences.onlineLyricsMatchingEnabled,
                                    )
                                },
                                modifier = Modifier
                                    .focusProperties { left = navFocusOf(SettingsSection.PLAYER); right = FocusRequester.Cancel }
                                    .focusRequester(onlineLyricsFocus),
                            )
                            Spacer(Modifier.height(GAP_GROUP))
                            GroupLabel("播放行为")
                            SettingsCheckbox(
                                label = "按返回键退出时在后台继续播放",
                                selected = preferences.backgroundContinueOnExit,
                                onClick = {
                                    container.appPreferences.setBackgroundContinueOnExit(
                                        !preferences.backgroundContinueOnExit,
                                    )
                                },
                                modifier = Modifier
                                    .focusProperties { left = navFocusOf(SettingsSection.PLAYER); right = FocusRequester.Cancel }
                                    .focusRequester(backgroundContinueFocus),
                            )
                        }

                        SettingsSection.AUDIO_FX -> {
                            // 不再有独立开关：选「无」= 关音效，选其它任意一档 = 开音效并套用该曲线。
                            // 和播放页进度条旁的音效选择器共用 AudioFxController 这一份状态。
                            GroupLabel("音效模式")
                            Text(
                                "选中任意一档即开启音效；选「无」即关闭音效。此处的选择与播放条上的音效按钮实时同步。",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = FnColors.Muted,
                            )
                            Spacer(Modifier.height(GAP_DESC))
                            val presets = FxPreset.selectable
                            val perRow = 5
                            presets.chunked(perRow).forEachIndexed { rowIndex, rowPresets ->
                                if (rowIndex > 0) Spacer(Modifier.height(GAP_ROW))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    rowPresets.forEachIndexed { columnIndex, preset ->
                                        val index = rowIndex * perRow + columnIndex
                                        SettingsChoiceButton(
                                            label = preset.displayName,
                                            selected = fxSettings.effectLabel == preset.displayName,
                                            onClick = { applyFx(fxSettings.selecting(preset)) },
                                            modifier = Modifier
                                                .width(96.dp)
                                                .focusProperties {
                                                    left = fxPresetFocuses.getOrNull(index - 1)
                                                        ?.takeIf { columnIndex > 0 } ?: navFocusOf(SettingsSection.AUDIO_FX)
                                                    right = fxPresetFocuses.getOrNull(index + 1)
                                                        ?.takeIf { columnIndex < rowPresets.lastIndex }
                                                        ?: FocusRequester.Cancel
                                                    down = fxPresetFocuses.getOrNull(index + perRow)
                                                        ?: FocusRequester.Cancel
                                                    up = fxPresetFocuses.getOrNull(index - perRow)
                                                        ?: FocusRequester.Cancel
                                                }
                                                .focusRequester(fxPresetFocuses[index]),
                                        )
                                    }
                                }
                            }
                        }

                        SettingsSection.PLATFORMS -> {
                            // 平台列表 + 在线音源直接铺在设置页内，不再进二级「管理平台与音源」页。
                            // 滚动由右侧内容区的 verticalScroll 统一处理，这里不再单独套滚动容器。
                            com.yueyin.tv.ui.connect.PlatformManageContent(
                                onAdd = onAddPlatform,
                                onRelogin = onRelogin,
                                onEdit = onEdit,
                                onDelete = onDelete,
                                onSignOutFn = onSignOutFn,
                                fnSessionActive = fnSessionActive,
                                onAddSource = onAddSource,
                                autoRequestFocus = false,
                                externalFirstFocus = platformFirstFocus,
                                // 左键出口：平台卡 / 新增平台 / 添加音源都回到左栏「平台」。
                                navExitFocus = navFocusOf(SettingsSection.PLATFORMS),
                            )
                        }

                        SettingsSection.CACHE -> {
                            GroupLabel("图片磁盘缓存上限")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CacheBudget.entries.forEachIndexed { index, budget ->
                                    SettingsChoiceButton(
                                        label = "${budget.megabytes} MB",
                                        selected = preferences.cacheBudget == budget,
                                        onClick = {
                                            scope.launch {
                                                container.appPreferences.setCacheBudget(budget)
                                                container.musicRepository.applyArtworkBudget()
                                                refreshUsage()
                                            }
                                        },
                                        modifier = Modifier
                                            .width(104.dp)
                                            .focusProperties {
                                                left = cacheFocuses.getOrNull(index - 1) ?: navFocusOf(SettingsSection.CACHE)
                                                right = cacheFocuses.getOrNull(index + 1) ?: clearCacheFocus
                                            }
                                            .focusRequester(cacheFocuses[index]),
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "当前 ${formatBytes(usage.totalBytes)}（图片 ${formatBytes(usage.artworkBytes)} / 资料 ${formatBytes(usage.indexBytes)}）",
                                color = FnColors.Muted,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                            )
                            Spacer(Modifier.height(GAP_BLOCK))
                            SettingsActionButton(
                                label = "清除图片和资料缓存",
                                onClick = {
                                    scope.launch {
                                        container.authenticatedActions.clearAllEvictableCaches()
                                        refreshUsage()
                                    }
                                },
                                modifier = Modifier
                                    .width(190.dp)
                                    .focusProperties {
                                        // 左键回到左栏本分组。原先指向 cacheFocuses.last()，按左会跳到最右边的 MB 档位，
                                        // 看起来就是「回不到缓存」；不写的话会走几何搜索，直接落到下面的「关于」。
                                        left = navFocusOf(SettingsSection.CACHE)
                                        right = FocusRequester.Cancel
                                        down = songCacheClearFocus
                                    }
                                    .focusRequester(clearCacheFocus),
                            )
                            Spacer(Modifier.height(GAP_GROUP))
                            GroupLabel("歌曲磁盘缓存")
                            Text(
                                "当前 ${formatBytes(songCacheBytes)} · 已播放歌曲的本地缓存",
                                color = FnColors.Muted,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                            )
                            Spacer(Modifier.height(GAP_DESC))
                            SettingsActionButton(
                                label = "清除歌曲缓存",
                                onClick = {
                                    container.playbackController.clearSongCache()
                                    scope.launch { refreshSongCacheUsage() }
                                },
                                modifier = Modifier
                                    .width(190.dp)
                                    .focusProperties {
                                        // 同上：不显式指定左键，几何搜索会落到左栏最靠近的「关于」。
                                        left = navFocusOf(SettingsSection.CACHE)
                                        right = FocusRequester.Cancel
                                        up = clearCacheFocus
                                    }
                                    .focusRequester(songCacheClearFocus),
                            )
                        }

                        SettingsSection.ABOUT -> {
                            AboutSection(
                                updateState = updateState,
                                updateEnabled = container.updateController.enabled,
                                lastCheckedAt = lastCheckedAt,
                                updateFocus = updateFocus,
                                // 更新按钮在卡片最上行：左键/上键都回左栏「关于」。
                                // 原先 up 与 left 都是 Cancel，按上去、按左去都没反应，焦点被锁死在卡片里。
                                updateUpFocus = navFocusOf(SettingsSection.ABOUT),
                                updateDownFocus = remoteGuideFocus,
                                updateLeftFocus = navFocusOf(SettingsSection.ABOUT),
                                onUpdateAction = {
                                    if (updateState is UpdateUiState.Available) {
                                        container.updateController.startDownload()
                                    } else {
                                        container.updateController.checkManually()
                                    }
                                },
                            )
                            Spacer(Modifier.height(GAP_BLOCK))
                            SettingsActionButton(
                                label = "查看按键说明",
                                onClick = { showRemoteGuide = true },
                                modifier = Modifier
                                    .width(150.dp)
                                    .focusProperties {
                                        up = if (container.updateController.enabled) updateFocus else FocusRequester.Cancel
                                        // 显式指定，别依赖几何搜索落到哪一项。
                                        left = navFocusOf(SettingsSection.ABOUT)
                                        right = FocusRequester.Cancel
                                    }
                                    .focusRequester(remoteGuideFocus),
                            )
                        }
                    }
                }
            }
        }
        if (showRemoteGuide) {
            RemoteControlGuide { showRemoteGuide = false }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 16.sp, color = FnColors.Muted, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(GAP_ROW))
}

@Composable
private fun SettingsRow(
    label: String,
    height: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = FnColors.Muted, fontSize = 12.sp, modifier = Modifier.width(144.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(SettingsDividerColor))
}


@Composable
private fun SettingsChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(FnRadius.lg)
    Button(
        onClick = onClick,
        modifier = modifier.focusBringIntoView().height(34.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) SettingsSelected else SettingsControl,
            contentColor = if (selected) FnColors.Coral else FnColors.Text,
            // 焦点一律珊瑚实心：和选中态（浅底+橙字）一眼可分，全页统一。
            focusedContainerColor = FnColors.FocusSolid,
            focusedContentColor = FnColors.FocusOnSolid,
            pressedContainerColor = FnColors.FocusSolid,
            pressedContentColor = FnColors.FocusOnSolid,
        ),
        border = ButtonDefaults.border(
            border = Border(
                BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) FnColors.Coral else SettingsControlBorder),
                shape = shape,
            ),
            // 实心焦点上用一道深色细边勾出轮廓，避免整块糊成一片。
            focusedBorder = Border(
                BorderStroke(1.5.dp, FnColors.FocusOnSolid.copy(alpha = 0.35f)),
                shape = shape,
            ),
            pressedBorder = Border(BorderStroke(1.5.dp, FnColors.FocusOnSolid.copy(alpha = 0.35f)), shape = shape),
        ),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
internal fun SettingsActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: String? = null,
    emphasis: Boolean = false,
) {
    val shape = RoundedCornerShape(FnRadius.lg)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.focusBringIntoView().height(34.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (emphasis) FnColors.Coral else SettingsControl,
            contentColor = if (emphasis) FnColors.FocusOnSolid else FnColors.Text,
            focusedContainerColor = FnColors.FocusSolid,
            focusedContentColor = FnColors.FocusOnSolid,
            pressedContainerColor = FnColors.FocusSolid,
            pressedContentColor = FnColors.FocusOnSolid,
            disabledContainerColor = if (emphasis) FnColors.Coral.copy(alpha = 0.34f) else FnColors.SurfaceAlt.copy(alpha = 0.55f),
            disabledContentColor = FnColors.Muted,
        ),
        border = ButtonDefaults.border(
            border = Border(
                BorderStroke(if (emphasis) 1.5.dp else 0.5.dp, if (emphasis) FnColors.Coral else SettingsControlBorder),
                shape = shape,
            ),
            focusedBorder = Border(
                BorderStroke(1.5.dp, FnColors.FocusOnSolid.copy(alpha = 0.35f)),
                shape = shape,
            ),
            pressedBorder = Border(BorderStroke(1.5.dp, FnColors.FocusOnSolid.copy(alpha = 0.35f)), shape = shape),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) Text(leading, fontSize = 16.sp, lineHeight = 16.sp)
                Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun SettingsCheckbox(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // 与按钮同一套语言：焦点 = 珊瑚实心；选中但焦点不在 = 珊瑚浅底 + 珊瑚边。
    val ink = when {
        focused -> FnColors.FocusOnSolid
        selected -> FnColors.Coral
        else -> FnColors.Text
    }
    val boxFill = when {
        selected && focused -> FnColors.FocusOnSolid
        selected -> FnColors.Coral
        else -> Color.Transparent
    }
    val boxStroke = when {
        focused -> FnColors.FocusOnSolid
        selected -> FnColors.Coral
        else -> FnColors.Muted
    }
    Row(
        modifier = modifier
            .focusBringIntoView()
            .width(320.dp)
            .height(34.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(
                when {
                    focused -> FnColors.FocusSolid
                    selected -> SettingsSelected
                    else -> SettingsControl
                },
                RoundedCornerShape(FnRadius.sm),
            )
            .border(
                if (focused || selected) 1.5.dp else 0.5.dp,
                when {
                    focused -> FnColors.FocusOnSolid.copy(alpha = 0.35f)
                    selected -> FnColors.Coral
                    else -> SettingsControlBorder
                },
                RoundedCornerShape(FnRadius.sm),
            )
            .focusable()
            .onPreviewKeyEvent { event ->
                // 遥控器确定键（DPAD_CENTER / Enter）也能切换复选框。
                if (event.key == Key.Enter || event.key == Key.DirectionCenter) {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                } else {
                    false
                }
            }
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(boxFill, RoundedCornerShape(2.dp))
                .border(1.5.dp, boxStroke, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text(
                    "✓",
                    color = if (focused) FnColors.FocusSolid else FnColors.FocusOnSolid,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            label,
            color = ink,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f KB".format(bytes / 1024.0)
}
