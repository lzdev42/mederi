package xyz.mederi.http

/**
 * SSE 流式连接空闲超时异常：在流式接收过程中，若连续超过设定阈值未收到任何数据包/行，
 * 主动抛出此异常掐断连接。
 */
class SseIdleTimeoutException(message: String) : Exception(message)
