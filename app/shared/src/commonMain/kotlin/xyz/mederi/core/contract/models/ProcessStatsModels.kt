package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/**
 * 宿主进程资源真实占用（诊断"卡是程序卡还是机器卡"）。
 *
 * 被监控端 = AiCore 所在宿主进程：desktop 直读本进程（UI + core 全部线程），
 * server 返回 server 进程；遥控端（wasm/android/ios）经 REST 透传，
 * 拿到的是被遥控端 server 进程的数据——各端语义天然一致。
 *
 * 字段与 core [xyz.mederi.debug.ProcessStats] 同名对应（MederiAiCore 负责映射）。
 * UI 以 500ms~1s 间隔轮询 [xyz.mederi.core.contract.AiCore.getProcessStats] 即得实时曲线。
 */
@Serializable
data class ProcessStats(
    /** 进程 CPU 使用率 0.0~1.0（整个进程口径，含全部线程）；平台不支持时 null */
    val cpuUsage: Double? = null,
    /** 逻辑核数 */
    val cpuCores: Int = 1,
    /** 堆已用字节 */
    val heapUsedBytes: Long = 0,
    /** 堆已提交字节（OS 已实际划给 JVM 的堆） */
    val heapCommittedBytes: Long = 0,
    /** 堆上限字节；未固定上限时 null */
    val heapMaxBytes: Long? = null,
    /** 进程 RSS 字节（真实常驻内存，含 native/堆外）；平台读不到时 null */
    val rssBytes: Long? = null,
    /** 快照采集时刻（epoch ms） */
    val timestampMillis: Long = 0,
)
