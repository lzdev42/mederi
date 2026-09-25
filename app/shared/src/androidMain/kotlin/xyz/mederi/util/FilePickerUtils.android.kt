package xyz.mederi.util

actual suspend fun pickSaveFile(defaultName: String, extension: String, title: String, filterLabel: String): String? = null

actual suspend fun writeTextToFile(filePath: String, text: String): Boolean = false