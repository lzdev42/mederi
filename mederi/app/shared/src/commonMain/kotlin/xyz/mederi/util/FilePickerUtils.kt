package xyz.mederi.util

/**
 * 弹出保存文件选择对话框。
 *
 * @param defaultName 默认文件名（可不带后缀）
 * @param extension 文件扩展名（如 "html", "pdf"）
 * @param title 原生对话框标题（UI 层本地化后传入）
 * @param filterLabel 文件过滤器标签（UI 层本地化后传入）
 * @return 选中的绝对路径；若用户取消或平台不支持，返回 null
 */
expect suspend fun pickSaveFile(defaultName: String, extension: String, title: String, filterLabel: String): String?

/**
 * 将文本写入指定绝对路径的文件中。
 *
 * @return 写入成功返回 true，失败返回 false
 */
expect suspend fun writeTextToFile(filePath: String, text: String): Boolean