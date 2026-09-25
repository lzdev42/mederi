# Mederi

Mederi est un framework Kotlin Multiplatform de construction d'applications à agents IA, bâti sur le moteur d'exécution Koog. Il fournit la configuration des fournisseurs, la gestion des projets et sessions, la persistance, un flux d'exécution piloté par plan, un bac à sable d'exécution et une UI multiplateforme — pour assembler un agent piloté par LLM et utilisant des outils sans avoir à assembler ces couches vous-même.

> **État du développement :** Mederi est en cours de développement. L'UI et les fonctionnalités principales peuvent changer à tout moment.

## Fonctionnalités

- **Multiplateforme** — Android, iOS, Web (Kotlin/Wasm), Desktop (JVM) et un serveur Ktor autonome partagent un même core.
- **Exécution pilotée par plan** — le travail complexe suit une boucle *plan → spec → exécution → vérification*, en mode **Approbation** (un humain approuve le plan) ou **Autonome** (auto-approuvé), avec accès fichier et shell bac à sable.
- **Contrôle à distance** — piloter une instance desktop depuis un navigateur ou un téléphone via un serveur intégré, avec porte à mot de passe et tunnel Cloudflare optionnels.
- **Automatisation du navigateur** — contrôle navigateur intégré (JCEF) et externe anti-détection pour les interactions web pilotées par l'agent.
- **InkCompose** — une bibliothèque autonome de rendu de texte enrichi : Markdown natif, LaTeX/mathématiques, coloration syntaxique et diagrammes Mermaid.

## Releases

Les builds de release sont publiés sous forme d'artefacts séparés :

- **core** — modèle de domaine, managers, adaptation Koog et stockage.
- **server** — la fine couche REST/SSE Ktor.
- **InkCompose** — la bibliothèque autonome de rendu Markdown et mathématiques.

## Build et tests

```bash
./gradlew :inkcompose:jvmTest     # filet de tests principal
./gradlew :inkcompose:assemble    # builder toutes les cibles
./gradlew :app:shared:jvmTest     # tests du module shared
./gradlew :core:jvmTest           # tests de core
```

## Licence

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### Remerciements tiers

Ce projet inclut des logiciels open source tiers ou leurs implémentations dérivées :

- **InkCompose** — module de rendu de texte enrichi, LaTeX et coloration syntaxique, dont les implémentations principales dérivent des projets open source de [@huarangmeng](https://github.com/huarangmeng) sous licence MIT :
  - [Markdown](https://github.com/huarangmeng/Markdown) (MIT License)
  - [latex](https://github.com/huarangmeng/latex) (MIT License)
  - [codehigh](https://github.com/huarangmeng/codehigh) (MIT License)
  - Copyright (c) 2026 huarangmeng

## Langues

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
