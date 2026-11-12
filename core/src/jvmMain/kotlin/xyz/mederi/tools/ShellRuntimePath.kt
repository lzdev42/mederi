package xyz.mederi.tools

import xyz.mederi.mcp.runtime.McpRuntime

internal actual fun ProcessBuilder.withSystemRuntimePath(): ProcessBuilder = apply {
    val inherited = environment()
    val pathKey = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        inherited.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    } else {
        "PATH"
    }
    McpRuntime.environment(emptyMap())["PATH"]?.let { inherited[pathKey] = it }
}
