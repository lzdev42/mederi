package xyz.mederi.core.contract.models

/**
 * 聊天界面思考菜单推导（纯函数，可独立测试）。
 *
 * 规则（v6：用户填什么发什么）：
 * - 供应商 ReasoningParameter 中**有值的档位**才显示（值 = 用户填的请求体 JSON 片段；
 *   填错了是用户的事，机制不兜底）
 * - **NONE 就是关闭档**：恒为菜单第一项，显示为"关闭"；它的值也是配置的一部分
 *   （如 Agnes 的显式关闭参数；OpenAI 类默认 null = 不发参数即关）
 * - 其余档位 = 有值档位 ∩ 模型勾选（勾选为空 → 回退显示全部有值档位）
 * - 显示条件：模型勾选了级别，或供应商参数任一档有值；两者皆无 → 不显示任何选项
 */
object ReasoningMenu {

    /** 级别显示顺序（唯一真理源 = ReasoningLevels.ORDER） */
    private val ORDER get() = ReasoningLevels.ORDER

    /**
     * 推导聊天菜单的级别选项（含关闭档）。
     *
     * @param providerLevels 供应商推理参数（级别名 → JSON 片段/null），null 表示未配置
     * @param modelLevels 模型勾选的级别名（模型编辑器勾选，空 = 未勾选）
     * @return 菜单级别名列表（NONE 恒第一）；空列表 = 不显示菜单
     */
    fun derive(providerLevels: Map<String, String?>?, modelLevels: List<String>): List<String> {
        val levels = providerLevels ?: emptyMap()

        // 有值的非 NONE 档位
        val valued = levels.filterKeys { it != "NONE" }
            .filterValues { it != null }
            .keys

        // 模型勾选为空 → 回退全部有值档位；非空 → 取交集
        val options = if (modelLevels.isEmpty()) {
            valued
        } else {
            modelLevels.filter { it in valued }.toSet()
        }

        // 显示条件：勾选了级别，或供应商参数任一档有值（含 NONE 档的关闭参数）
        val visible = modelLevels.isNotEmpty() || levels.values.any { it != null }
        if (!visible) return emptyList()

        return (options + "NONE").sortedBy { ORDER.indexOf(it) }
    }

    /**
     * 推理档位唯一推导链（**唯一真理源**）：
     * 模型记忆（用户上次为该模型选择、AppState 持久化的档位）> 模型默认档（MEDIUM 优先，否则首档）。
     *
     * 显示（推理选择器）与发送（ChatPromptInput.thinkingLevel）**必须同源**——一律经由本函数取值，
     * 禁止调用方另行回退或持有瞬态副本状态（历史上瞬态 + 快照 + 记忆多源并存，
     * 导致"界面显示推理高、实际发送 null→NONE 没推理"的显示与发送不一致）。
     *
     * @param modelMemoryLevel AppState 持久化的该模型推理档位记忆；null = 用户从未选择过
     * @param modelLevels 推理菜单档位（[derive] 推导，NONE 关闭档恒为首项）；空 = 模型不支持推理
     * @return 生效档位名；模型不支持推理（levels 为空）时返回 null（发送侧解析为关闭推理）
     */
    fun resolve(modelMemoryLevel: String?, modelLevels: List<String>): String? {
        if (modelLevels.isEmpty()) return null
        // 记忆档必须仍在当前菜单内（模型/供应商配置变更后旧记忆可能失效），失效则回退默认档；
        // 命中时返回菜单中的规范档名（大小写归一），保证下游拿到统一形态
        val memory = modelMemoryLevel
            ?.let { mem -> modelLevels.firstOrNull { it.equals(mem, ignoreCase = true) } }
        return memory
            ?: modelLevels.find { it.equals("MEDIUM", ignoreCase = true) }
            ?: modelLevels.firstOrNull()
    }
}
