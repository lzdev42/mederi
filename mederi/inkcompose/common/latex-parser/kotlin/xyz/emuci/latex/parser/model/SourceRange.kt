package xyz.emuci.latex.parser.model

/**
 * 源代码位置范围，表示 LaTeX 输入字符串中的 [start, end) 半开区间
 *
 * @param start 起始字符偏移（包含）
 * @param end 结束字符偏移（不包含）
 */
data class SourceRange(val start: Int, val end: Int) {

    /** 范围长度 */
    val length: Int get() = end - start

    /** 是否为空范围 */
    val isEmpty: Boolean get() = start >= end

    /** 判断偏移量是否在范围内 */
    fun contains(offset: Int): Boolean = offset in start until end

    /** 合并两个范围，返回覆盖两者的最小范围 */
    fun merge(other: SourceRange): SourceRange =
        SourceRange(minOf(start, other.start), maxOf(end, other.end))

    companion object {
        /** 空范围，用于无源码位置的节点 */
        val EMPTY = SourceRange(0, 0)
    }
}
