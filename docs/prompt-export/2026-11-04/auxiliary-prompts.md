# 辅助任务提示词原文

以下正文直接来自对应常量，保留原始语言；自动命名的方括号为动态输入占位值。动态拼接的完整代码仍见 templates-source.md。

## 历史压缩

来源：`core/src/commonMain/kotlin/xyz/mederi/koog/MederiCompressionStrategy.kt` / `SUMMARY_PROMPT`。

````text
直接总结以上对话，不要任何开场白、客套语或说明性文字，直接进入正文。

格式要求：
1. 第一行必须是 `TLDR:` 开头的一句话总览，总览后直接进入正文，不要空行铺垫或过渡句。
2. 正文包含以下五个部分，各部分之间用 `---` 分割线隔开：

## 关键决策
列出所有已做出的重要决定及其理由。包括技术选型、方案选择、用户偏好等。

---

## 用户讨论情况
记录用户的偏好、要求、反馈和约束条件。

---

## 未完成讨论
列出尚未解决的问题、待确认的事项、悬而未决的问题点及原因。这部分最重要，确保不遗漏任何待办事项。

---

## 当前阶段
说明讨论/任务进行到什么阶段，下一步是什么。

---

## 关键记忆
列出不能丢失的硬事实：代码结构、文件路径、技术约束、API 接口、数据结构等。

3. 每个部分用标题 + 简洁列表输出，信息完整、便于检索，排版清晰美观。

````

## 项目 AGENTS.md 生成

来源：`core/src/commonMain/kotlin/xyz/mederi/koog/AgentsFileGenerator.kt` / `GENERATION_SYSTEM_PROMPT`。

````text

You are generating an AGENTS.md file for a software project. AGENTS.md is a standard
instruction file that AI coding agents read automatically to work effectively in a
repository.

Explore the repository with list_directory and read_file:
- Identify the tech stack, build system, and module layout
- Read build files (build.gradle.kts, settings.gradle.kts, package.json, Cargo.toml,
  pyproject.toml...), README, and key configs
- Look at test sources to find how tests are run and with what command
- Sample a few representative source files to detect the conventions actually in use

Then produce the AGENTS.md with sections like:
- Project overview (what it is, tech stack, key dependencies)
- Module/directory layout (what lives where)
- Build / run / test commands (exact commands, verified from build files)
- Code conventions (naming, formatting, patterns in actual use)
- Hard rules (anything critical not to break)

HARD REQUIREMENTS:
- Ground every statement in what you actually read — never invent commands or paths.
- Be concise: instructions an agent needs, not documentation for humans.
- Your FINAL message must be ONLY the raw markdown content of the AGENTS.md —
  no preamble, no explanation, no surrounding code fences.

````

## 会话自动命名

来源：`app/shared/src/jvmMain/kotlin/xyz/mederi/core/autotitle/SessionTitleService.kt` / `PROMPT_PREFIX`。实际调用在其后追加截断后的用户原文。

````text
根据以下用户消息生成一个不超过20字的会话标题。只输出标题本身，不要引号、句号或任何解释。
用户消息：[USER_MESSAGE]
````
