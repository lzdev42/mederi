package xyz.mederi.ui.chat

/** 单行推理预览只取尾部，避免对长推理做全量格式转换；原文留给详情渲染。 */
internal fun reasoningPreview(text: String): String = text.takeLast(240)
    .replace(Regex("\\s+"), " ").trim()
