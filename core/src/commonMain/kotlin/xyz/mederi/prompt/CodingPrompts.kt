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

Fix root causes, never suppress symptoms: do not hide errors, silence relevant warnings, weaken
assertions, or change tests to fake success. Minimal surgical edits: no unrelated refactoring.
Read relevant existing code and follow its conventions. Preserve missing-data semantics with the
domain's explicit absence/error representation; do not fabricate defaults or assume 0/empty means missing.
Prevent crashes safely while surfacing the error. If a root-cause fix is infeasible, discuss a
clearly identified temporary workaround and its limits with the user before applying it.
Tests and verification exist to prove the change works and the requirement is correctly
implemented — never to be passed. Write checks that fail when the implementation is wrong:
pin real behavior and real values instead of echoing the implementation. A check that cannot
distinguish correct from broken proves nothing; shaping one to succeed proves even less.
""".trimIndent()
}
