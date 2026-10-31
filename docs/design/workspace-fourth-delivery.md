# 第四批：辅助面板与任务详情

> 2026-10-29，用户授权继续。状态：代码与组件验证完成；完整APP及原生PTY/网页宿主实机验收待环境。
> R12表现层子集；保留MVVM＋单向数据流，不改Core/VM/契约/PTY/网页宿主。

## 统一规格与范围

延续“对话继续、任务并行”。面板是查看与操作后台工作的窗口，关闭面板不等于停止工作。停止动作明确标注目标，终端关闭tab仍是关闭对应shell会话（原语义），不得误称仅隐藏tab。

- 工作台统一WorkspaceStyle 13/20、12/18、16dp图标、30dp最小热区、120ms反馈、180ms展开，无持续装饰扫光或弹跳。
- 共用WorkspacePanelHeader/WorkspaceIconButton/WorkspacePanelEmptyState；导航复用WorkspaceNavigationTab，动作复用WorkspaceActionButton。
- 终端保留TerminalVM的tab/session/unread/ended/error，选择与关闭独立，bootstrap/ensureSession/原生TerminalView接线不改。能力缺失真实说明，重试/重开可发现。
- 子代理详情沿用ChatListContent和按agentId阅读状态，统一身份/状态/停止与关闭；停止仍需确认且目标绑定，关闭只发onDismiss。
- 网页宿主路由不删除，即使文件有旧dead-code注释；无host真实空态。自动化Dock展示独立满面板内容，不依赖浮动胶囊展开态；推理/输出/步骤/截图与stop/preview保留，非运行不伪称成功。
- 右侧共同外壳减少边框，保留拖宽/最大宽度/平台native host接线，动效统一180ms。

## 小批分工与验收

| 子任务 | 文件 | 行为验收 | 状态 |
|---|---|---|---|
| E1 终端 | TerminalPanelContent | tab选择/关闭/新建独立、错误重试与结束重开、无能力 | 代码与组件验证完成 |
| E2 自动化 | BrowserAutomationDockPanel/FloatingBar/TimelineRow | Dock不受floating展开态控制、空态、真实过程/截图、单任务stop/preview回调 | 代码与组件验证完成 |
| E3 面板与子代理 | WorkspacePanel原子/RightExtensionPanel/SubagentConversationPanel/BrowserPanel/Workspace动效接缝 | 关闭不停止、停止确认只针对目标、长标题/字体缩放动作可达、无host | 代码与组件验证完成 |

独立文件分工，主代理维护共享原子/资源/文档与统一Gradle。实际Compose组件交互测试＋SubagentPanelViewModel/BrowserReasoningReducer及前批回归；wasm编译；深浅主题组件预览检查。APP/server仅idea-mcp运行，当前无工具，PTY/JCEF原生视图焦点/遮挡与完整APP三平台视觉明确未验证。


## 验证与交接记录

首轮7个suite共48项测试，0失败、0错误、0跳过；本批WorkspaceFourthDeliveryTest新增9项实际Compose交互测试，前三批29项、SubagentPanelViewModel 3项、BrowserReasoningReducer 4项、语言资源3项。wasm编译通过。已检查深浅组件预览：`temp/workspace-fourth-delivery/workspace-auxiliary-dark.png`、`workspace-auxiliary-light.png`；截图是实际组件fixture组合，不是完整APP，白色1×1图片仅为测试截图对象。

关键断言：280dp/1.5倍文字关闭仍可达且不停止；停止取消/确认绑定当前ID；切换agent清除确认，同agent完成清理确认且不会重新冒出；终态无停止、未知状态显示真实值；终端选择/关闭/新增独立，真实未读/结束保留；恢复回调/无能力无假动作；自动化不依赖floating展开、停止/截图回调独立，失败与空态不冒充完成。

最终预览检查后，将子代理/自动化停止动作统一为dangerText语义色；最终同一组48项回归全部通过（0失败/错误/跳过），wasm编译通过，最终深浅组件预览已重新检查。资源中英XML无重复键，`git diff --check`通过。完整APP、真实PTY/网页宿主focus与遮挡、三桌面平台仍按主计划待验，缺idea-mcp工具，未用bash运行APP/server。

实际验证命令：

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/workspace-fourth-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*WorkspaceFourthDeliveryTest' --tests '*WorkspaceThirdDeliveryTest' --tests '*WorkspaceSecondDeliveryTest' --tests '*WorkspaceFirstDeliveryTest' --tests '*SubagentPanelViewModelTest' --tests '*BrowserReasoningReducerTest' --tests '*AppLanguageResourceTest'
```

下一批按R13表现层与可访问性收口：诊断/原始消息、Todo/计划步骤、Tooltip/Menu/Dialog及跨区域动作一致性。继续保留Core事实与MVVM边界；全局ReducedMotion/状态协作者/完整实机验收尚未完成，不因本批标整个R12或主计划完成。
