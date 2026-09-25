# Mederi

Mederi는 Koog 실행 엔진 위에 구축된 Kotlin Multiplatform 기반 AI 에이전트 애플리케이션 프레임워크입니다. 프로바이더 설정, 프로젝트 및 세션 관리, 영속화, 계획 주도 실행 흐름, 실행 샌드박스, 크로스 플랫폼 UI를 제공하여, 이 계층들을 직접 꿰맞출 필요 없이 LLM 주도로 도구를 사용하는 에이전트를 조립할 수 있습니다.

> **개발 상태:** Mederi는 개발 중이며, UI와 핵심 기능은 언제든 변경될 수 있습니다.

## 특징

- **크로스 플랫폼** — Android, iOS, Web(Kotlin/Wasm), Desktop(JVM), 그리고 독립적인 Ktor 서버가 하나의 core를 공유합니다.
- **계획 주도 실행** — 복잡한 작업은 *계획 → 명세 → 실행 → 검증* 루프를 따르며, **승인**(사람이 계획을 승인) 또는 **자율**(자가 승인) 모드 중 선택할 수 있고 파일과 셸 접근은 샌드박스로 제한됩니다.
- **원격 제어** — 내장 서버를 통해 브라우저나 폰에서 데스크톱 인스턴스를 조작하며, 선택적으로 비밀번호 게이트와 Cloudflare 터널을 사용할 수 있습니다.
- **브라우저 자동화** — 에이전트 주도 웹 조작을 위한 내장(JCEF) 및 외부 반탐지 브라우저 제어.
- **InkCompose** — 자급식 리치 텍스트 렌더링 라이브러리: 네이티브 Markdown, LaTeX/수식, 구문 강조, Mermaid 다이어그램.

## 릴리스

릴리스 빌드는 별도의 산출물로 공개됩니다:

- **core** — 도메인 모델, 매니저, Koog 어댑터, 스토리지.
- **server** — Ktor의 얇은 REST/SSE 계층.
- **InkCompose** — 단독 Markdown 및 수식 렌더링 라이브러리.

## 빌드 및 테스트

```bash
./gradlew :inkcompose:jvmTest     # 주 테스트 안전망
./gradlew :inkcompose:assemble    # 모든 타깃 빌드
./gradlew :app:shared:jvmTest     # shared 모듈 테스트
./gradlew :core:jvmTest           # core 테스트
```

## 라이선스

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### 서드파티 감사

본 프로젝트는 다음 서드파티 오픈소스 소프트웨어 또는 그 파생 구현을 포함합니다:

- **InkCompose** — 리치 텍스트, LaTeX, 코드 하이라이트 렌더링 모듈. 핵심 구현은 [@huarangmeng](https://github.com/huarangmeng)의 오픈소스 프로젝트(MIT License)에서 파생되었습니다:
  - [Markdown](https://github.com/huarangmeng/Markdown)(MIT License)
  - [latex](https://github.com/huarangmeng/latex)(MIT License)
  - [codehigh](https://github.com/huarangmeng/codehigh)(MIT License)
  - Copyright (c) 2026 huarangmeng

## 언어

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
