# ChatGPT 授权供应商接入计划

日期：2026-11-05。范围已由用户确认：新增独立供应商、浏览器授权、自动获取模型、显示服务端提供的 5 小时/每周剩余额度。直接开始开发，不另设审批。保留所有其他供应商。

## 官方 API 核对

| 能力 | 官方接口/协议 | 本项目处理 |
| --- | --- | --- |
| 初次注册与授权 | GET https://auth.openai.com/api/accounts/authorize；client_id=dynamic_agent_client，PKCE S256、state、nonce、ext_agent_host_id | 系统浏览器授权，127.0.0.1 loopback 回调；保存返回的 issued client_id |
| 换取 token | POST https://auth.openai.com/api/accounts/oauth/token，authorization_code grant | 校验 state、OIDC 签名/issuer/audience/nonce/expiry 以及授权 scope 后保存 |
| 续期 | 同一 token endpoint，refresh_token grant | 同账号串行刷新，原子替换 rotating refresh token |
| OIDC 发现/JWKS/撤销 | GET https://auth.openai.com/.well-known/openid-configuration；读取 jwks_uri 与 revocation_endpoint | 验证 ID token；退出撤销会话并清本地 token；撤销未确认时明确报错 |
| 账号可用模型 | GET https://api.openai.com/v1/models，OAuth Bearer | 解析 models[] 的 slug/display_name/visibility，保持服务端顺序；不当作普通 data[].id |
| 推理 | POST https://api.openai.com/v1/responses，OAuth Bearer | store=false、stream=true、完整 input；developer 消息，function/custom 工具按授权路由规范编码 |
| Codex 额度 | app-server JSON-RPC account/rateLimits/read；通知 account/rateLimits/updated | 官方存在此 RPC，但 SIWC 新授权是否能读取同样额度尚未证实；不可编造 HTTP 地址或拿 API token 消耗估算订阅额度 |
| Codex token 活动 | app-server account/usage/read | 累计 token 活动不等于订阅剩余额度，不用于替代 |

来源（实际查阅）：
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server
- https://learn.chatgpt.com/docs/app-server#auth-endpoints

适用边界：官方 SIWC 套餐授权面向开源/本地自托管应用；商业托管另需官方接入。Plus 五小时窗口跨应用共享；官方注明 Pro 无五小时限额。窗口必须按服务端数据识别，缺失不代表 100% 剩余。新 SIWC 文档目前只明确提供 Usage 设置链接，额度读取需继续验证。

## 架构与边界

- Provider 保持 OPENAI_RESPONSES 协议，新增独立认证类型 CHATGPT_OAUTH（默认 API_KEY，兼容存量 payload）。每个授权连接是一个供应商 ID，不依赖名称判断。
- core 管理授权状态与保护的本地凭据；token 不写 Provider DTO、不进入遥控响应、日志、浏览器存储或普通 key 选择器。授权生命周期不挂宿主 main。
- ProviderManager 负责创建供应商与获取有效凭据；现有 ApiKeyResolver 的 OAuth 分支不参与 key 轮询/明文缓存。请求级刷新避免长任务持有过期 token。
- AiCore → MederiAiCore → ServerAiCore → Server 四处同步登录、状态、取消、退出和额度操作；UI 保持 commonMain + 中英文资源。
- 登录成功自动刷新模型；模型列表失败需单独报告，不能撤销已经成功的授权或默默宣告同步成功。
- 不启动新的 Codex agent 替换 Mederi/Koog。额度如无可验证官方读取路径，明确返回 unavailable 并提供 Usage 链接；此项不能标记为真实额度联调已通过。

## 子任务与验收

### 1. 授权内核和供应商凭据（进行中）
- 实现 PKCE/OIDC、回调生命周期、取消/超时、重启恢复、原子保护存储、串行刷新与注销。
- Provider 增加认证字段，ProviderManager/ApiKeyResolver 接入有效 OAuth 凭据，禁 OAuth 修改任意端点及 API key。
- Verification：`./gradlew :core:jvmTest --tests '*ChatGpt*'`；断言 state/nonce/签名错误拒绝、取消不落 token、刷新并发只调用一次、序列化不泄漏 token。

### 2. 模型目录及 Responses 适配（与 1 并行）
- OAuth 专用模型目录解析，远端元数据沿 ModelMerge 流转；普通 Key 协议不变。
- OAuth 请求限定 store=false/stream=true；工具 namespace 与历史调用/结果 ID 保持一致；非流式调用通过流式聚合支持。
- Verification：定向 `:core:jvmTest` 测试模型过滤、顺序、请求参数、工具循环和正文/工具输出，不仅编译。

### 3. 契约、服务端及跨平台 UI（与 1/2 并行）
- 添加授权入口、登录链接/取消、连接状态/退出、自动模型同步及额度卡片；连接多个供应商继续正常选择。
- 登录 loopback 回调发生在 core 所在宿主；遥控端明确提示使用宿主浏览器，避免手机本地 127.0.0.1 误回调。
- Verification：`:app:shared:jvmTest` 定向状态/契约测试，JVM/wasm 编译；敏感字段不出现在 DTO，全部新增文案中英齐备。

### 4. 额度证据核实与展示
- 逐项核对官方额度 API 的授权模式，优先真实可用路径；返回已用百分比/窗口分钟数/重置 Unix 秒，UI 算 remaining=100-used。
- 未提供窗口显示未提供；不适用、不支持、请求失败分别表达。不得展示虚构数值。
- Verification：样本覆盖 300/10080 分钟、多 bucket、缺失/null、错误和重置时间；真实账号调用需用户交互完成 OAuth，未联调明确记录。

### 5. 集成审查和架构同步
- 审查跨线程刷新/退出竞态、token 泄漏、供应商删除清理、与现有未提交改动的兼容。
- 同步 docs/architecture/01-core.md、02-app-shared.md、04-flows.md。
- Verification：相关 core/shared 测试和编译通过；APP/server 仅允许 idea-mcp 启动，本会话无此工具时报告运行环境限制，不 bash 启动。

## 执行记录

- 已核对官方 API，已读取 ProviderManager、Provider/ApiKeyResolver、KoogClientFactory、Responses 编码、AiCore 与供应商 UI 架构。仓库已有大量未提交改动，实施以最小局部补丁叠加，不回退已有工作。
- 真实登录及额度获取尚未执行；不读取本机其他应用的登录文件。

### 额度接口进一步核对（2026-11-05）

- SIWC token-reference 明确列出新 access token 的认证元数据为 `per_user_salt`/`encrypted_auth_metadata`，要求视为 opaque；未提供 Codex external auth 所需的 `chatgptAccountId`。因此不能凭 `sub`、email 或猜测账号 ID 调用 Codex `chatgptAuthTokens` 来宣称同一授权可读额度。
- 官方 SIWC app-server 配置是自定义 Responses provider + `requires_openai_auth=false`，不是 Codex 内置 ChatGPT 登录；app-server 文档中的 rateLimits RPC 不能直接等同新 SIWC 的授权能力。
- 当前实施采用官方原生 SIWC 推理，不隐式安装/启动 Codex CLI、不读取其他应用凭据、不调用猜测的 ChatGPT backend-api。额度 DTO/UI 真实支持窗口呈现，但官方直读路径未公开时返回 `OFFICIAL_USAGE_API_UNAVAILABLE`，保留官方 Usage 设置入口。**真实 5h/周剩余数值尚未满足，不能把此项写为验收通过。**
- 参考：https://developers.openai.com/siwc/token-sharing-open-source/token-reference 。OIDC discovery 已实际读取并确认授权、token、JWKS、revocation 地址：https://auth.openai.com/.well-known/openid-configuration 。

### 实施结果

- 子任务 1 已实现：`provider/oauth/` 五个实现文件，ProviderAuthentication、ProviderManager、ApiKeyResolver、Mederi 装配；凭据和运行时 callback 不进入 Provider 持久化，OAuth 禁用普通 key 轮询及任意端点。
- 子任务 2 已实现：账号模型目录独立解析；OAuth 请求 namespace、store/stream、完整 SSE 聚合及每请求有效 token；普通 key 适配保持原协议。
- 子任务 3 已实现：AiCore/两桥/server 五项接口同步，设置页授权与模型选择状态、中英文资源；遥控宿主浏览器提示。AiCore 自己观察授权终态并同步模型，离开设置页不会停止业务流程；UI dispose 仅停止观察。
- 子任务 4 **部分完成**：额度 DTO、百分比/重置时间呈现、未知/未提供状态及官方 Usage 链接已实现。真实订阅额度读取未接通，原因见上节；不是已完成的 5h/周额度功能。
- 子任务 5 架构文档已同步至 01-core、02-app-shared、04-flows；正在执行最终回归。
- 初次编译 core/JVM 与 shared JVM/wasm 均通过。测试中发现 JUnit expression-body 返回非 Unit，以及公开 LLMClientException 包装与测试期待的根因类型不一致，两处测试定义已修正，未放宽截断响应必须失败的业务断言。
- 当前会话无 idea-mcp；按 AGENTS.md §4 不启动 APP/server。真实浏览器账号登录、账号目录权限与实际推理尚未实测，不标记联调通过。

### 最终验证记录

以下命令通过（2026-11-05）：

```bash
./gradlew :core:jvmTest \
  --tests '*ChatGpt*' --tests '*ApiKeyResolverTest*' \
  --tests '*ApiKeySelectionTest*' --tests '*ModelMergeTest*' \
  --tests '*MederiOpenAILLMClientTest*' \
  :app:shared:jvmTest --tests '*ChatGptAuthorizationTest*' \
  --tests '*ProviderCatalogViewModelTest*' --tests '*ProviderSettingsFreeFilterTest*' \
  :app:shared:compileKotlinWasmJs --console=plain
```

- core：50 tests，0 failures/errors，其中新增授权安全 8 项、端点校验 2 项、模型/Responses 6 项。
- shared：11 tests，0 failures/errors，其中新增授权 UI 状态/取消/设置页退出/未知额度/DTO 安全 5 项。
- shared JVM 编译与 wasmJs 编译通过；`git diff --check` 通过。未新增依赖，未修改数据库 schema，未读取其他应用的登录凭据。
- 最终审查修复了授权超时与 token 保存的竞争：超时移除和取消使用同一待授权锁，终态保存前再次检查实例身份。
- **交付状态**：子任务 1/2/3 已实现并通过上述离线行为验证，子任务 5 文档/编译/定向回归完成；子任务 4 的真实额度读取未完成。真实账号与界面联调等待 idea-mcp 运行环境及用户完成 OAuth，不宣称全部需求已完成。

### UI 与浏览器修复（2026-11-05）

- 用户反馈：授权入口应归“添加供应商”，全局横条破坏布局；点击未启动浏览器。
- 已移除全局横条，目录增加 ChatGPT 授权方式；账号状态/操作与额度仅进入所选 OAuth 详情，并与模型共用滚动。
- 浏览器打开不再等待 Composition effect；收到授权地址后先执行可检查结果的系统派发，再导航。授权 VM 上移，避免选择更新清掉待登录状态。macOS 使用系统 open，URL 单独 argv；失败保留 pending 地址并提供重试、复制、取消。
- 新增浏览器派发/导航竞态与失败恢复测试，以及目录入口和详情窄宽布局的 Compose UI 回归/可选截图。正在验证。

UI 修复验证完成：shared JVM/wasm 编译通过；31 项相关回归（授权状态 7、浏览器派发 4、新目录/详情 UI 2、既有目录 10、既有详情 8）全部通过。新 UI 的测试预览已实际查看，保存在 `temp/chatgpt-ui-review/`。没有启动 APP/server，也没有在测试中打开真实浏览器；实机登录与系统弹窗仍需 idea-mcp 环境联调。
