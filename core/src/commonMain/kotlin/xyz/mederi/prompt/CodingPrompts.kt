package xyz.mederi.prompt

/**
 * 编码核心原则常量。
 *
 * 唯一真理源（SSOT）：主代理与 EXECUTOR 子代理共享，集中维护代码编写、修改与调试纪律。
 * RESEARCHER 等只读角色不挂载本原则。
 */
object CodingPrompts {
    val PRINCIPLES: String = """
# Coding Principles

1. Fix root causes, never suppress symptoms.
   - Always diagnose and resolve the true underlying defect.
   - Never mask failures: do not swallow exceptions, add blind null-checks or fallbacks,
     silence compiler/lint warnings, weaken assertions, or modify tests to fake a pass.
2. Minimal surgical edits.
   - Solve the problem with the smallest required diff and minimal blast radius.
   - No unsolicited refactoring, stylistic changes to untouched lines, or superfluous abstractions.
3. Follow existing conventions.
   - Read neighboring files before writing; match established patterns, idioms, and style.
""".trimIndent()
}
