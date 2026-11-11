# 04 · 全屏布局与交互动画

> 本文件把原型从「组件级」补到「页面级」：全屏布局装配、右面板/dock 切换、滚动行为、展开折叠机制、动画 keyframe、transition 规格、JS 交互逻辑、快捷键。前 3 个文件定义「长什么样」，本文件定义「怎么组装、怎么动」。
>

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

> **当前Compose裁定（2026-10-30）**：展开与箭头统一消费WorkspaceMotionPolicy.disclosureSpec（180ms），不再允许spring或独立120ms；减弱模式Enter/Exit.None，箭头即时切换。

## 5. Chevron 旋转规格

| 组件 | 折叠 | 展开 | 时长 | easing |
|---|---|---|---|---|
| work-trace / reasoning / tool / output / raw-main | `rotate(0)`（指向右） | `rotate(90deg)` | 0.2s | ease |
| panel-card `.panel-chevron` | `rotate(0)`（指向下） | `rotate(180deg)` | 160ms | ease |
| panel-header-btn `.rotating`（刷新） | — | `rotate(0→360)` infinite | 0.8s | linear |

> Compose：chevron 用 `Modifier.rotate(degrees)` + `animateFloatAsState(durationMillis = 200)`。

## 6. 历史原型动画 Keyframe（当前实现以§14为准）

### 6.1 `pulse-glow`（运行中脉冲点）

```css
@keyframes pulse-glow {
  0%, 100% { transform: scale(1);   opacity: 1;   }
  50%      { transform: scale(1.3); opacity: 0.6; }
}
/* .pulse-dot: 6dp, bg iris-9, box-shadow 0 0 0 2dp iris-5, 1.5s infinite ease-in-out */
```

历史原型说明：此缩放/外扩环方案已取消，不能作为当前WorkingAnimations的实现依据；当前只允许§14的单色alpha循环。

### 6.2 `panel-spin`（刷新按钮旋转）

```css
@keyframes panel-spin { from { rotate(0) } to { rotate(360deg) } }
/* .panel-header-btn.rotating svg: 0.8s linear infinite */
```

Compose：`rememberInfiniteTransition` + `Modifier.rotate`，0.8s linear。触发 `triggerCardRefresh` 旋转 800ms 后停。

### 6.3 `blink`（终端光标）—— ⚠ 缺失裁定

原型 `.terminal` 光标用 `animation: blink 1s infinite`，但**从未声明 `@keyframes blink`**——光标实际不闪。

> **裁定**：补 `@keyframes blink { 50% { opacity: 0 } }`（1s step），让光标真正闪烁。Compose 用 `rememberInfiniteTransition` + alpha 1↔0。

## 7. 历史原型 Transition 规格（当前实现以§14为准）

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
| `display: none ↔ flex` 展开折叠 | `AnimatedVisibility` + disclosureSpec（减弱模式None） | 比原型更顺，不违背意图 |
| chevron `transform: rotate` + `transition` | `Modifier.rotate` + animateFloatAsState(disclosureSpec) | — |
| dock→panel 瞬切 | `AnimatedContent` 或直接切 `visible` | 不要求宽度动画 |
| 主题瞬切 | `MederiColors` 整体替换；可选 `animateColorAsState` 平滑 | — |
| `pulse-glow` 历史keyframe | 单色alpha呼吸2400ms，无scale | WorkspaceMotionPolicy |
| 请求等待旋转 | WorkspaceLoadingIndicator，1000ms线性 | 减弱模式静态 |
| 历史`blink` keyframe | 不新增UI正文闪烁；原生终端光标归终端宿主 | 专用控件边界 |
| `transition 120ms ease` | `animateColorAsState(feedbackSpec())` | 统一 |
| `box-shadow` focus ring | `Modifier.border(1dp, iris-7)` + 外 ring `drawBehind` | 无 elevation |
| feather icon | compose-icons feather / Material Symbols | 尺寸/描边照 `02-components` |
| 无响应式 | 桌面三栏；移动端待定 | — |


## 13. 工作台首批局部交互覆盖（2026-10-29）

已迁移消息/输入/概览使用WorkspaceStyle：hover/focus颜色120ms，诊断/折叠/箭头180ms，无弹跳；任务整组2400ms呼吸，文字/小圆点/单行图标不叠加循环。概览显示/隐藏不再经过最小化胶囊；受真实内容区宽高约束，标题关闭按钮固定、正文滚动。此批没有实现全局ReducedMotion或其它面板动效迁移，保留为主计划后续任务。


第二批过程与交互卡沿用上述120ms hover/focus、180ms展开/箭头/临时提示进出。移除推理文字循环呼吸和StatusStrip装饰动效；真实活动使用状态标记。提问/审批/错误不加入循环动画，内容变化不借机阻塞输入。未实现的快捷键不显示提示；全局ReducedMotion仍为后续任务。


第三批文件与阅读器沿用上述反馈：导航/树/搜索/按钮120ms、目录箭头180ms；树展开内容不额外叠加弹跳或循环动画。工具栏内容随可用宽度换行，不锁死36dp高度。文档Tab保留原横向滚动，图片原比例保留双轴滚动，正文仍由InkCompose持有阅读滚动。刷新不清除查询/展开/选中；文件/文档打开路由与焦点策略不在视觉批次变更。


第四批Dock/子代理面板（桌面与移动抽屉）及自动化浮动详情统一180ms tween进出，不再弹簧展开；hover/focus120ms。关闭面板只隐藏内容，后台工作继续；终端tab关闭保留终止对应shell语义；子代理停止保留目标确认；同目标进入终态收起确认，切换目标清理旧确认。标题和次级身份不占固定行高，长标题先省略自己；关键关闭/新增/恢复入口固定或自然换行。自动化Dock不受浮动展开状态控制，列表有独立滚动；用户翻阅历史时不因新增步骤强制拉走。


第五批诊断/概览步骤继续120ms hover/focus、180ms展开及共享1500ms复制反馈，不增加运行装饰动画。概览共享栏头以最小40dp而非固定高度布局，键盘焦点有同族反馈，提供Expand/Collapse动作。资源展开只改变阅读层，不改变采样；原始JSON在限高后滚动，复制不遮内容；Todo与计划步骤只共享表现，不产生相互状态联动。通用Tooltip/Menu/Dialog、全局ReducedMotion与全页回焦仍待独立验收。


第六批公共弹层沿用120ms控件反馈，Tooltip显示200ms/离开150ms共享WorkspaceStyle。hover提示不抢输入焦点；帮助支持点击并临时聚焦popover接收键盘，Escape收起后不因锚点回焦而重开；Dock点击仍直接开关目标面板，不附带停止工作。平台菜单保留原生方向键与Escape dismiss。弹窗长文/大字体通过有界滚动与动作回流保持可达，未引入Enter默认确认危险动作。完整APP跨弹窗回焦/原生宿主遮挡仍待实机，不能由组件测试推定。


供应商S2a管理/添加以单一ProviderPage驱动，宽屏管理+详情，窄屏下钻；目录整区替换，无叠层目录弹窗。惰性目录按名、管理按ID保持稳定；query/scroll在添加步骤返回时保留。两种新查询不复用模型查询。真实请求失败留在步骤，成功才回详情；Key草稿退出即离开Composition，不持久化。原详情成功反馈4秒只清成功，错误由共同ErrorBoard明确关闭；统一120ms控件反馈，无装饰循环。


供应商S2b控件继续120ms反馈，选择/菜单/表单/确认同族；详情整体阅读滚动按provider.id归属，切换供应商回其身份，新模型更新不清同一阅读状态。宽窄布局不复制回调，操作有独立热区。弹窗按目标隔离、编辑对象移除则收起；无可用动作不显示假关闭/详情。保留成功4秒反馈；自动设置仍需原确认，数据权限仍由Core强制。


## 14. 当前统一动效实现（2026-10-30，覆盖上述历史原型与局部交付说明）

唯一策略/精确取值见01-tokens §6。MainScreen/Workspace/Sidebar/扩展面板、概览行、输入、过程/事件/决策、文件树/diff、共享按钮/开关/导航/弹层动作均消费LocalWorkspaceMotion，不用自定tween/spring或AnimatedVisibility默认动画。减弱模式开合使用None而不是仅将动画跑快；发送按钮取消按下缩放，但可用/焦点/选中状态不消失。

任务组优先整栏呼吸，折叠/展开均保留；子Agent与后台任务正文和单行图标不叠加循环。展开工作过程时去掉外层等待转圈，内部真实活动仍可显示共同请求等待；等待子Agent时外层不伪装请求转圈。Sidebar等待用户状态静态；会话运行点/旧badge/兼容WorkingAnimations共用同一周期，没有波纹/缩放/扫光。

持续动画只在running且裁剪后的boundsInWindow有面积且非reduced时创建；组件移出组合自动释放，裁掉后暂停、重新进入重新启动表现周期，不影响真实工作。当前不包含窗口失焦/应用后台检测，不能宣称已做。Loading使用共同不定进度语义，减弱模式静态而任务文字/数量保留。真实计时与静态复制反馈寿命不随动效偏好改变。

外观设置有“标准动效/减弱动效”同源单选，AppState设备偏好持久化，经AppTheme全树注入；没有Core状态或API参数副本。浏览器专属代码明确排除；Material/原生菜单、输入/第三方渲染器内部动效仍按各自平台机制，本批不宣称修改它们。完整APP/原生窗口录屏和三平台动画观感待idea-mcp实机验证。


## 15. 一次性命令与受控展开（R03a）

VM一次性效果只由窗口Route收集；叶子View接只读显示状态和回调，不各自订阅同一Channel、广播所有效果或保存递增打开计数。项目菜单的按钮/外部命令/选择/取消共享同一展开状态；关闭后重建输入分支仍关闭，不把历史打开请求重播。普通关闭只修改本地表现，不产生新的业务命令。此规则不改变菜单样式/动效，也不改变异步工作与输入可用性。

### 阅读上下文归属（R02a/b第一步）

阅读器标签与选择随会话恢复，同名/同ID文档在不同会话互不覆盖；新对话按项目保存独立阅读上下文，首发成功并仍在原草稿时移交新会话。新文档意图替代旧文件确认，切走不携带待确认弹框。关闭面板保留阅读状态，后台任务不受影响。阅读面板只消费VM投影与回调，实时正文继续同源Core，不能由View缓存第二份流式正文。所有外观/动效仍使用原Reader、Tab与公共组件，无新增风格。

### 执行权限与一次授权（2026-11）

执行授权卡按 `(conversationId, requestId)` 保持身份，多个存活请求同时可见，独立于可折叠的工作过程。用户切换会话不会把已有请求的批准回调改投当前会话；VM 的提交中/错误只是该请求的短命表现状态，在 core 快照移除请求后清理。批准成功等 core 事件或快照移除卡片，拒绝、取消和宿主配置变化均不能从历史工具消息恢复成新的待批操作。

项目权限两档通过同一 AiCore 设置 API 读写，桌面与遥控端一致；设置事件仅刷新对应项目配置，不覆盖路径输入草稿。读取失败不能用默认选中冒充成功，也不能用旧快照继续提交新权限；保存期间不重复提交，保存失败保留已确认配置。切换「完全访问」本身就是用户的权限选择，不另叠确认弹窗，不代替计划批准或提问。既有任务不因设置页隐藏或切会话而取消。


输入条的执行权限与计划批准合并为单个盾牌图标，点击打开分组菜单，hover/focus 提示与可访问名称读出两种当前状态。菜单内两个选择分别调用项目权限 store 与计划批准动作，互不修改对方；项目规则设置仍有独立入口。工具条宽度不足时沿现有 FlowRow 回流，模型名保持可读；手机 sheet 直接展开同一两组内容，不再先点二级菜单。菜单说明自然换行并受窗口可用宽度约束。

已有会话的项目身份必须来自匹配会话快照或项目的会话清单。水合期间查不到所属项目时，执行权限保持不可修改，不回退到另一个当前选中项目；新对话才使用当前选中的项目。独立项目设置使用显式目标，不因聊天切换改变编辑对象。


#### 过程标题扫光补充（2026-11-04）

推理和命令的运行标题允许 `ProcessLabel` 文字内部单色扫光（2400ms 左→右窄亮带），不加容器底色或缩放，也不与同一行 spinner 叠加。最新推理预览仅随内容增长向末尾滚动。完成/离屏/减弱动效时停止循环，失败行静态红色；外层工作过程汇总不传播历史工具失败。此项补充替代上述“没有扫光”对过程标题的旧限制，其他任务动画策略保持。
