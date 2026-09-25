# Mederi

Mederi ist ein Kotlin-Multiplatform-Framework zum Bauen von KI-Agent-Anwendungen, das auf der Koog-Ausführungsengine aufsetzt. Es bietet Anbieterkonfiguration, Projekt- und Sitzungsverwaltung, Persistenz, einen plangetriebenen Ausführungsworkflow, eine Ausführungs-Sandbox und eine plattformübergreifende UI — sodass Sie einen LLM-gesteuerten, werkzeugnutzenden Agent zusammenstellen können, ohne diese Schichten selbst zusammenzufügen.

> **Entwicklungsstatus:** Mederi befindet sich in Entwicklung. Sowohl die UI als auch Kernfunktionen können sich jederzeit ändern.

## Features

- **Plattformübergreifend** — Android, iOS, Web (Kotlin/Wasm), Desktop (JVM) und ein eigenständiger Ktor-Server teilen sich einen Core.
- **Plangetriebene Ausführung** — komplexere Arbeit durchläuft eine *Plan → Spec → Ausführung → Verifikation*-Schleife, wahlweise im Modus **Approval** (ein Mensch genehmigt den Plan) oder **Autonomous** (selbstgenehmigt), mit Sandbox-Zugriff auf Dateien und Shell.
- **Fernsteuerung** — eine Desktop-Instanz über einen eingebauten Server aus Browser oder Telefon steuern, optional mit Passwort-Gate und Cloudflare-Tunnel.
- **Browser-Automatisierung** — eingebaute (JCEF) und externe Anti-Detection-Browsersteuerung für agentengesteuerte Web-Interaktion.
- **InkCompose** — eine in sich geschlossene Bibliothek für Rich-Text-Rendering: natives Markdown, LaTeX/Mathematik, Syntax-Highlighting und Mermaid-Diagramme.

## Releases

Release-Builds werden als separate Artefakte veröffentlicht:

- **core** — Domänenmodell, Manager, Koog-Adapter und Speicherung.
- **server** — die dünne Ktor-REST/SSE-Schicht.
- **InkCompose** — die eigenständige Markdown- und Mathe-Rendering-Bibliothek.

## Build & Tests

```bash
./gradlew :inkcompose:jvmTest     # Haupt-Testsicherheitsnetz
./gradlew :inkcompose:assemble    # alle Targets bauen
./gradlew :app:shared:jvmTest     # Tests des shared-Moduls
./gradlew :core:jvmTest           # Tests von core
```

## Lizenz

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### Danksagung an Drittanbieter

Dieses Projekt enthält Drittanbieter-Open-Source-Software oder abgeleitete Implementierungen:

- **InkCompose** — das Rich-Text-, LaTeX- und Code-Highlighting-Rendering-Modul, dessen Kernimplementierungen von den Open-Source-Projekten von [@huarangmeng](https://github.com/huarangmeng) unter der MIT-Lizenz abgeleitet sind:
  - [Markdown](https://github.com/huarangmeng/Markdown) (MIT License)
  - [latex](https://github.com/huarangmeng/latex) (MIT License)
  - [codehigh](https://github.com/huarangmeng/codehigh) (MIT License)
  - Copyright (c) 2026 huarangmeng

## Sprachen

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
