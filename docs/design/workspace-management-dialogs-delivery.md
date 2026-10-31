# P0：Skill / MCP 管理弹窗统一

> 2026-10-30，用户明确先做剩余任务第2项。代码与组件验证完成，完整APP实机待验；不处理浏览器，由另一位AI删除。

1. 公共原子：普通表单、确认共用 MederiDialog、WorkspaceActionButton、ErrorBoard；抽同一字段与动作行，确认扩展可选 enabled/说明插槽保持调用兼容。共享提交协调只持有忙态/错误与去重，不保存领域配置；Store/Core 仍为真理源，表单草稿为局部表现状态。
2. Skill安装/目录/MCP新增编辑改为同一文本提交表单，保留原trim边界、MCP原JSON结构/内容、初值与失败草稿。安装/保存中不重复提交，不允许Escape/外部关闭绕过禁用取消而取消在途操作；成功按原行为关闭，失败就近共用ErrorBoard。目录选择保留桌面与遥端区别。
3. Skill卸载复用共同危险确认，不另画红按钮；精确名称、原Store API与失败反馈保留，不伪装成功。补齐原回调忽略Result导致无法在弹窗区反馈的问题。
4. 审计全部直接Dialog：共享底层、专用移动Sheet/截图Lightbox登记例外；普通压缩提示迁入共同壳。浏览器专属设置不触碰。测试真实组件回调/在途禁用/错误重试/取消独立/草稿与JSON/大字体滚动，实际深浅组件预览；更新架构、流程、标准与主计划。

验收命令预定：`:app:shared:compileKotlinWasmJs` + 本批管理弹窗Compose tests + 既有共享弹层/设置回归。完整APP仍只经idea-mcp运行，缺工具如实记录。


## 实际交付

- `WorkspaceForm.kt` 为共同字段/动作行/本地提交协调/文本表单唯一实现；ConfirmDialog、InputDialog、Provider LabeledTextField和Skill/MCP实际消费同一字段或动作View。没有仅复制外观的Skill/MCP V2控件。
- InstallSkillDialog、SetSkillRootDialog、McpConfigDialog只装配领域文案、原初值和Store回调，保留提交时trim及内部JSON空白/字符；失败保留草稿，父级初值刷新不覆盖正在输入的内容。目录选择只在桌面显示，取消选择不保存，选择期间不冒充正在保存。
- UninstallSkillConfirmDialog改用ConfirmDialog；原onConfirm忽略Result，现返回Result<Unit>，成功关闭、失败原地展示ErrorBoard，精确技能名称仍由原Overview目标传入。外部Store忙态与本地提交忙态共同约束重复操作。
- 共同ConfirmDialog追加enabled/supportingContent兼容参数；disabled覆盖确认、取消、Escape/外部dismiss，防止原来“取消按钮不可用但Esc仍取消在途协程”的不一致。异步操作由原Store/Core负责，无新配置副本、AiCore契约、数据库或依赖变更。局部提交状态仅为表现协调，不宣称整体MVVM迁移完成。
- MainScreen压缩跳过提示也使用MederiDialog/WorkspaceActionButton，保留单确定动作、原事件与notice状态。MCP编辑入口原getJson加载仍是后续整体MVVM核查范围，本批未重写Store或概览读取链。

## 直接 Dialog 审计

| 剩余位置 | 用途与决定 |
|---|---|
| atoms/Dialogs.kt | MederiDialog唯一平台窗口基底，保留 |
| ChatInputSelectors.kt / MobileModelBottomSheet | 专用底部定位、遮罩与移动选择交互，登记例外 |
| BrowserScreenshotLightbox.kt | 专用全屏截图预览/缩放，登记例外，不属普通确认表单 |
| BrowserSettingsPanel.kt / BrowserHomeDialog | 用户交由另一AI删除，本线排除，不改代码 |

Skill安装/目录/卸载、MCP配置和MainScreen普通提示已无直接Dialog；ui范围无直接AlertDialog。此结论不等于三平台原生窗口/焦点已验收。

## 验证

最终18个suite共124项测试（既有117＋本批7），0失败/错误/跳过；wasm/JVM编译通过。新增WorkspaceManagementDialogsDeliveryTest覆盖：安装URL trim、在途重复提交/取消/Escape无效、失败保留草稿/重试成功关闭；MCP JSON内部字符和空白保留、上游初值变化不覆盖草稿；目录初值/trim/失败与独立取消；卸载失败保留/重试成功/外部busy不误删；280dp/200%字体长说明/路径/错误/挑选与动作滚动可达；实际Content深浅截图。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/management-dialogs-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*Workspace*DeliveryTest' --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest'
```

已目视检查 `temp/management-dialogs-delivery/management-dialogs-dark.png`、`management-dialogs-light.png`，为实际共享Content/外壳配fixture数据，不是完整APP。中英XML合法/无重复键，git diff --check通过。无真实安装/卸载/MCP网络调用。当前未提供idea-mcp；需先启动/配置才能运行APP/server，未使用bash启动。原生焦点归还/三平台窗口与完整业务端到端仍待验。

自动审批最初拒绝了整文件覆盖命令，担心文件名对应管理卡片而误删业务。核对完整声明证明Skill文件仅三个弹窗、MCP文件仅弹窗与模板后，改用明确补丁迁移，保留模板与调用入口；没有阻塞或待批准动作。

## 当前交接

P0普通管理弹窗代码/组件交付完成；后续仍有沙盒/遥控设置、统一MotionPolicy/ReducedMotion、剩余工作台MVVM核查与整体原生验收。浏览器继续排除。不要把本批交付误写为整个设计计划完成。
