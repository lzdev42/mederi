# Skills / placeholder

[BASE_PROMPT]

# Available Skills

The following skills are installed locally. When the current task matches a skill's
description, call load_skill(name) to activate it — its full SKILL.md workflow is
mounted into your system prompt under '# Active Skill Workflows' on the next turn
(NOT into your conversation history). Apply its workflow within the agreed scope and role permissions; it refines
the generic tool guidelines, not user decisions or safety boundaries. Do NOT read_file the SKILL.md — that pollutes
your conversation history; load_skill keeps the workflow out of history.

- **[SKILL_NAME]**: [SKILL_DESCRIPTION] (SKILL.md at `[SKILL_LOCATION]`)

# Active Skill Workflows

The following skills are activated. Their full workflows are loaded below — follow them
when the current task matches:

## [SKILL_NAME]

[SKILL_BODY]

# Project rules / placeholder

[BASE_PROMPT]

# Project Instructions (AGENTS.md)

This project defines AGENTS.md files (ordered shallow to deep; deeper files refine
and override shallower ones). Treat them as binding project conventions:

## [RELATIVE_PATH]

[PROJECT_INSTRUCTIONS]

# Steering wrapper / placeholder

<user_intervention>
[System Note: The user submitted the following guidance while you were executing tools. Incorporate this guidance into your ongoing task without restarting from scratch]:
[USER_INTERVENTION]
</user_intervention>

# Active plan / placeholder



# Active Plan
[ACTIVE_PLAN]

# Todo / placeholder



# Current Todo
[CURRENT_TODO]
This list tracks work that does NOT go through the Plan Loop — including multi-step ad-hoc execution
you broke into items and dispatched to subagents. Keep it current via update_todo (one call replaces
the whole list).

# Browser judge / placeholder

### [判定规则与背景配置 / Recipe Rules]：
[RECIPE_RULES]

### [待分析页面内容 / Raw Content]：
[PAGE_CONTENT]

### [判定要求与输出格式 / Instruction]：
[JUDGE_INSTRUCTION]

# Browser report / placeholder

### [任务原始目标 / Task Goal]：
[TASK_GOAL]

### [配方规则 / Recipe Rules]：
[RECIPE_RULES]

### [操作员完成信息 / Operator Output]：
[OPERATOR_OUTPUT]

### [执行轨迹日志 / Execution History]：
[STEP_HISTORY]

请根据以上信息，生成一句话简报及结构化详情。

