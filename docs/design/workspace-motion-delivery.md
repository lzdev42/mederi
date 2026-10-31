# 统一动效与减弱动效

> 2026-10-30用户授权。代码与组件验证完成，完整APP/录屏/三平台观感待验。浏览器设置/专属组件由另一AI删除，本批不修改。设计仍为中性、轻量、内容优先，保持异步工作/输入独立。

1. 单一 WorkspaceMotionPolicy：反馈120ms、局部展开180ms、面板200ms、任务单色呼吸2400ms、无进度请求等待1000ms；复制1500ms为静态反馈寿命，不是动画速度。所有自有有限过渡引用同一Policy，无自创spring/时长。
2. 设备偏好：外观设置新增“减弱动效”，AppState/PreferencesStore设备本地持久化，AppTheme提供同一CompositionLocal。开启时有限过渡即时完成、持续循环不创建，状态文字/数量保留。不宣称自动读取系统偏好；无Core/Server契约改动。
3. 循环收敛：任务组优先一个呼吸，单行子任务不叠加循环；真正请求等待共用指示器。隐藏/不在组合内、停止/终态或减弱模式都不驱动循环；等待用户不伪装运行。原WorkingAnimations兼容入口收敛，保留静态标识。
4. 验证：策略/设备持久化与独立性、可见/运行/减弱分支、Compose时钟验证组展开折叠/终态停止、普通开合/静态模式即时响应。既有工作台/设置回归、wasm编译、深浅与减弱实际组件预览。更新架构、共同标准、主计划与明确未实机项。


## 实际交付

- 新增WorkspaceMotionPolicy/LocalWorkspaceMotion，所有本批自有有限动效引用同一策略；WorkspaceStyle旧时长转兼容别名。27个既有工作台/Sidebar/概览/内容/共享控件文件完成有限过渡迁移，无私有spring/tween节律；开合明确enter/exit，减弱模式None，发送按钮减弱模式取消按下缩放。
- WorkingAnimations历史BorderBeam/ShimmerWave/BreathingPulse签名保留，均委托同一2400ms单色alpha呼吸，duration/style/secondary仅旧调用兼容；不再扫描正文、扩散波纹或缩放。会话运行点/旧badge共用单点呼吸，WaitingUser静态。
- 任务组概览折叠/展开都保持唯一主循环，子Agent/后台正文不叠加循环。工作过程展开后去掉外层等待转圈；等待子Agent时不伪装成请求加载；原Core状态、输入可用性、真实计时和各项停止范围完全保留。
- 新增共同WorkspaceLoadingIndicator替换非浏览器10个文件中的Material独立spinner：工具/推理/工作过程/状态/文档、初始化、原始消息、Provider/Agents/遥控设置。统一1000ms线性圆弧；减弱时静态但保留不定进度可访问语义及周围真实状态文字，终态/隐藏不渲染。任务呼吸与等待转圈都在裁剪后的boundsInWindow面积为零时不创建无限transition；出组合自动释放。
- AppState设备偏好 `ui.motion.reduced` → StateFlow → App.collect → AppTheme/LocalWorkspaceMotion；外观页共用单选控件，不新增配置副本。新文案中英同源，未新增依赖、Core行为、数据库或AiCore/Server契约。只提供手动选择，未自动读取OS偏好。

## 验证证据

最终20个suite共135项测试（既有124＋本批11），0失败/错误/跳过；wasm/JVM编译通过。本批WorkspaceMotionPolicyTest 7项、WorkspaceTaskMotionDeliveryTest 4项，覆盖：统一时长与所有reduced/running/visible组合；设备持久化/重新水合与同Core不同设备独立；标准加载圆弧实际图像随时钟变化、减弱模式逐像素不变；设置选择仅通过外部状态生效；减弱模式展开即时保持内容；实际概览任务组折叠/展开均呼吸、终态逐像素静止；真实clipToBounds移出/返回暂停/恢复；等待用户实际会话点静态。

首轮裁剪测试需要额外一帧将布局后visibility写入投影到SideEffect，按Compose布局/重组时序修正测试后通过；未靠放宽可见性断言掩盖问题。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/workspace-motion-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*Workspace*DeliveryTest' --tests '*WorkspaceMotionPolicyTest' --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest'
```

已目视检查实际外观设置/静态加载fixture `temp/workspace-motion-delivery/motion-dark.png`、`motion-light.png`。这些是组件截图；运动行为另由实际组件时钟与图像差异断言证明，静态截图不冒充录屏。中英XML合法/无重复键、git diff --check通过。自有UI无限transition仅剩Task alpha和Loading rotation两个实现，所有自有有限动画无独立tween/spring；浏览器专属文件明确排除。

## 未覆盖与交接

当前没有idea-mcp工具，完整APP需先启动/配置idea-mcp再运行；未用bash启动APP/server。完整原生窗口/三平台观感录屏、键盘/焦点最终验收仍待环境。窗口失焦/应用进后台暂停、OS系统偏好自动映射未实现；Material/原生菜单、第三方控件/InkCompose与终端内部动画不宣称已迁入本策略。浏览器专属代码交另一AI删除，本线未动。

R04b自有动效代码/组件交付完成，不代表整个设计计划完成。剩余重点是沙盒/遥控设置、工作台MVVM边界复核与最终实机验收。
