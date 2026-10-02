# Mederi™

Mederi™ adalah framework Kotlin Multiplatform untuk membangun aplikasi agen AI, dibangun di atas mesin eksekusi Koog. Ia menyediakan konfigurasi provider, manajemen proyek dan sesi, persistensi, alur eksekusi berbasis plan, sandbox eksekusi, dan UI lintas platform — sehingga Anda dapat merakit agen yang digerakkan LLM dan menggunakan tools tanpa harus menjahit lapisan-lapisan ini sendiri.

> **Status pengembangan:** Mederi sedang dalam pengembangan. Baik UI maupun fungsionalitas inti dapat berubah kapan saja.

## Fitur

- **Lintas platform** — Android, iOS, Web (Kotlin/Wasm), Desktop (JVM), dan server Ktor mandiri berbagi satu core.
- **Eksekusi berbasis plan** — pekerjaan kompleks mengalir melalui loop *plan → spec → eksekusi → verifikasi*, dalam mode **Approval** (manusia menyetujui plan) atau **Autonomous** (disetujui sendiri), dengan akses file dan shell yang di-sandbox.
- **Kontrol jarak jauh** — mengendalikan instance desktop dari browser atau ponsel melalui server bawaan, dengan gerbang kata sandi dan tunnel Cloudflare opsional.
- **Otomasi browser** — kontrol browser bawaan (JCEF) dan eksternal anti-deteksi untuk interaksi web yang digerakkan agen.
- **InkCompose** — pustaka render teks kaya mandiri: Markdown native, LaTeX/matematika, penyorotan sintaks, dan diagram Mermaid.

## Rilis

Build rilis dipublikasikan sebagai artefak terpisah:

- **core** — model domain, manager, adaptasi Koog, dan penyimpanan.
- **server** — lapisan REST/SSE Ktor yang tipis.
- **InkCompose** — pustaka render Markdown dan matematika mandiri.

## Build & Tes

```bash
./gradlew :inkcompose:jvmTest     # jaring pengujian utama
./gradlew :inkcompose:assemble    # build semua target
./gradlew :app:shared:jvmTest     # tes modul shared
./gradlew :core:jvmTest           # tes core
```

## Lisensi

Copyright 2026 lzdev42

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

### Ucapan terima kasih pihak ketiga

Proyek ini mencakup perangkat lunak sumber terbuka pihak ketiga atau implementasi turunannya:

- **InkCompose** — modul render teks kaya, LaTeX, dan penyorotan kode, yang implementasi intinya diturunkan dari proyek sumber terbuka [@huarangmeng](https://github.com/huarangmeng) di bawah lisensi MIT:
  - [Markdown](https://github.com/huarangmeng/Markdown) (MIT License)
  - [latex](https://github.com/huarangmeng/latex) (MIT License)
  - [codehigh](https://github.com/huarangmeng/codehigh) (MIT License)
  - Copyright (c) 2026 huarangmeng

## Merek Dagang

"Mederi" dan logo Mederi adalah merek dagang dari lzdev42 di Amerika Serikat dan negara lainnya.

Meskipun kode sumber kami dilisensikan di bawah Apache License 2.0, lisensi ini tidak memberikan izin untuk menggunakan merek dagang, nama dagang, atau logo Mederi™.

- **Penggunaan Wajar (Fair Use)**: Anda dapat menggunakan nama ini untuk merujuk pada proyek ini secara jujur atau menunjukkan kompatibilitas (misalnya, "plugin Mederi™").
- **Batasan**: Anda tidak boleh mendistribusikan versi modifikasi dari perangkat lunak ini dengan nama "Mederi", atau menggunakannya dengan cara yang menyiratkan dukungan, sponsor, atau afiliasi resmi tanpa izin tertulis sebelumnya.

Untuk pedoman merek lengkap, izin penggunaan, dan kebijakan penamaan fork, silakan merujuk ke [Kebijakan Merek Dagang (TRADEMARK.md)](./TRADEMARK.md).

## Bahasa

[English](./README.md) · [简体中文](./README.zh-CN.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [한국어](./README.ko.md) · [Français](./README.fr.md) · [Deutsch](./README.de.md) · [Español](./README.es.md) · [Português (Brasil)](./README.pt-BR.md) · [Bahasa Indonesia](./README.id.md)
