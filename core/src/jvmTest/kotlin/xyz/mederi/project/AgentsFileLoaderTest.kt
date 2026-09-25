package xyz.mederi.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * AGENTS.md 加载器回归：
 * 1. 指令链：git 根 → 项目目录，浅 → 深；无 .git 时链仅项目目录。
 *    向下层：项目目录直接子目录（一层）里的 AGENTS.md 也纳入（AGENTS.md 不一定
 *    在项目根/git 根，可能在下一级或平级多目录）；跳过隐藏/生成目录；更深靠懒发现。
 * 2. 子树懒发现：访问路径向上到项目目录（不含）最近优先；
 *    项目目录自身/目录外/直接子级 → null（根已注入不重复）。
 * 3. 大小上限截断。
 */
class AgentsFileLoaderTest {

    private fun dir(name: String): File =
        createTempDirectory(name).toFile()

    private fun File.writeAgents(content: String): File =
        File(this, AgentsFileLoader.FILE_NAME).apply { writeText(content) }

    // ==================== 指令链 ====================

    @Test
    fun `git 根与项目目录都有 AGENTS_md 时，浅到深返回两个`() {
        val root = dir("repo")
        File(root, ".git").mkdirs()
        root.writeAgents("root rules")
        val project = File(root, "app").apply { mkdirs() }
        project.writeAgents("app rules")

        val chain = AgentsFileLoader().loadInstructionChain(project.absolutePath)

        assertEquals(listOf("root rules", "app rules"), chain.map { it.content })
    }

    @Test
    fun `项目绑定 git 根的嵌套子目录时，上层 AGENTS_md 不漏（mederi 实际结构）`() {
        val root = dir("repo")
        File(root, ".git").mkdirs()
        root.writeAgents("repo level")
        val nested = File(root, "mederi").apply { mkdirs() }
        nested.writeAgents("nested level")
        val project = File(nested, "core").apply { mkdirs() }

        val chain = AgentsFileLoader().loadInstructionChain(project.absolutePath)

        assertEquals(listOf("repo level", "nested level"), chain.map { it.content })
        // git 根相对项目目录隔两层，相对路径含 ..
        assertEquals("../..${File.separator}AGENTS.md", chain[0].relativePath)
    }

    @Test
    fun `无 git 时链仅项目目录自身`() {
        val project = dir("plain")
        project.writeAgents("only")

        val chain = AgentsFileLoader().loadInstructionChain(project.absolutePath)

        assertEquals(listOf("only"), chain.map { it.content })
    }

    // ==================== 向下层（项目目录直接子目录） ====================

    @Test
    fun `项目目录自身无 AGENTS_md 而下一级子目录有，纳入指令链（mederi 实际结构）`() {
        val project = dir("repo")
        val nested = File(project, "mederi").apply { mkdirs() }
        nested.writeAgents("nested level")

        val chain = AgentsFileLoader().loadInstructionChain(project.absolutePath)

        assertEquals(listOf("nested level"), chain.map { it.content })
        assertEquals("mederi/AGENTS.md", chain.single().relativePath)
    }

    @Test
    fun `平级子目录 A 下 B C 各有一份 AGENTS_md 时全部纳入按名排序`() {
        val project = dir("A")
        val b = File(project, "B").apply { mkdirs() }
        b.writeAgents("B rules")
        val c = File(project, "C").apply { mkdirs() }
        c.writeAgents("C rules")

        val chain = AgentsFileLoader().loadInstructionChain(project.absolutePath)

        assertEquals(listOf("B rules", "C rules"), chain.map { it.content })
    }

    @Test
    fun `项目目录自身与下一级平级子目录同时存在时，浅层在前`() {
        val project = dir("repo")
        project.writeAgents("root rules")
        File(project, ".git").mkdirs()
        val sub = File(project, "sub").apply { mkdirs() }
        sub.writeAgents("sub rules")

        val chain = AgentsFileLoader().loadInstructionChain(project.absolutePath)

        assertEquals(listOf("root rules", "sub rules"), chain.map { it.content })
    }

    @Test
    fun `向下扫描跳过隐藏目录与生成目录`() {
        val project = dir("repo")
        // 隐藏目录（.module/.git）、生成目录（node_modules/build）里的 AGENTS.md 都不纳入
        File(project, ".module").apply { mkdirs(); writeAgents("hidden module") }
        File(project, "node_modules").apply { mkdirs(); writeAgents("dep") }
        File(project, "build").apply { mkdirs(); writeAgents("build") }

        val chain = AgentsFileLoader().loadInstructionChain(project.absolutePath)

        assertTrue(chain.isEmpty())
    }

    @Test
    fun `链上有字节限制时子目录 AGENTS_md 同样截断`() {
        val project = dir("repo")
        val sub = File(project, "sub").apply { mkdirs() }
        sub.writeAgents("a".repeat(100))

        val chain = AgentsFileLoader(maxFileBytes = 40)
            .loadInstructionChain(project.absolutePath)

        val found = chain.single()
        assertTrue(found.content.startsWith("a".repeat(40)))
        assertTrue(found.content.contains("truncated"))
    }

    @Test
    fun `链上没有 AGENTS_md 时返回空列表`() {
        val root = dir("repo")
        File(root, ".git").mkdirs()
        val project = File(root, "app").apply { mkdirs() }

        assertTrue(AgentsFileLoader().loadInstructionChain(project.absolutePath).isEmpty())
    }

    @Test
    fun `git 根在中间层时，中间层与项目目录都进链`() {
        val outer = dir("outer")
        val gitRoot = File(outer, "repo").apply { mkdirs() }
        File(gitRoot, ".git").mkdirs()
        gitRoot.writeAgents("git root")
        val project = File(gitRoot, "sub").apply { mkdirs() }
        project.writeAgents("sub")
        // git 根之上还有目录，但 AGENTS.md 在 git 根之外 → 不进链
        outer.writeAgents("outside git")

        val chain = AgentsFileLoader().loadInstructionChain(project.absolutePath)

        assertEquals(listOf("git root", "sub"), chain.map { it.content })
    }

    // ==================== 子树懒发现 ====================

    @Test
    fun `访问深层文件时发现其路径上最近的 AGENTS_md`() {
        val project = dir("proj")
        val sub = File(project, "sub").apply { mkdirs() }
        sub.writeAgents("sub rules")
        val deep = File(sub, "a").apply { mkdirs() }
        val target = File(deep, "f.kt").apply { writeText("x") }

        val found = AgentsFileLoader().discoverSubtree(project.absolutePath, target.absolutePath)

        assertEquals("sub rules", found?.content)
    }

    @Test
    fun `项目目录自身的 AGENTS_md 不由子树发现返回（已进指令链）`() {
        val project = dir("proj")
        project.writeAgents("root")
        val target = File(project, "f.kt").apply { writeText("x") }

        assertNull(AgentsFileLoader().discoverSubtree(project.absolutePath, target.absolutePath))
    }

    @Test
    fun `访问项目根本身或直接子级文件时返回 null`() {
        val project = dir("proj")
        project.writeAgents("root")

        assertNull(AgentsFileLoader().discoverSubtree(project.absolutePath, project.absolutePath))

        val direct = File(project, "f.kt").apply { writeText("x") }
        assertNull(AgentsFileLoader().discoverSubtree(project.absolutePath, direct.absolutePath))
    }

    @Test
    fun `访问路径在项目目录外时返回 null`() {
        val project = dir("proj")
        val other = dir("other").apply { writeAgents("other") }
        val target = File(other, "f.kt").apply { writeText("x") }

        assertNull(AgentsFileLoader().discoverSubtree(project.absolutePath, target.absolutePath))
    }

    @Test
    fun `多层 AGENTS_md 时取距访问路径最近的一个`() {
        val project = dir("proj")
        val mid = File(project, "mid").apply { mkdirs() }
        mid.writeAgents("mid")
        val deep = File(mid, "deep").apply { mkdirs() }
        deep.writeAgents("deep")
        val target = File(deep, "f.kt").apply { writeText("x") }

        val found = AgentsFileLoader().discoverSubtree(project.absolutePath, target.absolutePath)

        assertEquals("deep", found?.content)
    }

    // ==================== 大小上限 ====================

    @Test
    fun `超过大小上限时截断并标注`() {
        val project = dir("proj")
        project.writeAgents("a".repeat(100))

        val found = AgentsFileLoader(maxFileBytes = 40)
            .loadInstructionChain(project.absolutePath)
            .single()

        assertTrue(found.content.startsWith("a".repeat(40)))
        assertTrue(found.content.contains("truncated"))
    }
}
