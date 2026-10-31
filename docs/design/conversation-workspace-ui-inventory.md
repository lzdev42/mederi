# 对话工作台 UI 盘点与迁移表

> 2026-10-28 静态盘点。实施顺序、理念与验收以[整体改造计划](conversation-workspace-redesign-plan.md)为入口。
> 范围是整个对话工作台，不仅消息列表；路径前缀 `app/shared/src/commonMain/kotlin/xyz/mederi/`。
> “目标”均为待实施建议，不能当作当前代码结构；最近已完成局部改动单独标明。

## 1. 主对话与输入区

| ID | 部件 / 源码定位 | 当前观察与风险 | 目标家族 / 批次 |
|---|---|---|---|
| C01 | `ui/chat/ChatListContent.kt`、`ChatListItems.kt`、`ChatItemsBuilder.kt` | 主/子聊天共用；renderer与整个Workspace VM耦合，承担路由；Core交付与用户边界已有重要约束 | Route/纯Message renderer，R02/R06/R07 |
| C02 | ChatListContent 用户气泡 | 受限宽度、实底与边框；不能改变稳定消息ID、重复正文/附件对账 | Message，R06 |
| C03 | `ui/components/UserMessageCards.kt` UserPastedTextCard | 外卡和内滚动框双层边界、局部复制/展开态 | Object/Attachment，R06/R08 |
| C04 | UserMessageFooter | 时间/回滚/复制；复制反馈自行delay；子代理合成用户消息不能显示回滚 | MessageActionBar，R06 |
| C05 | ChatListContent 助手正文、图片；`atoms/MederiMarkdown.kt` | InkCompose承载选择、复制、代码、表格、公式、图表/竖排；内容主题与UI主题边界需明确 | MessageContent，R06 |
| C06 | AssistantMessageFooter | 模型/审批/推理/时间/耗时常显；mono与两个复制文字入口密集 | MessageActionBar + Details，R06 |
| C07 | `WorkTraceCard.kt` | 共享ProcessRow是好基础；默认折叠，320dp内部滚动与“展开全部”，存在嵌套展开/滚动 | Activity，R07 |
| C08 | `ReasoningBlock.kt` | 共享ProcessRow；200dp独立限高；局部文字呼吸，无活动也可能创建transition | ActivityDetail，R04b/R07 |
| C09 | `ToolCallsBlock.kt` | 动作聚合/输入参数/输出/报告/ASK结果；运行中可查看输入，不能退回不可展开占位 | ActivityDetail + CodeOutput，R07 |
| C10 | ToolCallsBlock SubagentCallsBlock | 已有成功派发细rail/Check/两行固定说明，折叠外展示；Builder与View重复判成功谓词 | Notice，R07；保留已有异步说明 |
| C11 | `EventMessageCard.kt` | 子Agent、command、timer轻量终态行；仅传打开意图，VM异步读报告 | R03c代码/组件验证完成；R07及完整实机仍待验 |
| C12 | `QuestionCard.kt` | 紫底框，单/多选/自由输入/自定义/多页；Material表单和自绘控制尺寸不同；局部560dp需改用ChatLayout公共常量（2026-10-29复核实际actionCardMaxWidth=560dp，旧架构文档640dp已纠正） | Decision/Form，R09 |
| C13 | `PlanApprovalCard.kt` | 紫底框；整卡查看、内部批准；Cmd提示硬编码；非pending分支未精细区分终态 | Decision，R09 |
| C14 | `DocumentArtifactCard.kt` | 10dp外框、38dp图标座、SemiBold标题、DOC badge、元数据和独立CTA同时强调 | ObjectLink，R11 |
| C15 | `DiffCards.kt` TurnDiffSummaryCard/FileDiffRow | 扁平变更列表较轻；mono路径/增删数、Review按钮，需统一密度和操作 | ObjectList，R10 |
| C16 | `WorkspaceFloatingOverlay.kt`、`StatusBar.kt` | 已删重复子Agent浮条；仍有主轮次状态、压缩结果、文件打开失败、截图预览；Capsule与Strip风格并存 | SharedFeedback，R04/R09 |
| C17 | `ErrorBoard.kt`、`ErrorDetailDialog.kt` | 简报/详情/继续/关闭；红底红框红字叠加；关闭小热区；恢复入口必须保留 | ActionNotice + Dialog，R09 |
| C18 | `ChatInputCard.kt` | 输入/键盘/剪贴板/popup/能力提示/effect收集混合；与MainScreen竞争effect | Composer + Route，R03a/R08 |
| C19 | `ChatInputAttachments.kt` | 图片缩略图、大文本卡、预算、删除；多层框，删除/点击语义需统一 | Attachment，R08 |
| C20 | `ChatInputControls.kt` | 审批模式帮助/开关/Chip；有遗留无效参数；自绘开关语义/热区需补 | ComposerControl，R08 |
| C21 | `ChatInputSelectors.kt` 模型/Key/档位/项目菜单 | popup圆角10/8/6、选中粗体/图标/背景各一套，移动sheet另写；直接收VM | SelectionMenu，R08 |
| C22 | ChatInputCard SlashMenuPopupContent、`ui/command/` | 上下/Enter/Tab/Esc、token删除、粘贴、IME是行为，不可视觉迁移时损坏 | ComposerAutocomplete，R08 |
| C23 | ChatInputControls SendButton / ChatInputCard submit | 现已支持主轮次工作中有内容仍send；空输入且主轮次活动才stop（Core当前会级联取消本会话任务）；不得用独立任务状态禁发 | ComposerAction，R08 |
| C24 | 文件/目录打开 ConfirmDialog、图片Lightbox | 平台确认与焦点/遮挡；关闭预览≠取消工作；文件打开失败不可静默 | Dialog/Overlay，R09/R11/R12 |

## 2. 工作台外壳、概览与辅助面板

| ID | 部件 / 源码定位（ui/下） | 当前观察与风险 | 目标家族 / 批次 |
|---|---|---|---|
| W01 | `Workspace.kt`、`ChatLayout.kt` | Header40dp、对话最小360dp、内容最大820dp、Dock46dp；Header整块描边；Route/布局混合 | Chrome/Route，R02/R05 |
| W02 | `MainScreen.kt`、`Sidebar.kt`、`SidebarReveal.kt` | 侧栏固定/浮层/边缘唤出已有状态机；部分手绘控件 | Chrome接缝，R05；不另造抽屉机制 |
| W03 | `overview/FloatingOverviewWindow.kt`、`OverviewWindowState.kt` | 已改共享visible显隐/Grid选中/无胶囊；292dp固定宽、shadow8dp；无整窗限高滚动可能越界 | Chrome/Overlay，R05 |
| W04 | `overview/OverviewAccordionRow.kt` | 34dp行头、1dp divider、180ms高度动画、chevron直接切角度；缺统一展开语义 | Disclosure，R04/R05 |
| W05 | `overview/OverviewRunningTaskRow.kt` | 后台command/timer；每2秒View触发轮询；spinner与文字均呼吸800ms | TaskGroup/ActivityQuery，R03b/R05 |
| W06 | `overview/OverviewContextRow.kt` | 上下文、压缩、自绘阈值slider及局部动效 | Metric/Progress/Slider，R05 |
| W07 | `overview/OverviewUsageRow.kt` | 用量依真实数据有无显示；摘要字号与其它行不同 | Metric，R05 |
| W08 | `overview/OverviewPlanRow.kt` | 空则隐藏；批准/plan/spec入口/步骤/局部展开，可能卡中卡 | Decision + TaskStep，R05/R09 |
| W09 | `overview/OverviewSubAgentRow.kt` | 已有整栏2400ms呼吸；头部点1600ms、单行Loader1200ms仍三种节律；概览Stop用红X直接停 | TaskGroup，R04b/R05；先统一动作与组级动效 |
| W10 | `overview/OverviewMcpRow.kt` | refresh/add/开关/菜单/弹窗；View内调Store/load；局部图标控件22dp | ManagementRow + 状态holder，R02/R05 |
| W11 | `overview/OverviewSkillRow.kt` | 目录选择/刷新/安装与Store编排仍在Composable；与旧管理卡重复 | ManagementRow + 状态holder，R02/R05 |
| W12 | `overview/OverviewTodoRow.kt` | 局部SemiBold；与计划步骤形态可复用，数据源不能合并 | TaskStep，R05/R13 |
| W13 | `overview/OverviewRawMessagesRow.kt`、`RawMessagesViewModel.kt` | 懒加载、嵌套折叠/JSON滚动、复制2秒；VM由Route创建可保留 | Diagnostic/Disclosure，R13 |
| W14 | `components/RightDock.kt` | 已复用IconSquare；rail全边框+每按钮框底，手绘tooltip；存在false角标/死动画参数 | Chrome/IconAction/Tooltip，R05/R14 |
| W15 | `components/RightExtensionPanel.kt` | 共同Header+divider+外边框/拖拽柄多线；spring+fade；局部宽度与切面板重置 | Panel/Resize，R05 |
| W16 | `components/FilesPanel.kt`、`TreePanelContent.kt` | 树/变更二级Tab、工具栏/搜索/分隔线；保持过滤/展开/选择 | NavigationTab/Tree，R10 |
| W17 | `InfoPanels.kt` DiffPanelContent/DiffQueryContent | VM按session/messageId缓存查询和选择；纯反馈/内容，关闭不被旧返回拉回；平台DiffView保持 | R02a/b diff切片完成；R10整体/实机保留 |
| W18 | InfoPanels ArtifactsPanelContent/ReaderPanelContent、`ViewerTabs.kt` | 同源Reader/Tab/工具栏；VM按会话/项目归档标签，纯View消费状态/回调；导出局部工作态仍保留 | R02a/b阅读切片已实现；R11导出编排/原生验收保留 |
| W19 | ViewerTabs ImageViewerTabContent | 图片自有toolbar/border/文字缩放按钮；需保留双轴滚动/原比例/适应 | Reader/ImageContent，R11 |
| W20 | `TerminalPanelContent.kt`、`TerminalViewModel.kt` | Route创建VM正确；Tab34dp、12dp关闭热区、独立未读色 | Panel/DocumentTab，R12 |
| W21 | `BrowserPanel.kt`、`BrowserAutomationDockPanel.kt`、`BrowserAutomationFloatingBar.kt` | Globe实际指向BROWSER_AUTOMATION，BROWSER另有宿主网页视图；旧浮动capsule/popover嵌大面板 | Panel/TaskTimeline，R12 |
| W22 | `SubagentConversationPanel.kt` | 40dp头、角色tag/mono模型、停止确认与关闭；内容共用ChatListContent，无输入/回滚 | Panel/Message，R12 |
| W23 | `SystemInfoBar.kt` | CPU/RSS/JVM常显、全border、32dp；属于资源诊断不是任务状态 | DiagnosticBar，R13 |

## 3. 公共原子与渲染边界

| 现有基础 | 保留/演进方式 |
|---|---|
| `theme/Theme.kt`、`theme/Tokens.kt` | 保留语义色与深浅主题；清理兼容角色、集中行高/热区/控件尺寸/MotionPolicy，不在每个文件填字面量 |
| `atoms/ProcessRow.kt`、`ExpandableRow.kt` | Activity/Disclosure公共基础；统一箭头动画、可展开语义、焦点与详情滚动 |
| `atoms/MederiButtons.kt`、`MederiIconButton.kt` | 统一Button/IconAction变体、热区、enabled/selected/focus/hover，不再Box.clickable各自写壳 |
| `atoms/MederiCards.kt`、`PanelCard.kt` | 从“一律卡片带边”演进为有明确语义的surface/decision/overlay；保留边界必要性，不能为减重删除表格网格 |
| `atoms/StatusStrip.kt`、`MederiBadges.kt` | 收敛Notice/Status；派发、终态、错误按语义使用变体，不让一个组件偷偷承担业务判断 |
| `atoms/CopyButton.kt` | 统一复制反馈、失败与时长，去除各处copied/delay重复实现 |
| `atoms/WorkingAnimations.kt` | 演进为集中MotionPolicy；不需要三种装饰动画到处任选，保留明确运行语义与ReducedMotion |
| `atoms/Dialogs.kt` / ConfirmDialog | 统一关闭/确认/停止目标、焦点与键盘，不把取消UI等同业务取消 |
| InkCompose API/Markdown主题桥接 | 只在应用主题适配层调整阅读样式；不能为修改UI token破坏代码/数学/图表/竖排语义 |

## 4. 状态与行为债务对应表

1. `core/contract/dto/ConversationState.kt` 反向import UI，R02先分离事实和会话presentation，再兼容清理。
2. `ui/WorkspaceUiState.kt` 目前只是若干内部状态分组；新分组只读契约需真实实现，不能仅换文件名。
3. `MainScreen.kt` 与 `ChatInputCard.kt` 同收`UiEffect` Channel，R03a建立唯一消费/路由。
4. `WorkspaceViewModel.pollBackgroundTasks` 每次launch且无会话/版本guard，R03b明确取消、互斥与晚返回隔离。
5. `EventMessageCard` 同步读报告、概览行调Store、ViewerTabs编排导出，在R03c/R05/R11分别迁到协作者，View只渲染。
6. `SubagentCallsBlock` 与Builder重复成功判断，R07纯投影单点生成通知模型。
7. 停止/关闭混用X、停止确认不一致；R04明确语义，R05/R09/R12统一调用。
8. 旧OverviewTabContent/管理卡/假角标与临时适配器，R14只在引用核实后删除。

## 5. 已有验证资产（执行前仍确认实际匹配）

- `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/chat/WorkTracePresentationTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/chat/ChatScrollLayoutTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/chat/SubagentChatItemsTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/overview/OverviewWindowStateTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/MergeLocalUiStateTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/ConversationStateAndSubagentTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/EventMessageParsingTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/SubagentPanelViewModelTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/FileOpeningFlowTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/SubagentTerminalKindTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/PlanApprovalLayoutLogicTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/ParseAskItemsTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/core/i18n/AppLanguageResourceTest.kt`
- `app/shared/src/jvmTest/kotlin/xyz/mederi/core/contract/BrowserReasoningReducerTest.kt`
- `app/shared/src/commonTest/kotlin/xyz/mederi/core/contract/ResponseBoundaryReducerTest.kt`
- `app/shared/src/commonTest/kotlin/xyz/mederi/ReasoningMenuTest.kt`
- `app/shared/src/commonTest/kotlin/xyz/mederi/PromptComposerTest.kt`
- `app/shared/src/commonTest/kotlin/xyz/mederi/SlashCommandModelTest.kt`

新增行为测试的拟定类名、具体场景与阶段对应见主计划，不能将此处已有测试误认为覆盖全部新设计。

2026-10-29复核：当前ChatLayout.contentMaxWidth=820dp、actionCardMaxWidth=560dp，旧架构文档1000/640dp已纠正；本批仅统一组件对公共常量的引用，不修改布局业务基线。


### 2026-10-29 第三批迁移登记

C15/W16–W19表现层已按第三批规格迁移：变更入口、文件导航/树、diff文件tab、文档tab、文本/图片共用工具栏。代码/组件验证与剩余APP验收以[第三批交付](workspace-third-delivery.md)为准；表中旧样式描述是盘点基线，不再代表这些调用点的现状。导出状态迁出View、完整文件状态/报告协作者仍为主计划后续，不因纯表现入口抽取声明整体R10/R11完成。


### 2026-10-29 第四批迁移登记

W15/W20–W22辅助面板表现层已按第四批规格迁移：共同面板身份/关闭、终端导航与恢复、网页能力说明、独立浏览器自动化内容、子代理详情状态与停止确认。自动化记录保留目标/耗时/截图等信息，浮动入口与Dock共用内容而不共用容器。代码/组件验证与剩余实机验收见[第四批交付](workspace-fourth-delivery.md)。PTY/native网页能力、VM业务与Core生命周期未重写；盘点表的旧样式为基线描述。


### 第五批迁移记录（2026-10-29）

W11/W12计划/Todo活动概览项：WorkspaceTaskStep共享轻量图形、Body/Caption和显式状态文字；计划批准/阅读/Spec独立，展开按ID，旧InfoPanels非活动卡保留。W13原始消息：ExpandableRow两层标题元数据、复制独立工具栏、共享1500ms反馈、有界JSON；VM原派生与格式化复制保持。W23资源诊断：默认收起的ProcessRow入口，展开FlowRow原指标，采集不变。概览共同头补focus/Expand/Collapse及最小高度。验证以workspace-fifth-delivery.md为准；弹窗/菜单/Tooltip和全局ReducedMotion仍待后续。


### 第六批迁移记录（2026-10-29）

C17错误详情复用MederiDialog/ActionButton，保留完整报告/复制/GitHub预填URI，日志有界滚动；C21四个输入菜单复用WorkspaceDropdownMenu/MenuItem，保留选择目标与原生菜单；C24与Sidebar/Provider共享Confirm/Input弹窗统一内距、字号、动作回流/长表单滚动；W14 Dock选中/focus语义、去常态描边/不可达角标；Help和Dock共用提示内容/定位。Tooltip hover不抢焦点，点击帮助可聚焦并用Escape/外部关闭。真实验证以workspace-sixth-delivery.md为准，全局ReducedMotion与完整APP回焦仍待后续。


### 设置S1衔接（2026-10-29）

用户调整优先级，先迁设置导航与外观/系统样板。SettingsDialog七入口与原Route不变；公共SettingsSection/Card/Row同族轻量规格，选择/复制复用已有原子。工作台Skill/MCP遗漏弹窗、全局MotionPolicy及剩余MVVM收口仍待后续，不因转向设置而标记完成。详细盘点与证据见settings-redesign-plan.md/settings-first-delivery.md。


### 供应商S2a衔接（2026-10-29）

ProviderSettingsPanel管理只显示已配置，独立名称/端点查询；旧常驻BuiltinPresetsSection与AddBuiltinProviderDialog删除，添加目录/凭据改同区域步骤。ProviderChoiceRow/SearchField宽窄共用，LazyColumn支持大目录。VM单一ProviderPage和独立query，无providers副本；旧isCreatingCustom派生。错误统一ErrorBoard无详情变体，原详情只保留成功反馈。完整模型/详情样式为S2b，证据见settings-provider-catalog-delivery.md。


### 供应商S2b迁移（2026-10-30）

Provider详情头/凭据/工具栏/模型同一纯Content与阅读滚动；旧宽窄工具栏删除。所有表单字段、动作/协议、错误/确认同源，不另造Provider组件体系；ProviderTokens仅为共享规格别名。ModelInfoRow呈现FETCHED只读事实，图片覆盖仍可用；MANUAL完整编辑；凭据Key掩码只显示不回写。实际文件/行为与103项回归证据见settings-provider-details-delivery.md。下一批S3 Agents；全页原生焦点与三平台完整APP仍待验。


### 2026-10-30 设置 S3 Agents

AgentSettingsPanel已拆成Route、AgentSettingsViewModel/UiState和纯Content/角色设置；旧多层角色卡、重复宽窄分支和三个独立菜单删除。共同SettingsRow/WorkspaceDropdownMenu/MenuItem/ActionButton及ModelPickerList负责呈现；NumberStepper/SegmentedPillToggle真实复用共同按钮/Radio导航。读取/写失败统一ErrorBoard，角色和并发独立状态；不显示假默认，不串Key ID/轮询/继承。117项相关回归与wasm通过，实际深浅组件预览已检查，见settings-agents-delivery.md；下一批S4浏览器。APP实机、全局MotionPolicy与其余架构收口仍待验。


### 2026-10-30 P0管理弹窗

Skill安装/目录/MCP配置均通过共同TextSubmissionDialog/WorkspaceTextField/WorkspaceDialogActions/ErrorBoard；Skill卸载共同ConfirmDialog，返回Result明确成功关闭/失败留存；MainScreen压缩跳过提示共同MederiDialog。剩余直接Dialog只有基底、移动Sheet、截图Lightbox及交另一AI删除的浏览器目录设置，例外见workspace-management-dialogs-delivery.md。124项相关回归与wasm/JVM通过，实际深浅组件预览已检查；完整APP/焦点/三平台仍待验。


### 2026-10-30 R04b统一动效

有限反馈/局部展开/面板全部消费WorkspaceMotionPolicy，旧WorkingAnimations/徽标/会话点归2400ms单色alpha；非浏览器请求等待10个文件共用WorkspaceLoadingIndicator1000ms，减弱模式静态。概览整组含折叠头运行，工作过程展开/等待子任务不再叠加外层转圈。设备偏好与外观入口同源；135项相关回归和wasm/JVM通过，实际组件深浅截图已检查。具体范围、未覆盖平台/第三方内部动画与实机录屏见workspace-motion-delivery.md。


### 2026-10-30 S5沙盒与遥控

SandboxSettingsPanel/RemoteSettingsPanel去重卡/图标托盘，状态和原配置真理源保持；新增SandboxSettingsViewModel只查询宿主状态，纯Content接路径与回调；遥控纯Content与Actions接原AppState流/服务动作。SettingsCopyableValue为系统/地址同一View、SettingsSecretField为密码/Token同一显隐、MiniSwitch真实开关语义/热区；状态错误与加载同源。152项相关回归和wasm/JVM通过，实际深浅预览已检查，见settings-host-controls-delivery.md。剩余工作台MVVM与S6/完整APP验收继续保留，浏览器排除。


### 2026-10-30 R03a效果路由

MainScreen唯一rememberWorkspaceEffectState消费effects，设置/压缩/项目菜单状态归窗口；Workspace三布局和ChatInputCard两分支只传受控展开与回调，ProjectSelectorMenu去内部expanded/openRequest计数。160项相关回归（本批8）与wasm/JVM通过；实际菜单聚焦Escape/精确ID/null/目录回调/重建不重放已验。无视觉变化，完整窗口/原生焦点/IME待验。R03b后台任务与R03c报告加载仍待后续，详见workspace-effect-routing-delivery.md。


### 2026-10-30 R03b后台任务查询

OverviewRunningTaskRow不再定时查询，只适配纯Content；Workspace Route按概览可见性持租约，BackgroundTaskPresenter按会话/代际串行查询与逐ID停止反馈。隐藏只取消查询，旧响应不串台；查询成功不吞停止错误，折叠头文字可发现。176项相关回归（本批16）与wasm/JVM通过，深浅实际Content截图已检查；最终夹具自然高度定向8项追加通过。R03c报告加载与整体MVVM/S6/实机保留，见workspace-background-tasks-delivery.md。

### 2026-10-30 R03c报告读取

EventMessageCard彻底移除报告文件I/O，VM→ReportLoadPresenter→纯ReportReaderContent；ReportKey按session+event、完整请求和原source引用身份缓存，取消/代际防晚返回。Loading无假文档工具，缺失/失败/暂停可重读且原摘要明确来源；后台命令/定时器直接原事件正文。192项相关回归（本批16）与wasm/JVM通过，实际Reader深浅预览已检查。原全局产物归属/导出编排未迁移，继续R02a/b复核，整体MVVM/S6/完整实机待验，详见workspace-report-loading-delivery.md。

### 2026-10-30 R02a/b阅读器切片

原全局artifactItems/activeArtifactId已迁到VM readerStates，ReaderOwner区分真实session与未发消息project草稿；只读兼容属性和ReaderPanelState同源。纯ReaderPanelContent只接状态/回调，Text实时内容由当前聊天派生、Report状态来自原协作者。确认框不串台、首发移交、切回恢复、迟到文件结果owner+序号拒绝；无新视觉风格/动效/导出机制。218项相关回归（新增11＋额外既有15）与wasm/JVM通过，详见workspace-reader-ownership-delivery.md。整体R02a/b/c、错误/diff等分组、导出编排/S6/实机仍保留。

### 2026-10-31 R02a/b diff切片

DiffPresenter按session缓存messageId范围/内容/选择/反馈，关闭/换面板/切会话取消+代际拒旧返回，完成不控制Dock。showDiffPanel派生Dock，不再双写DiffUiState.showPanel；refreshDiff保持单轮范围。纯DiffQueryContent复用共享等待/错误/动作与原DiffFilesContent，失败保留非空快照，真实空态/未读取/无会话区别。234项相关回归（新增16）与wasm/JVM通过，实际深浅组件预览已检查，见workspace-diff-ownership-delivery.md。主对话ErrorState/异步反馈下一批，整体R02/S6/实机保留。

### 2026-10-31 R02a/b主对话反馈切片

WorkspaceFeedbackPresenter按Session/Draft分离Core投影、本地失败、关闭身份与详情身份；输入区纯WorkspaceFeedbackContent和Workspace详情消费同一feedbackState，继续委托原ErrorBoard/ErrorDetailDialog。相同快照不撤销关闭，新错误可见；本地失败无旧Core诊断/ID/断流继续。创建/发送/回滚捕获原目标与sequence，回滚恢复需原上下文/文本/附件仍有效。256项相关回归（新增15＋额外既有会话恢复7）与wasm/JVM通过，见workspace-feedback-ownership-delivery.md。无新视觉样式；剩余Chrome/独立提示/整体R02a/b/c、S6/实机继续保留。
