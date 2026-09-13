package xyz.mederi.mcp.engine

import io.modelcontextprotocol.kotlin.sdk.shared.Transport

/**
 * 创建 stdio MCP 传输（本地进程）。
 *
 * 进程的启动、env 注入是平台相关实现（jvmMain 用 ProcessBuilder + Koog `defaultStdioTransport`），
 * commonMain 只声明契约，让 McpConnector 保持跨源集可用。
 */
internal expect fun mcpStdioTransport(command: String, args: List<String>, env: Map<String, String>): Transport
