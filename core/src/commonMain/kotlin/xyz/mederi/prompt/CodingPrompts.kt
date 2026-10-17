package xyz.mederi.prompt

/**
 * 编码核心原则常量。
 *
 * 唯一真理源（SSOT）：主代理使用。EXECUTOR 子代理使用独立提示词，不挂载本原则。
 * RESEARCHER 等只读角色不挂载。
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
4. Fallback discipline — only two legitimate goals: prevent crash, prevent spurious
   request/command. All other fallbacks are harmful — they hide problems instead of
   surfacing them.
   - No data fabrication: never invent plausible-looking defaults (hardcoded names, URLs,
     ports, etc.) to mask missing data. Use honest empties — 0, null, empty list — chosen
     by type context, so the absence is visible downstream.
   - No business-logic fallback: when the correct behavior is ambiguous, surface the error
     honestly. Do not guess a "safe" path and silently proceed.
""".trimIndent()
}
