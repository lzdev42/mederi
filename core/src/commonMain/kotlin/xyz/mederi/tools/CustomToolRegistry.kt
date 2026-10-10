package xyz.mederi.tools

import ai.koog.agents.core.tools.ToolBase
import java.util.concurrent.ConcurrentHashMap

/**
 * 自定义工具全局注册表（仿 BrowserRegistry 模式）。
 *
 * - UI 层在启动装配阶段 [register] 工厂闭包（同名幂等覆盖，重连时刷新实例）。
 * - TurnExecutor 每轮 turn 调 [buildAll]（每轮产出新实例）。
 * - 与 mcpTools 并列旁路合并：不参与 toolNames 角色裁剪，工具描述由 Koog 自动发给 LLM。
 */
object CustomToolRegistry {

    private val factories = ConcurrentHashMap<String, () -> ToolBase<*, *>>()

    /** 注册工厂；幂等，同名覆盖。 */
    fun register(name: String, factory: () -> ToolBase<*, *>) {
        factories[name] = factory
    }

    /** 移除指定工具。 */
    fun unregister(name: String) {
        factories.remove(name)
    }

    /** 每轮 turn 收集：为每个注册名调用工厂，产出新实例。 */
    fun buildAll(): List<ToolBase<*, *>> = factories.values.map { it() }

    /** 清空（测试用）。 */
    fun clear() {
        factories.clear()
    }

    /** 已注册工具名（排序，调试/UI 展示用）。 */
    fun names(): List<String> = factories.keys.sorted()

    fun isEmpty(): Boolean = factories.isEmpty()
}