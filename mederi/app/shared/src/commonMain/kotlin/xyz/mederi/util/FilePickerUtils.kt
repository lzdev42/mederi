package xyz.mederi.util

/**
 * 弹出保存文件选择对话框。
 *
 * @param defaultName 默认文件名（可不带后缀）
 * @param extension 文件扩展名（如 "html", "pdf"）
 * @return 选中的绝对路径；若用户取消或平台不支持，返回 null
 */
expect suspend fun pickSaveFile(defaultName: String, extension: String): String?

/**
 * 将文本写入指定绝对路径的文件中。
 *
 * @return 写入成功返回 true，失败返回 false
 */
expect suspend fun writeTextToFile(filePath: String, text: String): Boolean
