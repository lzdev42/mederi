package xyz.mederi.ui

/**
 * 自动隐藏侧边栏（hover 抽屉）唤出/收起的纯逻辑状态机（无 Compose 依赖，可单测）。
 *
 * 不变量：
 * 1) 唤出权限唯一来源 = 边缘条 hover / 点击把手（edgeHovered）；抽屉自身 hover（drawerHovered）只维持开启，永不唤出
 *    —— 否则退场动画期间抽屉会把自己重新唤出（真实事故：点「新建任务」侧边栏先左后右再收起的三段抖动）；
 * 2) 显式关闭（导航项 / 关闭按钮 / 遮罩 / 项目选择器）进入 latch（explicitlyClosed），
 *    在指针离开抽屉且无菜单弹窗交互、或用户重新贴边之前，任何 hover 都不得重新唤出；
 * 3) 菜单/对话框打开期间（interacting）维持开启。
 */
object SidebarReveal {

    /** 失去全部 hover/交互来源后的宽限收起时长（毫秒）——给「边缘条 → 抽屉」的移动留安全窗口。 */
    const val HIDE_DELAY_MILLIS = 350L

    /** 抽屉是否应展开。 */
    fun shouldReveal(
        edgeHovered: Boolean,
        drawerHovered: Boolean,
        interacting: Boolean,
        explicitlyClosed: Boolean,
    ): Boolean = !explicitlyClosed && (edgeHovered || drawerHovered || interacting)

    /** 显式关闭 latch 是否应解除（重新武装）：用户主动贴边，或指针已离开抽屉且无菜单弹窗交互。 */
    fun shouldRearm(
        edgeHovered: Boolean,
        drawerHovered: Boolean,
        interacting: Boolean,
    ): Boolean = edgeHovered || (!drawerHovered && !interacting)
}
