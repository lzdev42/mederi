# 03 · 状态 / 实现约定 / 迁移 / 速查

> 本文件是 Mederi UI 的**约定层**：状态模式规则、Compose 实现约定、GitHub-dark → Radix 迁移注记、取值速查。
> 色值一律引用 `01-tokens.md` 的语义别名 / 基础色阶 token，不自创；组件规格见 `02-components.md`。
> 输入依据：原型 `docs/design/mederi_ui_prototype.html` 报告 §8/§9/§10、现有 `theme/Theme.kt`（`MederiColors` 32 字段）调研报告。

## 1. 状态模式规则表（State Patterns）

总表：状态 → 表达方式（行 / 卡 / icon-btn / accent-bordered 行）+ Compose 落地提示。颜色全部用 01 的别名 / 色阶 token。

| 状态 | 行 / 列表项 | 卡（card） | icon 按钮 | accent-bordered 行（artifact / subagent） | Compose 提示 |
|---|---|---|---|---|---|
| **Hover**（1.1） | bg → `bg-hover`（gray-3）；text → `text-primary`（gray-12） | 只改 border → `border-strong`（gray-7），不改 bg | bg → `bg-hover`（gray-3） | border → `iris-7` + bg → `bg-hover` | `pointerInput` hoverState / `Modifier.background(anim)`；卡 hover 只动 border 色 |
| **Active / Selected**（1.2，两族） | ① **iris 族**：bg → `accent-bg`（iris-3）+ 可选 1dp `iris-6` border + text → `accent-text`（iris-11）；适用 sidebar-menu-item / question-option / dock-btn / diff-file-tab / tree-row（tree-row 另加左侧 2dp accent bar）。② **neutral 族**：bg → `bg-hover`（gray-3）+ text → `text-primary` + 字重 400→500；适用 conv-item。③ **tabs**：text → `accent`（iris-9）+ weight 500（现有 600 按 §2.7 降级）+ 2dp 底 underline（subtab）或 bg → `bg-workspace` + border（viewer tab） | — | — | — | `isSelected` 参数驱动；tree-row 的 2dp accent bar 用 `Modifier.border(2.dp, ...)` 左侧 |
| **Pressed**（1.3） | bg → gray-4 / gray-5（按压加深一档） | — | soft button → `iris-5` | — | `pressable` / `interactionSource`；send 圆钮 active 时 `Modifier.graphicsLayer(scaleX = 0.96f, scaleY = 0.96f)` |
| **Disabled**（1.4） | bg → `surface-2`（gray-3）；text → gray-9；border 透明；cursor not-allowed | 同左 | 同左 | 同左 | `enabled = false` + `alpha` 或显式 `disabledColors`；禁止自造灰色 hex，用 gray-3/gray-9 |
| **Focus**（1.5） | — | 输入卡：border → `iris-7` + 1dp `iris-7` 外 ring（**非** iris-8） | — | — | `Modifier.focusBorder(...)` 或 `Modifier.border(1.dp, iris7)`；ring 用 `Modifier.border(1.dp, iris7)` 包一层 1dp padding 模拟 |
| **Running / Live**（1.6） | 6dp 脉冲点：`iris-9` 实心点 + glow ring `0 0 0 2dp iris-5`；1.5s pulse（scale 1↔1.3，opacity 1↔0.6） | running badge（`running-pulse-badge`）同上 | — | subagent 行 running 态 = 20dp status circle（`accent-bg` 底 + 脉冲点） | 复用现有 `WorkingAnimations.kt`（BorderBeam / ShimmerWave / BreathingPulse）；ring 用 `Modifier.drawBehind` 画 2dp 圈 |
| **Done**（1.7） | check 13px `green-11` + 删除线 + text → gray-10 | — | — | — | `textDecoration = TextDecoration.LineThrough`；check 用 `success-text`（=green-11） |
| **Modified**（1.8） | text → `amber-11`；或 amber badge（`warning-text` 字 on `warning-bg` 底 + `border`） | — | — | — | badge 见 §4 Chip/Badge 规格；modified 行 label 用 `warning-text` |
| **Chevron 方向**（1.9） | 折叠 → 右；展开 → `rotate(90°)`（卡片类 `rotate(180°)`），0.15–0.2s | 同左 | 同左 | — | `animateRotationAsState` 或 `graphicsLayer(rotationZ)`，150–200ms |
| **Transition 时长**（1.10） | rows / buttons / switches：120ms | card borders：140ms | 同 rows | 同 rows | toggles / spin 0.1–0.8s；脉冲 infinite；Compose 统一 `AnimationSpec` 常量 |

## 2. Compose 实现约定（Implementation Conventions）

### 2.1 Token 驱动（颜色）

- 所有颜色**统一读 `LocalMederiColors.current`**（现有机制，`theme/Theme.kt` 安装的 `staticCompositionLocalOf`；组件可接收可选 `colors: MederiColors` 参数，默认取 `LocalMederiColors.current`）。
- **禁止** hex 字面量进 `ui/`（2026-09「hex 硬编码收口」已清理，新代码维持此不变量）。
- **禁止**组件直读 `MaterialTheme.colorScheme`：M3 `light/darkColorScheme` 只是 `AppTheme` 里做的 **derivative 映射**（给 M3 组件兜底），组件必须绕过 scheme 直读 `MederiColors`，不允许绕过 `MederiColors` 又回去读 scheme。

### 2.2 Atoms 位置

- 可复用原子（通用）：`app/shared/src/commonMain/kotlin/xyz/mederi/ui/components/atoms/`（现有：`MederiIconButton` / `CopyButton` / `PanelCard` / `Dialogs.kt`（MederiDialog 等）/ `ExpandableRow` / `MederiMarkdown` / `WorkingAnimations`）。
- settings 专用原子：`ui/settings/SettingsAtoms.kt`。
- 新**通用** atom（如 Chip / Badge，目前尚无）进 `atoms/` 包；settings 专用才进 `SettingsAtoms.kt`。

### 2.3 i18n

- 所有用户可见文案走 composeResources：`values/`（中文）+ `values-en/`（英文）两份，新增文案必须同时进两份（AGENTS.md §5）；Composable 内禁止硬编码用户可见文案。
- 内容性数据（如 `AgentOption.name`、模型名）例外，不进 i18n。

### 2.4 M3 映射

- `AppTheme` 把 `MederiColors` → `light/darkColorScheme`，**仅作 derivative**（组件不消费 scheme，见 2.1）。
- `LocalCodeTheme`（ink 主题）随 `isDark` 切：dark = `OneDarkProTheme`，light = `GithubLightTheme`（即 github 风格明色主题）。
- Markdown 渲染走 inkcompose `RenderStyle.Chat` / `RenderStyle.Github`，排版/配色由 inkcompose 自有字体与主题管，不在本标准管辖。

### 2.5 命名

- 语义别名一律用 **surface / accent / text** 前缀（`surface-hover`、`accent-bg`、`text-secondary` 等，01 已定）。
- Composable 用 **`Mederi` 前缀**：`MederiIconButton` / `PanelCard`（面板卡族）/ `MederiDialog`。
- 未来 token 对象命名 **`MederiSpacing` / `MederiRadius` / `MederiTypeScale`**，避免与现有 panel-local `ProviderTokens`（`ui/settings/ProviderTokens.kt`）撞名；`ProviderTokens` 后续提升为全局或并入（见 §3.4⑥）。

### 2.6 新组件流程

写新 UI 前依次：① 读 `01-tokens.md` 取值；② 查 `02-components.md` 找组件规格；③ 遵本文件状态规则（§1）与约定（§2）；④ 文案进 i18n（§2.3）；⑤ 自测 dark / light 两套。

### 2.7 字重

- 仅 **Regular（400）/ Medium（500）** 两档。
- **禁止** `SemiBold`（600）/ `Bold`（700）；现有组件残留的 `SemiBold`/`Bold`（如 SettingsSection 16sp Bold、PanelCard CardHeader 13sp SemiBold）属技术债，迁移时降级为 `Medium`（见 §3.4④）。
- 原型 CSS 的 450 字重在 Compose 无对应，近似 `400`。

### 2.8 等宽（mono）

- 只在**代码块 / 终端 / 路径 / 模型名 / JSON** 用 mono 字体（`FontFamily.Monospace`）。
- 不进卡片标题、过渡语等 prose 场景。

## 3. 迁移注记（Migration Notes）

> 迁移方向：现有 `MederiColors`（GitHub-dark，github 暗色取值）→ 01 的 Radix 色阶 + 语义别名。本节只记录**裁定与映射**，代码落地是 §3.4 的后续任务（待起计划，非本次执行）。

### 3.1 GitHub-dark → Radix 字段级映射表（全 32 字段）

现有 `MederiColors`（`theme/Theme.kt`）逐字段映射。目标值取自 `01-tokens.md` §1.2/§1.3。

| 现有字段 | 现有 dark | 现有 light | 目标语义别名 | 目标 dark（Radix） | 目标 light（Radix） |
|---|---|---|---|---|---|
| `surfaceSidebar` | `#090C10` | `#F3F4F6` | `bg-sidebar` | `gray-1` `#111113` | `gray-2` `#f9f9fb` |
| `surfaceWorkspace` | `#0D1117` | `#FAFAFA` | `bg-workspace` | `gray-1` `#111113` | `#ffffff` |
| `surfaceCard` | `#161B22` | `#FFFFFF` | `bg-card` | `gray-2` `#18181a` | `#ffffff` |
| `surfaceCardBorder` | `#21262D` | `#E5E7EB` | `border` | `gray-6` `#3a3a40` | `#d9d9df` |
| `surfaceHover` | `#1C2128` | `#ECEEF1` | `bg-hover` / `bg-card-hover` | `gray-3` `#222225` | `#f0f0f3` |
| `surfaceInput` | `#0D1117` | `#F3F4F6` | `bg-input` | `gray-2` `#18181a` | `#ffffff` |
| `surfaceOverlay` | `0x99000000` | `0x40000000` | —（遮罩 scrim） | `0x99000000` 保留 | `0x40000000` 保留（半透明黑角色，待迁移裁定） |
| `surfaceCode` | `#0D1117` | `#F6F8FA` | `bg-code` | `#131316`（待 token 化） | `#f3f3f6`（待 token 化） |
| `onSurfaceCode` | `#C9D1D9` | `#24292F` | `text-secondary`（裁定） | `gray-11` `#a09fa6` | `#646470` |
| `textPrimary` | `#E6EDF3` | `#1F2328` | `text-primary` | `gray-12` `#ededef` | `#18181b` |
| `textSecondary` | `#8B949E` | `#656D76` | `text-secondary` | `gray-11` `#a09fa6` | `#646470` |
| `textMuted` | `#6E7681` | `#8C959F` | `text-muted` | `#74737d` | `#8c8b94` |
| `accentPrimary` | `#5E6AD2` | `#4C5CD6` | `accent` | `iris-9` `#5b5bd6` | `#5b5bd6` |
| `onAccentPrimary` | `#F0F6FC` | `#FFFFFF` | `on-accent` | `gray-1` `#111113` | `#ffffff` |
| `accentSecondary` | `#56B4E9` | `#0969DA` | —（Okabe-Ito sky） | 保留角色待裁定 | 保留角色待裁定 |
| `accentDanger` | `#E5534B` | `#CF222E` | `danger` | `red-9` `#e5484d` | `#e5484d` |
| `accentWarning` | `#E69F00` | `#9A6700` | `warning` | `amber-9` `#ffb224` | `#e59300` |
| `accentSuccess` | `#009E73` | `#1A7F37` | `success` | `green-9` `#30a46c` | `#2d9d64` |
| `statusWorking` | `#F59E0B` | 同值 | —（Okabe-Ito） | 保留角色待裁定（建议归并 `warning`） | 同左 |
| `statusWaiting` | `#10B981` | 同值 | —（Okabe-Ito） | 保留角色待裁定（建议归并 `success`） | 同左 |
| `statusIdle` | `#38BDF8` | 同值 | —（Okabe-Ito） | 保留角色待裁定（建议归并 `text-muted`） | 同左 |
| `statusError` | `#EF4444` | 同值 | —（Okabe-Ito） | 保留角色待裁定（建议归并 `danger`） | 同左 |
| `buttonSecondary` | `#21262D` | `#EAECEF` | `surface-1`（按钮次级底） | `gray-2` `#18181a` | `#ffffff` |
| `onButtonSecondary` | `#C9D1D9` | `#24292F` | `text-primary`（次级按钮文字） | `gray-12` `#ededef` | `#18181b` |
| `iconMuted` | `#7D8590` | `#8C959F` | `text-muted` | `#74737d` | `#8c8b94` |
| `divider` | `#21262D` | `#D0D7DE` | `border` | `gray-6` `#3a3a40` | `#d9d9df` |
| `thoughtAccent` | `#9D86E9` | `#7C3AED` | — | 保留角色待裁定 | 同左 |
| `thoughtBackground` | `#1E212B` | `#F1F3F5` | — | 保留角色待裁定 | 同左 |
| `thoughtBorder` | `#2E3240` | `#E2E5E9` | — | 保留角色待裁定 | 同左 |
| `thoughtText` | `#E2E8F0` | `#1F2328` | — | 保留角色待裁定 | 同左 |
| `userBubbleBackground` | `0x385E6AD2` | `0x1F4C5CD6` | `bg-hover`（裁定：原型 user-bubble 用 gray-3 实底） | `gray-3` `#222225` | `#f0f0f3` |
| `userBubbleBorder` | `0x805E6AD2` | `0x594C5CD6` | `border`（裁定：原型用 gray-6 实线） | `gray-6` `#3a3a40` | `#d9d9df` |

> 无 1:1 对应的字段（`statusWorking` / `statusWaiting` / `statusIdle` / `statusError` 为 Okabe-Ito、`thought*`、`userBubble*`、`accentSecondary`、`surfaceOverlay`）一律标注「保留角色待裁定」，迁移时由实施计划决定归并或新增别名，本标准不预先臆断。

### 3.2 GLASS_* 已移除（2026-09-27 完成）

玻璃主题（`GLASS_DARK` / `GLASS_LIGHT`）已废弃（01 §1.5），只保留 Dark / Light 两套。**代码移除已完成**（随未提交 WIP 一并落地，grep 无残留引用）：

- ✅ `AppThemeMode.GLASS_DARK` / `GLASS_LIGHT` 枚举值 —— 已删（`AppThemeMode` 仅 DARK/LIGHT，`isGlass` 参数已删）
- ✅ `GlassDarkColors` / `GlassLightColors` 调色板 —— 已删
- ✅ `AppThemeMode.isGlass` 字段 —— 已删
- ✅ `theme/GlassAmbientBackground.kt` —— 文件已删
- ✅ `AppState` 中 glass 相关持久化（`app.theme` 取值域）—— `fromString` 不再映射 GLASS；未知值回落 DARK
- ✅ `App.kt` 中 glass 分支 —— 已删（`AppEnvironment { MainScreen() }`）
- ✅ `AppThemeMode.fromString` 的 `GLASS` / `FROST_*` 遗留映射 —— 已删（`CATPPUCCIN_*` 等 light 遗留别名保留作迁移兼容）
- ✅ `GeneralSettingsPanel.kt` 主题卡玻璃选项 / 预览分支 / `theme_glass_*` i18n —— 已删
- ✅ `Sidebar.kt` 主题循环 GLASS 分支 —— 已删（DARK↔LIGHT）

### 3.3 原型 10 处不一致裁定表

取自原型报告 §10，逐条裁定（裁定结果即标准，进 01/02 消费）：

| # | 原型问题 | 裁定 |
|---|---|---|
| 1 | `--text-code` 消费但未定义 | = `text-secondary`（代码正文用次要文字色 + mono 字体） |
| 2 | `--accent-danger` 消费但未定义 | = `danger`（= `red-9`） |
| 3 | `.panel-icon-btn` 无 CSS 规则 | 复用 minimal-icon 按钮规格（`icon-btn-minimal`） |
| 4 | `.btn-primary-decision.high-contrast` 死开关（JS 有、CSS 无） | 移除，不实现 high-contrast 变体 |
| 5 | `@keyframes blink` 被引用未声明 | 补 blink：终端光标 1s 闪烁 |
| 6 | Inter 已加载但未进 `--font-sans` | **不引 Web 字体，走系统默认**（KMP 优先，01 §2.1） |
| 7 | 硬编码 hex（muted / code-bg / 语法色 / 白黑 send hover / kt 渐变） | 全部 token 化；`file-icon-kt-gradient`（`#7F52FF→#C711E1`）标注**品牌专属**允许保留 |
| 8 | `btn-compact-stroke:hover` text 硬编码 `#ffffff` | 改 `text-primary` |
| 9 | `send-btn` hover 硬编码 `#ffffff` / `#000000` | 用 `on-accent`（随主题翻转） |
| 10 | `sidebar-menu-item.active` 加 1px border 导致 1px 布局抖动 | **常态预留 1dp 透明 border**（active 时换成 `iris-6`，不占位差） |

### 3.4 后续代码任务清单（衔接，待起计划，非本次执行）

1. 新建 `MederiSpacing` / `MederiRadius` / `MederiTypeScale` 全局 token 对象（命名见 §2.5）。 —— ✅ 已存在（`xyz.mederi.theme`，settings 等已消费）
2. `MederiColors` 迁值到 Radix + 补新字段（`accent-hover` / `accent-bg` / `accent-text` / `on-accent` / `success-bg` 等，见 01 §1.2 别名层）。 —— ✅ 已完成（本计划 S0：32 字段按 01 §1.4 迁值 + 14 个语义别名字段）
3. ~~删 GLASS_*~~ —— ✅ 已完成（§3.2）。
4. 现有组件字重 `SemiBold`/`Bold` 降级为 `Medium`（§2.7）。 —— 对话流组件已随 S2-S4 降级，其余区域待办
5. Chip / Badge 原子化进 `ui/components/atoms/`（§2.2）。
6. `ProviderTokens` 提升为全局 token 或并入 `MederiSpacing`/`MederiRadius`，消除 panel-local 取值。

## 4. Compose 取值速查（Quick Reference）

> 可直接粘贴。dark 一套为示例；light 套见 `01-tokens.md` §1.2 / §1.6。

### 4.1 Color

```kotlin
// Compose 取值速查 · Color（dark 套，Color(0xFFRRGGBB)；light 套见 01-tokens.md）
val Surface0       = Color(0xFF111113) // bg = gray-1
val Surface1       = Color(0xFF18181A) // surface-1 = gray-2
val Surface2       = Color(0xFF222225) // surface-2 = gray-3
val BorderHairline = Color(0xFF3A3A40) // border = gray-6
val BorderStrong   = Color(0xFF484851) // border-strong = gray-7
val TextPrimary    = Color(0xFFEDEDEF) // text-primary = gray-12
val TextSecondary  = Color(0xFFA09FA6) // text-secondary = gray-11
val TextMuted      = Color(0xFF74737D) // text-muted（硬编码值）
val Accent         = Color(0xFF5B5BD6) // accent = iris-9
val AccentHover    = Color(0xFF534BC6) // accent-hover = iris-10
val AccentBg       = Color(0xFF201E39) // accent-bg = iris-3
val AccentText     = Color(0xFFA39CF4) // accent-text = iris-11（dark）
val OnAccent       = Color(0xFF111113) // on-accent = gray-1（light 套 = #ffffff）
val Success        = Color(0xFF30A46C) // success = green-9
val Warning        = Color(0xFFFFB224) // warning = amber-9
val Danger         = Color(0xFFE5484D) // danger = red-9
```

### 4.2 Shape

```kotlin
// Compose 取值速查 · Shape
val CardRadius    = 8.dp   // card（panel-card / chat-input-card / tokens-card）
val ControlRadius = 6.dp   // control（按钮 / 输入 / pill / switch）
val BadgeRadius   = 3.dp   // badge（tag / chip / status badge）
val PillRadius    = 10.dp  // pill（= track 高一半：tab-badge / running-pulse / switch-track）
val BubbleRadius  = 12.dp  // bubble（仅 user-bubble：RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp)）
val Circle        = CircleShape // status dots
// 无 elevation 阴影；ring 仅 focus（1dp iris-7）与 pulse glow（2dp iris-5）
```

### 4.3 Spacing

```kotlin
// Compose 取值速查 · Spacing（未来收进 MederiSpacing）
val XS  = 4.dp
val SM  = 8.dp
val MD  = 12.dp
val LG  = 16.dp
val XL  = 20.dp
val XXL = 24.dp
// 微值 2 / 3 / 5 / 6 / 10 / 14.dp 按需（仅限密集行：badge、tag、icon 行）
```

### 4.4 TypeScale

```kotlin
// Compose 取值速查 · TypeScale（仅 Regular 400 + Medium 500 两档；CSS 450 近似 400）
val Caption     = TextStyle(fontSize = 10.sp,   fontWeight = FontWeight.Medium)
val Micro       = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Normal)
val Label       = TextStyle(fontSize = 11.sp,   fontWeight = FontWeight.Normal)
val BodyCompact = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal)
val Body        = TextStyle(fontSize = 12.sp,   fontWeight = FontWeight.Normal)
val Row         = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal)
val Title       = TextStyle(fontSize = 13.sp,   fontWeight = FontWeight.Medium)
val Section     = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal)
val Header      = TextStyle(fontSize = 15.sp,   fontWeight = FontWeight.Medium)
val H2          = TextStyle(fontSize = 16.sp,   fontWeight = FontWeight.Medium)
val Metric      = TextStyle(fontSize = 20.sp,   fontWeight = FontWeight.Medium)
val MetricLg    = TextStyle(fontSize = 24.sp,   fontWeight = FontWeight.Medium)
```
