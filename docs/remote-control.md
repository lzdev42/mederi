# 遥控功能实现细节

> 本文档从 AGENTS.md 拆出，记录 Server interface 的实现细节（端口记忆、密码门、Cloudflare 隧道）。
> 只在改动遥控相关功能时读。契约同步硬性规则仍在 AGENTS.md §6。

## 嵌入式 server interface（app/shared/jvmMain）

`Server`（`app/shared/src/jvmMain/kotlin/xyz/mederi/server/Server.kt`）= server interface 的实现：

- **启动**：`Server.start(aiCore, port, password, webappDir)`，幂等；`stop()` 幂等
  - `host = "0.0.0.0"` 监听所有网卡，局域网内手机/浏览器可直接访问
  - **端口策略**：先按请求端口起；被占用 → 改 port=0 让 OS 自动挑空闲端口，经 `engine.resolvedConnectors()` 读回实际端口
  - `webappDir` 非空时同源托管 wasm Web UI；`password` 非空时 `/v1` 路由要求 Bearer 鉴权（`/` 与 `/v1/ready` 豁免）
- **调用方**：desktop（在 desktop interface 之外再起一个 server interface 实例，观察 `remoteControlEnabled` 自动启停，供遥端遥控进来）和 server 模块（独立部署，只跑 server interface），都依赖 `app:shared`，不相互依赖
- **server 模块**（`server/Application.kt`）：薄启动器，仅创建 `MederiAiCore` + `initialize()` + 调 `serverModule()`，路由代码不重复

### 端口记忆（AppState 职责）

遥控端口由 `appState` 持久化（`remote.port`，默认 8081）：`startRemoteControl()` 用已保存端口起，
成功（含端口回退）后回写实际端口。UI 展示 `remoteServerState`（Idle/Starting/Running(port, portFallback)/Failed）。

### 密码门（app/shared/wasmJsMain）

`RemoteGate` 在 wasm 遥控端的 `MederiApp` 外层包裹，密码未验证时不创建 AiCore：
- 启动时带 localStorage 密码探测（GET /v1/providers + Bearer）→ 200 直接进，401 弹密码输入
- 用户提交 → 同接口重试（真实服务端判定，不做本地比对）→ 通过写 `localStorage("mederi.remote.password")`
- **禁止 `?password=` URL 参数**（会留浏览器历史）

### Cloudflare 隧道（桌面宿主）

设置中填完整的公网子域名（例如 `m.emuci.site`）和 Cloudflare API Token。域名所在 Zone（例如
`emuci.site`）需要在 Cloudflare 中激活。Token 授予对应账户的 Cloudflare Tunnel Edit，以及目标
Zone 的 DNS Edit 和 Zone Read。桌面仍需安装 `cloudflared`。

- `AppState.startTunnel()` 使用 `RemoteServerUiState.Running.port`，由 Ktor `resolvedConnectors()`
  报告实际监听端口。端口回退后用同一个来源，不依赖上次保存值。
- `CloudflareTunnelProvisioner` 按公网主机名查 Zone 和现有 CNAME。已有代理 CNAME 指向同账户的
  Cloudflare 远程管理隧道时复用它；没有记录时按固定名称查找或创建隧道，再补建代理 CNAME。
  遇到冲突的 DNS 记录停止并报错。
- 读取隧道配置，将对应主机名的 `service` 对齐到 `http://127.0.0.1:<实际端口>`，保留其他规则、
  顺序及源站设置。配置一致时跳过 PUT；端口变化不会修改已有 DNS 记录。
- API 取得连接器 Token，以 `TUNNEL_TOKEN` 环境变量交给 pty4j 启动的 `cloudflared`。
  连接边缘后，自动模式验证公网 `/v1/ready` 可达。公网验证需要该探测路径允许访问。
- 自动模式首次启动成功后，随遥控服务恢复。显式停止隧道关闭自动恢复；关闭遥控或退出应用暂停连接。
  pty 生命周期保留，应用退出时 OS 关闭 pty master，`cloudflared` 随之退出。
- API Token 保存到本机 `~/.mederi/cloudflare-api-token`，文件只允许所有者访问。公网域名和自动恢复
  开关仍存设备偏好。不填 API Token 时仍可使用已有连接器 Token 启动手动配置的隧道。

[Cloudflare 官方 API 设置流程](https://developers.cloudflare.com/tunnel/get-started/)
