# 01 · 设计 Token 层

> 本文件是 Mederi UI 的**取值真理源**。配色、排版、间距、圆角的精确值都在这里，组件层（`02-components.md`）与约定层（`03-conventions.md`）只允许引用本文件的语义别名，不得自创色值。
>
> 色值取自 `docs/design/mederi_ui_prototype.html` 的 `:root` 两个块（Radix Themes 色阶）。所有 hex 逐字照原型，不自创。

## 1. 配色

配色分两层：**基础色阶**（唯一真理源，Radix 12 步 gray/iris + 状态色步阶）与**语义别名层**（组件唯一允许消费的层）。组件不直接读基础色阶，只读语义别名。

### 1.1 基础色阶（唯一真理源）

#### Gray（中性，Radix 命名）

| token | dark | light | 用途 |
|---|---|---|---|
| `gray-1` | `#111113` | `#fcfcfd` | 应用背景 |
| `gray-2` | `#18181a` | `#f9f9fb` | 次级背景（卡片、侧栏 subtle） |
| `gray-3` | `#222225` | `#f0f0f3` | UI 元素背景（hover、代码、输入） |
| `gray-4` | `#2a2a2e` | `#e8e8ec` | hovered UI 元素背景 |
| `gray-5` | `#313136` | `#e0e0e6` | active / pressed 元素背景 |
| `gray-6` | `#3a3a40` | `#d9d9df` | 细边框 / 分割线（hairline） |
| `gray-7` | `#484851` | `#ceced6` | UI 元素边框 / focus ring |
| `gray-8` | `#60606b` | `#bbbbc4` | hovered UI 元素边框 |
| `gray-9` | `#6e6d7a` | `#8d8d99` | 实色背景 |
| `gray-10` | `#7b7a87` | `#83838f` | hovered 实色 |
| `gray-11` | `#a09fa6` | `#646470` | 低对比文字（muted、元数据） |
| `gray-12` | `#ededef` | `#18181b` | 高对比文字（primary readable） |

#### Iris（品牌强调色，Radix 命名）

| token | dark | light | 用途 |
|---|---|---|---|
| `iris-1` | `#13131e` | `#fdfdff` | 最深 |
| `iris-2` | `#171625` | `#f8f8ff` | — |
| `iris-3` | `#201e39` | `#f0f0ff` | 软按钮背景（accent-bg） |
| `iris-4` | `#272449` | `#e6e5ff` | hovered 软按钮 |
| `iris-5` | `#302c5b` | `#dad9ff` | active 软按钮 |
| `iris-6` | `#3c3673` | `#cbcafe` | 细 accent 边框 |
| `iris-7` | `#4e459c` | `#b8b6fc` | accent 边框 / focus ring |
| `iris-8` | `#6357c7` | `#9f9cf9` | focus ring（声明值，实际 focus 用 iris-7） |
| `iris-9` | `#5b5bd6` | `#5b5bd6` | 实色 accent / 主按钮（**两主题同值**） |
| `iris-10` | `#534bc6` | `#514ec7` | hovered 实色 |
| `iris-11` | `#a39cf4` | `#534bc6` | dark: 低对比 accent 文字；light: 高对比 accent 文字 |
| `iris-12` | `#e0ddfe` | `#272368` | dark: 高对比 accent 文字；light: 深邃高贵紫 |

> ⚠ light 主题下 `iris-11` 与 `iris-12` 的语义反转：light 的 accent 文字用更深的 `#534bc6`，hover 强调用 `#272368`。

#### 状态色步阶（仅定义 3 / 6 / 9 / 11）

| token | dark | light |
|---|---|---|
| `green-3` | `#13271f` | `#e6f6eb` |
| `green-6` | `#1f4f3a` | `#b4dfc4` |
| `green-9` | `#30a46c` | `#2d9d64` |
| `green-11` | `#55cf8d` | `#218358` |
| `amber-3` | `#2a1f0a` | `#fef3d6` |
| `amber-6` | `#583d0c` | `#f5d990` |
| `amber-9` | `#ffb224` | `#e59300` |
| `amber-11` | `#ffca16` | `#9b5a00` |
| `red-3` | `#291415` | `#feecee` |
| `red-6` | `#5c2023` | `#f9c6cb` |
| `red-9` | `#e5484d` | `#e5484d`（**两主题同值**） |
| `red-11` | `#ff8589` | `#ce2c31` |

### 1.2 语义别名层（组件唯一允许消费）

| 语义别名 | dark 指向 | light 指向 |
|---|---|---|
| `bg`（= surface-0） | `gray-1` `#111113` | `gray-1` `#fcfcfd` |
| `surface-1` | `gray-2` `#18181a` | `#ffffff`（硬编码，如实记录） |
| `surface-2` | `gray-3` `#222225` | `gray-2` `#f9f9fb` |
| `border` | `gray-6` | `gray-6` |
| `border-strong` | `gray-7` | `gray-7` |
| `text-primary` | `gray-12` | `gray-12` |
| `text-secondary` | `gray-11` | `gray-11` |
| `text-muted` | `#74737d`（硬编码） | `#8c8b94`（硬编码） |
| `accent` | `iris-9` | `iris-9` |
| `accent-hover` | `iris-10` | `iris-10` |
| `accent-bg` | `iris-3` | `iris-3` |
| `accent-text` | `iris-11` | `iris-11` |
| `on-accent` | `gray-1`（accent 实色上的文字） | `#ffffff` |
| `bg-inverted` | `gray-12`（反色灰控件底，如 send 按钮） | `gray-12` |
| `on-inverted` | `gray-1`（反色灰控件上的文字） | `gray-1` |
| `bg-inverted-hover` | `#FFFFFF`（更亮一档） | `gray-12` |
| `success` | `green-9` | `green-9` |
| `success-bg` | `green-3` | `green-3` |
| `success-text` | `green-11` | `green-11` |
| `warning` | `amber-9` | `amber-9` |
| `warning-bg` | `amber-3` | `amber-3` |
| `warning-text` | `amber-11` | `amber-11` |
| `danger` | `red-9` | `red-9` |
| `danger-bg` | `red-3` | `red-3` |
| `danger-text` | `red-11` | `red-11` |

> 消费规则：① 组件只读语义别名，不读基础色阶；② 凡带 `-bg` 后缀的只用于该语义的软底（success-bg / warning-bg / danger-bg / accent-bg）；③ `on-accent` 仅用于 accent 实色（主按钮、send 按钮）之上的文字/图标。

### 1.3 便利别名表

| 便利别名 | dark | light | 说明 |
|---|---|---|---|
| `bg-sidebar` | `bg`（=`gray-1`） | `gray-2` | 侧栏底色 |
| `bg-workspace` | `bg`（=`gray-1`） | `#ffffff` | 工作区底色 |
| `bg-card` | `surface-1`（=`gray-2`） | `#ffffff` | 卡片底色 |
| `bg-card-hover` | `gray-3` | `gray-3` | 卡片 hover 底 |
| `bg-input` | `gray-2` | `#ffffff` | 输入框底 |
| `bg-code` | `#131316`（硬编码，**待 token 化**） | `#f3f3f6`（硬编码，**待 token 化**） | 代码块/终端底 |
| `bg-hover` | `gray-3` | `gray-3` | 通用 hover 底 |
| `bg-active` | `iris-3` | `iris-3` | 选中/激活底 |
| `rail-color` | `gray-6` | `gray-6` | 缩进引导线 |

### 1.4 现有 MederiColors → 新语义别名映射表

现有 `MederiColors`（`app/shared/.../theme/Theme.kt`，32 字段，GitHub-dark 取值）逐字段映射到新语义别名。目标色值取自本文件 §1.1/§1.2（Radix）。

| 现有字段 | 现有 dark / light（GitHub-dark） | → 新语义别名 | 目标 dark / light（Radix） |
|---|---|---|---|
| `surfaceSidebar` | `#090C10` / `#F3F4F6` | `bg-sidebar` | `gray-1 #111113` / `gray-2 #f9f9fb` |
| `surfaceWorkspace` | `#0D1117` / `#FAFAFA` | `bg-workspace` | `gray-1 #111113` / `#ffffff` |
| `surfaceCard` | `#161B22` / `#FFFFFF` | `bg-card` | `gray-2 #18181a` / `#ffffff` |
| `surfaceCardBorder` | `#21262D` / `#E5E7EB` | `border` | `gray-6 #3a3a40` / `#d9d9df` |
| `surfaceHover` | `#1C2128` / `#ECEEF1` | `bg-hover` / `bg-card-hover` | `gray-3 #222225` / `#f0f0f3` |
| `surfaceInput` | `#0D1117` / `#F3F4F6` | `bg-input` | `gray-2 #18181a` / `#ffffff` |
| `surfaceOverlay` | `0x99000000` / `0x40000000` | — | 遮罩 scrim，保留角色（半透明黑），待迁移裁定 |
| `surfaceCode` | `#0D1117` / `#F6F8FA` | `bg-code` | `#131316` / `#f3f3f6`（待 token 化） |
| `onSurfaceCode` | `#C9D1D9` / `#24292F` | `text-secondary`（裁定） | `gray-11 #a09fa6` / `#646470` |
| `textPrimary` | `#E6EDF3` / `#1F2328` | `text-primary` | `gray-12 #ededef` / `#18181b` |
| `textSecondary` | `#8B949E` / `#656D76` | `text-secondary` | `gray-11 #a09fa6` / `#646470` |
| `textMuted` | `#6E7681` / `#8C959F` | `text-muted` | `#74737d` / `#8c8b94` |
| `accentPrimary` | `#5E6AD2` / `#4C5CD6` | `accent` | `iris-9 #5b5bd6` / `#5b5bd6` |
| `onAccentPrimary` | `#F0F6FC` / `#FFFFFF` | `on-accent` | `gray-1 #111113` / `#ffffff` |
| `accentSecondary` | `#56B4E9` / `#0969DA` | — | Okabe-Ito sky，保留语义角色，待迁移裁定 |
| `accentDanger` | `#E5534B` / `#CF222E` | `danger` | `red-9 #e5484d` / `#e5484d` |
| `accentWarning` | `#E69F00` / `#9A6700` | `warning` | `amber-9 #ffb224` / `#e59300` |
| `accentSuccess` | `#009E73` / `#1A7F37` | `success` | `green-9 #30a46c` / `#2d9d64` |
| `statusWorking` | `#F59E0B` | — | Okabe-Ito，建议归并到 `warning`，待裁定 |
| `statusWaiting` | `#10B981` | — | 建议归并到 `success`，待裁定 |
| `statusIdle` | `#38BDF8` | — | 建议归并到 `text-muted`，待裁定 |
| `statusError` | `#EF4444` | — | 建议归并到 `danger`，待裁定 |
| `buttonSecondary` | `#21262D` / `#EAECEF` | `surface-1`（按钮次级底） | `gray-2 #18181a` / `#ffffff` |
| `onButtonSecondary` | `#C9D1D9` / `#24292F` | `text-primary`（次级按钮文字） | `gray-12 #ededef` / `#18181b` |
| `iconMuted` | `#7D8590` / `#8C959F` | `text-muted` | `#74737d` / `#8c8b94` |
| `divider` | `#21262D` / `#D0D7DE` | `border` | `gray-6 #3a3a40` / `#d9d9df` |
| `thoughtAccent` | `#9D86E9` / `#7C3AED` | — | thought 角色保留，待迁移裁定 |
| `thoughtBackground` | `#1E212B` / `#F1F3F5` | — | 待裁定 |
| `thoughtBorder` | `#2E3240` / `#E2E5E9` | — | 待裁定 |
| `thoughtText` | `#E2E8F0` / `#1F2328` | — | 待裁定 |
| `userBubbleBackground` | `0x385E6AD2` / `0x1F4C5CD6` | `bg-hover`（裁定：原型 user-bubble 用 `gray-3` 实底） | `gray-3 #222225` / `#f0f0f3` |
| `userBubbleBorder` | `0x805E6AD2` / `0x594C5CD6` | `border`（裁定：原型用 `gray-6` 实线） | `gray-6 #3a3a40` / `#d9d9df` |

> 无 1:1 对应的字段（`accentSecondary`、`status*`、`thought*`、`userBubble*`、`surfaceOverlay`）标注「保留语义角色，待迁移时裁定」——迁移时由实施计划决定归并或新增别名，本标准不预先臆断。

### 1.5 GLASS_* 废弃声明

> **⚠ 已废弃**：`GLASS_DARK` / `GLASS_LIGHT` 玻璃主题已废弃，将从代码中移除。本标准**只定义 Dark / Light 两套配色**，不覆盖玻璃主题。
>
> 待移除的代码（后续单独任务，不在本次范围）：`AppThemeMode.GLASS_DARK` / `GLASS_LIGHT` 枚举值、`GlassDarkColors` / `GlassLightColors` 调色板、`isGlass` 字段、`GlassAmbientBackground.kt`、`AppState` 中 glass 相关持久化、`App.kt` 对 glass 的分支、`AppThemeMode.fromString` 的 `GLASS` / `CATPPUCCIN_*` 遗留映射。

### 1.6 Compose Color 取值片段（dark 示例）

```kotlin
// app/shared/.../theme/ —— 仅示例，非全量；light 套见上表
val Surface0     = Color(0xFF111113) // bg = gray-1
val Surface1     = Color(0xFF18181A) // gray-2
val Surface2     = Color(0xFF222225) // gray-3
val Border       = Color(0xFF3A3A40) // gray-6
val BorderStrong = Color(0xFF484851) // gray-7
val TextPrimary   = Color(0xFFEDEDEF) // gray-12
val TextSecondary = Color(0xFFA09FA6) // gray-11
val TextMuted     = Color(0xFF74737D)
val Accent        = Color(0xFF5B5BD6) // iris-9
val AccentHover   = Color(0xFF534BC6) // iris-10
val AccentBg      = Color(0xFF201E39) // iris-3
val AccentText    = Color(0xFFA39CF4) // iris-11
val OnAccent      = Color(0xFF111113) // gray-1
val Success       = Color(0xFF30A46C) // green-9
val Warning       = Color(0xFFFFB224) // amber-9
val Danger        = Color(0xFFE5484D) // red-9
```

---

## 2. 排版

### 2.1 字体族

| 用途 | 栈 | 说明 |
|---|---|---|
| `sans` | `-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif` | KMP 走平台默认系统字体 |
| `mono` | `"SF Mono", "JetBrains Mono", Consolas, monospace` | 仅代码块/终端/路径/模型名/JSON |

> **裁定**：原型虽通过 Google Fonts 加载了 Inter，但 `--font-sans` 栈中未含 Inter，实际渲染走系统字体。本标准裁定「不引 Web 字体、走系统默认」（KMP 优先原则，AGENTS.md §2）。inkcompose 内容渲染自带的 KaTeX / NotoSansMongolian 字体不在本标准管辖。

### 2.2 base

| 属性 | 值 | 说明 |
|---|---|---|
| font-size | `13sp` | 基础正文字号 |
| font-weight | `450` | Android Compose 无 450，用 `400`（Regular）近似 |
| line-height | `1.5` | 正文行高 |
| letter-spacing | `−0.015em` | Compose 对 letter-spacing 支持有限，用 `TextUnit`/`TextLineStyle` 近似 |

### 2.3 字号阶梯表

| 档位 | sp | 字重 | 行高 | 颜色别名 | 典型用途 |
|---|---|---|---|---|---|
| `caption` | 10 | 500 | — | `text-muted` / 语义色 | badge、tab-badge、role-tag |
| `micro` | 10.5 | 400 | — | `text-muted` | footer、描述行、时间戳 |
| `label` | 11 | 450–500 | — | `text-secondary` / uppercase | 区块标题、元数据、tree 工具栏 |
| `body-compact` | 11.5 | 450 | 1.5 | `text-secondary` / mono | sub-text、终端 mono、raw-json |
| `body` | 12 | 450 | 1.55–1.6 | `text-primary` / `text-secondary` | 正文、选项、artifact 标题、报告 |
| `row` | 12.5 | 400–500 | — | `text-secondary` / `text-primary` | conv-item、summary bar、plan 标题 |
| `title` | 13 | 500 | — | `text-primary` / `text-secondary` | project-row、card 标题、step in-progress |
| `section` | 13.5 | 450 | 1.6 | `text-primary` | sidebar-menu-item、user-bubble、chat-input |
| `header` | 15 | 500 | — | `text-primary` | workspace 标题 |
| `h2` | 16 | 500 | — | `text-primary` / `accent` | overview 标题、markdown h2 |
| `metric` | 20 | 500 | 1.2 | `text-primary` | 度量数值 |
| `metric-lg` | 24 | 500 | 1.0（ls −0.02em） | `text-primary` | 最大度量值（token 总量） |

### 2.4 字重规则

- **仅允许** `Regular`（400）与 `Medium`（500）两档。
- **禁止** `SemiBold`（600）/ `Bold`（700）：深色底上 Bold 显廉价，是界面「不够高级」的常见原因。
- 现有代码残留的 `SemiBold`/`Bold` 属技术债，迁移时降级为 `Medium`。

### 2.5 行高规则

| 场景 | 行高 |
|---|---|
| 正文 | `1.5` |
| 气泡 / 报告 | `1.6` |
| 长文 / markdown | `1.7` |
| 度量数 | `1.2`–`1.0` |
| 等宽块（固定值） | `16dp` / `18dp` / `20dp` |

### 2.6 Compose 排版片段

```kotlin
// 字重：仅两档
val WRegular = FontWeight.Normal   // 400
val WMedium = FontWeight.Medium   // 500

// 字号阶梯（节选）
val Caption     = TextStyle(fontSize = 10.sp,  fontWeight = WMedium)
val Body        = TextStyle(fontSize = 12.sp,  fontWeight = WRegular)
val Title       = TextStyle(fontSize = 13.sp,  fontWeight = WMedium)
val Section     = TextStyle(fontSize = 13.5.sp, fontWeight = WRegular)
val Header      = TextStyle(fontSize = 15.sp,  fontWeight = WMedium)
val MetricLg    = TextStyle(fontSize = 24.sp,  fontWeight = WMedium, lineHeight = 24.sp)
```

---

## 3. 间距

### 3.1 dp 刻度

```
2 / 3 / 4 / 5 / 6 / 8 / 10 / 12 / 14 / 16 / 20 / 24
```

4pt 网格 + 必要的 `2`/`3`/`5`/`14` 微值。建议**优先**用 `4`/`8`/`12`/`16`/`24`；`2`/`3`/`5`/`6`/`10`/`14` 仅限密集行（badge、tag、icon 行）。

### 3.2 布局常量表

| 常量 | dp | 说明 |
|---|---|---|
| `sidebar` | 245 | 左侧栏宽 |
| `right-dock` | 44 | 右侧折叠 dock 宽 |
| `right-panel` | 410 | 右侧展开面板宽 |
| `content-max` | 820 | 主内容最大宽 |
| `user-bubble-max` | 680 | 用户气泡最大宽 |
| `question` / `plan` / `diff-max` | 560 | 决策类块最大宽 |
| `workspace-header` / `panel-header` | 40 | 头部高 |
| `sidebar-menu-item` | 36 | 侧栏菜单项高 |
| `conv-item` | 32 | 会话项高 |
| `subtab` / `viewer-tab` | 36 | 子标签 / 文件标签高 |
| `tree-node-row` | 25 | 树节点行高 |

### 3.3 组件内边距速查表

| 组件 | padding（T/R/B/L 或 规范） | gap |
|---|---|---|
| chat-scroll-area | 20 / 24 / 10 / 24 | 16 |
| chat-input-textarea | 14 / 16 / 4 / 16 | — |
| chat-input-controls | 8 / 12 | 6 |
| tokens-card / plan-card | 14 / 16 | 10 |
| metric-card（usage-col） | 12 / 14 | 4 |
| panel-card-header | 10 / 12 | 6 |
| panel-card-body | 8 / 12 / 10 / 12 | 6 |
| user-bubble | 10 / 14 | — |
| sidebar | 8 / 10 | — |
| sidebar-menu-item | 0 / 12 | 10（icon-text） |
| conv-item | 0 / 10 | — |
| project-row | 6 / 8 | 8 |
| subagent-task-row | 8 / 10 | 10 |
| mcp / skill row | 7 / 10 | 8 |
| raw-messages-summary | 12 / 16 | 8 |
| raw-messages-body | 4 / 12 / 12 / 12 | 4 |
| inspector（overview） | 20 / 20 / 12 / 20 | 16 |
| subtab-header | 0 / 10 | 14 |
| viewer-tab-bar | 0 / 8 | 4 |

---

## 4. 圆角

### 4.1 radius 刻度

```
2 / 3 / 4 / 6 / 8 / 10 / 12 / full
```

建议归并：

| 角色 | radius | 说明 |
|---|---|---|
| badge | 3 | tag / chip / status badge |
| control | 6 | 按钮 / 输入 / pill / switch |
| card | 8 | 卡片 / 容器 |
| pill | 10 | = track 高一半（tab-badge / running-pulse / switch-track） |
| bubble | 12 | 特殊，仅 user-bubble（`12/12/2/12`） |
| circle | full | status dots |

> 原型散落的 `2`/`4`/`5` 硬编码值裁定归并到 `3` 或 `6`；`5dp` 的 tab/viewer-tab 归并到 `6dp`。

### 4.2 组件 → 圆角映射表

| 圆角 | 组件 |
|---|---|
| `8dp`（card） | chat-input-card、tokens-card、plan-card、panel-card、raw-messages-collapsible、rt-Card |
| `6dp`（control） | 按钮、selector-pill、switch、send-round、icon-square、sidebar-menu-item、conv-item、project-row、file-diff-row、settings-button |
| `3dp`（badge） | plan-id-tag、subagent-role-tag、skill-compat-badge、git/change-badge、file-type-icon |
| `10dp`（pill） | tab-badge、running-pulse-badge、switch-track（=track 高一半） |
| `12dp`（bubble） | user-bubble（`12/12/2/12`） |
| `full` | status dots（conv-status-dot、step-dot-active/todo/done） |

### 4.3 阴影规则

- **不用** elevation 阴影（无 `Modifier.shadow`）。
- 仅保留两种 ring：
  - **focus ring**：输入卡聚焦时 `1dp iris-7` border + `1dp iris-7` 外 ring。
  - **pulse glow ring**：running 状态 `0 0 0 2dp iris-5` 脉冲（1.5s）。
- 其余一律无阴影。

### 4.4 Compose Shape 片段

```kotlin
val BadgeRadius  = 3.dp
val ControlRadius = 6.dp
val CardRadius  = 8.dp
val PillRadius  = 10.dp
val BubbleRadius = 12.dp
// user-bubble 用 RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp)
```
