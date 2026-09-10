# KaTeX 数学字体架构

## 唯一字体管线

Renderer 只使用随库发布的 KaTeX TTF 字体集。字体文件、逐字形度量、
TeX 布局参数和字形路由策略作为一个不可拆分的配置使用。公开 API 不接受
外部字体文件或自定义字体家族。

`LatexConfig` 不提供字体配置项。渲染入口始终通过 `TtfFontSetProvider`
与 `defaultLatexFontFamilies()` 使用内置 KaTeX TTF。

## 字体角色

| 内容 | KaTeX 字体 |
|---|---|
| 正体文本、数字、运算符 | Main |
| 数学变量 | Math Italic |
| AMS 符号、黑板粗体 | AMS |
| 花体、哥特体、手写体 | Caligraphic / Fraktur / Script |
| 无衬线、等宽 | SansSerif / Typewriter |
| 大型运算符、定界符、根号 | Main / Size1 / Size2 / Size3 / Size4 |

## 测量规则

- 文本测量和实际绘制必须使用同一字体槽位。
- 墨迹边界从对应 KaTeX TTF 字节精确测量，不以文本行框代替字形边界。
- 定界符按 Main → Size1 → Size2 → Size3 → Size4 选择首个足够高的字形；
  Size4 仍不足时再等比放大字号。
- 大型运算符在行内使用 Size1，展示模式使用 Size2。
- 分数、上下标、根号间距与数学轴对齐遵循 KaTeX 度量和 TeX 规则。
- 重音水平锚点使用 `advance / 2 + skew`，垂直尺寸使用字形真实墨迹边界，
  不再使用按字号估算的下沉偏移。

## 资源要求

`composeResources/font` 中只允许 `katex_*.ttf` 资源。新增或替换字体时，必须同步更新
字形度量、路由、可视化预览与 renderer 测试，不得从 `LatexConfig` 注入字体。
