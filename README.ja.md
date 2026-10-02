# Mederi™

Mederi™ は、Koog 実行エンジンの上に構築された Kotlin Multiplatform 向け AI エージェントアプリケーションフレームワークです。プロバイダ設定、プロジェクトおよびセッション管理、永続化、計画駆動の実行フロー、実行サンドボックス、クロスプラットフォーム UI を提供し、これらの層を自前で縫い合わせることなく、LLM 駆動でツールを利用するエージェントを組み立てられます。

> **開発状態：** Mederi は開発中であり、UI およびコア機能は随時変更される可能性があります。

## 特徴

- **クロスプラットフォーム** — Android、iOS、Web（Kotlin/Wasm）、Desktop（JVM）、および独立した Ktor サーバがひとつの core を共有します。
- **計画駆動実行** — 複雑な作業は *計画 → 仕様 → 実行 → 検証* のループで進み、**承認**（人が計画を承認）または **自律**（自己承認）モードを選択でき、ファイルとシェルへのアクセスはサンドボックス化されます。
- **リモート操作** — 内蔵サーバを通じてブラウザやスマートフォンからデスクトップインスタンスを操作し、任意でパスワードゲートと Cloudflare トンネルを利用できます。
- **ブラウザ自動化** — エージェント駆動の Web 操作のため、内蔵（JCEF）および外部アンチ検出ブラウザ制御を備えます。
- **InkCompose** — 自己完結型のリッチテキストレンダリングライブラリ：ネイティブ Markdown、LaTeX/数式、シンタックスハイライト、Mermaid 図表。

## リリース

リリースビルドは個別のアーティファクトとして公開されます：

- **core** — ドメインモデル、マネージャ、Koog 適合、ストレージ。
- **server** — Ktor の薄い REST/SSE 層。
- **InkCompose** — 単体の Markdown および数式レンダリングライブラリ。

## ビルドとテスト

```bash
./gradlew :inkcompose:jvmTest     # 主なテストの安全網
./gradlew :inkcompose:assemble    # 全ターゲットのビルド
./gradlew :app:shared:jvmTest     # shared モジュールのテスト
./gradlew :core:jvmTest           # core のテスト
```

## ライセンス

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### サードパーティ謝辞

本プロジェクトは以下のサードパーティオープンソースソフトウェアまたはその派生実装を含みます：

- **InkCompose** — リッチテキスト、LaTeX、コードハイライトのレンダリングモジュール。コア実装は [@huarangmeng](https://github.com/huarangmeng) のオープンソースプロジェクト（MIT License）から派生しています：
  - [Markdown](https://github.com/huarangmeng/Markdown)（MIT License）
  - [latex](https://github.com/huarangmeng/latex)（MIT License）
  - [codehigh](https://github.com/huarangmeng/codehigh)（MIT License）
  - Copyright (c) 2026 huarangmeng

## 商標

"Mederi" および Mederi ロゴは、米国およびその他の国における lzdev42 の商標です。

本プロジェクトのソースコードは Apache License 2.0 の下でライセンスされていますが、このライセンスは Mederi™ の商標、商号、またはロゴを使用する権利を許諾するものではありません。

- **公正な利用（フェアユース）**：本プロジェクトを事実に基づいて言及したり、互換性を示すために名称を使用したりすることができます（例: "Mederi™ プラグイン"）。
- **利用制限**：事前の書面による許可なく、改変されたソフトウェアを "Mederi" という名称で配布すること、または公式な推奨、後援、提携を示唆するような方法で使用することはできません。

商標ガイドライン、許諾範囲、フォーク時の命名ポリシーの詳細については、[商標ポリシー (TRADEMARK.md)](./TRADEMARK.md) をご参照ください。

## 言語

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
