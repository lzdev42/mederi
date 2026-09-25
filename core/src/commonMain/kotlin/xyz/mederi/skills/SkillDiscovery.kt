package xyz.mederi.skills

import xyz.mederi.skills.domain.SkillInfo
import java.io.File

/**
 * BFS 目录扫描器，发现 `SKILL.md` 并解析其 frontmatter 产出 [SkillInfo]。
 *
 * 与 Koog discoverSkills 参数约定对齐：depth-first 搜索结果按 name 去重（LAST_FOUND：
 * 后发现的覆盖先发现的，且保持首次发现顺序）。
 */
object SkillDiscovery {

    private const val MAX_DEPTH = 4
    private const val MAX_DIRECTORIES = 2000
    private const val SKILL_MD_FILE = "SKILL.md"

    private val SKIPPED_DIRECTORIES = setOf(".git", "node_modules")

    fun discover(root: String): List<SkillInfo> {
        val rootFile = File(root)
        if (!rootFile.exists() || !rootFile.isDirectory) return emptyList()

        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(rootFile to 0)
        var directoriesProcessed = 0
        // 按 name 去重：LAST_FOUND 语义（重复 name 覆盖值，不改变插入顺序）
        val result = linkedMapOf<String, SkillInfo>()

        while (queue.isNotEmpty() && directoriesProcessed < MAX_DIRECTORIES) {
            val (dir, depth) = queue.removeFirst()
            directoriesProcessed++

            // 找本目录下的 SKILL.md 并解析
            val skillFile = dir.listFiles()?.firstOrNull { it.isFile && it.name == SKILL_MD_FILE }
            if (skillFile != null) {
                val parsed = SkillFrontmatterParser.parse(skillFile.readText())
                // name/description 缺一不可（invalid 时二者必为 null）
                if (parsed.name != null && parsed.description != null) {
                    val info = SkillInfo(
                        name = parsed.name,
                        description = parsed.description,
                        location = skillFile.absolutePath,
                        license = parsed.license,
                        compatibility = parsed.compatibility,
                        allowedTools = parsed.allowedTools,
                    )
                    result[info.name] = info
                }
            }

            if (depth >= MAX_DEPTH) continue
            dir.listFiles()
                ?.filter { it.isDirectory && it.name !in SKIPPED_DIRECTORIES }
                ?.forEach { queue.add(it to depth + 1) }
        }

        return result.values.toList()
    }
}