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

### Cloudflare 隧道（desktop jvmMain）

`DesktopRemoteControlHooks`（`app/desktopApp/.../DesktopRemoteControlHooks.kt`，desktop 宿主专属注入）：

- `cloudflared --version` 探测；未装 → UI 显示"请自行安装"，mederi 不代装不教程
- `startTunnel()`：**pty4j**（JVM-only 无 KMP 替代品）以真实 pty 拉起 `cloudflared tunnel run`
- **生命周期**：本进程退出 → OS 关 pty master → SIGHUP → cloudflared 退出（"终端关了进程就死"，无需看门狗）
- **域名**：解析 `~/.cloudflared/config.yml` 第一条 ingress `hostname`；**局域网地址**：`localAddress` 枚举 site-local IPv4
