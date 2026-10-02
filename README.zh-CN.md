# Mederi™

Mederi™ 是一个基于 Kotlin Multiplatform 的 AI Agent 应用框架，构建于 Koog 执行引擎之上。它提供供应商配置、项目与会话管理、持久化、计划驱动的执行流程、执行沙箱以及跨平台 UI——无需自行拼接这些层即可组装出由 LLM 驱动、可调用工具的 Agent。

> **开发状态：** Mederi 处于开发中，UI 与核心功能随时可能变动。

## 特性

- **跨平台** — Android、iOS、Web（Kotlin/Wasm）、Desktop（JVM）以及独立的 Ktor server 共享同一套 core。
- **计划驱动执行** — 复杂工作经 *计划 → 规范 → 执行 → 验证* 闭环完成，可选 **审批**（由人批准计划）或 **自主**（自动批准）模式，并对文件与命令执行施加沙箱限制。
- **远程遥控** — 通过内置 server 从浏览器或手机遥控桌面端，可选密码门与 Cloudflare 隧道。
- **浏览器自动化** — 内置（JCEF）与外置反检测浏览器操控，供 Agent 驱动的网页交互。
- **InkCompose** — 自包含富文本渲染库：原生 Markdown、LaTeX/数学公式、代码高亮与 Mermaid 图表。

## 发布

Release 版本以独立产物分别发布：

- **core** — 领域模型、Manager、Koog 适配与存储。
- **server** — Ktor 薄 REST/SSE 层。
- **InkCompose** — 独立的 Markdown 与数学公式渲染库。

## 构建与测试

```bash
./gradlew :inkcompose:jvmTest     # 主测试安全网
./gradlew :inkcompose:assemble    # 构建全部目标
./gradlew :app:shared:jvmTest     # shared 模块测试
./gradlew :core:jvmTest           # core 测试
```

## 开源协议

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### 第三方开源致谢

本项目包含以下第三方开源软件或其衍生实现：

- **InkCompose** — 富文本、LaTeX 与代码高亮渲染模块，核心实现衍生自 [@huarangmeng](https://github.com/huarangmeng) 的开源项目（MIT License）：
  - [Markdown](https://github.com/huarangmeng/Markdown)（MIT License）
  - [latex](https://github.com/huarangmeng/latex)（MIT License）
  - [codehigh](https://github.com/huarangmeng/codehigh)（MIT License）
  - Copyright (c) 2026 huarangmeng

## 商标

"Mederi" 及 Mederi 徽标是 lzdev42 在美国及其他国家/地区的商标。

尽管本项目源代码基于 Apache License 2.0 开源，但该许可证并未授予使用 Mederi™ 商标、商业名称或徽标的权利。

- **合理使用**：您可以使用该名称如实引用本项目或表明兼容性（例如 "Mederi™ 插件"）。
- **使用限制**：未经事先书面许可，不得以 "Mederi" 名称分发本软件的修改版本，亦不得以此暗示存在官方背书、赞助或附属关系。

关于商标使用准则、许可范围及分支命名政策的完整细则，请参阅 [商标政策文件 (TRADEMARK.md)](./TRADEMARK.md)。

## 语言

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
