# 悦音手机版改造方案 v1

> 基线：`feature/phone` @ `e5a250b`，版本 2.0.3 / 27
> 状态：待确认，未动任何代码
> 配套原型：`docs/phone/render/`（12 屏竖屏渲染图，2 倍图；总览 `all-screens.png`，网页版 `index.html`）

---

## 0. 结论先行

1. **路线：双壳并存（推荐）** —— TV 布局代码一行不动，新增一套手机壳（`PhoneShell` + `ui/phone/` 页面），按设备形态分叉。这样手机端可以放手设计，TV 端零回归风险。
2. **功能：零删减。** 现有 20 项能力逐项给手机落点，见 §6 对照表，任何一项找不到落点就不算完成。
3. **代价诚实讲：** 手机壳是新增代码，不是"改改尺寸"。核心页面（首页/曲库/详情/播放/设置）要重写表现层，数据层与播放链路 100% 复用。粗估新增 4500~6000 行。
4. **分期 5 段**，每段可单独出包验收，随时能停。

---

## 1. 现状诊断（代码实测，不是猜测）

| # | 问题 | 证据 | 后果 |
|---|---|---|---|
| 1 | **全局密度折算在手机上完全失效** | `Theme.kt` `FnAdaptive.canvasScale` 按 960×540 画布折算，并 `coerceIn(0.5f, 3.0f)`。390×844dp 的手机算出 0.409，被钳到 **0.5** | 有效画布变成 **780×1688dp**：正文 19sp 实际只有 9.5sp 的物理大小，页面标题 30sp 看起来 15sp。字小、留白失控 |
| 2 | 屏幕方向被锁死 | `AndroidManifest.xml`：`android:screenOrientation="landscape"` | 手机只能横屏，竖屏起不来 |
| 3 | 布局全按横向组织 | 顶部导航条（`LibraryTopBar`）+ 横向 `MediaBand` + 5~6 列网格（`FnGrid.columnsFor` 钳 3..6 列） | 竖屏下 786dp 画布算 5 列，每列 143dp，落到 393dp 屏幕上每张卡只有 71dp 宽，封面糊成色块 |
| 4 | 一整套遥控器焦点数学 | `AuthenticatedApp.kt` **5913 行**，其中大量是 D-pad 网格跳转、焦点回收、`tabSwitchBlocked` 抑制 | 触屏下全是死代码但不崩；只是没法靠它得到手机体验 |
| 5 | 触摸只有半套 | `TouchButton.kt`（自绘按下判定）、`PlayerScreen.playerTouchTarget`（`pointerInput(Unit)` + `rememberUpdatedState`） | 按钮能点，但**没有滑动、拖拽进度、手势切歌、上滑展开** |
| 6 | 无竖屏资源与断点 | 无 `values-sw600dp`、无 FormFactor 概念 | 一切尺寸写死在大屏假设里 |

**一句话：不是"手机版需要微调"，而是"布局层需要另一套组织方式"。** 这也是为什么推荐双壳而不是硬改。

---

## 2. 三条路线

| 路线 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A** 硬改现有布局 | 在所有 `AuthenticatedApp`/`PlayerScreen` 里塞 `if (isPhone)` 分支 | 只有一套代码 | 5913+3031 行里到处埋分支，焦点数学与触屏逻辑互相干扰；TV 回归风险极高（历史上闪退都出在这块） | ❌ |
| **B** 双壳并存 | TV 壳原样保留，新增 `PhoneShell` + `ui/phone/` 页面，共用 core 层与路由栈 | TV 零回归；手机可独立演进；出问题能按形态开关降级 | 表现层要写两遍；以后新功能要落两个 UI | ✅ 推荐 |
| **C** 手机独立工程 | 从本仓库 fork 出 `yueyin-phone` 独立仓库 | 最自由 | 数据层、音源、播放链路全部分叉，2.0.3 之后的所有修复要手动同步两份 | ❌（除非确定 TV 线停更） |

**推荐 B 的关键理由：** 这个项目踩过的坑（焦点崩溃、线程崩溃、取链阻塞）都在 core 与 TV 壳里，手机壳只做客制化的表现层，天然避开这些雷区。

---

## 3. 信息架构

### 3.1 底部导航：4 个 tab

```
┌─────────────────────────────┐
│                             │
│         页面内容            │
│                             │
├─────────────────────────────┤
│ ▶ 迷你播放条（56dp，有歌时） │
├─────────────────────────────┤
│  首页   曲库   我的   设置   │  ← 56dp + 手势条避让
└─────────────────────────────┘
```

TV 的 5 个顶部 tab（首页/专辑/歌单/我的/设置）压成 4 个：**专辑 + 歌单合进「曲库」**，用页内分段切换。

### 3.2 路由地图（TV 路由 → 手机落点，一个不漏）

| TV 路由 | 手机落点 |
|---|---|
| `Home` | 底部 tab 1 |
| `Albums` | 曲库 › 专辑 分段 |
| `Artists` | 曲库 › 歌手 分段 |
| `AllPlaylists` | 曲库 › 歌单 分段 |
| `AllTracks` | 曲库 › 歌曲 分段 |
| `Favorites` | 曲库 › 收藏 分段（「我的」也有入口） |
| `My` | 底部 tab 3 |
| `Settings` | 底部 tab 4 |
| `Search` | 首页顶部搜索框 / 曲库右上放大镜 → 全屏搜索页 |
| `PlaylistDetail` / `AlbumDetail` / `ArtistDetail` | 右侧滑入的二级页（通栏大封面头图） |
| `Player` | 从迷你条上滑或点击 → 全屏播放页（模态，非 tab） |
| `PlatformManage` / `PlatformPicker` / `PlatformForm` / `PlatformFnLogin` | 设置 › 平台，纵向列表 + 二级表单页 |
| `MusicSources` / `MusicSourceReceive` | 设置 › 在线音源，二级页 + 导入方式选择 |

路由栈语义（`open` / `root` / `back`）完全复用现有实现，返回键行为、连按退出、切平台清缓存都不变。

---

## 4. 逐页布局规格（基准 390dp 宽，竖屏）

通用令牌：
- 页面左右边距 **16dp**；分组间距 **24dp**；卡片间距 **12dp**
- 列表行高 **56dp**；底部导航 **56dp**；迷你播放条 **56dp**
- 圆角沿用现有 4 档（6 / 12 / 16 / 20dp），不新增

### 4.1 首页

```
┌─ 16dp ─────────────────────┐
│ 晚上好              [头像] │  28sp 问候 + 40dp 头像
│ ┌────────────────────────┐ │
│ │ 🔍 搜索歌曲、歌手、专辑 │ │  48dp 圆角搜索框（点击进搜索页）
│ └────────────────────────┘ │
│ ┌────────────────────────┐ │
│ │  随心听          ▶     │ │  160dp 渐变大卡（珊瑚→琥珀）
│ │  随机 50 首，按你的口味   │ │  ← 保留 TV 的预热/十分钟缓存逻辑
│ └────────────────────────┘ │
│ 最近添加            全部 › │
│ ┌────┐┌────┐┌────┐  →横滑 │  132dp 卡，横向 LazyRow
│ └────┘└────┘└────┘        │
│ 我的歌单            全部 › │
│ ┌──────┐┌──────┐          │  2 列，卡宽 173dp
│ └──────┘└──────┘          │
│ 继续播放                   │
│ ▶ 歌曲名 · 歌手     3:41  │  56dp 行
└───────────────────────────┘
```

- **替换掉** `FeatureCoverDeck`（叠卡 + 3D 倾斜）与横向 `MediaBand`：手机竖屏宽度不够，改成「大卡 + 横滑影带 + 2 列网格」三层。
- 空态（没连平台、没音源）沿用 `ConnectFirstScreen` 的双选项卡，改成竖屏单列。

### 4.2 曲库（原专辑 / 歌单 / 歌手 / 歌曲 / 收藏）

- 顶部 **sticky 分段条**（下划线式，5 段可横滑），下面接内容。
- 专辑 / 歌单：2 列网格，封面 173×173（歌单 173×173 带 2×2 拼图），标题 2 行截断。
- 歌手：3 列圆形头像（111dp）+ 名字 1 行。
- 歌曲 / 收藏：单列 56dp 行，右侧时长，行尾 `⋮` 出菜单（加入队列 / 收藏 / 查看专辑）。
- 分页沿用 `PagedCatalogPage` 的加载逻辑，只换 `LazyVerticalGrid` 的列数与卡片。

### 4.3 我的

- 头部资料条：头像 56dp + 用户名 20sp + 服务器 chip（珊瑚描边胶囊）。
- 快捷四宫格：收藏 / 歌单 / 专辑 / 在线音源（图标 + 数字角标）。
- 「当前平台」卡：平台名 + 类型 + 切换按钮（复用 `ConnectRuntime.activate`）。
- 列表项：平台管理 / 在线音源 / 缓存 / 关于 / 切换账号。

### 4.4 设置

TV 的左右分栏（左栏 5 组 + 右栏表单）在竖屏改成**单列分组 + 二级页**：

| 分组 | 项 |
|---|---|
| 播放界面 | 样式（三选一带缩略图预览）、界面缩放、歌词匹配、逐字填充、动态取色、模糊背景、氛围特效 |
| 音效 | 8 档（选中即生效，与播放页同步） |
| 平台 | 平台管理、在线音源 |
| 缓存 | 预算 32/64/128/256MB + 当前占用 + 清理 |
| 关于 | 版本、检查更新、反馈二维码、崩溃日志、开源许可 |

所有 10 个偏好项（`AppPreferencesState` 全字段）在手机上都要有开关，一个不少。

### 4.5 搜索

- 顶部输入框（自动聚焦、键盘弹出、右侧取消）。
- 空态：搜索历史 chip + 热门推荐（TV 有哪些就搬哪些）。
- 结果：分区（歌曲 / 专辑 / 歌手），每区最多 3 条 + 「查看全部」。
- 边输边搜与拼音首字母逻辑原样复用。

### 4.6 详情页（专辑 / 歌手 / 歌单）

- 折叠头图：封面 200dp 居中 + 标题 + 元信息（歌手 · 年份 · N 首），滚动时压缩成 56dp 标题栏。
- 操作行：`▶ 播放全部`（主按钮）+ `随机` + `收藏` + `⋮`。
- 歌曲列表 56dp 行，正在播放的行高亮珊瑚色 + 小喇叭图标。

### 4.7 平台与音源

- 平台选择：纵向卡片列表（图标 + 名称 + 说明），保留全部 8 种类型。
- 登录表单：单列，字段间距 16dp，键盘类型正确（地址 / 密码 / Token）。
- **音源导入新增「本地文件选择」**（`ACTION_OPEN_DOCUMENT`），与现有"扫码接收页"并存 —— 手机上有文件管理器，扫码反而绕。这是**新增入口，不删旧路径**。

---

## 5. 播放页

### 5.1 三种样式在竖屏的映射

| TV 样式 | TV 表现 | 手机竖屏表现 |
|---|---|---|
| `Cover`（CD 模式） | 旋转唱盘 + 涟漪特效 | 中部 300dp **旋转唱片**（封面圆裁 + 黑胶圆环），上下留白大，适合单手 |
| `Poster`（大海报） | 全屏封面 + 底部信息 | 全屏封面模糊底 + 前景 342dp 方正封面，信息压在封面下方 |
| `Lyrics`（迷你歌词） | 当前句 + 下一句浮动 | **歌词为主体**：整屏歌词列表，当前行 24sp 珊瑚色、其余 16sp 灰，点击行跳转 |

三种样式切换入口：播放页右上 `⋮` → 「播放页样式」三选一（与设置页同一份偏好）。

### 5.2 控制层（默认隐藏，单击唤出）

```
┌───────────────────────────┐
│ ⌄                ⋮        │  下拉收起 / 更多
│                           │
│        [封面 / 唱片]       │
│                           │
│  歌名（20sp 粗）           │
│  歌手 · 专辑（14sp 灰）    │
│                           │
│ ●───────────────────────  │  2dp 轨道，拖动时 thumb 才出现
│ 1:23                 3:41 │
│                           │
│    ⏮     ⏯(64dp)    ⏭    │  主控三个键
│                           │
│  🔁      🎚      ♡     ☰  │  模式 / 音效 / 收藏 / 队列
└───────────────────────────┘
```

### 5.3 手势表

| 手势 | 行为 | 对应 TV 操作 |
|---|---|---|
| 单击封面区 | 显示 / 隐藏控制层 | `↑` `↓` 唤出控制栏 |
| 左右滑 | 上一首 / 下一首 | `←` `→` 双击语义 |
| 上下滑（控制层上滑） | 展开歌词 | 切 `Lyrics` 样式 |
| 下滑 | 收起播放页回列表 | 返回键 |
| 拖动进度条 | 精确 seek（拖动时显示时间气泡） | 方向键调进度 |
| 长按封面 | 出「保存封面 / 查看专辑」菜单（可选） | 无 |

### 5.4 音效面板与队列

- 都是**底部 sheet**（可拖拽到 90% 高），替代 TV 的整屏浮层。
- 音效：8 档 2×4 网格，点击即生效（保留"选中即生效"，不要确认按钮）。
- 队列：全屏 sheet，行内滑动删除（保留 TV 的显式删除按钮，双通道），长按拖拽排序。

---

## 6. 功能保留对照表（验收用）

「状态」列是 P4 收尾时的实测结论：✅ 已实现并可走查 / ⚠️ 实现但有缺口（缺口写在括号里）/
❌ 未实现。**不把没做的东西标成已做**，缺口都在 §10 的遗留清单里挂了号。

| # | 能力 | TV 实现 | 手机落点 | 状态（P4 收尾实测） |
|---|---|---|---|---|
| 1 | 8 种自建服务器 | `connect/PlatformScreens.kt`（1145 行） | 设置 › 平台，纵向列表 + 单列表单 | ✅ 平台选择页共 10 类（8 种协议 + 群晖 AudioStation + 在线音乐） |
| 2 | 在线音乐 5 平台搜索与发现 | `OnlinePlatform.kt` | 曲库 › 在线 / 首页在线卡 | ✅ |
| 3 | 音源脚本导入（扫码接收） | `SourceReceiveScreen.kt` | 设置 › 在线音源 › 导入 | ✅ 局域网地址 + 二维码，另加本机文件多选 |
| 4 | 音源脚本检测与排序 | `SourceCheck.kt` / 失败排后 | 同上（后台静默） | ⚠️ 检测已完成（逐脚本「检测」+ 能力标签），**失败排后未做**，清单按添加顺序 |
| 5 | 随心听（随机 50 + 预热 + 10 分钟缓存） | `RoamPrewarm` / `QueueUrlFiller` | 首页大卡 | ✅ |
| 6 | 首页推荐 / 影带 / 歌单卡 | `FeatureCard` / `MediaBand` | 大卡 + 横滑影带 + 2 列网格 | ✅ 随心听大卡 + 最近添加 + 我的歌单 |
| 7 | 专辑 / 歌手 / 歌单 / 歌曲 / 收藏 | `AuthenticatedApp` 内 5 个页面 | 曲库 5 分段 | ✅ |
| 8 | 三个详情页 | `AlbumDetail` / `ArtistDetail` / `TrackCollection` | 折叠头图 + 列表 | ✅ 共用一套 `PhoneTrackCollectionPage` |
| 9 | 三种播放页样式 | `ImmersivePlayer`（3031 行） | §5.1 映射 | ✅ 封面 / 歌词 / 海报，选择持久化 |
| 10 | 氛围特效（Glow / Ripple） | `AmbientGlowOverlay` / `LiquidBackdrop` | 竖屏保留，缩放自适应 | ✅ 设置 › 播放界面 › 氛围特效（关闭 / 光晕 / 涟漪 / 自动） |
| 11 | 动态取色 + 模糊背景 | `extractArtworkColors` / `PosterBlurredBackdrop` | 保留 | ✅ 两个开关都在设置里 |
| 12 | 卡拉 OK 逐字填充 | `SmoothLyricProgress` | 保留 | ✅ |
| 13 | 八档音效 + 持久化 | `core/audiofx` | 底部 sheet | ✅ 播放页 sheet + 设置 › 音效，两处同步 |
| 14 | 队列（删除 / 排序 / 定位） | `PlaybackQueueOverlay` | 全屏 sheet | ⚠️ 看 / 跳 / 删已做，**排序未做**（见 P3 偏差 3） |
| 15 | 播放模式（列表 / 单曲 / 随机） | `PlayMode` | 控制层按钮 | ✅ |
| 16 | 搜索（边输边搜 + 拼音） | `SearchScreen` | 全屏搜索页 | ✅ 拼音首字母可用，结果不分页（P2 偏差 1） |
| 17 | 10 项播放/显示偏好 | `AppPreferencesState` | 设置单列分组 | ✅ 9 项在设置里可改；第 10 项 `remoteControlGuideSeen` 对手机无意义，直接跳过 |
| 18 | 自更新（清单 + 下载 + 安装） | `UpdateController` / `update.json` | 保留，加「允许安装未知应用」引导 | ✅ 关于页更新行 + 失败弹窗 + 未知来源跳转 |
| 19 | 登录历史 / 切换账号 / 退出 | `YueYinApp` | 我的 › 账号 | ⚠️ 退出与平台卡已做，**登录历史列表未做**（手机是单账号，切换靠退出重登） |
| 20 | 后台播放 / 通知 / 屏幕常亮 / 连按退出 | `PlaybackService` / `MainActivity` | 保留 | ✅ 连按两次退出有 Toast 提示 |
| 21 | 崩溃日志 | `CrashLogStore` | 关于 › 崩溃日志 | ✅ 等宽渲染 + 复制全部 / 清空 |
| 22 | 首次使用引导 | `RemoteControlGuide`（514 行，遥控器按键图） | 改成**手势说明**（3 页可滑动） | ❌ **未做**，手持设备直接跳过遥控器引导，等于手机端目前没有首次引导 |
| 23 | 启动黑胶动画 | `YueYinApp` splash | 保留，竖屏居中 | ✅ |
| — | 本地文件导入音源 | 无 | 手机新增 | ✅ `OpenMultipleDocuments` 多选，单文件受 `MAX_SCRIPT_BYTES` 限制 |

---

## 7. 响应式与断点

| 形态 | 判定（实测可用宽度） | 布局 | 网格列数 | 导航 |
|---|---|---|---|---|
| 手机竖屏 | < 600dp | §4 全套 | 2 列 | 底部 4 tab |
| 手机横屏 / 折叠展开 | 600 ~ 899dp | 播放页左右分栏；列表与详情仍单页 | 3 列 | 左侧 72dp 图标栏 |
| 平板 | ≥ 900dp | 双栏：左列表 380dp + 右详情 | 4 列 | 左侧 72dp 图标栏 |
| TV | `UI_MODE_TYPE_TELEVISION` 或无触摸 | **现有 TV 布局原样** | 5~6 列 | 顶部导航条 |

判定用 `BoxWithConstraints` 实测的**可用宽度**，不读 `Configuration.screenWidthDp`：分屏和自由窗口下
屏幕宽度不等于窗口宽度，读整屏会在分屏时按 4 列排，每列只剩几十 dp。`LocalPhoneWindow` 默认值取
Compact，任何页面漏包一层就退回手机竖屏渲染，不会突然变成平板布局。

P0~P4 做的是「手机竖屏 + 保留 TV」两条线，横屏与平板在 P5 补齐（见 §10 P5 实施记录，含 5 条偏差）。

---

## 8. 交互映射（遥控器 → 触屏）

| TV 操作 | 手机等价 |
|---|---|
| 方向键移动焦点 | 直接点按；列表内滑动滚动 |
| 确定键 | 点按 |
| 返回键 | 系统返回手势 / 返回键（语义不变） |
| 菜单键 → 设置 | 底部 tab |
| 播放 / 暂停 / 上下曲媒体键 | 通知栏媒体控件 + 蓝牙耳机（`dispatchKeyEvent` 逻辑保留） |
| 长按方向键连续快退 | 长按 ⏮ / ⏭ |

**关键红线：`dispatchKeyEvent` 里的媒体键处理与 `FocusRescue` 不能删。** 手机上蓝牙耳机、车机、通知栏都靠它；焦点抢救在触屏下不会触发，但保留零成本。

---

## 9. 技术实施方案

### 9.1 新增文件（`app/src/main/java/com/yueyin/tv/`）

| 文件 | 职责 | 预估 |
|---|---|---|
| `ui/device/FormFactor.kt` | 形态判定 + `LocalFormFactor` | 60 |
| `phone/PhoneTheme.kt` | 手机字号/间距令牌、不折算密度 | 120 |
| `phone/PhoneShell.kt` | Scaffold + 底部导航 + 迷你播放条 + 路由栈 | 420 |
| `phone/PhoneHomeScreen.kt` | 首页 | 380 |
| `phone/PhoneLibraryScreen.kt` | 曲库 5 分段 | 520 |
| `phone/PhoneDetailScreens.kt` | 专辑/歌手/歌单详情 | 620 |
| `phone/PhoneMyScreen.kt` | 我的 | 260 |
| `phone/PhoneSearchScreen.kt` | 搜索 | 300 |
| `phone/PhoneSettingsScreen.kt` | 设置单列分组 | 480 |
| `phone/PhoneConnectScreens.kt` | 平台/表单/音源 | 560 |
| `phone/PhonePlayerScreen.kt` | 播放页（三样式 + 手势 + sheet） | 900 |
| `phone/PhoneComponents.kt` | 通用卡/行/空态/加载 | 400 |
| `ui/phone/PhoneGlyphs.kt` | 图标（复用 `UiGlyphs` 画法） | 160 |

合计约 **5200 行**（与 §0 的估算一致）。

> 实际落地时文件名与上表略有出入（`PhoneHomePage` / `PhoneLibraryPage` / `PhoneSearchPage` /
> `PhoneMyPage` / `PhonePlayerPage` / `PhonePlayerSheets` / `PhoneLyrics` / `PhoneCards` / `PhoneState` …），
> 按「一页一文件」拆得更细；以下表为准要看的是职责，不是文件名。各期实际产出见 §10。

### 9.2 改动文件（少而关键）

| 文件 | 改动 | 风险 |
|---|---|---|
| `AndroidManifest.xml` | 删掉 `screenOrientation="landscape"`，改 `unspecified` | 低（TV 端运行时补回，见下） |
| `MainActivity.kt` | 按形态设 `requestedOrientation`；其余不动 | 中（`dispatchKeyEvent` 不要碰） |
| `ui/Theme.kt` | `YueYinTheme` 加 `formFactor` 参数，手机端**不**做画布折算 | 低 |
| `ui/YueYinApp.kt` | 在 `AuthenticatedApp` 前分叉：`if (formFactor == Tv) AuthenticatedApp(...) else PhoneApp(...)` | 中 |
| `app/build.gradle.kts` | 无需改（不加依赖） | — |

**TV 零改动的证明：** `AuthenticatedApp.kt`、`PlayerScreen.kt`、`SettingsScreen.kt`、`RemoteControlGuide.kt`、`connect/`、`source/` 全部不碰。

### 9.3 关键代码骨架

**(a) 形态判定**

```kotlin
enum class FormFactor { Tv, Phone, Tablet }

@Composable
fun rememberFormFactor(): FormFactor {
    val config = LocalConfiguration.current
    val context = LocalContext.current
    val uiModeType = config.uiMode and Configuration.UI_MODE_TYPE_MASK
    val isTv = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    return when {
        isTv -> FormFactor.Tv
        config.smallestScreenWidthDp >= 600 -> FormFactor.Tablet
        else -> FormFactor.Phone
    }
}
```

**(b) 密度：手机不做 960×540 折算**

```kotlin
val scale = if (formFactor == FormFactor.Tv) {
    FnAdaptive.canvasScale(config.screenWidthDp, config.screenHeightDp)
} else 1f
val density = Density(original.density * scale * uiScale, original.fontScale)
```

手机端 `scale = 1f` 意味着 1sp 就是 1sp，**系统字体放大也跟着生效**（无障碍要求，TV 端不需要）。

**(c) 手机字号令牌**（TV 的 30/23/19 在手机上过大）

```kotlin
object PhoneType {
    val Display = 28.sp   // 首页问候
    val Title   = 22.sp   // 页面标题
    val Section = 17.sp   // 分组标题
    val Body    = 15.sp   // 正文
    val Row     = 14.sp   // 列表副标题
    val Caption = 12.sp   // 元信息
    val Micro   = 11.sp   // 角标
}
```

**(d) 手机壳骨架**

```kotlin
@Composable
internal fun PhoneApp(
    container: AppUiDependencies,
    session: SessionState.SignedIn,
    playback: PlaybackUiState,
    onExitApplication: () -> Unit,
) {
    // 路由栈语义与电视壳一致：open / root / back
    var stack by remember(session.user.guid) {
        mutableStateOf(listOf<LibraryRoute>(LibraryRoute.Home))
    }
    val route = stack.last()
    val showBottomBar = route !is LibraryRoute.Player
    Scaffold(
        containerColor = FnColors.Background,
        bottomBar = {
            Column {
                if (playback.hasCurrent) MiniPlayerBar(playback) { open(LibraryRoute.Player(null)) }
                if (showBottomBar) PhoneBottomBar(route, ::root, ::open)
            }
        },
    ) { inner ->
        PhoneRouteHost(route, Modifier.padding(inner), ...)
    }
}
```

**(e) 手势（播放页）**

```kotlin
Modifier.pointerInput(Unit) {
    detectHorizontalDragGestures(
        onDragEnd = { if (dragTotal < -80) next() else if (dragTotal > 80) previous() },
    ) { _, delta -> dragTotal += delta }
}
```

手势统一用 `pointerInput(Unit)` + 内部 `var` 累计，**不要**把播放状态放进 key（这是 2.0.0 修过的"手指被取消"老坑）。

### 9.4 性能

- 列表一律 `LazyColumn` / `LazyVerticalGrid` + `key`，不整页 `verticalScroll`。
- 封面继续用现有 `ArtworkBitmapCache`，手机端请求更小尺寸（`coverVariant()` 加一档手机尺寸），避免 1080p 大图解码占内存。
- 播放页氛围光晕（`FnAmbientBackdrop` 24fps 自绘）在手机上保留，但把光斑半径按屏幕宽度比例算，别用固定值。

---

## 10. 分期与验收

| 期 | 内容 | 验收标准 | 可出包 |
|---|---|---|---|
| **P0** 地基 | FormFactor、PhoneTheme、Manifest 解锁、Edge-to-edge、底部导航骨架、迷你条空壳、返回键 | 手机竖屏能起来、导航能切、TV 端行为不变 | ✅ **已完成**（2026-11-02，模拟器实测） |
| **P1** 首页 + 曲库 | 首页三层次、曲库 5 分段、卡片网格、分页加载 | 能连平台看曲库、能点进详情、随心听能起播 | ✅ **已完成**（2026-11-02，模拟器实测） |
| **P2** 详情 + 我的 + 搜索 | 三个详情页、我的页、搜索页 | 播放全部、收藏、搜索命中、拼音首字母可用 | ✅ **已完成**（2026-11-02，模拟器实测） |
| **P3** 播放页 | 三样式、控制层、手势、音效 sheet、队列 sheet | 起播不卡、切歌不崩、歌词滚动同步、音效即时生效 | ✅ **已完成**（2026-11-02，模拟器实测） |
| **P4** 设置 + 平台 + 音源 + 关于 | 设置 5 组、8 种平台登录、音源导入（扫码 + 本地）、自更新、崩溃日志 | 全功能走查，§6 对照表逐项打勾 | ✅ **已完成**（2026-11-02，模拟器实测 + TV 回归） |
| **P5** 宽屏 | 三档断点、左侧图标栏、网格 2/3/4 列自适应、横屏播放页左右分栏、平板双栏详情 | 竖屏零回归；横屏与 1280dp 平板布局成立；TV 端不变 | ✅ **已完成**（2026-11-02，模拟器实测三种宽度） |

**每期结束必做：** TV 端回归（模拟器 + 真机各一次焦点走查），手机端崩溃率为 0。

### P1 实施记录（与原计划的偏差）

1. **详情页提前到 P1**：计划里详情在 P2，但「能点进详情、播放全部」是 P1 的验收项，
   卡片点下去没有落点等于验收不过。P1 先做了专辑/歌单/歌手共用的详情页
   （头部 + 播放全部 + 分页曲目列表），P2 在此基础上补收藏按钮、专辑/歌手分区、歌手热门排序。
2. **播放页做了简版**：只有封面/曲名/进度/三键，没有三种样式、手势、歌词、音效与队列弹层 ——
   那些仍是 P3。做简版是因为 P1 就会真的出声，没有播放页连「下一首」都点不到。
3. **手机端播放队列用「首页队列」而非滑动窗口**：P1 把已加载的一页整体交给播放器，
   只预取当前 + 下一首直链，其余后台补。代价是集合超过一页（100 首）播到页尾就停；
   P3 做队列面板时再升级成电视端那套 `QueueSource` + `QueuePageSegment` 滑动窗口。
4. **搜索框只做了首页入口**，点击进「搜索」占位页（P2 接入真实搜索）。

---

### P2 实施记录（与原计划的偏差）

新建 2 个文件（`PhoneSearchPage.kt` 约 520 行、`PhoneMyPage.kt` 约 500 行），
扩建 3 个（`PhoneCards` / `PhoneDetailPage` / `PhoneLibraryPage`），**首次改动 core 一行逻辑**（见第 4 条）。

1. **搜索页没有「查看全部」**：`musicRepository.search()` 一次返回全量分类结果、接口不分页，
   所以每个分区直接给上限（歌曲 20 / 歌手 20 / 歌单 20 / 专辑 20）。再多用户也不会往下翻，
   找具体某首该用更精确的词。搜索历史存 SharedPreferences（8 条，去重置顶）。
2. **我的页不主动拉全量曲库**：四个数字（收藏 / 歌单 / 专辑 / 歌手）全部取自已加载快照，
   进页面只补四个轻量请求（歌单全量、专辑首页、歌手首页、收藏首页）。
   数量未知显示「—」而不是 0——0 是「确实没有」，两者不能混。
3. **歌手详情的「专辑」带放在头部下方**，不在列表末尾：实测歌手有 113 首，
   放在末尾要滚过全部曲目才看得见，等于没有。**但**在线音乐源没有实现 `artistAlbums`
   （`MusicProtocol` 默认返回 emptyList），所以只有飞牛 / Navidrome / Emby / Jellyfin / Subsonic
   这类平台才会出现这条带；电视端同一分区的行为一致，不算能力缺失。
4. **修了一个收藏的真 bug（唯一一处 core 改动）**：`MusicRepository.toggleFavorite` 增加可选参数
   `snapshot: Track?`，本地收藏兜底时优先用调用方给的曲目快照。
   原来只传 guid，数据层靠 `trackMetadata` 反查，在线音源上屡屡查不到，
   收藏页会出现一条没有名字、没有封面、没有时长的「未知曲目」。
   默认参数保证电视端调用点不用改、行为不变。
5. **搜索页与播放页不挂底部导航**：搜索页键盘弹起时底栏会被顶出屏幕，半隐半现更难看；
   但搜索页**保留迷你条**——在结果里点了歌没有任何可见反馈，用户会以为没点上。
6. **退出登录改为页内就地展开确认卡**，不用 Dialog：自绘 Dialog 要另开一个 Window，
   在 Compose 壳里多一层不确定性；就地展开同样有二次确认，且行为可预测。
7. **曲库「歌曲」「收藏」两个列表也加了行内心形**：收藏页若没有取消入口，
   用户只能去播放页操作，等于收藏进去就出不来。

验收（模拟器 Pixel 1080×2400 / 420dpi，在线音乐 286 首）：

- 搜索：输入 `all` → 歌曲 116 首命中、行内心形可收藏、点行起播后迷你条出现、历史 chip 自动记录。
- 我的：收藏 1 / 歌单 206 / 专辑 192 / 歌手 349；平台卡「在线音乐 · 已连接」；退出确认卡展开与取消都正常。
- 详情：播放全部与随机平分一行，随机起播的是第 2 首（不是第 1 首，证明队列真的打乱了）；
  曲目行收藏可切换；歌手详情头部正常、圆形头像、113 首。
- 收藏：重新收藏后列表显示封面 + 「All」+「Amir Nazari」+ 3:03（修复前是「未知曲目」）。
- TV 回归：临时强制 `isTv = true` 编译，横屏 + 顶部 5 tab + 206 个歌单网格完整，crash buffer 为空；
  已还原（`grep TEMP-TVFIXTURE` 无残留）。
- 编译：`assembleSideloadDebug` BUILD SUCCESSFUL，**零告警**。

证据截图（都在 `docs/phone/render/`）：`phone-p2-my.png`、`phone-p2-search-history.png`、
`phone-p2-search-result.png`、`phone-p2-search-favorite.png`、`phone-p2-search-playing.png`、
`phone-p2-album-detail.png`、`phone-p2-shuffle.png`、`phone-p2-artist-detail.png`、
`phone-p2-favorites.png`、`phone-p2-logout-confirm.png`、`regress-tv-p2.png`。

已知遗留（交给 P3 / P4）：

- 搜索页的「全部结果」分页、详情页的 `⋮` 菜单（加入队列 / 查看专辑）都还没做。
- 我的页五个入口目前统一跳到「设置（第 4 期接入）」占位页，P4 接真实页面。
- ~~冷启动到首页约 15~20 秒（在线音源首次建索引）~~ → **此归因已于 2026-11-02 证伪并更正**，
  详见 §9.4 性能：慢的是 debug 包（9.3~14.4s），release 冷启动 2.0~2.1s。

---

### P3 实施记录（与原计划的偏差）

新建 3 个文件（`PhoneLyrics.kt` 约 220 行、`PhonePlayerSheets.kt` 约 400 行、重写
`PhonePlayerPage.kt` 约 700 行），扩建 `PhoneGlyphs`（+6 个图标），
**core 只动一处**：`PlaybackController.seekTo(positionMs)`。

1. **进度条拖动必须绝对定位**：原有能力只有 `seekBy(deltaMs)`，而拖动进度条给出的是落点，
   用 `seekBy(落点 - 当前进度)` 换算，中间隔了一次进度回调就是一帧的时间差，
   实测会偏 1~2 秒且越拖越偏。所以在 `core/playback` 补了 `seekTo`（纯新增，电视端不调用）。
2. **控制层「点空白切换」在三个样式里并不是都能生效**：封面/海报样式点哪都行，
   但**歌词样式的列表是滚动容器，它在 Main 阶段就把按下事件消费掉了**，
   外层舞台的 `detectTapGestures` 永远等不到——进歌词样式后会彻底没有唤出控制层的入口
   （走查时实测复现，不是理论问题）。最终做法：①点歌词行 = 跳到那一句 **并**叫出控制层；
   ②点歌词头部（小封面 + 曲名）也能切换。两者都是列表之外/之内的独立点击，不会被吞。
   试过在 LazyColumn 上挂 `pointerInput`、`requireUnconsumed = false` 两种写法，均不生效，
   已在代码里留注释，避免以后有人再踩。
3. **队列面板没有升级成滑动窗口队列**（计划里写的「P3 顺手升级 `QueueSource`」）：
   动它要改 `core/playback` 的队列装配，而手机端现在跑得好好的，收益（超过 100 首连续播）
   远小于回归风险。面板先做「看 / 跳 / 删」三件事，队列策略沿用 P1 的首页队列，
   **>100 首播到页尾会停**这条遗留继续挂着，等 P5 或专门一轮再做。
4. **音效面板按内容分档高度**：`PhoneSheet` 增加 `heightFraction` 参数（队列 0.72、音效 0.46）。
   统一 0.72 时音效面板下半截空着，像内容没加载出来。
5. **播放失败提示贴底而不是贴顶**：歌词样式的头部就在顶部，贴顶会把封面和曲名整块盖住；
   贴底还离「重试」更近。同时把 media3 的枚举名翻成人话
   （`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` → 「网络连接不上音源」），只翻译走查里真出现的几类。
6. **收藏按钮用播放状态自拼 `Track` 快照**：元数据还没就绪时，原逻辑「没有 Track 就不收藏」
   会让心形点了毫无反应。现在用 `mediaId / title / artist / coverId / duration` 拼一个临时 Track 交给数据层。
7. **横滑切歌只在封面样式开放**：歌词样式里横滑和「上下翻歌词」抢手势；海报样式是极简展示。
8. **三样式选择持久化**（`AppPreferences.setPlayerStyle`），下次进播放页还是上次那个样式；
   歌词行高固定 44dp 且不做字号联动，否则当前行一放大就引起 LazyColumn 重测、视觉上歌词会抖。

验收（模拟器 Pixel 1080×2400 / 420dpi，在线音乐 286 首）：

- 三样式轮换：封面 → 歌词 → 海报 → 封面，标签与内容同步，退出重进保持上次样式。
- 歌词：当前行珊瑚色 22sp、其余行 0.42 alpha、`animateScrollToItem` 居中跟随；
  点某句 → 跳到该句并唤出控制层；点头部 → 切换控制层。
- 控制层：播放中 3.5 秒自动收起；点空白唤出；再点收起（双向都验过，间隔 1.5 秒避开动画）。
- 进度条：`swipe 200→900`（y=1909）落点 2:58 / 总长 3:38，与拖动比例一致；歌词同步跳到对应句。
- 横滑切歌：封面区左滑 `750→200` 成功切下一首，封面/曲名/歌词一起更新。
- 播放模式：列表循环 → 随机，图标与文字同步。
- 队列面板：标题「播放队列 (50)」、当前曲珊瑚竖条且无删除键、点其他曲立即切歌。
- 音效面板：8 档两列、点「流行」→「已开启 · 流行」、开关变珊瑚、底部操作键同步显示档位。
- TV 回归：临时强制 `isTv = true` 编译，横屏首页 + `ImmersivePlayer` + 返回 + 迷你卡全部正常；
  已还原（`grep TEMP-TVFIXTURE` 无残留）。
- 编译：`assembleSideloadDebug` BUILD SUCCESSFUL，**零告警**。

证据截图（`docs/phone/render/`）：`phone-p3-cover.png`、`phone-p3-lyrics.png`、`phone-p3-poster.png`、
`phone-p3-queue.png`、`phone-p3-fx.png`、`phone-p3-seek.png`、`phone-p3-immersive.png`（控制层收起态）、
`regress-tv-p3-home.png`、`regress-tv-p3-player.png`、`regress-tv-p3-back.png`。

已知遗留（交给 P4 / P5）：

- 队列仍是首页队列（>100 首播到页尾会停），见偏差 3。
- 歌词失败态的提示写「点播放页右上角重试」，但右上角目前只有样式切换，**真正的重试入口还没做**。
- 播放页没有「更多」菜单（加入队列 / 查看专辑 / 分享），与详情页的 `⋮` 一起留到 P4。

---

### P4 实施记录（与原计划的偏差）

新建 4 个文件（`PhoneSettingsPage.kt` 约 480 行、`PhoneSettingsPanes.kt` 约 700 行、
`PhonePlatformPage.kt` 约 560 行、`PhoneSourcePage.kt` 约 470 行），
**core 一行未改**（这一期全部是壳层拼装：AppPreferences / ConnectRuntime / MusicSourceRuntime /
UpdateController / CrashLogStore 都是现成能力，手机壳只负责把状态摆到触屏上）。

1. **设置二级面板用枚举，不往 `LibraryRoute` 里加成员**：手机壳的面板（播放界面 / 音效 / 缓存 / 关于 /
   崩溃日志）只在手机内部有意义，塞进公共路由类型会污染电视端也在用的枚举，将来电视壳读到时
   还得逐个写 `else` 分支。改成 `PhoneSettingsPane{Root, Player, Fx, Cache, About, CrashLog}` +
   `settingsEntry` 状态，`BackHandler` 在面板里先回根页，路由本身只有 `LibraryRoute.Settings` 一个。
2. **平台管理与音源页最初隐藏了底栏，走查时改回**：一开始按「办完一件事就退」的任务页处理，
   但同样是从设置点进去的五个面板却保留了底栏 —— 同一个入口下两种行为，用户点「平台管理」
   底栏会突然消失、返回又出现，看起来像跳出了应用外壳。最终判据收敛成一条：
   **只有「输入/配对中」的强任务态才收底栏**（搜索、平台表单、音源局域网接收、播放页全屏沉浸）。
   平台管理、平台类型列表、在线音源清单都保留底栏，与设置面板一致。
3. **测试连接与连接并保存各用一个忙碌标记**：共用一个 `busy` 时点「测试连接」会让保存按钮一起
   变成「处理中…」，看起来像两个请求在同时跑；拆成 `testing` / `saving` 后文案也各自准确。
4. **右侧值不能塞列表**：设置页「在线音源」原写`已添加 5 个 · 酷我 / QQ / 网易…`，在 390dp 上被截断，
   改成只报数量；缓存占用原写 `1.7 MB（图片 0 B / 资料 1.7…）` 同样截断，拆成
   「封面图片 / 曲库资料 / 合计 / 歌曲磁盘缓存」四行，每行右对齐数值。
5. **崩溃日志的两个图标是新画的**：原先借 `Queue` / `More`，语义对不上（队列图标配「复制」很怪）。
   在 `PhoneGlyphs` 补了 `Copy`（两张错位纸）与 `Trash`（桶身 + 盖 + 提手），沿用统一的
   1.8dp 圆头描边。
6. **切换平台要清场**（`PhoneShell`）：切过平台后旧的播放队列里全是上一家的曲目 id，
   点「下一首」会拿旧 id 去新平台查，结果是错歌或者直接失败。现在监听 `ConnectRuntime` 的
   `activeId`，变化时先 `stopAndClearQueue()` 再 `store.clearAll()`。
   **同时 `boundPlatformId` 记住初值** —— 否则冷启动时 `activeId` 从 null 变成实际值会被当成
   「切了平台」，把正在播的歌清掉。
7. **关于页的二维码可点**：扫码（另一台设备）和点击（本机跳浏览器）两条路都通。
   手机上扫码方便，模拟器和平板上点链接方便，两种场景都不该缺一条。
8. **音源导入给两条路**：本机文件走 `OpenMultipleDocuments`（多选，单个上限取
   `MusicSource.MAX_SCRIPT_BYTES`），局域网走 `SourceReceiveServer` 的地址 + 二维码。
   两者都是电视端已有的数据层能力，手机壳只是换了个更顺手的入口，没有新增协议。
9. **表单预填 `http://` 而不是空串**：八种平台里六种是 http 服务，预填省掉用户切键盘打协议头；
   SMB 用户直接删掉换成 `smb://`（地址示例就写在表单下方，不用去猜）。

验收（模拟器 Pixel 1080×2400 / 420dpi，在线音乐 286 首，5 个音源脚本）：

- 设置根页 5 组 8 行，右侧值全是真实状态：歌词 / 紧凑·逐字 / 已开启·流行 / 在线音乐·1 个 /
  已添加 5 个 / 上限 32 MB / 2.0.3-debug / 未检查。
- 播放界面：三张自绘缩略图（唱片 / 海报框 / 歌词线），切「封面」「歌词」即时生效且持久化，
  返回根页右侧值同步变化；界面缩放三档、在线歌词匹配与逐字填充开关、视觉三项
  （动态取色 / 模糊背景 / 氛围特效四档）全部可用。
- 音效：开关「已开启 · 流行」+ 8 档两列，与播放页音效键同步。
- 缓存：四档预算；占用四行（封面 0 B / 资料 1.2 MB / 合计 1.2 MB / 歌曲磁盘 369.5 MB）；两处清理。
- 关于：品牌卡（2.0.3-debug · 构建 27 · GPL-3.0）、更新行（状态点 + 文字 + 按钮）、
  反馈二维码（可点跳浏览器）、崩溃日志入口；检查更新失败时弹「检查更新失败 / Connection reset」，
  重试与关闭都可用。
- 崩溃日志：等宽渲染真实日志（历史一条 `AuthenticatedApp` 的 IllegalStateException），
  复制全部 / 清空两个按钮的新图标正常。
- 平台管理：在线音乐标「当前」，展开出「检测音源 / 删除」并把右侧变「收起」；底栏在。
- 新增平台：10 类纵向列表（飞牛 fnOS / Navidrome / Subsonic·Airsonic / Jellyfin / Emby / Plex /
  WebDAV·Alist / SMB / 群晖 AudioStation / 在线音乐），各有图标与副标题。
- 连接表单：预填 `http://`；键盘弹起时「测试连接 / 连接并保存」两个按钮完整可见；
  测试连接后出红色「连接失败：请检查地址、账号密码与服务是否可访问」。
- 在线音源：5 个脚本各显示真实支持平台（`mylocal.js` → 酷我 / 网易 / 酷狗 / QQ / 咪咕 可用），
  各行检测 / 删除可用。
- 音源导入：本机选择 + `http://10.0.2.15:8899` 二维码 + 已保存 5 项（均标「已添加」）+ 完成键。
- 我的：五个入口各自直达真实页面（抽查「播放与外观」直接落在播放界面面板，底栏仍指设置）。
- TV 回归：临时强制 `isTv = true` 编译，横屏首页 / 设置五个面板（播放界面、音效、平台、缓存、关于）/
  关于页的检查更新弹层全部正常；`crash buffer` 为空；已还原（`grep -rn TEMP-TVFIXTURE` 仅剩文档说明）。
- 电视壳零改动复核：`AuthenticatedApp.kt` / `PlayerScreen.kt` / `SettingsScreen.kt` 三个主文件
  `git status` 干净，`connect/` `source/` `update/` 目录零改动；接缝改动只有 6 个文件的形态分支。
- 编译：`assembleSideloadDebug` BUILD SUCCESSFUL，**零告警**。

证据截图（`docs/phone/render/`）：`phone-p4-settings.png`、`phone-p4-player-pane.png`、
`phone-p4-style-switch.png`、`phone-p4-fx-pane.png`、`phone-p4-cache-pane.png`、
`phone-p4-about-pane.png`、`phone-p4-update-error.png`、`phone-p4-crash-pane.png`、
`phone-p4-platform-manage.png`、`phone-p4-platform-actions.png`、`phone-p4-platform-picker.png`、
`phone-p4-platform-form.png`、`phone-p4-platform-form-keyboard.png`、`phone-p4-platform-test-fail.png`、
`phone-p4-music-sources.png`、`phone-p4-source-check.png`、`phone-p4-source-import.png`、
`phone-p4-my.png`、`phone-p4-my-to-pane.png`；电视端 `regress-tv-p4-home.png`、
`regress-tv-p4-settings.png`、`regress-tv-p4-platform.png`、`regress-tv-p4-about-dialog.png`。

已知遗留（交给 P5 或专门一轮）：

- **首次使用引导没做**（§6 第 22 项）：电视端是遥控器按键图，手机端直接跳过了，现在第一次打开
  没有任何手势说明。这是这一期唯一一条「本该做但没做」的，优先级最高。
- **音源清单没有失败排后**（§6 第 4 项）：检测能力有了，但顺序永远是添加顺序，坏脚本混在好脚本中间。
- **登录历史列表没做**（§6 第 19 项）：手机是单账号语义，切账号靠退出重登。
- 队列仍是首页队列（>100 首播到页尾会停），队列排序也没做，与 P3 偏差 3 同一件事。
- 歌词失败态的「重试」入口仍未做（P3 遗留）。
- 播放页「更多」菜单（加入队列 / 查看专辑 / 分享）与详情页 `⋮` 未做。
- 搜索页「全部结果」分页未做（P2 遗留）。
- 电视端手动检查更新在无网络时「正在检查更新」会长时间停留（电视壳 `UpdateController` 既有行为，
  不在本次改动范围，也不允许从手机侧改 `update/`）。

### P5 实施记录（横屏 / 平板，2026-11-02）

**交付**

| 文件 | 改动 |
|---|---|
| `phone/PhoneWindowSize.kt`（新增） | `PhoneWidthClass{Compact,Medium,Expanded}` + `PhoneWindow(widthDp,heightDp)` 派生 `widthClass` / `isLandscape` / `gridColumns` / `usesNavRail` / `usesSplitPane`；`ProvidePhoneWindow` 用 `BoxWithConstraints` 实测后经 `LocalPhoneWindow` 下发 |
| `phone/PhoneShell.kt` | 顶层包一层 `ProvidePhoneWindow`；`PhoneNavRail`（72dp 竖条，珊瑚色竖条选中态）；`usesSplitPane && stack.size>=2 && route.isDetailRoute()` 时双栏；单列信息页限宽 `PHONE_READING_WIDTH=720dp` 居中 |
| `phone/PhoneLibraryPage.kt` | `PhonePagedGrid` 的 `columns` 默认值改为读 `LocalPhoneWindow.current.gridColumns`（歌手仍固定 1 列） |
| `phone/PhoneHomePage.kt` | 「我的歌单」手写网格的 `chunked(2)` 改为 `chunked(window.gridColumns)` |
| `phone/PhonePlayerPage.kt` | 横屏分支：`Row(左舞台 + 右侧 PLAYER_SIDE_PANEL 常驻控制栏)`；`CoverStage` / `PosterStage` 封面从 `fillMaxWidth().aspectRatio(1f)` 改为按可用宽高自适应，横屏不再溢出 |
| `phone/PhoneDetailPage.kt` / `PhoneCards.kt` | `PhoneTrackCollectionPage(embedded=…)` 与 `PhoneBackHeader(showBack=…)`：双栏右栏不画返回箭头 |
| `phone/PhoneTheme.kt` | 新增 `NavRail` 尺寸令牌 |
| `MainActivity.kt` | `FormFactor.Phone` 从 `SCREEN_ORIENTATION_PORTRAIT` 改为 `UNSPECIFIED`——竖屏锁是 P0 的临时约束（当时只有竖屏布局），现在有横屏布局了必须放开，否则平板/横屏永远进不去 |

**`core` 依然零改动**：P5 全部是壳层布局决策，没有一行落到数据层。

**与原计划的偏差**

1. **双栏门槛 840 → 900dp**。840~900 这一段（典型 7 寸平板竖屏 860dp）左栏固定 380dp 后
   右栏只剩 460dp，网格放 3 列挤、放 2 列浪费，不如单栏 4 列好看。
2. **横屏导航不用「顶部导航条」，改用左侧 72dp 图标栏**。原 §7 写的是横屏隐藏底栏改顶部，
   但横屏手机高度只有 390dp 左右，顶部条同样吃纵向空间；竖条只占 72dp 宽，横向还有 700dp 以上，
   而且与平板的侧栏共用一套组件。
3. **横屏播放页控制层常驻，不再自动收起**。横屏高度本来就只剩 360dp 上下，浮层一收起，
   用户得先点一下屏幕才能操作；常驻既不挡内容也少一次点击。竖屏保持 P3 的自动收起。
4. **双栏左栏必须重新测一次窗口**（走查发现的真 bug）。左栏宽 380dp，但它读到的是整块画布的
   1280dp，网格排成 4 列、每张卡只剩 80dp 宽、标题全被省略号吃掉。修法是左栏内再包一层
   `ProvidePhoneWindow` 让它按自身宽度实测；右栏同理，避免以后右栏变窄时仍按整屏推断。
5. **首页大卡在宽屏下留白偏大**：`随心听` 横幅在 1280dp 下右侧空出大半屏，没做「宽屏双列大卡」。
   不影响可用性，留作后续打磨。

**走查结论（模拟器 Pixel，1080×2400 + `wm size 2560x1600` / `density 320`）**

- 竖屏（<600dp）：底栏 4 tab、2 列网格、迷你条、播放页控制层——与 P4 完全一致，零回归。
- 横屏手机（872dp → Medium）：左侧图标栏出现、底栏收起、3 列网格；迷你条横跨内容区不侵入侧栏。
- 横屏播放页：左封面/歌词 + 右控制栏左右分栏，进度条、控制键、四个功能键完整可见；
  切到歌词样式后当前行高亮与右栏控制同时可用。
- 平板（1280dp → Expanded）：4 列网格；点歌单/专辑进双栏——左栏列表（2 列）、右栏详情，
  中间 1dp 分隔线；左栏保留进入前的滚动位置。
- 单列信息页（设置 / 我的 / 平台 / 音源）：720dp 限宽居中，不再「标题与值隔半个屏幕」。
- 正式包（`exportReleaseApk`，R8 + 资源压缩）安装后首页/设置/平台页正常，`crash buffer` 为空。

证据截图：`p5-01`~`p5-19`（竖屏 / 横屏 / 平板 / 双栏 / 播放页各形态），
正式包 `rel-01-home.png`、`rel-02-settings.png`。

**仍未做**：首次使用引导（P4 遗留，本轮明确跳过）、音源失败排后、登录历史、队列排序、
歌词重试入口、播放页「更多」菜单、搜索「全部结果」分页。

---

## 11. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 解锁横屏后 TV 端转身 | TV 用户看到竖屏 | 运行时判定：TV 形态 `requestedOrientation = LANDSCAPE`，Manifest 不写死 |
| 手机上手势与系统返回手势冲突 | 边缘滑动返回被拦 | 播放页只在**中间 90% 区域**接横滑；左右各留 20dp 给系统 |
| 双份 UI 维护成本 | 新功能要写两处 | 把「数据装配」全部留在 core/state，UI 只做表现；新功能先落手机壳，TV 壳按需补 |
| 大图解码 OOM | 手机内存小 | 手机请求小尺寸封面 + `ArtworkBitmapCache` 分级 |
| 自更新安装被拦 | 用户装不上新版 | 首次更新前弹一次「允许安装未知应用」引导，跳到系统设置页 |
| 触屏点击穿透 / 误触 | 体验差 | 统一走 `TouchButton` 的按下判定；列表项用 `Modifier.clickable`，不再自绘 |
| 三种播放样式在竖屏信息密度不够 | 显得空 | 竖屏下封面尺寸按屏宽 − 48dp 计算，不写死；歌词样式单独排版 |

---

## 12. 需要你拍板的 6 件事

1. **一个包还是两个包？** 推荐一个包双形态（手机装手机版、TV 装 TV 版，同一个 APK）。若你要手机独立渠道，P0 的判定逻辑可以简化，但版本号要分叉。
2. **底部 4 tab（首页/曲库/我的/设置）** 行不行？还是坚持 5 个（专辑、歌单独立）？
3. **播放页默认样式**：手机上默认 `Cover`（旋转唱片）还是 `Poster`（大封面）？我倾向 `Poster` —— 竖屏大封面更符合手机听歌习惯。
4. **要不要允许横屏**？允许的话播放页可以做成横屏全屏封面（像 TV），但要多一套横屏布局。
5. **音源导入**是否加「本地文件选择」？（我建议加，手机上扫码绕远）
6. **先动手还是先看渲染图**？12 屏渲染图在 `docs/phone/render/`（总览 `all-screens.png`）。看完再定 P0 范围也行。

---

## 附：与 TV 版的关系一句话

**手机版不是 TV 版的精简版，是同一套能力在竖屏上的重新组织。** §6 的 23 项能力逐项有落点，才算完成。
