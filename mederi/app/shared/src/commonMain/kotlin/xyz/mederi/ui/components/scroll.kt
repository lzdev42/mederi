package xyz.mederi.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * 判定 ReasoningBlock 是否应启用内部限高与垂直滚动容器：
 * - 只有外层要求限高 (enforceMaxHeight=true) 且未切换为无界全部展开 (!isUnbounded) 时才启用滚动与限高。
 * - 当 isUnbounded=true（用户点击底部展开按钮）或 enforceMaxHeight=false 时，必须禁用 verticalScroll，
 *   避免在 LazyColumn (垂直无界 Constraints.Infinity) 内挂载 verticalScroll 触发 Compose 崩溃。
 */
fun shouldEnableReasoningScroll(enforceMaxHeight: Boolean, isUnbounded: Boolean): Boolean {
    return enforceMaxHeight && !isUnbounded
}

/**
 * 阻断向外层滚动容器（如 LazyColumn）冒泡的嵌套滚动连接器。
 * 消费掉未被子容器消费的所有垂直方向滚动量与滑动速度。
 */
val ContainNestedScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        // 彻底消费掉垂直方向未被内部容器消费的剩余滚动量，防止向上冒泡到外层 LazyColumn
        return Offset(0f, available.y)
    }

    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity
    ): Velocity {
        // 彻底消费垂直方向惯性滑动的剩余速度，防止惯性带动外层 LazyColumn 晃动
        return Velocity(0f, available.y)
    }
}

/**
 * 拦截并消费子容器未消费的垂直滚动与滑动量（overscroll），
 * 阻断向外层滚动容器（如 LazyColumn）的滚动穿透冒泡（Scroll Chaining）。
 * 语义等同于 Web 标准的 `overscroll-behavior: contain`。
 */
fun Modifier.containScroll(): Modifier = this.nestedScroll(ContainNestedScrollConnection)
