package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.model.SourceRange

/**
 * 解析诊断信息
 *
 * 记录解析过程中遇到的非致命问题（如未识别的 token、不完整的结构等），
 * 供渲染层用于错误标注或调试。
 *
 * @param range 问题在源文本中的位置
 * @param message 人类可读的诊断描述
 * @param severity 严重级别
 * @param category 诊断分类（P1 增强：结构化错误列表）
 */
data class ParseDiagnostic(
    val range: SourceRange,
    val message: String,
    val severity: Severity,
    val category: Category = Category.GENERAL
) {
    enum class Severity {
        INFO,
        WARNING,
        ERROR
    }

    /**
     * 诊断分类 — 用于结构化错误列表和分类过滤
     */
    enum class Category {
        /** 一般性诊断 */
        GENERAL,
        /** 未识别的命令 */
        UNKNOWN_COMMAND,
        /** 缺少闭合花括号 } */
        MISSING_BRACE,
        /** 缺少闭合方括号 ] */
        MISSING_BRACKET,
        /** 不匹配的 \begin/\end */
        MISMATCHED_ENVIRONMENT,
        /** 无效的参数 */
        INVALID_ARGUMENT,
        /** 意外的 token */
        UNEXPECTED_TOKEN,
        /** 自定义命令相关 */
        MACRO_ERROR,
    }
}

/**
 * 解析结果 — 包含 AST 和结构化诊断信息
 *
 * 提供比 [LatexNode.Document] 更丰富的解析输出，
 * 支持诊断面板 API 和错误过滤。
 *
 * 用法示例：
 * ```kotlin
 * val result = LatexParser().parseWithDiagnostics("\\frac{a}{b} + \\unknowncmd")
 * println("AST: ${result.document}")
 * println("错误数: ${result.errors.size}")
 * println("警告数: ${result.warnings.size}")
 * result.diagnostics.forEach { println("${it.severity}: ${it.message} at ${it.range}") }
 * ```
 */
data class ParseResult(
    val document: LatexNode.Document,
    val diagnostics: List<ParseDiagnostic>
) {
    /** 是否有任何诊断信息 */

    /** 筛选出所有错误级别的诊断 */
    val errors: List<ParseDiagnostic> get() =
        diagnostics.filter { it.severity == ParseDiagnostic.Severity.ERROR }

    /** 筛选出所有警告级别的诊断 */
    val warnings: List<ParseDiagnostic> get() =
        diagnostics.filter { it.severity == ParseDiagnostic.Severity.WARNING }

    /** 按分类过滤诊断 */
    fun diagnosticsByCategory(category: ParseDiagnostic.Category): List<ParseDiagnostic> =
        diagnostics.filter { it.category == category }
}
