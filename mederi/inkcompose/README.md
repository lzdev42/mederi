# InkCompose

Mederi 的富文本渲染核心：Markdown + LaTeX + 代码高亮 + 图表（Mermaid/PlantUML/DOT）+ 竖排文字（蒙古文/满文），单模块 Kotlin Multiplatform + Compose Multiplatform 库。

## 统一入口

```kotlin
import xyz.emuci.inkcompose.MarkdownView
import xyz.emuci.inkcompose.RenderType
import xyz.emuci.inkcompose.InkImage

// 通用模式：任何字符串走 Markdown 解析，代码块/公式/图表自动分发到子渲染器
MarkdownView("## 标题\n\n\`\`\`mermaid\ngraph TD; A-->B;\n\`\`\`\n\n$E=mc^2$")

// 针对模式：跳过 Markdown，按指定类型渲染
MarkdownView(latexSource, type = RenderType.LATEX)
MarkdownView(code, type = RenderType.CODE, language = "kotlin")
```

## 模块结构（目录即边界，单 Gradle 模块）

| 目录 | 领域 |
|---|---|
| `common/entry/` | 统一入口（MarkdownView / RenderType），唯一公共 API 面 |
| `common/latex-*/` | LaTeX base → parser → renderer |
| `common/syntax-*/` | 语法高亮 parser → renderer（25+ 语言） |
| `common/diagram-*/` | 图表 core → layout → parser → renderer |
| `common/markdown-*/` | Markdown parser → runtime → renderer |
| `common/vtext/` | 竖排文字（蒙古文/满文，打包 Noto Sans Mongolian） |
| `jvm/ android/ ios/ wasmJs/` | 各平台 expect/actual |
| `common-test/ jvm-test/` | 测试（按领域分目录） |

领域间依赖只有一条链：markdown-renderer → latex / syntax / diagram（嵌入渲染），其余领域互相零依赖。

## 设计文档

- [MATH_FONT_SPEC.md](docs/MATH_FONT_SPEC.md) — 数学字体规格
- [RENDER_SPEC.md](docs/RENDER_SPEC.md) — LaTeX 渲染规格
- [TEST_COVERAGE.md](docs/TEST_COVERAGE.md) — latex-parser 测试覆盖

---

## 开源协议与致谢 (License & Acknowledgements)

本项目采用 [MIT License](LICENSE)。

核心解析与渲染模块衍生自 [@huarangmeng](https://github.com/huarangmeng) 的开源项目，在此对其开源贡献表示衷心感谢：
- [Markdown](https://github.com/huarangmeng/Markdown) (MIT License)
- [latex](https://github.com/huarangmeng/latex) (MIT License)
- [codehigh](https://github.com/huarangmeng/codehigh) (MIT License)

Copyright (c) 2026 huarangmeng

