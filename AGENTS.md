# AGENTS.md — MockGPS 外观系统设计规范

本文件是本工程**外观/UI 层的唯一权威规范**。任何 AI 编码代理或人在改动界面之前，先读完本文件。
它记录的不只是「长什么样」，更是「为什么必须这样做」——里面每一条规则都对应一个真实踩过的坑。

> 工程基本信息（2026-09 核对）
>
> | 项 | 值 |
> |---|---|
> | 包名 / namespace | `com.example.mockgps` |
> | 版本 | `versionName 1.26` / `versionCode 27` |
> | SDK | `compileSdk 34` / `minSdk 21` / `targetSdk 34` |
> | 技术栈 | Java 17 + 传统 View 体系 + XML，Material Components **1.12.0**，AppCompat 1.7.0 |
> | 地图 | osmdroid-android 6.1.18（免 API Key） |
> | 主题基类 | `Theme.Material3.DayNight.NoActionBar` |
> | 页面容器 | ViewPager2 + TabLayout，3 页（主页 / 选点 / 轨迹） |

---

## 0. 职责边界：什么不许碰

外观改造只动**主题层与 View 层**。以下逻辑一行都不要改：

- 定位引擎（`MockLocationProvider` 相关）、`LocationManager` 的模拟位置注册流程
- 坐标系换算（`CoordUtil`，WGS-84 / GCJ-02）
- 行政区数据与下钻逻辑（`DivisionData`、`HomeFragment` 里的列表装配）
- 收藏夹的读写与存储结构
- `CrashLogger`、权限申请流程

判断标准很简单：**改完之后「打开虚拟定位 → 生效 → 轨迹记录」这条主链路的行为必须完全一致**。

---

## 1. 三条铁律

### 铁律 1：视觉参数一律走主题属性，禁止写死

颜色、圆角、间距、动效强度都必须通过 `?attr/...` 引用 `res/values/attrs.xml` 里声明的属性。
写死色值意味着用户切换方案/主色/夜间模式时它不会跟着变。

已被清除、**禁止再出现**的历史色值：`#1976D2`、`#3D7BF5`、`#8A8B9A`
（`tools/verify_mockgps.py` 第 [4] 项会扫出来并报错。）

### 铁律 2：每个令牌必须有消费点

新增一个 `attr` / `dimen` / `color` 之前，先回答：**谁读它？**

- 声明了一个 attribute 却没有任何 `?attr/xxx` 或 `R.attr.xxx` 读它 → 「空转属性」（校验器第 [6] 项报错）
- 声明了一个 dimen/color 却没有任何 `@dimen/xxx` / `@color/xxx` 引用 → 「死令牌」（校验器第 [7] 项报错）

这条规则是整套规范的起点。原设计包最严重的几个问题（见第 9 节）全部源于此。

确实要保留、但暂时没有消费点的尺度档位，必须在 `tools/verify_mockgps.py` 的 `RESERVED_SCALE`
白名单里**逐个列出**，而不是整体放过。

### 铁律 3：卡片和普通容器用两套不同的背景机制，不许混用

| 控件类型 | 用什么 |
|---|---|
| `MaterialCardView` 及其子类 | **只用** `cardBackgroundColor` / `cardCornerRadius` / `strokeColor` / `strokeWidth` |
| `LinearLayout` / `FrameLayout` / `TextView` 等普通容器 | `android:background` 指向 layer-list drawable |

原因：`MaterialCardView` 内部用自己的 `MaterialShapeDrawable` 当背景（`cardBackgroundColor` 就是在改它），
如果你另外写 `android:background`，两套机制会互相覆盖，结果不可预期。

> ⚠ 这是原设计包的 **A 级硬伤**：`Widget.MockGPS.Card.Glass` 曾同时使用 `android:background` 与
> `cardBackgroundColor`。现已重定义为只走 `card*` 属性（parent 为 `Widget.Material3.CardView.Filled`）。

---

## 2. 令牌层

### 2.1 `res/values/attrs.xml` — 24 个自定义属性

全部有消费点，全部在 `Theme.MockGPS` 里赋了默认值。（「有默认值」很重要：某些 ThemeOverlay 叠加组合下
如果没有基础值，`?attr` 会解析失败。）

| 分组 | 属性 | 谁在读 |
|---|---|---|
| 图标语义色 | `colorIconPrimary` | `styles.xml` 的 `Widget.MockGPS.IconButton`、`fragment_home.xml` |
| | `colorIconSecondary` | 地图/主页/轨迹三个 fragment 的 ImageButton、`IconButton.Secondary` |
| | `colorIconTertiary` | `item_division.xml`、`item_favorite.xml`、`IconButton.Tertiary` |
| | `colorIconOnGlass` | `fragment_map.xml` 的地图提示条文字 |
| 玻璃 | `colorGlassSurface` | `GlassBackdrop.java`、`bg_glass_card(_solid/pill/tint/tint_specular).xml` |
| | `colorGlassBorder` | 三个 `bg_glass_*` drawable、`Widget.MockGPS.Card.Glass` |
| | `colorGlassHighlight` | `ThemeManager.applySpecularHighlight()`、`bg_glass_card`、`bg_glass_tint_specular` |
| 状态胶囊 | `colorStateIdleBg` / `colorStateOkBg` / `colorStateWarnBg` | `bg_status_idle/ok/warn/pill.xml` |
| | `colorStateIdleText` / `colorStateOkText` / `colorStateWarnText` | `Widget.MockGPS.Pill.Status.*`、`fragment_home/home map` |
| | `colorStateDanger` | `Widget.MockGPS.Button.Danger` |
| 形状/间距 | `dimenRadiusCard` | `bg_glass_card(_solid/tint/tint_specular).xml`、`ShapeAppearanceOverlay.MockGPS.Card` |
| | `dimenRadiusButton` | `bg_glass_pill`、`bg_spinner(_popup)`、`bg_text_field`、各按钮样式、`AppearanceActivity` |
| | `dimenRadiusSheet` | `ShapeAppearanceOverlay.MockGPS.Sheet` → `Widget.MockGPS.BottomSheet` |
| | `dimenCardPadding` | `ThemeManager.cardPaddingPx()`、三个 fragment 的卡片内边距（**密度预设的落点**） |
| | `dimenCardElevation` | `Widget.MockGPS.Card.Glass`（`cardElevation`）、`Widget.MockGPS.Surface.Glass*` |
| 玻璃参数 | `integerGlassBlur` | `ThemeManager.glassBlurDp()` → `GlassBackdrop.attach()`（0 = 不挂染色层） |
| | `booleanGlassSpecular` | `ThemeManager.glassSpecular()` → `ThemeManager.applySpecularHighlight()`（普通容器内高光） |
| | `booleanGlassDynamicTint` | `ThemeManager.glassDynamicTint()` → `GlassBackdrop.apply()`（主色占比 8% → 18%） |
| 动效 | `integerMotionLevel` | `Motion.level()`、`ThemeManager.motionLevel()` |
| 方案标识 | `enumDesignScheme` | `ThemeManager.designScheme()` |

**不要加进这个文件的东西**（都试过，都不行）：

- `integerGlassAlpha` 这类「透明度整数」——玻璃底色的消费点（`cardBackgroundColor` / `android:background`）
  只吃颜色，吃不下独立的 alpha 整数。要调节透明度请用 `colors.xml` 里的 `glass_surface_sNN` 颜色斜坡。
- `dimenIconStroke` 这类「图标描边尺寸」——VectorDrawable 的 `strokeWidth` 在编译期就固化进资源了，
  运行时换 attr 不会重绘描边。需要不同粗细就各做一套图标资源（`ic_pin.xml` / `ic_pin_bold.xml`）。
- `dimenSpaceUnit` 这类「栅格单位」——XML 里没法拿一个 dimen 去做乘法，声明出来必然没人读。
  要落到布局里的量，直接做成具体属性，例如 `dimenCardPadding`。

### 2.2 `res/values/colors.xml` + `values-night/colors.xml`

- **5 组主色预设必须成套**：`accent_{blue,teal,purple,green,amber}` 各带
  `_on_primary` / `_container` / `_on_container`。只换 `colorPrimary` 会让卡片、开关、
  状态栏、Container 之间串色（原设计包 **A3 硬伤**）。
- **玻璃透明度用颜色斜坡表达**：`glass_surface_s50 … s95`（浅色 = 白蒙版 alpha 0.50~0.95，
  深色 = 低 alpha 白 0x0F~0x2E）。不要在代码里拼 `#AARRGGBB`。
  不要再新增 `glass_surface` / `glass_surface_plain` 这种单值键——它们和斜坡里的某一档数值完全重复
  （旧 `glass_surface` = `#B3FFFFFF` = `s70`），两套等价写法就是 A1 硬伤的温床。
- 深色模式**只覆盖需要变的键**，其余沿用 `values/colors.xml`。

### 2.3 `res/values/dimens.xml`

圆角、间距、图标、玻璃四组。其中 **22 个键目前没有消费点但被有意保留**（在
`verify_mockgps.py` 的 `RESERVED_SCALE` 里逐个声明），分两类：

- 通用尺度档位：`space_*`、`radius_*`、`icon_size_*`、`icon_grid`、`icon_live_area`
  —— 新建界面时直接取用，避免每次现编一个数。
- 尚未启用的语义槽：`black`、`scrim`、`text_tertiary`。

注意实际生效的圆角来自 `radius_preset_*`（由外观设置的三档切换），实际生效的卡片内边距来自
`card_padding_*`。`radius_*` / `space_*` 是底层尺度，不是当前落点。

---

## 3. 主题层 `res/values/themes.xml`

### 3.1 结构

| 名字 | 作用 |
|---|---|
| `Theme.MockGPS` | 基础主题 = 方案 A 的默认值。Manifest 里 application 用它 |
| `Theme.MockGPS.Appearance` | 外观设置页专用（本质是具名别名，留作以后单独换底） |
| `ThemeOverlay.MockGPS.SchemeA / B / C` | 三套设计风格。**v1.20 起外观设置页只开放 B（液态玻璃）**，A/C 的 Overlay 保留但已无 UI 入口 |
| `ThemeOverlay.MockGPS.SurfaceAlpha.s50 … s95` | 玻璃透明度，步进 0.05，共 10 档 |
| `ThemeOverlay.MockGPS.Glass.Off / Light / Medium / Strong` | `integerGlassBlur` = 0 / 8 / 16 / 28 dp。v1.20 起只用作「是否挂玻璃染色层」的开关（0 = 关），不再对应真实模糊半径 |
| `ThemeOverlay.MockGPS.Radius.Compact / Standard / Round` | 圆角三档 |
| `ThemeOverlay.MockGPS.Density.Compact / Standard / Comfortable` | 卡片内边距三档 |
| `ThemeOverlay.MockGPS.Motion.Off / Basic / Rich / Full` | 动效等级 0~3。**v1.20 起只开放 Full（全开）** |
| `ThemeOverlay.MockGPS.Accent.{Blue,Teal,Purple,Green,Amber}` | 主色预设（成套覆盖 8 项） |
| `ThemeOverlay.MockGPS.BottomSheetDialog` | 挂 `bottomSheetStyle` 钩子 |

### 3.1a v1.20：这三组只剩唯一选项

外观设置页（`AppearanceActivity`）里有三组已经固定，不再提供选择：

| 分组 | 唯一取值 | 持有者 |
|---|---|---|
| 设计风格 | **B 液态玻璃** | `ThemeManager.DEF_SCHEME` |
| 玻璃强度 | **off 关**（卡片改用半透明白底表达玻璃感，不再做模糊） | `ThemeManager.DEF_GLASS` |
| 动效等级 | **full 全开** | `ThemeManager.DEF_MOTION` |

因为只剩一个选项，这三组**不再生成选择按钮**（走 `AppearanceActivity.addFixedValue()`），
只展示当前值；「恢复默认外观」按钮也已移除。

约定：

- **唯一合法值只在 `ThemeManager.DEF_SCHEME / DEF_GLASS / DEF_MOTION` 三处常量里持有。**
  `arrays.xml` 只保留 `appearance_*_labels` 负责显示文案，对应的 `_values` 数组已删除
  —— 两份定义只会互相打架。
- 历史存过的旧值（A/C、light/medium/strong、off/basic/rich）由
  `ThemeManager.migrateDefaultsOnce()` 一次性归位，**必须有这一步**：
  设置页已经没有任何入口能把它们改回来。
- 要重新开放某一组，需要同时做三件事：恢复 `_values` 数组、把 `addFixedValue` 换回
  `addChoice`、删掉 `migrateDefaultsOnce` 里对应的那行强制写入。

### 3.2 叠加顺序（不可更改）

`ThemeManager.setupActivity()` 按这个顺序 `theme.applyStyle(..., true)` 就地叠加：

```
Base → Scheme → SurfaceAlpha → Radius → Density → Motion → Accent → Glass
```

**用户手动微调必须排在 Scheme 之后**，否则会被方案默认值吃掉。
`Glass` 放最后也是刻意的：模糊强度是跨方案的全局偏好。

### 3.3 三条硬规则

1. **方案差异必须落在「同一组 attr 的不同取值」上**。只在基础主题里给值、三套 Scheme 不各自赋值，
   会导致三套方案渲染结果完全一样——这正是原设计包的 **A1 硬伤**
   （`colorGlassSurface` 只在 `Theme.MockGPS` 赋过值）。现在 A = s70 / B = s60 / C = s85。
2. **每个 `Accent.*` 必须成套覆盖** primary / onPrimary / primaryContainer / onPrimaryContainer /
   secondary / secondaryContainer / onSecondaryContainer / colorAccent。
3. **窗口级属性写在 ThemeOverlay 里不生效**。窗口读的是 Activity 自身的 theme，不会重新解析叠加层的
   `android:statusBarColor`。所以状态栏跟随主色由代码完成：
   `ThemeManager.applyWindowColors()` → `window.setStatusBarColor(color(a, R.attr.colorPrimary))`。

> 另外注意：`ThemeOverlay` **不支持热更新**。改完主题必须 `recreate()` Activity，
> `applyStyle` 只对之后 inflate 的 View 生效。

### 3.4 全局组件钩子

`Theme.MockGPS` 里挂了 4 个钩子，作用是**后续新增界面不写 `style=` 也能拿到设计**：

```xml
<item name="materialCardViewStyle">@style/Widget.MockGPS.Card.Glass</item>
<item name="snackbarStyle">@style/Widget.MockGPS.Snackbar</item>
<item name="snackbarTextViewStyle">@style/Widget.MockGPS.Snackbar.TextView</item>
<item name="bottomSheetDialogTheme">@style/ThemeOverlay.MockGPS.BottomSheetDialog</item>
```

新增页面时**直接写 `<com.google.android.material.card.MaterialCardView>` 就已经是玻璃卡片**，
不需要额外的 `style` 属性。

---

## 4. 运行时：`ThemeManager`

### 4.1 Activity 里的固定写法

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    ThemeManager.setupActivity(this);   // 必须在 super.onCreate 之前
    super.onCreate(savedInstanceState);

    setContentView(R.layout.xxx);
    ThemeManager.applyWindowColors(this);   // 必须在 setContentView 之后

    appliedThemeGeneration = ThemeManager.generation(this);   // 主界面才需要
    ...
}

@Override
protected void onResume() {
    super.onResume();
    int gen = ThemeManager.generation(this);
    if (gen != appliedThemeGeneration) {
        appliedThemeGeneration = gen;
        recreate();      // 从外观设置页返回后重建，让新主题真正生效
    }
}
```

- `setupActivity()` 干两件事：`AppCompatDelegate.setDefaultNightMode(...)` + 7 层 `applyStyle`。
  它**不设窗口色**（窗口色必须等 `setContentView` 之后）。
- 为什么要在 `super.onCreate` 之前：AppCompat 会在 `super.onCreate` 里应用深浅模式，
  提前设好可以避免「先按旧主题建一次、再重建」。

### 4.2 读写外观设置

`SharedPreferences` 文件名为 `appearance`（`ThemeManager.PREFS`）。9 个键
（`KEY_SCHEME` / `KEY_GLASS` / `KEY_ALPHA` / `KEY_RADIUS` / `KEY_MOTION` / `KEY_ACCENT` /
`KEY_DARK` / `KEY_DENSITY` / `KEY_HAPTICS`）加一个 `KEY_GEN`（变更代号）。

默认值：方案 `A`、玻璃 `medium`、不透明度 `0.70`、圆角 `standard`、动效 `rich`、
主色 `blue`、深浅 `system`、密度 `standard`、触感 `true`。

透明度取值 `0.50 ~ 0.95`、步进 `0.05`（`ALPHA_MIN/MAX/STEP`）。

**所有写操作都会把 `KEY_GEN` 加一**（`put` / `putAlpha` / `putHaptics`）。
界面靠比对 `KEY_GEN` 判断要不要 `recreate()`，不要自己另发明一套刷新机制。

### 4.3 读取主题属性

```java
ThemeManager.color(ctx, R.attr.colorGlassSurface);   // 内部走 ContextCompat.getColor
ThemeManager.dimen(ctx, R.attr.dimenRadiusCard, def); // 返回 float px
ThemeManager.bool(ctx, R.attr.booleanGlassSpecular, false);
ThemeManager.intValue(ctx, R.attr.integerGlassBlur, 0);
```

> **不要直接用 `Context.getColor(int)`**：它需要 API 23，而本工程 `minSdk` 是 21，
> 在 API 21/22 上会抛 `NoSuchMethodError`。统一走 `ContextCompat.getColor`。

---

## 5. 组件样式层 `res/values/styles.xml`

命名约定 `Widget.MockGPS.*`（形状叠加用 `ShapeAppearanceOverlay.MockGPS.*`，
字体用 `TextAppearance.MockGPS.*`）。

### 5.1 已在使用

| 样式 | 用途 | 现状 |
|---|---|---|
| `Widget.MockGPS.Card.Glass` | 玻璃卡片（parent `Widget.Material3.CardView.Filled`，只用 `card*` 属性） | 6 处，并被 `materialCardViewStyle` 钩子设为默认 |
| `Widget.MockGPS.Button.Primary` | 主操作（开始定位 / 轨迹三态按钮） | 2 处 |
| `Widget.MockGPS.Button.Ghost` | 次级操作（撤销 / 开始画） | 4 处 |

> **轨迹页那个按钮是三态合一的**：「开始 ⇄ 停止 ⇄ 继续」共用一个 `btn_track_start`
> （原来的独立「停止」按钮已合并掉）。状态由 `TrackFragment.trackButtonState()` 从
> `TrackEngine` 推出来，判据是：
> `isRunning()` → 停止；否则 `!isFinished() && canStart() && distanceM() > 0.5` → 继续；
> 其余 → 开始。**「跑完」和「清空」都会自动回到「开始」**。
> 路线被改动（撤销 / 加节点 / 结束画）时会调 `onRouteChanged()`：
> 若正处于暂停态，进度已与新路线对不上，直接作废回「开始」。
> 对应的引擎侧 API 是 `pause()`（保留进度）/ `resume()`（断点续跑）——
> **`TrackEngine` 本来就有，不用自己新造**。
| `Widget.MockGPS.Button.Tonal` | 次要按钮（收藏） | 1 处 |
| `Widget.MockGPS.Button.Danger` | 破坏性操作（清空轨迹） | 2 处 |
| `Widget.MockGPS.Button.MapFloat` | 地图浮层圆形/胶囊按钮 | 6 处 |
| `Widget.MockGPS.Segmented` | `MaterialButtonToggleGroup` 的子按钮 | 7 处 |
| `Widget.MockGPS.TextField` | 输入框（配 `bg_text_field.xml`） | 1 处 |
| `Widget.MockGPS.Pill.Status` | 状态胶囊（空闲态） | 2 处 |
| `Widget.MockGPS.Tab.Pill` | 顶部胶囊 Tab | 2 处 |
| `Widget.MockGPS.Snackbar` / `.TextView` | 由主题钩子自动套用 | 2 处（钩子） |
| `Widget.MockGPS.BottomSheet` | 由 `bottomSheetDialogTheme` 钩子套用 | 1 处（钩子） |
| `ShapeAppearanceOverlay.MockGPS.Pill` / `.Sheet` | 全圆角 / 顶部圆角 | 各 1 处 |

> ⚠ `Widget.MockGPS.Segmented` 刻意**不覆盖** `backgroundTint` 与 `strokeColor`：
> M3 的 `OutlinedButton` 自带「选中/未选中」状态选择器，覆盖掉会丢失选中态。只调几何与字号。

### 5.2 备用（已定义、当前无引用，属组件库预留）

`Widget.MockGPS.Card.Solid`、`Widget.MockGPS.Surface.Glass(.Solid)`、
`Widget.MockGPS.IconButton(.Secondary/.Tertiary)`、`Widget.MockGPS.Pill.Status.Ok/.Warn`、
`ShapeAppearanceOverlay.MockGPS.Card`、`TextAppearance.MockGPS.{TitleLarge,TitleMedium,Body,Caption}`。

新增界面时优先复用它们，不要另起一套。

---

## 6. 玻璃实现 `GlassBackdrop`

玻璃卡片 = **卡片底色 + 一层淡主色染色**，两层都走主题令牌，不依赖任何第三方库。

> ⚠️ **v1.20 整段重写**。旧实现是「抓地图快照 → 存成 1/4 位图 → 内嵌 ImageView +
> `RenderEffect` 模糊」，已被删除，因为它在真机上爆出三个硬伤：
>
> 1. **白算**：染色内容其实是「纯白 + 纯主色」两层 `SRC_OVER` **纯色填充** ——
>    对纯色做高斯模糊在视觉上等价于同一个纯色。整套 bitmap + RenderEffect + 400ms 定时器
>    没有任何可见效果，还常驻一张 ARGB_8888 位图。
> 2. **卡片被改成全透明**：染色时那句 `card.setCardBackgroundColor(0x00000000)` 把样式里
>    配好的 `?attr/colorGlassSurface` 覆盖掉了，卡片可见遮罩只剩染色图的 30% 白。
>    真机表现就是用户报的**「选点 / 轨迹的窗口过于透明」**。
> 3. **卡片再也缩不回去**：染色载体是内嵌 `ImageView`，要铺满卡片只能用 `match_parent`；
>    而 `wrap_content` 容器里的 `match_parent` 子 View 会被量成"父可用高度"，把卡片顶成满屏。
>    为了压住它，代码把 ImageView 的 `LayoutParams` **钉死**成当时的高宽 —— 一旦钉死，
>    卡片高度就丧失了收缩能力。这是**「点折叠箭头只隐藏了按钮、窗口没缩小」**的根因。

### 6.1 现在的实现（两层，都极轻）

| 层 | 载体 | 由谁控制 |
|---|---|---|
| 卡片底色（**遮罩主体**） | `MaterialCardView` 的 `cardBackgroundColor` | `?attr/colorGlassSurface` ← 「卡片不透明度」滑杆（s50~s95，默认 **s80**） |
| 主色色感（**辅层**） | 内容容器的 `background`（一个 `GradientDrawable`） | `?attr/colorPrimary`，alpha 8%；方案 B「动态染色」开启时 18% |

**为什么染色走 `background` 而不是 ImageView**：`background` drawable **完全不参与测量**，
卡片高度永远由内容决定，折叠 / 展开自然生效。

### 6.2 挂载契约

布局里必须提供一个容器承接染色（`fragment_map.xml` / `fragment_track.xml` 是范例）：

```xml
<com.google.android.material.card.MaterialCardView ...>
  <!-- 容器就用玻璃卡片内那层 FrameLayout，id 固定为 glass_backdrop -->
  <FrameLayout android:id="@+id/glass_backdrop"
               android:layout_width="match_parent" android:layout_height="wrap_content">
    <LinearLayout ...> 正常内容 </LinearLayout>
  </FrameLayout>
</com.google.android.material.card.MaterialCardView>
```

`BaseMapFragment.setupGlass(View)` 会自动：找 `@id/glass_backdrop` → 向上找最近的
`MaterialCardView` → `GlassBackdrop.attach(card, host)`。

- 容器**不要**再设 `android:background`，会被染色覆盖。
- 它没有定时器 / 回调 / 生命周期钩子：`apply()` 把颜色写进 View 之后就没有别的事了。
  Fragment **不需要**在 `onResume` / `onPause` / `onDestroyView` 做任何配套调用
  （旧版的 `start()` / `stop()` / `release()` / `invalidate()` 已随重写一并删除）。

### 6.3 三条实现注意

- **`colorGlassSurface` 是遮罩主体，任何情况下都不能把它覆盖成透明**（旧版就死在这）。
- **染色层必须不参与测量**。要加视觉层优先用 `background` / `foreground`；
  不要往 `wrap_content` 容器里塞 `match_parent` 的子 View，更不要"为了压住它"去
  钉死 `LayoutParams` —— 那会让卡片永远无法收缩。
- **任何一步失败都静默降级**：`attach()` 返回 null，卡片退回纯 `cardBackgroundColor`
  外观。宁可不好看，不能崩、也不要黑块。

> 两个玻璃开关令牌的消费链（改令牌前先确认没断）：
> `booleanGlassDynamicTint` → `GlassBackdrop.apply()`（方案 B 开启，主色占比 8% → 18%）；
> `booleanGlassSpecular` → `ThemeManager.applySpecularHighlight()`（作用于**普通容器**，不是 CardView）。

---

## 6.5 地图超限层级缩放 `ScalingTilesOverlay`

高德矢量 / 卫星瓦片**真实只提供到 z18**（`BaseMapFragment.AMAP_REAL_MAX_Z`），
但地图允许用户放大到 z22（`TemplateTileSource.DECLARED_MAX_Z`）。
z19~z22 由 `ScalingTilesOverlay` 取 z18 祖先瓦片的对应 `1/2^k` 区域裁剪后再放大填格 ——
线条变粗、像素变糊，但**路网位置准确、不重复**（不是把整张 z18 图铺满每一格）。

### 铁律：**超限层级绝不请求该层级的瓦片**

`mTileProvider.getMapTile(index)` 里的 `index` 一旦指向 z19+，而源只到 z18：

1. 拿不到真瓦片，**只会得到占位图**；
2. 失败的瓦片**不进缓存**，于是**每一帧都会重新请求一次**。

表现就是「放大后地图不停闪烁并变全白」。v1.20 的兜底分支正是踩了这个坑。

### 现在的策略（`onHandleTile` 的超限分支）

1. 从 `realMaxZ` 开始试 —— 用 z18 瓦片裁剪缩放；
2. 拿不到就往**更低层级**回退，最多 `MAX_FALLBACK = 3` 级（z17 / z16 / z15）——
   低层级瓦片基本都在缓存里（用户是一路放大上来的），用它顶上能避免大片空白；
3. 拿到源瓦片后**先拷贝一份存进自有缓存**（`mSourceCache`，LRU 16 张），再裁剪绘制；
   后续帧直接命中自己的副本，不再问 osmdroid；
4. 全都没就绪 → **这一格留空**，下一帧再画。宁可短暂空白，也不要闪白。

判断"是否拿到了真瓦片"靠 `asBitmap()`：**只有 `BitmapDrawable` 且位图未被回收才算数**；
拿到 loading 占位一律按"没就绪"处理，绝不能直接 `onTileReadyToDraw` 画出去（那会画出灰白块）。

### 为什么必须自带一份副本缓存（治"闪烁"的关键）

只做上面 1~3 条是不够的。`mTileProvider.getMapTile()` 返回的是 osmdroid 的
`ReusableBitmapDrawable`，**受内存 LRU 与 BitmapPool 管理，随时可能被驱逐或回收**。
一旦某一帧拿不到有效位图，这一格就只能留空；而 `MapView` 每帧会**先铺一层背景色**再画瓦片
—— 屏幕上于是出现"**一闪一闪的白**"。
（真机反馈：放大到比例尺 ≤ 60m 时开始闪，那个分界对应 z20 附近，远超 z18。）

把成功取到的源瓦片 `copy()` 一份留在自己手里之后：**只要某个源瓦片成功取到过一次，
之后每一帧都命中本地副本**，完全不受 osmdroid 缓存策略影响。这是这个问题的根治点。

代价与配套：

- 最多 16 张 × 256KB ≈ **4MB** 内存；z22 时屏幕总共只需 2~6 张，绰绰有余；
- **切换地图源时必须 `clearSourceCache()`** —— 同一个瓦片索引在「高德矢量」和「卫星影像」下
  是完全不同的图，不清就会串图（`BaseMapFragment.switchTileSource()` 已接好）；
- `onDetach()` 里清空并 `recycle()`，别把 4MB 位图留在已废弃的 overlay 上。

---

## 7. 图标规范

### 7.1 颜色：资源里烤死，不要靠 tint 兜底

所有 vector 图标内部一律写 `@color/icon_primary` / `@color/icon_secondary` /
`@color/icon_tertiary`（**带 `#FF` 前缀的标准色值**，不要写 `#1B1B1F` 这种 6 位形式，
避免某些渲染路径把 alpha 当成 0）。

这样即使 tint 因任何原因没生效，图标也不会变成黑块，而是退回一个合理的语义色。

### 7.2 着色：布局用 `app:tint`，样式用 `android:tint`

两条规则不要混：

- **布局 XML 里写 `app:tint`**。lint 的 `UseAppTint` 会把 `android:tint` 报成 error；
  而且 `<ImageView>` / `<ImageButton>` 在 `AppCompatActivity` 下会被 AppCompat 的 viewInflater
  替换成 `AppCompatImageView` / `AppCompatImageButton`，只有它们才读 `app:tint`。
  → **前提是承载它的 Activity 必须是 `AppCompatActivity`。**
- **共享 style 里写 `android:tint`**。`<style>` 的 item 名不带命名空间前缀，`app:tint` 根本写不进去；
  而且 style 可能被套到非 AppCompat 的视图上（比如代码里 `new` 出来的 ImageView），
  `android:tint` 由 framework ImageView 自 API 21 起直接支持，任何场合都生效。

两者最终效果一致，且都有「退回烤入色」的兜底。

### 7.3 描边是编译期的

`strokeWidth` 固化在 vector 资源里。需要不同粗细就**另做一套图标**，不要指望换 dimen。

### 7.4 启动图标

- 自适应图标：`mipmap-anydpi-v26/ic_launcher.xml` → `@drawable/ic_launcher_foreground`
  + `@color/ic_launcher_background`。
- `ic_launcher_foreground.xml` 用 108dp 画布 + 24 单位 viewport，针形压到 15.75 单位塞进中央安全区，
  只用 `@color/brand_primary` / `@color/white`。
- **启动图标里绝对不能用 `?attr/...`**：launcher 进程不加载本 App 的主题，attr 取不到值。
- ⚠ **已知遗留项**：`mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` 仍是历史位图，
  **API < 26 的机器上会显示旧图标**。改动 `ic_launcher_foreground.xml` 后需要同步重新导出这些 PNG。

---

## 8. 动效 `Motion`

```java
Motion.press(view);          // 按下 0.96 缩放 + 抬起回弹（90ms / 140ms）
Motion.rise(view);           // 入场浮起
Motion.rise(view, delayMs);  // 带延迟
Motion.haptic(view);         // 触感反馈
```

- 所有方法都会先读 `integerMotionLevel`：**level < 2 时 `rise()` 直接 return**（不设 alpha），
  所以调用方不需要自己判断等级。
- `haptic()` 只在 `level >= 3` 且用户在设置里开了触感时才震。
- `press()` 用 `setOnTouchListener` 但**返回 false**，不吞掉点击事件。

---

## 9. 原设计包已修复的 4 个「硬伤」（改版时不要再犯）

| 编号 | 问题 | 修法 |
|---|---|---|
| **A1** | 三套方案渲染结果完全相同：`colorGlassSurface` 只在基础主题赋过值，三套 `Scheme` 没各自赋值；同时声明了 4 处 `integerGlassAlpha` 却无人读取 | 三套 Scheme **各自**赋 `colorGlassSurface`（A=s70/B=s60/C=s85）；透明度改用 `glass_surface_sNN` 颜色斜坡；删除 `integerGlassAlpha` |
| **A2** | `dimenIconStroke` 对 VectorDrawable 无效（描边是编译期固化的） | 删除该属性；需要不同粗细时另做一套图标资源 |
| **A3** | `Accent.*` 只换 `colorPrimary`，导致卡片/开关/选中态/状态栏之间串色 | 每个预设**成套覆盖 8 项**（primary / onPrimary / container / onContainer / secondary*） |
| **A4** | launcher 前景写死 `#1976D2`，且设计包根本没提供前景资源 | 用 `ic_pin` 的几何重做前景，只用 `@color/` 资源；`?attr` 禁用于启动图标 |
| **A 级** | `Widget.MockGPS.Card.Glass` 同时用 `android:background` 与 `cardBackgroundColor`，两套背景机制互撞 | 重定义为 parent `Widget.Material3.CardView.Filled`，只用 `card*` 属性；内高光改由 `ThemeManager.applySpecularHighlight()` 补在**普通容器**上 |

判定这类问题的方法很统一：**看「配置项」和「消费点」之间的链路是不是断的**。
配置了却没人读、或有两套机制同时管同一件事，就是硬伤。

---

## 9.5 切页 / 生命周期安全（轨迹回调）

这一段是拿真机崩溃换来的，改这块代码前务必读完。

### 背景：轨迹 tick 与 Fragment 生命周期**不同步**

`MainActivity` 的 `tick`（每秒一次）挂在 **Activity** 上，与 Fragment 无关。
`ViewPager2` + `offscreenPageLimit = 1` 下，切页会**销毁离屏页的 Fragment 视图**，
但 tick 照跑 —— 于是回调会打到"视图已经没了"的 Fragment 上。

**典型崩溃**（真机栈）：

```
PolyOverlayWithIW.setPoints(PolyOverlayWithIW.java:379)   ← mOutline 已是 null
TrackFragment.redrawTrail(TrackFragment.java:309)
MainActivity.notifyTrailChanged
MainActivity$1.run                                        ← 每秒的 tick
```

`MapView.onDetach()` 会把 `Polyline` 内部的 `LinearRing` 清空，而**重绘回调仍持有这个
已经"半死"的对象**；`setPoints()` 一调就 NPE。

### 五条必须同时做到的防御

1. **回调里先查视图是否还在**：`if (!isAdded() || getView() == null) return;`
   光判 `mapView == null` / `trailLine == null` **不够** —— 字段可能还指着那个被 detach 过的对象。
2. **`onDestroyView` 移除监听器不要用 `isAdded()` 当条件**。
   ViewPager2 销毁离屏页时 Fragment 可能已经 detached，`isAdded()` 为 false，
   那样**整段移除会被安静地跳过**（最阴的一处 bug）。用 `getActivity()` 判空。
3. **`onDestroyView` 里先把自建图层从 `mapView.getOverlays()` 摘掉，再清字段**。
   只清字段会把对象留在列表里，让 `onDetach()` 去清理"我们已经不再持有的东西"。
4. **写点必须自愈**（`TrackFragment.writePoints()` / `PickFragment.redrawPickTrail()`）：
   `PolyOverlayWithIW.setPoints()` 内部直接调 `mOutline.setPoints()`，**没有任何 null 检查**
   （用 `javap` 反编译 6.1.18 确认）；而 `mOutline` 全类只在 `onDetach()` 里被置空
   （`usePath()` 是重建），偏偏它是 `protected`、跨包探测不到。
   所以每次写点都要：
   ```java
   try { line.setPoints(pts); }
   catch (NullPointerException broken) { /* 摘掉这个半死对象 → 重建 → 重写 */ }
   ```
   **靠推演时序是堵不完的，自愈才可靠。**
5. **`MainActivity.notifyTrailChanged()` 对每个 listener 单独 try/catch**（最后一道防线）。
   漏掉上面任何一条，也只是少画一条线、多打一行 log，**不应该闪退**。

> 新增任何「长生命周期回调 → 短生命周期视图」的链路（定时器、定位回调、网络回调）时，
> 都按这五条来。

### 附带：新建 MapView 后必须自己确定中心

`onCreateView` 每次都 inflate 出**新的** `MapView`，而**它的中心默认是世界原点 (0,0)**
—— 那是几内亚湾的海面，屏幕上一片空白，用户看到的就是「**地图是白的**」。
`setZoom()` 并不会改变这一点。

所以 `setupMap` 末尾必须按优先级摆一次中心：
**① 上次记下的中心**（按子类类名缓存在静态表里，切页 / 转屏重建后仍能找回、不跳位置）
→ **② 已选坐标** → **③ 真实位置**。

注意 `centerOnRealIfIdle()` 只在「没有已选坐标」时才动作，指望它兜底是不够的。

### 附带：坐标不许出现 NaN

`Double.parseDouble("NaN")` **不抛异常**，而 `NaN` 参与的任何 `<` / `>` 比较都是 `false`
——**它能绕过全部范围校验**。曾经的表现是主页经纬度显示 `NaN`，而且会自我循环：

```
state.lat = NaN → fmt() 渲染成字符串 "NaN" 写进输入框
              → afterTextChanged 把它 parse 回 NaN → state.set(NaN) → …
```

所以坐标链路上要**三处都挡**：`MainActivity.fmt()`（非有限值返回空串）、
输入同步 `syncStateFromInput()`（`Double.isFinite` 校验）、
以及最终的 `SharedState.set()`（非有限值直接拒绝，这是根，前两处是防线）。

#### 还有第二层：`valid` 是独立字段，**绝不能直接赋**

上面三处挡的是"从输入进来"的 NaN。还有一条更隐蔽的路径：

```java
// startTrack() 里曾经这么写 —— 已经删掉
state.valid = true;      // 而 lat/lng 还是初始的 Double.NaN！
```

`SharedState` 的 `valid` / `lat` / `lng` 是三个**互相独立**的字段，直接赋 `valid = true`
并不会同步坐标。后果链：轨迹跑起来后点「清空」→ `isRunning()` 变 false →
tick 落到 `else if (state.valid)` 分支 → **把 NaN 推给系统定位**。

**判断"能不能拿去用"统一用 `state.isUsable()`**（`valid && 两个分量都 isFinite`）。

#### 为什么 NaN 推进系统定位会崩（vivo 特有，很坑）

```
java.lang.IllegalArgumentException: For input string: "NaN"
    at java.lang.Integer.parseInt
    at android.location.VIVORsaForLocation.checkEncrypt
    at android.location.VivoLocationImpl.encryptLocation
    at android.location.Location.toString
```

vivo 定制 ROM 在 `setTestProviderLocation` 的链路里会对 `Location` 做加密，
内部把字段转成字符串再 `Integer.parseInt` —— 拿到 `"NaN"` 直接抛
（而且是**在 system_server 里抛**，应用侧只收到 `RemoteException`，看起来莫名其妙）。

**关键**：`Location.toString()` 会遍历**所有**字段，所以不只是 lat/lng，
`accuracy` / `speed` / `bearing` 是 NaN 一样会踩到。

因此 `pushLocation()` / `pushTrackPoint()` 里对每个字段都做了兜底（`safeFloat()`）
—— 这是最后一道防线：**宁可这一帧不推，也不能崩**。

---

## 10. 新增一个界面 / 按钮的标准做法

1. **不要写死任何颜色和圆角**。颜色取 `?attr/colorOnSurface` 一类 M3 角色，
   圆角取 `?attr/dimenRadiusCard` / `dimenRadiusButton`，间距取 `?attr/dimenCardPadding`。
2. **卡片直接用 `<com.google.android.material.card.MaterialCardView>`**，什么都不用指定
   —— `materialCardViewStyle` 钩子已经把它变成玻璃卡片。要加玻璃染色就按 6.2 的契约
   给内层 `FrameLayout` 加 `android:id="@+id/glass_backdrop"`，`BaseMapFragment` 会自动挂上
   （不需要在 Fragment 里手动调 `setupGlass`，它由 `setupMap` 统一调用）。
3. **按钮从 5.1 的表里挑**：主操作用 Primary、次要用 Ghost、强调用 Tonal、
   破坏性用 Danger。都自带 `dimenRadiusButton` 圆角与 `button_height` 高度。
4. **图标用 `<ImageView>` / `<ImageButton>` + `app:tint="?attr/colorIconXxx"`**，
   `src` 指向 `res/drawable/` 里已烤色的图标。
5. **要加动效就调 `Motion.press()` / `rise()`**，不要自己写 `ViewPropertyAnimator` ——
   `Motion` 已经处理了动效等级和触感开关。
6. **新增 Activity**：
   - 必须 `extends AppCompatActivity`（否则 `app:tint` 与动态主题都不会生效）；
   - 在 Manifest 里给一个继承 `Theme.MockGPS` 的主题；
   - `onCreate` 按 4.1 的顺序调 `ThemeManager.setupActivity` / `applyWindowColors`；
   - 若要能在外观设置改动后刷新，按 4.1 实现 `onResume` 的 `generation` 比对 + `recreate()`。
7. **跑完校验和编译**（见第 11 节）。

---

## 11. 构建与验证

工程路径含中文（`C:\Users\冷月街\...`），**AGP 拒绝在非 ASCII 路径下构建**，所以要先镜像到
纯 ASCII 路径再构建。仓库根目录的 `tools/` 已经封装好：

```bash
# 编译（自动同步到 C:/mgbuild/MockGPS 再构建）
bash tools/build_mockgps.sh assembleDebug

# 一次跑多个任务
bash tools/build_mockgps.sh assembleDebug lintDebug

# 静态校验（不需要 Gradle，秒级）
python tools/verify_mockgps.py
```

`tools/verify_mockgps.py` 有 7 项检查，**必须全绿**：

| # | 检查 | 通过线 |
|---|---|---|
| 1 | XML 良构 | 0 失败 |
| 2 | 资源引用悬空 | 0 处 |
| 3 | 主题属性：已声明 / 已赋默认值 / 未声明就用 | 24 / 0 / 0 |
| 4 | 旧色值残留（`#1976D2` / `#3D7BF5` / `#8A8B9A`） | 0 处 |
| 5 | Manifest 引用的主题存在 | 是 |
| 6 | 空转属性（声明了没人读） | 0 个 |
| 7 | 死令牌（白名单外的无引用 dimen/color） | 0 个 |

### 已知的 lint 例外（`lintDebug` 已 0 error）

代码里有 3 处显式 `@SuppressLint`，都是**有依据的抑制**，不要随手删：

- `BaseMapFragment.bestRealLocation()` / `registerRealUpdates()` 的 `MissingPermission`
  —— 两处都被 `hasLocationPermission()` 把关且包了 `try/catch(Exception)`，
  lint 只认 `ContextCompat.checkSelfPermission` 那几种固定写法，看不懂本工程自己的辅助方法。
- `MainActivity.ensureProvider()` 的 `WrongConstant`
  —— lint 认为 `addTestProvider` 只接受 `ProviderProperties.*`，而 AOSP 文档给的
  可选值恰恰是 `Criteria.*`。两者数值完全一致（已用 `javap -constants` 核对 android-34 的
  `android.jar`：`Criteria.POWER_LOW = 1 = ProviderProperties.POWER_USAGE_LOW`，
  `Criteria.ACCURACY_FINE = 1 = ProviderProperties.ACCURACY_FINE`）。**这是 lint 误报。**
- `AndroidManifest.xml` 里 `ACCESS_MOCK_LOCATION` 的 `ProtectedPermissions`
  —— 该权限是 signature 级，普通应用永远拿不到运行时授权，**这正是本 App 需要在
  开发者选项里手动选中的原因**；声明它的唯一目的是让系统把包名列进模拟位置应用列表。
