# 第三批：文件、diff与阅读器

> 2026-10-29，用户认可前两批后授权继续。状态：代码与组件验证完成；完整APP视觉验收待idea-mcp环境。
> 仅推进R10/R11表现层子集；完整MVVM协作者迁移和三平台实机验收仍见主计划。

## 理念与边界

延续“对话继续、任务并行”的中性轻量工作台。文件是可打开的内容，变更是可检查的事实，阅读器让正文优先。导航、工具条与主内容各自只承担一层职责，不重复强调标题、外框与操作。

- 消费WorkspaceStyle现有13/20、12/18、16dp图标、最小30dp控件、120ms反馈；不另建主题。
- 文件/阅读器导航共用WorkspaceNavigationTab：明确selected语义，单一软底选中，hover/focus一致；单项上限240dp保证横向滚动中标签可见，文件两种导航随字体缩放换行。
- 文件列表保留真实路径、增删数、文件打开回调；长路径让位于主操作，不挤出计数。
- 树保留provider、过滤、父路径保留、展开、选中、刷新与统一openDocument路由。
- 阅读器保留Markdown渲染、复制全文、HTML/PDF导出互斥与结果、图片适应/原比例及双轴滚动；轻量工具条允许窄屏换行。
- 纯视图以参数/回调为边界，VM包装保留现有行为；不修改Core/契约、文件安全路由或导出I/O。现有导出编排迁出View为R11后续任务，不在视觉批次偷改。

## 执行与验收

| 子任务 | 文件范围 | 行为验收 | 状态 |
|---|---|---|---|
| D1 文件导航与树 | FilesPanel、TreePanelContent、共享导航原子 | selected语义；过滤保留父路径；展开/折叠、选中/打开、刷新回调 | 代码与组件验证完成 |
| D2 变更入口 | DiffCards、InfoPanels的diff | review/文件入口独立；长路径增删可见；diff选择与正文一致 | 代码与组件验证完成 |
| D3 阅读器 | InfoPanels、ViewerTabs | 文档tab切换、复制、导出互斥/失败、图片比例切换、深浅主题/窄屏 | 代码与组件验证完成 |

独立文件分工，主代理维护共享原子、资源、规范、验证；不并发构建。实际Compose组件测试与FileOpeningFlowTest回归、wasm编译；深浅色组件预览检查。完整APP只能通过idea-mcp，本会话无该工具，实机验收明确待办，不用bash运行替代。

## 验证记录（持续更新）

首轮43项相关测试中42项通过；1项树刷新测试因搜索输入与目录同名导致定位歧义，改为定位非输入目录动作。预览检查另发现横向滚动Tab的无限宽约束使加权文字宽度归零，已用共享240dp上限修正，并补文字可见宽度断言；不是以仅能点击/语义存在替代视觉验证。文件导航额外覆盖280dp、1.5倍文字；最终回归已全部通过，结果如下。

导出测试使用可控假结果，验证真实按钮互斥、失败与取消，不弹系统保存框；文件路由仅回归provider路径/目录解析与分类，不在测试中实际打开外部Office应用。完整文件打开路由、真实导出和长Markdown/图片滚动仍保留实机验收，未声称R10/R11整体完成。


## 最终结果与续做

2026-10-29：6个suite共44项测试，0失败、0错误、0跳过；其中WorkspaceThirdDeliveryTest 14项真实Compose组件测试，首批6项、第二批9项、文件路径1项、文件类型11项、语言资源3项。`:app:shared:compileKotlinWasmJs`编译通过，`git diff --check`通过。

实际命令：

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/workspace-third-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*WorkspaceThirdDeliveryTest' --tests '*WorkspaceSecondDeliveryTest' --tests '*WorkspaceFirstDeliveryTest' --tests '*FileTypeClassifierTest' --tests '*FileOpeningFlowTest.testProjectFileTreeProviderJvmResolutionAndDirectory' --tests '*AppLanguageResourceTest'
```

最终深浅色Compose组件预览已生成并检查：`temp/workspace-third-delivery/workspace-files-dark.png`、`workspace-files-light.png`。文字/计数/操作可见，导航标签宽度已修正。预览是实际组件的fixture组合，不是完整APP或三平台截图；长正文、真实图片和系统导出的视觉/平台集成仍按主计划验收。

本批沿用MVVM＋单向数据流。新增纯视图参数入口保留原VM接线；导出协作者/文件查询协作者迁移不在本批完成范围，不能标记整体R10/R11完成。下一批：R12表现层子集——终端、网页/自动化与子代理详情，先读主计划与对应架构定位，继续复用同族导航/工具条/动作/动效。
