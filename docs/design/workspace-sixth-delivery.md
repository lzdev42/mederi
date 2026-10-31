# 第六批：弹窗、菜单与提示

> 2026-10-29，用户授权继续。状态：代码与组件验证完成，完整APP实机待验。R04a/R09/R13表现层子集。

延续中性轻量、13/20与12/18、120ms反馈、最小30dp操作。保留MVVM与异步工作；关闭弹层只转发既有dismiss，不停止后台任务。

1. MederiDialog公共壳修正内外留白，适应可用宽高；Confirm/Input动作可回流，输入空白仍不可确认，危险确认仍区分。检查Sidebar/设置复用，避免只迁移某个确认框。
2. 输入区四类下拉菜单共用外壳和选中项表现，保留模型/Key/推理/项目现有数据、点击和平台菜单行为。
3. Dock与帮助提示共用轻量提示内容及窗口边缘定位；Dock去装饰边框/死角标，选中、键盘焦点可辨；Help保留点击/触控打开、外部关闭，不依赖hover。
4. 错误详情沿用解析和Bug报告/URI回调，收敛到共同弹窗、动作和可读日志。

实际Compose测试覆盖确认/取消/禁用/输入、长文缩放、菜单目标选择、Dock选中与回调、帮助点击与提示位置边界；深浅组件预览和wasm编译。完整APP焦点回归、原生视图遮挡、三平台实机仍待idea-mcp，不把本批标记整体重构完成。


## 实施边界与首轮修正

未改Core/VM、契约、供应商设置数据、报告模板或异步任务调度。新原子均在commonMain，无新依赖。公共MederiDialog签名保留，Sidebar/Provider调用编译兼容；Provider整页与原生窗口仍未实机验证。

首轮59项回归发现菜单Escape与帮助重复点击/回焦问题，修正后定向本批9项全部通过：菜单显式消费Escape KeyDown；帮助点击使用可聚焦popover接收Escape，关闭后抑制锚点自动回焦重开，hover仍不抢输入焦点。危险primary复用WorkspaceActionButton末尾可选tone参数，Danger为语义淡红底/文字，其余调用默认Accent不变。


## 最终验证与交接

最终7个suite共59项测试（本批9、前五批47、语言资源3），0失败/错误/跳过；wasm编译通过。覆盖确认/取消独立、空白禁用且输入原值不被裁剪、280dp/200%字体长文滚动可达、菜单selected/选择回调/Escape独立、Dock选中和目标开关、帮助点击/键盘开关与Escape、Tooltip LTR/RTL边缘与超大内容定位安全、错误详情日志和三动作独立。

深浅主题实际组件fixture已检查：`temp/workspace-sixth-delivery/workspace-popups-dark.png`与`workspace-popups-light.png`。危险动作已用淡红背景，常规选中iris；错误无红色大底，完整日志保留。`git diff --check`通过，无新增用户文案/依赖。截图不是完整APP；Sidebar/Provider整页、三平台Dialog原生焦点归还与网页/PTY遮挡尚未实机验证。当前idea-mcp不可用，需启动/配置后运行APP/server，未使用bash启动。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/workspace-sixth-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*WorkspaceSixthDeliveryTest' --tests '*WorkspaceFifthDeliveryTest' --tests '*WorkspaceFourthDeliveryTest' --tests '*WorkspaceThirdDeliveryTest' --tests '*WorkspaceSecondDeliveryTest' --tests '*WorkspaceFirstDeliveryTest' --tests '*AppLanguageResourceTest'
```

下一批：收敛统一MotionPolicy/ReducedMotion，明确用户偏好和平台适配边界，再推进剩余MVVM状态所有权及最终实机验收；本批不标整个R13/总体计划完成。
