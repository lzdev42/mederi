package xyz.mederi.workspace

import xyz.mederi.mcp.runtime.McpRuntime

internal actual fun systemGitExecutable(): String =
    McpRuntime.commandLine("git", emptyList(), McpRuntime.environment(emptyMap())).first()
