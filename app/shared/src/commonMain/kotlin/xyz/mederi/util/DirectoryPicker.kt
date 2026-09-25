package xyz.mederi.util

/**
 * 弹出系统目录选择对话框。
 *
 * @param title 原生对话框标题（UI 层本地化后传入）
 * @return 选中的目录绝对路径；若用户取消或平台不支持，返回 null
 */
expect suspend fun pickDirectory(title: String): String?