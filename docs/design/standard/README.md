# Mederi UI 开发标准

本标准是 Mederi 桌面 / Web / 移动端所有 UI 改动与新增的**唯一依据**；本次风格优化及之后任何 UI 工作必须遵守。

## 规范效力

标准的强制力来自 AGENTS.md §0 维护义务 + 团队约定。本标准目前是「文档先行」：对应 Kotlin token 对象、`MederiColors`（32 字段）迁值、删除 GLASS 主题（`GLASS_DARK`/`GLASS_LIGHT`）均为后续代码任务，迁移注记见 `03-conventions.md`。文档定义目标态，代码落地前以本文档 token 取值为唯一标准。

## 设计原则

**1. 唯一品牌强调色**
只保留一个 accent（iris），用于选中态、主按钮、链接；其余颜色全部收作语义色（success/warning/danger），仅在对应状态出现，不参与日常装饰。
反例：橙（待批准）、蓝（状态点/链接）、绿（在线）、紫（选中）同屏出现，注意力被分散——"杂乱感"即源于此。

**2. 留白与 surface 层次分区，而非边框**
用 `surface-0`/`surface-1`/`surface-2` 三档背景 + 间距区分区块；分割线预算砍半，层级靠背景档位而不是线条。
反例：每个区块都描 1px 边框切割，边框越密越像表格/终端，不像产品。

**3. 信息层级靠字号 + 灰度递进**
关键数值大号 `text-primary`，次要信息 `text-secondary`，辅助说明 `text-muted`；层级要"一眼看到"，不要让眼睛主动"找"重点。
反例：标签、数值、正文几乎同字号同灰度，阅读者被迫逐行扫描。

**4. 等宽字体只出现在代码块/终端输出/路径/模型名等真正需要等宽的地方**
等宽是代码子区域的事，不得泄漏到卡片标题、过渡语、普通标签。
反例：给模型名/工具名套等宽字体 + 灰底 pill，整屏变成"开发者控制台"而非产品。

**5. 字重只用 Regular(400) 与 Medium(500) 两档，禁用 Bold**
字重仅用于激活态强调（400 → 500）；深色底上 Bold 过重，是界面显"廉价"的常见原因。
反例：卡片标题用 600/700 Bold + 全大写，工具感扑面而来。

## 文件导航

| 文件 | 内容 | 何时查 |
|---|---|---|
| `README.md`（本文件） | 设计原则、索引、canonical 命名约定 | 入口，先读 |
| `01-tokens.md` | 配色/排版/间距/圆角取值 | 写任何 UI 前，先取 token 值 |
| `02-components.md` | 按钮/卡片/输入控件规格 + Compose 片段 | 实现或修改组件时 |
| `03-conventions.md` | 状态规则/实现约定/迁移注记/速查 | 保证一致性与衔接现有代码 |
| `04-layout-interactions.md` | 全屏布局/右面板切换/滚动/展开折叠/动画 keyframe/交互/快捷键 | 装配页面与实现动效时 |

## 如何使用

1. **取值**：写新 UI 前先读 `01-tokens.md`——颜色一律走语义别名，间距/圆角/字号一律用命名 token，禁止直接写 hex / dp / sp 字面量。
2. **找组件**：查 `02-components.md` 找最接近的组件规格与 Compose 骨架，在其基础上改，不要从零写样式。
3. **查约定**：按 `03-conventions.md` 的状态规则与实现约定自检（token 驱动、禁 hex、i18n 文案两份）。

## 术语表

| 术语 | 定义 |
|---|---|
| `surface-0` / `surface-1` / `surface-2` | 背景三档层次：页面底色 / 卡片背景 / 弹出层与嵌套区块（代码区域） |
| `hairline` | 1px 低对比分割线，按"预算砍半"原则仅在必要处使用 |
| `rail` | 左侧竖线（缩进导引），用于视觉归拢子块，不替代边框分区 |
| `semantic alias` | 基础色阶之上的语义命名层（如 `text-muted`、`accent-bg`）；组件唯一允许消费的颜色层 |
| `canonical token` | 下节钉死的 token 命名；标准内所有文件与 Kotlin token 对象必须原样使用，禁止改名 |

## Canonical Token 命名约定

本节为权威定义，`01-tokens.md` / `02-components.md` / `03-conventions.md` 及后续 Kotlin token 对象均须照用。

### 基础色阶（Radix 命名，dark + light 各一套）

- 中性：`gray-1`..`gray-12`（深色主题 1 最暗、12 最亮；浅色主题反向）
- 品牌：`iris-1`..`iris-12`（`iris-9` = 实心 accent，两主题同值；`iris-3` 软按钮底；`iris-6`/`iris-7` accent 边框；`iris-11`/`iris-12` accent 文字）
- 状态：各只取 4 档——`green-3/6/9/11`、`amber-3/6/9/11`、`red-3/6/9/11`（3 底 / 6 边 / 9 主色 / 11 前景）

### 语义别名层（组件只读这层）

| 别名 | 指向 |
|---|---|
| `bg` | = `surface-0`，页面底色 |
| `surface-1` / `surface-2` | 卡片背景 / 弹出层与嵌套区块 |
| `border` / `border-strong` | 默认 / hover 边框 |
| `text-primary` / `text-secondary` / `text-muted` | 主 / 次 / 辅助文字 |
| `accent` / `accent-hover` / `accent-bg` / `accent-text` | 实心 accent 及其 hover / 软底 / 软文字 |
| `on-accent` | accent 实心底色上的文字色 |
| `success` / `success-bg` / `success-text` | 状态三组：主色 / 底 / 文字（`warning`、`danger` 同构） |

### 便利别名（场景级，仍属别名层）

`bg-sidebar`、`bg-workspace`、`bg-card`、`bg-card-hover`、`bg-input`、`bg-code`、`bg-hover`、`bg-active`、`rail-color`。

### 规则

- 组件 / Compose 代码只引用语义别名层与便利别名，禁止直接引用基础色阶（`gray-N`/`iris-N` 等）；
- 两主题各自定义一套基础色阶，语义别名指向基础色阶、自身不随主题翻转；
- 新增别名必须同步登记本文件与 `01-tokens.md`，禁止临时别名。
