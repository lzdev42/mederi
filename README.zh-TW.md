# Mederi™

Mederi™ 是一個基於 Kotlin Multiplatform 的 AI Agent 應用框架，構建於 Koog 執行引擎之上。它提供供應商設定、專案與工作階段管理、持久化、計畫驅動的執行流程、執行沙箱以及跨平台 UI——無需自行拼接這些層即可組裝出由 LLM 驅動、可呼叫工具的 Agent。

> **開發狀態：** Mederi 處於開發中，UI 與核心功能隨時可能變動。

## 特性

- **跨平台** — Android、iOS、Web（Kotlin/Wasm）、Desktop（JVM）以及獨立的 Ktor server 共享同一套 core。
- **計畫驅動執行** — 複雜工作經 *計畫 → 規範 → 執行 → 驗證* 閉環完成，可選 **審批**（由人批准計畫）或 **自主**（自動批准）模式，並對檔案與命令執行施加沙箱限制。
- **遠端遙控** — 透過內建 server 從瀏覽器或手機遙控桌面端，可選密碼門與 Cloudflare 隧道。
- **瀏覽器自動化** — 內建（JCEF）與外置反偵測瀏覽器操控，供 Agent 驅動的網頁互動。
- **InkCompose** — 自包含富文本渲染庫：原生 Markdown、LaTeX/數學公式、程式碼高亮與 Mermaid 圖表。

## 發布

Release 版本以獨立產物分別發布：

- **core** — 領域模型、Manager、Koog 適配與儲存。
- **server** — Ktor 薄 REST/SSE 層。
- **InkCompose** — 獨立的 Markdown 與數學公式渲染庫。

## 建置與測試

```bash
./gradlew :inkcompose:jvmTest     # 主測試安全網
./gradlew :inkcompose:assemble    # 建置全部目標
./gradlew :app:shared:jvmTest     # shared 模組測試
./gradlew :core:jvmTest           # core 測試
```

## 開源協議

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### 第三方開源致謝

本專案包含以下第三方開源軟體或其衍生實作：

- **InkCompose** — 富文本、LaTeX 與程式碼高亮渲染模組，核心實作衍生自 [@huarangmeng](https://github.com/huarangmeng) 的開源專案（MIT License）：
  - [Markdown](https://github.com/huarangmeng/Markdown)（MIT License）
  - [latex](https://github.com/huarangmeng/latex)（MIT License）
  - [codehigh](https://github.com/huarangmeng/codehigh)（MIT License）
  - Copyright (c) 2026 huarangmeng

## 商標

"Mederi" 及 Mederi 標誌是 lzdev42 在美國及其他國家/地區的商標。

儘管本專案原始碼基於 Apache License 2.0 開源，但該授權條款並未授予使用 Mederi™ 商標、商業名稱或標誌的權利。

- **合理使用**：您可以使用該名稱如實引用本專案或表明相容性（例如 "Mederi™ 外掛"）。
- **使用限制**：未經事前書面許可，不得以 "Mederi" 名稱分發本軟體的修改版本，亦不得以此暗示存在官方背書、贊助或附屬關係。

關於商標使用準則、許可範圍及分支命名政策的完整細則，請參閱 [商標政策文件 (TRADEMARK.md)](./TRADEMARK.md)。

## 語言

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
