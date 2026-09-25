# Mederi

Mederi is a Kotlin Multiplatform framework for building AI agent applications, built on top of the Koog execution engine. It provides provider configuration, project and session management, persistence, a plan-driven execution workflow, an execution sandbox, and a cross-platform UI — so you can assemble an LLM-driven, tool-using agent without stitching these layers yourself.

> **Development Status:** Mederi is under active development. Both the UI and core functionality may change at any time.

## Features

- **Cross-platform** — Android, iOS, Web (Kotlin/Wasm), Desktop (JVM), and a standalone Ktor server all share one core.
- **Plan-driven execution** — complex work flows through a *plan → spec → execute → verify* loop, in either **Approval** (a human approves the plan) or **Autonomous** (self-approved) mode, with sandboxed file and shell access.
- **Remote control** — drive a desktop instance from a browser or phone through a built-in server, with an optional password gate and Cloudflare tunnel.
- **Browser automation** — built-in (JCEF) and external anti-detection browser control for agent-driven web interaction.
- **InkCompose** — a self-contained rich-text rendering library: native Markdown, LaTeX/math, syntax highlighting, and Mermaid diagrams.

## Releases

Release builds are published as separate artifacts:

- **core** — domain model, managers, Koog adaptation, and storage.
- **server** — the thin Ktor REST/SSE layer.
- **InkCompose** — the standalone Markdown and math rendering library.

## Build & Tests

```bash
./gradlew :inkcompose:jvmTest     # main test safety net
./gradlew :inkcompose:assemble    # build all targets
./gradlew :app:shared:jvmTest     # shared module tests
./gradlew :core:jvmTest           # core tests
```

## License

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### Third-Party Acknowledgements

This project includes third-party open-source software or derivative implementations:

- **InkCompose** — the rich-text, LaTeX, and code-highlighting rendering module, with core implementations derived from [@huarangmeng](https://github.com/huarangmeng)'s open-source projects under the MIT License:
  - [Markdown](https://github.com/huarangmeng/Markdown) (MIT License)
  - [latex](https://github.com/huarangmeng/latex) (MIT License)
  - [codehigh](https://github.com/huarangmeng/codehigh) (MIT License)
  - Copyright (c) 2026 huarangmeng

## Languages

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
