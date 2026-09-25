# Mederi

Mederi es un framework Kotlin Multiplatform para construir aplicaciones de agentes de IA, construido sobre el motor de ejecución Koog. Proporciona configuración de proveedores, gestión de proyectos y sesiones, persistencia, un flujo de ejecución guiado por planes, una sandbox de ejecución y una UI multiplataforma — para que puedas ensamblar un agent impulsado por LLM y que usa herramientas sin tener que coser estas capas tú mismo.

> **Estado de desarrollo:** Mederi está en desarrollo. Tanto la UI como la funcionalidad principal pueden cambiar en cualquier momento.

## Características

- **Multiplataforma** — Android, iOS, Web (Kotlin/Wasm), Desktop (JVM) y un servidor Ktor autónomo comparten un mismo core.
- **Ejecución guiada por planes** — el trabajo complejo sigue un bucle *plan → spec → ejecución → verificación*, en modo **Approval** (un humano aprueba el plan) o **Autonomous** (autoaprobado), con acceso a archivos y shell en sandbox.
- **Control remoto** — controlar una instancia de escritorio desde un navegador o teléfono a través de un servidor integrado, con puerta de contraseña y túnel de Cloudflare opcionales.
- **Automatización del navegador** — control de navegador integrado (JCEF) y externo anti-detección para interacción web guiada por el agente.
- **InkCompose** — una biblioteca autónoma de renderizado de texto enriquecido: Markdown nativo, LaTeX/matemáticas, resaltado de sintaxis y diagramas de Mermaid.

## Releases

Los builds de release se publican como artefactos separados:

- **core** — modelo de dominio, managers, adaptación Koog y almacenamiento.
- **server** — la capa delgada REST/SSE de Ktor.
- **InkCompose** — la biblioteca autónoma de renderizado de Markdown y matemáticas.

## Build y tests

```bash
./gradlew :inkcompose:jvmTest     # red de pruebas principal
./gradlew :inkcompose:assemble    # construir todos los targets
./gradlew :app:shared:jvmTest     # pruebas del módulo shared
./gradlew :core:jvmTest           # pruebas de core
```

## Licencia

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### Agradecimientos a terceros

Este proyecto incluye software de código abierto de terceros o implementaciones derivadas:

- **InkCompose** — el módulo de renderizado de texto enriquecido, LaTeX y resaltado de código, cuyas implementaciones principales derivan de los proyectos de código abierto de [@huarangmeng](https://github.com/huarangmeng) bajo la licencia MIT:
  - [Markdown](https://github.com/huarangmeng/Markdown) (MIT License)
  - [latex](https://github.com/huarangmeng/latex) (MIT License)
  - [codehigh](https://github.com/huarangmeng/codehigh) (MIT License)
  - Copyright (c) 2026 huarangmeng

## Idiomas

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
