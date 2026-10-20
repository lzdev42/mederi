package xyz.mederi.browser

import ai.koog.agents.core.tools.ToolBase
import java.util.concurrent.ConcurrentHashMap

/**
 * 浏览器操作员专属工具注册中心（纯 Core 抽象，Core 对 UI 零耦合）。
 *
 * 宿主层（如 app:desktopApp）在启动装配阶段通过本注册表向特定浏览器注册物理操作工具集，
 * 仅由 [BrowserOperator] 在执行对应浏览器任务时拉取并装配进其独立的 ToolRegistry 中。
 * 主代理（[xyz.mederi.tools.ToolFactory]）与其它子代理对此完全不可见。
 */
object BrowserOperatorToolRegistry {

    private val toolFactories = ConcurrentHashMap<String, (BrowserControl) -> List<ToolBase<*, *>>>()

    /**
     * 为指定浏览器标识注册操作工具工厂。幂等，同名覆盖。
     */
    fun register(browserName: String, factory: (BrowserControl) -> List<ToolBase<*, *>>) {
        toolFactories[browserName] = factory
    }

    /**
     * 移除指定浏览器的操作工具工厂。
     */
    fun unregister(browserName: String) {
        toolFactories.remove(browserName)
    }

    /**
     * 获取指定浏览器绑定的操作工具实例列表。未注册时返回空列表。
     */
    fun getTools(browserName: String, control: BrowserControl): List<ToolBase<*, *>> =
        toolFactories[browserName]?.invoke(control).orEmpty()

    /**
     * 清空全部注册（单元测试清理用）。
     */
    fun clear() {
        toolFactories.clear()
    }

    /**
     * 是否为空。
     */
    fun isEmpty(): Boolean = toolFactories.isEmpty()
}
