# Mederi

Mederi é um framework Kotlin Multiplatform para construção de aplicações de agentes de IA, construído sobre o motor de execução Koog. Ele oferece configuração de provedores, gestão de projetos e sessões, persistência, um fluxo de execução orientado por planos, uma sandbox de execução e uma UI multiplataforma — para que você monte um agent orientado por LLM e que usa ferramentas sem precisar costurar essas camadas você mesmo.

> **Estado de desenvolvimento:** Mederi está em desenvolvimento. Tanto a UI quanto a funcionalidade principal podem mudar a qualquer momento.

## Funcionalidades

- **Multiplataforma** — Android, iOS, Web (Kotlin/Wasm), Desktop (JVM) e um servidor Ktor autônomo compartilham um mesmo core.
- **Execução orientada por planos** — o trabalho complexo segue um loop *plano → spec → execução → verificação*, no modo **Approval** (um humano aprova o plano) ou **Autonomous** (autoaprovado), com acesso a arquivos e shell em sandbox.
- **Controle remoto** — controlar uma instância de desktop a partir de um navegador ou telefone por meio de um servidor integrado, com porta de senha e túnel Cloudflare opcionais.
- **Automação do navegador** — controle de navegador integrado (JCEF) e externo anti-detecção para interação web orientada pelo agente.
- **InkCompose** — uma biblioteca autônoma de renderização de texto rico: Markdown nativo, LaTeX/matemática, destaque de sintaxe e diagramas Mermaid.

## Releases

Os builds de release são publicados como artefatos separados:

- **core** — modelo de domínio, managers, adaptação Koog e armazenamento.
- **server** — a camada fina REST/SSE do Ktor.
- **InkCompose** — a biblioteca autônoma de renderização de Markdown e matemática.

## Build e testes

```bash
./gradlew :inkcompose:jvmTest     # rede de testes principal
./gradlew :inkcompose:assemble    # compilar todos os alvos
./gradlew :app:shared:jvmTest     # testes do módulo shared
./gradlew :core:jvmTest           # testes de core
```

## Licença

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### Agradecimentos a terceiros

Este projeto inclui software de código aberto de terceiros ou implementações derivadas:

- **InkCompose** — o módulo de renderização de texto rico, LaTeX e destaque de código, cujas implementações principais derivam dos projetos de código aberto de [@huarangmeng](https://github.com/huarangmeng) sob a licença MIT:
  - [Markdown](https://github.com/huarangmeng/Markdown) (MIT License)
  - [latex](https://github.com/huarangmeng/latex) (MIT License)
  - [codehigh](https://github.com/huarangmeng/codehigh) (MIT License)
  - Copyright (c) 2026 huarangmeng

## Idiomas

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
