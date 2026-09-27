# 04 · 全屏布局与交互动画

> 本文件把原型从「组件级」补到「页面级」：全屏布局装配、右面板/dock 切换、滚动行为、展开折叠机制、动画 keyframe、transition 规格、JS 交互逻辑、快捷键。前 3 个文件定义「长什么样」，本文件定义「怎么组装、怎么动」。
>
> 依据：`docs/design/mederi_ui_prototype.html` 的 body 结构（行 2288–3460）与尾部 `<script>`（行 3461–3847），以及 CSS 中段布局/动画规则。

## 1. 全屏布局装配

### 1.1 顶层结构

`body` = `flex` 行容器，`height: 100vh`，`overflow: hidden`。`app-container` 内四区横向排列：

```
┌──────────┬──────────────────────────────┬───────────────┬──────┐
│ sidebar  │       workspace              │ right-panel   │ dock │
│ 245dp    │       flex: 1                │ 410dp         │ 44dp │
│ flex-col │       flex-col               │ flex-col      │ flex │
│ shrink 0 │       min-width:0            │ shrink 0      │ shrink0│
└──────────┴──────────────────────────────┴───────────────┴──────┘
```

| 区 | class | 宽 | flex | shrink | 边界 | 滚动 |
|---|---|---|---|---|---|---|
| 左侧栏 | `.sidebar` | 245dp | column | 0 | 右 1dp `border` | 内部 `.project-tree` overflow-y auto |
| 中央工作区 | `.workspace` | flex:1 | column | — | min-width:0（防溢出挤压） | `.chat-scroll-area` overflow-y auto |
| 右侧面板 | `.right-extension-panel` | 410dp | column | 0 | 左 1dp `border` | `.panel-view-content` overflow-y auto |
| 右侧 dock | `.right-dock` | 44dp | column（居中） | 0 | 左 1dp `border` | 不滚动 |

### 1.2 各区内部装配

**左侧栏**（自顶向下）：
1. `.sidebar-search-row`（40dp，搜索图标按钮）
2. `.fixed-menu-group`（三大固定菜单：新建任务 / 插件市场 / 自动化）
3. `.sidebar-section-header`（「项目」+ 加号按钮）
4. `.project-tree`（flex:1，overflow-y auto，含 project-row + conversation-list）
5. `.sidebar-divider`（1px）
6. `.sidebar-footer`（设置按钮 + 语言/主题 icon-square + 版本号 mono 11sp）

**中央工作区**（自顶向下）：
1. `.workspace-header`（40dp，标题 + project-pill + 右侧 icon 按钮）
2. `.chat-scroll-area`（flex:1，overflow-y auto，padding 20/24/10/24，gap16，`align-items:center`，内含 `.chat-content-container` max-width 820dp gap20）
3. `.chat-input-wrapper`（底部，padding 0/24/20，含 `.chat-input-card`）

**右侧面板**（自顶向下）：
1. `.panel-header`（40dp，左标题+图标，右关闭按钮）
2. `.panel-view-content`（flex:1，overflow-y auto；多个视图通过 `.active` 切换显示）

**右侧 dock**（自顶向下）：6 个 `.dock-btn`（32×32，mb6）+ `.dock-spacer`（flex:1）+ 底部设置 `.dock-btn`。

### 1.3 宽度/高度常量

见 `01-tokens.md` §3.2 布局常量表。补充：`.chat-input-textarea` min-h 48 / max-h 160dp；`.work-trace-scroll-viewport` max-h 320dp（`unbounded` 时 max-h none）；`.tool-terminal-box` max-h 280dp。

## 2. 右面板 / Dock 切换机制

- **dock → 面板**：点 `.dock-btn` → `switchRightPanel(key)`：① 若 `.right-panel` `display:none` 则改 `flex`（瞬切，**无宽度动画**）；② 清所有 dock-btn 的 `.active`，给目标加 `.active`（bg `bg-active`=iris-3，color `accent`）；③ 清所有 `.panel-view-content` 的 `.active`，给目标 `view-{key}` 加 `.active`（display flex）；④ 更新 panel-header 标题文字 + 图标（feather icon 名映射）；⑤ `feather.replace()`。
- **关闭面板**：`.closeRightPanel()` → `.right-panel` `display:none` + 清所有 dock-btn `.active`。
- **dock 按钮顺序**（index）：overview / files / reader / plan / terminal / browser /（spacer）/ settings。默认激活 overview。
- **跨区跳转**：对话流里的 artifact 行 / plan-approval-top / file-diff-row / Spec 链接调 `openFileInViewer()` 或 `switchRightPanel()` 跳到对应面板视图。

> Compose 落地：dock→panel 瞬切可保留（`AnimatedContent` 或直接切 `visible`）；若想要更顺滑，面板宽度可用 `animateDpAsState`，但原型本身不做宽度动画，本标准**不要求**加。

## 3. 滚动行为

| 区域 | overflow | max-height | 策略 |
|---|---|---|---|
| `.project-tree` | y auto | —（填满侧栏剩余） | 常规滚动 |
| `.chat-scroll-area` | y auto | —（flex:1） | 常规滚动，新消息滚动到底 |
| `.panel-view-content` | y auto | —（flex:1） | 常规滚动 |
| `.work-trace-scroll-viewport` | y auto | 320dp（`unbounded` 时 none） | 限高独立滚动 + 底部「展开全部步骤」切换解除限高 |
| `.tool-terminal-box` | y auto | 280dp | 命令输出限高滚动 |
| `.raw-messages-body` | — | —（随展开） | 展开后逐条手风琴 |
| `.diff-viewer-box` | — | — | 固定内容，不独立滚 |
| `.viewer-content-body`（文件代码） | — | — | 跟随 panel-view-content 滚 |

> **无虚拟列表**：原型所有列表（会话、树、原始消息）都是常规 DOM 渲染。Compose 落地时，会话列表/文件树量大建议用 `LazyColumn`，但**视觉行为**保持一致（不引入虚拟化特有的占位/动画）。

## 4. 展开 / 折叠机制（重要）

**原型统一用 `display: none ↔ display: flex/block` 瞬切**，靠 `.expanded` class 控制，**没有** height/max-height transition、没有展开高度动画。只有 chevron 旋转有过渡。

| 组件 | 折叠态 | 展开态（加 `.expanded`） | 触发 |
|---|---|---|---|
| `.work-trace-card` | `.work-trace-expanded-container` display none | display flex | 点 summary-bar |
| `.reasoning-block` | `.reasoning-expanded-content` none | display block | 点 reasoning-bar |
| `.tool-action-group` | `.tool-action-details` none | display flex | 点 tool-action-bar |
| `.tool-output-block` | `.tool-terminal-box` none | display block | 点 output-trigger |
| `.panel-card` | `.panel-card-body` none | display flex | 点 panel-card-header |
| `.raw-messages-collapsible` | `.raw-messages-body` none | display flex | 点 summary-bar |
| `.raw-log-item` | body none | 展开显示 JSON | 点 raw-log-header |
| `.tree-children-container` | `.collapsed` → display none | 去 `.collapsed` → display | 点 tree-node-row |

> **Compose 落地裁定**：原型瞬切在网页上可接受，但 Compose 里建议用 `AnimatedVisibility`（`expandVertically` / `shrinkVertically`，`spring` 或 `tween(120)`）做平滑展开——视觉上更顺且不违背原型意图（原型只是没做，不是禁止）。**保留 chevron 旋转**作为状态指示。若要严格贴合原型，`AnimatedVisibility` 的展开时长用 `~120ms` 与 transition 对齐。

## 5. Chevron 旋转规格

| 组件 | 折叠 | 展开 | 时长 | easing |
|---|---|---|---|---|
| work-trace / reasoning / tool / output / raw-main | `rotate(0)`（指向右） | `rotate(90deg)` | 0.2s | ease |
| panel-card `.panel-chevron` | `rotate(0)`（指向下） | `rotate(180deg)` | 160ms | ease |
| panel-header-btn `.rotating`（刷新） | — | `rotate(0→360)` infinite | 0.8s | linear |

> Compose：chevron 用 `Modifier.rotate(degrees)` + `animateFloatAsState(durationMillis = 200)`。

## 6. 动画 Keyframe

### 6.1 `pulse-glow`（运行中脉冲点）

```css
@keyframes pulse-glow {
  0%, 100% { transform: scale(1);   opacity: 1;   }
  50%      { transform: scale(1.3); opacity: 0.6; }
}
/* .pulse-dot: 6dp, bg iris-9, box-shadow 0 0 0 2dp iris-5, 1.5s infinite ease-in-out */
```

Compose 落地：`rememberInfiniteTransition` + `animateFloat`（scale 1↔1.3、alpha 1↔0.6），`repeatMode = Restart`，1.5s；glow ring 用 `Modifier.drawBehind` 画 2dp iris-5 描边或 `shadow`。对应现有 `WorkingAnimations.kt`。

### 6.2 `panel-spin`（刷新按钮旋转）

```css
@keyframes panel-spin { from { rotate(0) } to { rotate(360deg) } }
/* .panel-header-btn.rotating svg: 0.8s linear infinite */
```

Compose：`rememberInfiniteTransition` + `Modifier.rotate`，0.8s linear。触发 `triggerCardRefresh` 旋转 800ms 后停。

### 6.3 `blink`（终端光标）—— ⚠ 缺失裁定

原型 `.terminal` 光标用 `animation: blink 1s infinite`，但**从未声明 `@keyframes blink`**——光标实际不闪。

> **裁定**：补 `@keyframes blink { 50% { opacity: 0 } }`（1s step），让光标真正闪烁。Compose 用 `rememberInfiniteTransition` + alpha 1↔0。

## 7. Transition 规格

| 对象 | 时长 | easing | 属性 |
|---|---|---|---|
| 行 / 按钮 / switch / sidebar-item / conv-item | 120ms | ease | background-color / border-color / color |
| 卡片边框（rt-Card / panel-card / raw-messages） | 140ms | ease | border-color / background-color |
| chevron（work-trace/reasoning/tool/raw） | 0.2s（200ms） | ease | transform |
| panel-chevron | 160ms | ease | transform |
| dock-btn | 0.15s（150ms） | ease | all |
| mini-switch-thumb | 0.15s | ease | all |
| send-btn transform（active） | 80ms | ease | transform |
| send-btn bg | 120ms | ease | background-color |
| tree-refresh 旋转 | 0.5s | ease | transform（360° 一次性） |
| panel-spin | 0.8s | linear | transform（infinite） |
| pulse-glow | 1.5s | ease-in-out | scale / opacity（infinite） |
| copy 成功回退 | 1500ms | — | 图标切换（无过渡，定时器） |
| 主题切换 | **瞬切**（无过渡） | — | data-theme 切换，CSS 变量即时重算 |

> Compose 落地：用 `animateColorAsState(animationSpec = tween(120))`、`animateFloatAsState`、`Modifier.clickable` 的 interactionSource。主题切换瞬切即可（`MederiColors` 整体替换），如要平滑可给 `AppTheme` 加 `animateColorAsState` 过渡色值。

## 8. JS 交互逻辑要点

| 函数 | 行为 |
|---|---|
| `toggleTheme()` / `setTheme(t)` | 读当前 `data-theme`（默认 dark）→ 翻转；设 `<html data-theme>` + `body.className`；存 `localStorage["mederi_theme"]`；换所有 `.theme-toggle-icon` 的 feather 名（moon/sun）；light 时图标色 iris-9，dark 时 accent-primary；更新按钮 title 提示；`feather.replace()` |
| `switchRightPanel(key)` | 见 §2 |
| `closeRightPanel()` | 面板 display none + 清 dock active |
| `switchFilesSubTab('tree'\|'diff')` | 切项目目录树 / 已修改 diff 两子 tab |
| `toggleFolderNode(id)` | 翻 folder 的 `.collapsed` + chev 的 `.expanded` |
| `selectTreeNode(el)` | 清所有 `.tree-node-row.active`，给当前加 |
| `collapseAllTreeNodes()` | 所有 `.tree-children-container` 加 `.collapsed` + 所有 chev 去 `.expanded` |
| `refreshTree()` | 旋转图标 0.5s 360° 后归零 |
| `filterTreeNodes(q)` | 按标签文本过滤；命中则连父容器一起展开 |
| `switchDiffTarget(file)` | 切 diff-file-tab + 对应 diff-viewer-box 显隐 |
| `openFileInViewer(name,type)` | 跳 reader 面板 + `selectViewerTab` |
| `selectViewerTab(name,type)` | 切 viewer-tab active + 改路径/元数据 + 代码/ markdown 视图显隐 |
| `toggleRawMsg(id)` | 翻 raw-log-item 的 `.expanded` |
| `copyRaw(btn,jsonId)` | 写剪贴板；图标换 check（accent-success）；1.5s 后换回 copy |
| `approvePlan()` | btn disabled + 文本改「Approved」+ alert |
| `toggleSwitch(el)` | thumb 左/右移动 + 底色 accent / border |
| `togglePanelCard(id)` | 翻 `.expanded` |
| `toggleMcpSwitch(e,el)` | 翻 `.off` |
| `triggerCardRefresh(e,btn)` | 加 `.rotating`，800ms 后移除 |
| `toggleRadixButtonVariant()` | ⚠ **死开关**：翻 `.high-contrast` class，但无对应 CSS（见裁定） |

### 裁定

- `toggleRadixButtonVariant` + `.high-contrast`：**移除**，不实现 solid 高对比变体（标准只保留 soft iris 的 primary-decision）。
- `toggleGitBranchDisplay()`：仅演示用（模拟无 Git 状态），非产品交互，标准不收。

## 9. 快捷键

| 快捷键 | 行为 |
|---|---|
| `⌘T` / `Ctrl+T` | 切换深色/浅色主题（`preventDefault`） |
| `⌘↵`（按钮 kbd-hint 标注） | 确认问答 / 批准计划（原型仅显示提示，未实现键盘监听） |
| `⌘P`（tree-filter placeholder 标注） | 聚焦文件过滤（原型仅显示提示，未实现） |

> Compose 落地：`⌘T` 用 `Modifier.onKeyEvent` / 全局键盘监听实现；`⌘↵` / `⌘P` 原型未真正实现，标准**建议补齐**（确认/批准快捷确认、⌘P 聚焦过滤）。

## 10. 响应式

**原型无任何 `@media` 查询**，固定桌面三栏布局，不适配窄屏。

> Compose 落地：桌面/Web 宽屏沿用三栏；移动端（Android/iOS）需另行定义（窄屏时右面板/dock 可改抽屉或底部 tab）。**移动端布局不在本标准范围**，待移动端适配时单独定义。

## 11. 第三方依赖裁定

| 依赖 | 原型用法 | 裁定 |
|---|---|---|
| feather-icons | 几乎所有图标 | Compose 用对应图标库（如 `compose-icons/feather` 或 Material Symbols）；图标尺寸/描边见 `02-components` |
| `@radix-ui/themes` CSS | 外链 + `body` 挂 `radix-themes`/`data-accent-color=iris`/`data-gray-color=slate`/`data-radius=medium` 等 | **不引入** Radix。原型组件全用本地 CSS 变量（已 shadow Radix 同名变量），Radix 实际未提供组件。`data-gray-color=slate` 与 gray 注释「Neutral Base」矛盾——以本地 gray 色阶为准 |
| Google Fonts（Inter + JetBrains Mono） | load 但 Inter 不在 `--font-sans` 栈 | **不引 Web 字体**，走系统默认（KMP 优先，见 `01-tokens` §2.1）。JetBrains Mono 也走系统等宽栈，不打包 |

## 12. Compose 落地建议汇总

| 原型机制 | Compose 实现 | 备注 |
|---|---|---|
| `display: none ↔ flex` 展开折叠 | `AnimatedVisibility`（`expandVertically`/`shrinkVertically`, `tween(120)`） | 比原型更顺，不违背意图 |
| chevron `transform: rotate` + `transition` | `Modifier.rotate` + `animateFloatAsState(200)` | — |
| dock→panel 瞬切 | `AnimatedContent` 或直接切 `visible` | 不要求宽度动画 |
| 主题瞬切 | `MederiColors` 整体替换；可选 `animateColorAsState` 平滑 | — |
| `pulse-glow` keyframe | `rememberInfiniteTransition` + scale/alpha | 对齐 `WorkingAnimations.kt` |
| `panel-spin` keyframe | `rememberInfiniteTransition` + rotate | — |
| `blink` keyframe（缺失） | `rememberInfiniteTransition` + alpha 1↔0（1s） | 补齐 |
| `transition 120ms ease` | `animateColorAsState(tween(120))` | 统一 |
| `box-shadow` focus ring | `Modifier.border(1dp, iris-7)` + 外 ring `drawBehind` | 无 elevation |
| feather icon | compose-icons feather / Material Symbols | 尺寸/描边照 `02-components` |
| 无响应式 | 桌面三栏；移动端待定 | — |
