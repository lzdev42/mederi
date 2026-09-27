# 02 · 组件层（按钮 / 卡片 / 输入控件）

> 本文件定义 Mederi UI 的可复用组件规格。所有颜色**只能引用** `01-tokens.md` 的语义别名，禁止写裸 hex（品牌专属渐变除外，已标注）。每类组件先一张规格表，再给一个 Compose 代码骨架，骨架颜色读 `LocalMederiColors.current`，对齐现有 atom 命名（`MederiIconButton` / `PanelCard` 等，见 `app/shared/.../ui/components/atoms/`）。

## 1. 按钮（Button Variants）

### 1.1 Primary Decision（soft iris）`btn-primary-decision`

| 属性 | 值 |
|---|---|
| 尺寸 | 高 28dp |
| padding | 0 / 12dp |
| 圆角 | control 6dp |
| 字号 | 12sp / 500 |
| gap | 5dp |
| bg | `accent-bg`（iris-3） |
| text | `accent-text`（iris-11） |
| border | 1dp iris-6 |
| hover | bg iris-4 / border iris-7 / text iris-12 |
| active | bg iris-5 |
| disabled | bg `surface-2` / text gray-9 / 透明边框 / `enabled=false` |

用于 Proceed / 确认问答 / Compact 计划。

```kotlin
@Composable
fun MederiPrimaryDecisionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalMederiColors.current
    // hover/active 用 interactionSource + animateColorAsState；此处简化
    Surface(
        modifier = modifier.height(28.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) c.accentBg else c.surface2,
        border = BorderStroke(1.dp, if (enabled) Color(0xFF3C3673) else Color.Transparent),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(text, color = if (enabled) c.accentText else c.textMuted,
                fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
```

### 1.2 Surface（raised secondary）`rt-variant-surface` / `btn-review-diff`

| 属性 | 值 |
|---|---|
| 尺寸 | 高 24dp |
| padding | 0 / 8dp |
| 圆角 | 6dp |
| 字号 | 11sp / 500 |
| gap | 4dp |
| bg | `surface-1`（gray-2） |
| border | 1dp `border`（gray-6） |
| text | `text-primary`（gray-12） |
| hover | bg `surface-hover`（gray-3）/ border `border-strong`（gray-7） |

```kotlin
@Composable
fun MederiSurfaceButton(text: String, icon: ImageVector?, onClick: () -> Unit) {
    val c = LocalMederiColors.current
    Surface(
        modifier = Modifier.height(24.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = c.surface1, border = BorderStroke(1.dp, c.border),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(12.dp), tint = c.textPrimary)
            Text(text, color = c.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
```

### 1.3 Ghost（borderless）`rt-variant-ghost`

| 属性 | 值 |
|---|---|
| bg | 透明 |
| border | 1dp 透明 |
| text | `text-secondary`（gray-11） |
| 圆角 | 6dp |
| hover | bg `surface-hover`（gray-3）/ text `text-primary` |

用于 footer 文字按钮。

### 1.4 Compact Stroke `btn-compact-stroke`

| 属性 | 值 |
|---|---|
| padding | 2 / 8dp |
| 圆角 | 6dp |
| 字号 | 11sp / 500 |
| bg | `surface-hover`（gray-3） |
| border | 1dp `border`（gray-6） |
| text | `text-primary`（gray-12） |
| hover | text `text-primary` / border `border-strong` / bg gray-4 |

> ⚠ **裁定**：原型 hover 文字色硬编码为 `#ffffff`，dark 下尚可、light 下错误。本标准裁定 hover 文字色为 `text-primary`，而非裸白。

### 1.5 Minimal Icon `icon-btn-minimal`

| 属性 | 值 |
|---|---|
| 尺寸 | 28 × 28dp |
| 圆角 | 4dp（原型 4dp，可接受） |
| bg | 透明 |
| text/icon | `text-secondary`（gray-11） |
| hover | bg `surface-hover`（gray-3）/ text `text-primary` |
| transition | 120ms |

> 现有 `MederiIconButton` 默认 28dp + CircleShape；此变体建议加 `shape` 参数支持 4dp 方角。

### 1.6 Icon Square `icon-square-btn`

| 属性 | 值 |
|---|---|
| 尺寸 | 28 × 28dp |
| 圆角 | 6dp |
| bg | `surface-hover`（gray-3） |
| border | 1dp `border`（gray-6） |
| text/icon | gray-11 |
| hover | text `text-primary` / border `border-strong` / bg gray-4 |

### 1.7 Panel Header Icon `panel-header-btn`

| 属性 | 值 |
|---|---|
| 尺寸 | 22 × 22dp |
| 圆角 | 4dp |
| bg | 透明 |
| text/icon | `text-muted` |
| hover | bg gray-4 / text `text-primary` |
| 旋转 | 展开时 chevron rotate 180°（0.8s） |

### 1.8 Send Round `send-round-btn`

| 属性 | 值 |
|---|---|
| 尺寸 | 28 × 28dp |
| 圆角 | 6dp（原型非圆形） |
| bg | `bg-inverted`（gray-12，反色控件专用） |
| text/icon | `on-inverted`（gray-1） |
| border | 无 |
| hover | bg 更亮（dark: `#FFFFFF`；light: `gray-12`） |
| active | scale 0.96 |

> ⚠ **裁定修正**：send 按钮是「反色灰控件」——dark 下 bg 近白、text 近黑、hover 更亮。这和 accent 紫按钮语义不同，**不能用 `on-accent`**（`on-accent` dark = gray-1 深色，会让 hover 变暗，与原型相反）。本标准单列 `bg-inverted` / `on-inverted` 一对 token（见 `01-tokens.md` §1.2）；hover 取更亮一档：dark 用纯白 `#FFFFFF`，light 用 `gray-12`。

### 1.9 Sidebar Menu Item（作控件）`sidebar-menu-item`

| 属性 | 值 |
|---|---|
| 尺寸 | 高 36dp |
| padding | 0 / 12dp |
| 圆角 | 6dp |
| 字号 | 13.5sp / 450 |
| gap | 10dp |
| icon | 16dp，stroke 1.8 |
| text | `text-secondary`（gray-11） |
| hover | bg gray-3 / text `text-primary` |
| active | bg `accent-bg`（iris-3）/ text `accent-text`（iris-11）/ border 1dp iris-6 / weight 500 |

> ⚠ **裁定**：原型 active 态加 1dp border 但未预留空间 → 激活时 1px 抖动。本标准要求**常态即预留 1dp 透明 border**，active 时 border 变 iris-6，杜绝抖动。

```kotlin
@Composable
fun MederiSidebarMenuItem(
    icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit,
) {
    val c = LocalMederiColors.current
    val borderColor = if (selected) Color(0xFF3C3673) else Color.Transparent // 常驻预留
    Surface(
        modifier = Modifier.height(36.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) c.accentBg else Color.Unspecified,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(16.dp),
                tint = if (selected) c.accentText else c.textSecondary)
            Text(label,
                color = if (selected) c.accentText else c.textSecondary,
                fontSize = 13.5.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        }
    }
}
```

---

## 2. 卡片（Card Types）

### 2.1 Chat Input Card `chat-input-card`

| 属性 | 值 |
|---|---|
| bg | `surface-1`（gray-2） |
| border | 1dp `border` |
| 圆角 | 8dp |
| overflow | hidden |
| focus-within | border iris-7 + 1dp iris-7 ring |
| 内部 | textarea（min-h 48 / max-h 160dp，13.5sp / lh 20dp，placeholder `text-secondary`）+ controls row（8/12dp，gap6） |

### 2.2 User Bubble `user-bubble`

| 属性 | 值 |
|---|---|
| bg | `surface-hover`（gray-3） |
| border | 1dp `border`（gray-6） |
| 圆角 | **12 / 12 / 2 / 12dp**（特殊） |
| padding | 10 / 14dp |
| 字号 | 13.5sp / lh 1.6 / `text-primary` |
| footer | 10.5sp / `text-muted`（时间 + edit + copy） |

```kotlin
// 圆角顺序 T/R/B/L
Surface(
    shape = RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp),
    color = c.surfaceHover, border = BorderStroke(1.dp, c.border),
) { Column(Modifier.padding(10.dp, 14.dp)) { Text(text, color = c.textPrimary, fontSize = 13.5.sp) } }
```

### 2.3 Work Trace `work-trace-*`

聚合栏 + 可展开：

| 部分 | 规格 |
|---|---|
| summary bar | activity icon 13dp `accent`；title 12.5sp/600 `text-secondary`；chevron 11dp；hover bg gray-3 |
| 展开容器 | 1.5dp 左 rail（`border-strong`）；内部 scroll max-h 320dp；gap 8dp |
| reasoning-bar | 2/4dp；4dp；2dp 左 rail；label「已思考」12sp/500 + text 12.5sp/450 `text-secondary` |
| step-narration | 12sp italic `text-muted` |
| tool-terminal-box | `bg-code`；1dp `border`；6dp；8/12dp；mono 11.5sp / lh 16dp；max-h 280dp scroll |
| report-box | `surface-1`；1dp `border`；6dp；10/14dp；12sp / lh 1.6 |
| footer toggle | 「展开全部步骤」11sp/500 `text-muted`；1dp `border` 顶分割 |

### 2.4 Question Block `question`

| 属性 | 值 |
|---|---|
| 边框 | 无；2dp 左 `iris-8` rail |
| header | 10sp/600 uppercase |
| options | radio rows（13dp radio，accent=iris-9） |

### 2.5 Plan Approval Block `plan-approval-block`

| 属性 | 值 |
|---|---|
| 边框 | 无；2dp 左 `border`（gray-6） hairline |
| title | 12.5sp/500（hover→`accent-text`） |
| plan-id-tag | mono 10sp chip |
| summary | 12sp / lh 1.55 `text-secondary` |
| 主操作 | primary-decision 按钮（§1.1） |

### 2.6 Turn Diff Block `turn-diff`

| 属性 | 值 |
|---|---|
| 壳 | 扁平无壳 |
| title | 11.5sp/500 |
| 按钮 | `btn-review-diff`（24dp surface 变体，§1.2） |
| file rows | mono 11sp；3/6dp；6dp；hover bg gray-3；add `green-11` / del `red-11` weight 500 |

### 2.7 Document Artifact Row `document-artifact-card`

| 属性 | 值 |
|---|---|
| 形态 | chip 非 card |
| padding | 4 / 10dp |
| 圆角 | 6dp |
| bg | `surface-1`（gray-2） |
| border | 1dp `border`（gray-6） |
| hover | border iris-7 + bg gray-3 |
| 内容 | icon `accent-text` / title 12sp/500 `text-primary` / sub 11sp `text-secondary` / trailing capsule 11sp `accent-text`→iris-12 hover |

### 2.8 Tokens Card `tokens-card`

| 属性 | 值 |
|---|---|
| padding | 14 / 16dp |
| gap | 10dp |
| title | 13sp/500 `text-secondary` + Compact stroke 按钮 |
| 大值 | 24sp/500 ls −0.02em `text-primary` |
| sub | 11.5sp `text-secondary` |
| progress | 4dp track（gray-4，2dp）+ `accent`（iris-9）fill |

### 2.9 Metric Card `usage-col-card`

| 属性 | 值 |
|---|---|
| padding | 12 / 14dp |
| 圆角 | 6dp |
| bg | `surface-1`（gray-2） |
| border | 1dp `border`（gray-6） |
| title | 11.5sp `text-secondary` |
| value | 20sp/500 lh 1.2 `text-primary` |
| sub | 11sp `text-secondary` |

### 2.10 Panel Card `panel-card`

| 部分 | 规格 |
|---|---|
| shell | `surface-1`（gray-2）/ 1dp `border`（gray-6）/ 8dp / overflow hidden |
| hover | border→`border-strong`（gray-7） |
| header | 10/12dp；hover bg gray-3；左 13sp/600 + count 11sp/500 `accent-text` + 可选 running badge；右 22dp icon btns + 14dp chevron（展开 rotate180） |
| body（折叠） | 8/12/10/12dp；gap6；顶 1dp `border`；bg=`bg` |
| inner rows | subagent/mcp/skill：bg gray-2 / 1dp `border` / 6dp / padding 8-7/10dp |

> 现有 `PanelCard` / `CardHeader` atom 已对齐此结构。

### 2.11 Raw Messages Accordion `raw-messages-*`

| 部分 | 规格 |
|---|---|
| shell | gray-2 / gray-6 / 8dp |
| collapsed | 「共 N 条 ›」12/16dp；12.5sp/500 `text-secondary`；hover gray-3 |
| 展开 body | 4/12/12dp；item 40dp 行 |
| JSON pre | mono 11.5sp / lh 1.5 |

### 2.12 Diff Viewer `diff-viewer-box`

| 属性 | 值 |
|---|---|
| bg | `bg-code` |
| border | 1dp `border` |
| 圆角 | 6dp |
| 字体 | mono 11sp / lh 18dp |
| line-num col | 38dp；`surface-hover`（gray-3）bg；右 border；0.8 opacity |
| add 行 | bg `success-bg`（green-3）/ text `green-11` |
| del 行 | bg `danger-bg`（red-3）/ text `red-11` |
| file-selector | tabs 在上（见 §1 状态） |

### 2.13 File Viewer

| 部分 | 规格 |
|---|---|
| tab bar | 36dp（`bg-card`，底 border）+ 1dp `border` |
| meta bar | 6/12dp mono 11sp |
| code body | 14dp；mono 12sp / lh 20dp；line-num 36dp @ 0.5 opacity |
| markdown mode | 16dp padding；13.5sp / lh 1.7；h2 16sp `accent` |

---

## 3. 输入与控件（Inputs & Controls）

### 3.1 Textarea `chat-input-textarea`

| 属性 | 值 |
|---|---|
| 边框 | 无（内嵌卡内） |
| 字号 | 13.5sp / lh 20dp / `text-primary` |
| placeholder | `text-secondary` |
| auto-grow | 48→160dp |

### 3.2 Tree Filter Input `tree-search-input`

| 属性 | 值 |
|---|---|
| bg | `bg-card` |
| border | 1dp `border` |
| 圆角 | 4dp |
| padding | 4 / 8dp |
| 字号 | 11sp |
| placeholder | `text-muted` |

### 3.3 Selector Pill `selector-pill`

| 属性 | 值 |
|---|---|
| 尺寸 | 高 26dp |
| padding | 0 / 8dp |
| 圆角 | 6dp |
| bg | `surface-hover`（gray-3） |
| border | 1dp `border`（gray-6） |
| 字号 | 11.5sp/500 `text-primary` |
| gap | 6dp |
| hover | border `border-strong` + bg gray-4 |

### 3.4 Switch `auto-approve` / `mcp-toggle`

| 属性 | 值 |
|---|---|
| track | 28 × 16dp；圆角 10dp |
| ON | `accent`（iris-9） |
| OFF | `border`（gray-6） |
| thumb | 12 × 12dp；`on-accent`（裁定：原型 white，统一用 on-accent/on-surface） |
| thumb inset | 2dp |
| transition | 140ms |

```kotlin
@Composable
fun MederiSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = LocalMederiColors.current
    val track = if (checked) c.accent else c.border
    val thumbColor = if (checked) c.onAccent else c.textPrimary
    // track 28x16, thumb 12x12, animate thumb x over 140ms
}
```

### 3.5 Radio

| 属性 | 值 |
|---|---|
| 尺寸 | 13 × 13dp |
| accent-color | `accent`（iris-9） |

### 3.6 Chips / Badges

| 控件 | padding | 圆角 | bg | text |
|---|---|---|---|---|
| `tab-badge` | 1/5dp | 10dp | `bg-input`+border | 10sp `text-muted` |
| `running-pulse-badge` | 1/6dp | 10dp | `accent-bg`/border iris-6 | `accent-text` 10sp/500 + 6dp pulse dot iris-9 ring iris-5 1.5s |
| `subagent-role-tag` | 1/5dp | 3dp | gray-4 | `text-primary` 10sp/500 |
| `skill-compat-badge` | 1/4dp | 3dp | amber-3/amber-6 | amber-11 9sp/600 |
| `header-project-pill` | 4/10dp | 6dp | gray-3+border | `text-secondary` 12sp |
| `plan-id-tag` | 1/5dp | 3dp | gray-3+border | mono 10sp `text-secondary` |

### 3.7 Status Dots

| 控件 | 规格 |
|---|---|
| `conv-status-dot` | 6dp `accent`（idle→gray-8） |
| `step-dot-active` | 8dp solid `accent` |
| `step-dot-todo` | 7dp hollow，1.5dp gray-8 ring |
| `step-dot-done` | 13dp check，14×14 `green-11` |

### 3.8 Status Text

| 状态 | 颜色 |
|---|---|
| IDLE | `text-muted` |
| 待批准 / PENDING | `warning` |
| ONLINE | `success` |

### 3.9 File Type Icon Squares

| 属性 | 值 |
|---|---|
| 尺寸 | 14 × 14dp |
| 圆角 | 3dp |
| 字体 | mono 8–8.5sp/700 |
| 文字色 | 白 |
| Kt | 渐变 `#7F52FF`→`#C711E1` |
| MD | iris-3 / iris-6 / iris-11 |
| Gradle | green-3 / green-6 / green-11 |

> ⚠ **裁定**：Kt 渐变属品牌专属，保留为单独 token `file-icon-kt-gradient`，标注为「已知硬编码」——这是唯一允许的裸 hex 例外。

### 3.10 Git / Change Badges

| 属性 | 值 |
|---|---|
| 字体 | mono 10sp/600 |
| padding | 0 / 4dp |
| 圆角 | 3dp |
| M | `amber-11` on `amber-3` |
| A / U | `green-11` on `green-3` |
| 树内 modified 标签 | text `amber-11` |
