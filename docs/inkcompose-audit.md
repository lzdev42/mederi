# InkCompose 审计：精简、性能与自由选取

日期：2026-10-27。范围：当前工作区（包含既有未提交改动）；本次只新增审计报告，没有修改生产代码。

## 结论与边界

当前最值得处理的是：选取能力与实际排版不一致、缓存的内容与生命周期边界不一致，以及流式路径仍有全文工作。不能把“抽象更多”当作减代码的目标；优先统一重复真理源，让渲染、选取、复制共用同一份实际文本布局。

本次采用架构文档入口后的定向源码审计，不是全部算法的完整正确性证明。下文“代码确认”指调用路径或结构可确定；手势体验和实际性能收益仍需交互回归与测量。当前没有 idea-mcp / Compose UI MCP 工具，未启动 APP/server；需要先启动/配置 idea-mcp 才能运行 APP/server 做实机验证。

## 规模基线

统计排除 build/.gradle，行数含注释与空行：

| 范围 | Kotlin 文件 | 行数 |
|---|---:|---:|
| 生产代码，含平台实现 | 389 | 59,905 |
| 测试代码 | 132 | 23,501 |
| Markdown renderer | 98 | 13,879 |
| Markdown parser | 62 | 14,502 |
| LaTeX parser + renderer | 90 | 20,032 |

例如 HtmlEntities.kt 的 2232 行主要是实体数据，不应为了行数指标删除。解析器、数学排版、平台适配与测试承担不同职责，不能一概视作冗余。

下列路径均相对仓库根目录；行号对应本次审计快照。

## 代码确认的问题

### S1：代码块、表格无法任意按字符选取（优先修复）

- 位置：`inkcompose/common/markdown-renderer/kotlin/xyz/emuci/markdown/renderer/internal/selection/MarkdownSelectionController.kt:526–554`。
- 非 LayoutInlineBlockModel 统一走 textOnlyBlockOffset，只按鼠标落在块上半还是下半返回 0 或 totalChars。代码块是 widget，表格是 table model，因此选择一个标识符、半个单元格文本不在当前模型能力内。高亮同样按整块处理。
- 修复方向：代码行、单元格、普通段落统一注册文本片段、实际 TextLayoutResult、坐标与逻辑文本范围。图片/图表/数学公式可以保留明确的原子选择策略，不能让所有非段落内容都被迫原子化。
- 验收：代码中只选一个标识符；跨代码行；从正文拖到表格单元格中间。分别断言精确复制文本与高亮范围。

### S2：多栏命中忽略横坐标，会选错栏

- 位置：同一 controller 的 `anchorFromWindow():494–520`。
- 只计算 y 方向距离；第一个 y 落在块内的条目立即 break。左右两栏纵向重叠时，在右栏拖选可能命中索引更靠前的左栏。
- 修复方向：先二维 bounds 命中，再按二维距离寻找最近片段；明确重叠区域优先级。
- 验收：左右栏在相同 y 放不同内容，在右栏真实拖选，断言命中右栏与复制内容。

### S3：选区跨越 Tab 容器时会复制隐藏页签

- 位置：`.../internal/selection/SelectionModelIndex.kt:214`、`.../block/TabBlockRenderer.kt:79–81`、`.../internal/selection/SelectionTextExtractor.kt:20`（前三者均位于 markdown renderer 包）。
- 索引遍历所有 tab children，但画面只渲染 selectedIndex。复制前后段落间的连续范围时，隐藏页签文本也在索引内。
- 修复方向：选择索引使用交互状态投影。必须区分“页签隐藏”和“因虚拟化暂未挂载”；不能简单过滤所有无坐标项，否则屏外正文也会丢失。
- 验收：Tab 前后跨块选取，只复制当前页签；切换页签后索引更新；长文屏外正文仍能复制。

### S4：跨视口与跨消息选取缺少宿主协议

- 位置：`.../internal/selection/SelectionGestures.kt:109–118`、`SelectionHandles.kt:84–87`；`.../MarkdownDocumentRenderer.kt:151,203–207`。
- 鼠标/手柄仅更新 anchor，没有视口边缘自动滚动请求。命中仅查已注册可见块，静止指针不会驱动滚动并继续扩选。
- 每个 MarkdownDocumentRenderer 有独立 controller/index。聊天每条助手消息独立创建 MederiMarkdown（`app/shared/src/commonMain/kotlin/xyz/mederi/ui/chat/ChatListContent.kt:379`），所以跨 MarkdownView / 跨消息选择不在当前设计能力内。
- 修复方向：宿主提供共享 selection host 与滚动适配器；普通单文档自动使用默认 host。边缘按帧滚动，并重新命中最后的指针坐标。
- 验收：拖至视口边缘后停住仍持续滚动扩选；反向拖选；跨 LazyColumn 回收边界；如纳入聊天范围，再验证跨消息复制。
- 限制：缺失协议由代码确认；具体交互表现尚未在运行中的应用复现。

### P1：流式追加仍对全文扫描

- 位置：`inkcompose/common/markdown-parser/kotlin/xyz/emuci/markdown/parser/incremental/IncrementalEngine.kt:305–308`；`.../core/SourceText.kt:88–90,161–201`。
- 每次实际 append 解析先 fullText.toString，再 SourceText.of；后者对全文做规范化检查及行偏移构建。块解析虽然只处理尾部，这些前置工作仍随全文长度增长。
- 固定小 chunk 累积到 N 字符时，仅这部分工作就可能累计到 O(N²)；合并 chunk 只降低次数，不能消除全文扫描。
- 修复方向：追加源文本/行索引走增量更新，减少全文物化；同时量测 AST 合并、后处理，不能只优化扫描后宣称整条链路已增量化。
- 验收：固定 chunk 大小，分别输入 10k/50k/100k 字符；记录总耗时、分配、全文扫描字符数。最终 AST 与全量解析等价；覆盖 CRLF、围栏与引用定义。

### P2：编译缓存存在但未接线

- 位置：`.../internal/core/compile/RenderCompileCache.kt:18`、`DefaultRenderModelCompiler.kt:108–117`、`InlineCompiler.kt:52–70`。
- RenderCompileCache 只有定义，没有调用方。每份新 Document 都 compileBlocks；compileInlineModel 无条件重建 atoms，稳定前缀也重复工作。架构文档称跨 tick 复用编译缓存，与实际代码不一致。
- 修复方向：先明确定义含上下文的缓存键，再在真实编译入口接入。标题编号、TOC、引用变化等不能因节点看似稳定而被漏掉；仅存在缓存类不等于有缓存收益。
- 验收：只追加尾段，稳定前缀 inline 编译计数不增加；同内容不同位置身份不串用；引用/编号变化能正确失效。

### P3：选取每次移动扫描全文索引

- 位置：`.../internal/selection/SelectionCoordinateRegistry.kt:61`、controller `anchorFromWindow():495`。
- visibleEntries 使用 index.entries.filter，每次命中都遍历全文条目并分配新列表，虚拟化没有把命中成本限制到可见部分。
- 修复方向：registry 直接维护已挂载片段及其对应 entry；优先查询可见空间索引，复制仍使用完整逻辑索引。
- 验收：数万块正文但仅几十个可见时，单次命中访问条目数随可见数量变化；测量拖选延迟与分配。

### C1：解析文档缓存漏掉输入转换插件身份

- 位置：`.../MarkdownStreaming.kt:71–80,97–103`，以及 `updateStreamingDocumentState` 中 markdown 等于 lastNonStreamingMarkdown 的短路分支。
- 全局缓存键只有原始 markdown/config，但实际解析的是 runtimePipeline.transform(value).markdown。不同插件把相同原文转换为不同结果时，可能直接复用另一条 pipeline 的 AST；初始化还会把原文记为已解析，从而跳过重新转换。
- 修复方向：为转换结果与缓存身份建立一致契约；插件不能稳定描述其转换版本时，不跨 pipeline 共用解析缓存。
- 验收：两个 transformer 将相同原文分别转成 A/B，两个实例以及回收重建后均显示各自结果。

### C2：共享布局缓存可能持有旧点击回调

- 位置：`.../inline/InlineModelAdapter.kt:205–230`、`.../internal/layout/engine/MarkdownLayoutSource.kt:88–120`、`.../internal/layout/inline/InlineLayoutEpoch.kt:10–14`。
- AnnotatedString 的 LinkAnnotation.listener 捕获 onLinkClick/onFootnoteClick；布局结果缓存包含这些文本对象，但全局缓存键的 epoch 明确不包含回调身份。相同正文、版式在另一个实例复用布局时，可能保留之前实例的回调及导航状态。
- 修复方向：缓存纯文本语义与几何，把活动回调在绘制/交互阶段绑定；不要为了正确性把每次新建 lambda 都加入布局键而令跨回收缓存完全失效。
- 验收：同正文两个视图分别绑定回调 A/B，点击第二个只触发 B；回收后脚注定位到当前宿主。

### C3：目录与标题编号未完整进入缓存身份

- 位置：`.../internal/core/compile/DefaultRenderModelCompiler.kt:178,345,486`；`.../internal/layout/engine/MarkdownLayoutSource.kt:113`。
- TOC entries 来自全文标题，但 identity 取占位符 AST；标题 numbering 也由前置标题决定，identity 却由自身节点生成。最终显示数据发生变化时，缓存身份未必变化。
- 风险：保留开头 TOC 占位符，修改后面的标题，可能复用旧目录；相同位置与内容的标题，在不同编号上下文下可能复用旧编号。
- 修复方向：建立“相同缓存身份意味着相同渲染数据”的约束，把派生内容纳入 revision。
- 验收：同一缓存先布局旧文档，再修改后置标题并布局新文档，断言目录文本/链接更新；另验证不同前置层级下的标题编号。

## 精简候选及取舍

1. **统一三套绘制分发**：`MarkdownLayouts.kt:38` 的 AST → BlockRenderer，`DefaultMarkdownComposePainter.kt:274` 的 RenderModel → RenderXxxBlockModel，以及 LayoutModel → RenderXxxLayoutBlockModel 并存；同文件 `PaintCompiledBlock:541` 还把 RenderModel 包装为零尺寸 LayoutRenderBlockModel。代码、公式、列表、表格、Tab 等均有平行分支。应迁移到统一布局绘制链路，公共入口保留薄适配。AST 路径仍被 directive 子内容及 fallback 使用，不能当死代码直接删除。
2. **块开启器保留单一实现**：`markdown-parser/.../block/BlockStarters.kt` 700 行旧实现，与 `block/starters/*` 注册式实现并存。`BlockParser.kt:44,51,188–191,237–247` 仍保留 nullable registry 和双分支；IncrementalEngine 三个创建点均传 registry。可以让默认入口也委托统一注册表，删除重复机制。BlockParser 是 public，必须验证默认行为与兼容性，不能直接删除 fallback 宣称无功能损失。700 行是候选文件规模，不是已承诺净减量。
3. **选取复用渲染几何**：优先解决 S1/S2/S3，把字符范围、点击命中、高亮、复制关联到同一文本片段；避免继续按代码块/表格/竖排追加独立选择分支。不能另建一套重复测量布局。
4. **收紧缓存职责**：解析缓存只负责有效解析输入；编译缓存只负责语义模型；布局缓存只负责可安全共享的几何。补接 P2 与修正 C1/C2 后再评估能合并哪些 facade/adapter/cache。缓存数量多本身不是证据。
5. **保留有实际价值的层次**：AST → render model → layout → painter 分别承担解析、语义、几何、Compose 生命周期，不能仅为少跳转而全合并。优先删除并行旧实现、重复状态、只转发且没有语义边界的包装；合并前须列出生产/测试调用点。

## 测试结论与缺口

本次执行 `./gradlew :inkcompose:jvmTest --console=plain`：BUILD SUCCESSFUL，166 个 suite、1825 tests、0 failures、0 errors、0 skipped（以本次 XML 结果为准，并非沿用 AGENTS 的历史数量）。

已有 SelectionInputModeTest 主要直接设置 range 并调用 controller；SelectionAtomicBlockTest 验证整块选区高亮尺寸。测试通过不能证明真实拖选、手势竞争、边缘滚动和多栏命中正确。

还应覆盖：触屏长按进入选区、手柄扩选、鼠标拖选与链接点击共存、Cmd/Ctrl+A 与 Shift 扩选、emoji 代理对、表格横向滚动、字体/窗口宽度变化后选区稳定。SelectionGestures 当前只处理 tap/doubleTap 与复制键，未实现完整的长按/键盘扩选；触屏分支仅 dispatchPreScroll 后 consume，嵌套滚动行为需要真实输入测试确认，暂不判定具体故障表现。

## 建议实施次序（尚未实施）

1. 建立真实输入回归用例，先钉住 S1/S2/S3 及 C1/C2 的失败行为。
2. 修复缓存正确性与二维命中，再统一文本片段协议，实现代码/单元格精确选择。
3. 接入边缘滚动；按产品范围接入聊天共享选区域。隐藏 Tab 与虚拟化的区别必须保留。
4. 采样流式/拖选基线，修复 P1/P2/P3，以计数和耗时证明收益。
5. 统一 BlockStarter 并清理重复包装；跑语法、导出、排版、选取回归与受影响平台编译；同步更新架构文档。

不预设减代码百分比，不用编译成功代替行为验收，不以性能优化为由损失现有 Markdown/LaTeX/图表/竖排能力。
