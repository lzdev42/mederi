package xyz.mederi.workspace

import kotlinx.coroutines.runBlocking
import xyz.mederi.project.AgentsFileLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.*

class WorkspaceIncludeTest {

    private suspend fun fixture(block: suspend (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/workspace-include-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun runGit(dir: File, vararg args: String) {
        val pb = ProcessBuilder("git", *args)
        pb.directory(dir)
        pb.environment()["GIT_AUTHOR_NAME"] = "Test User"
        pb.environment()["GIT_AUTHOR_EMAIL"] = "test@example.com"
        pb.environment()["GIT_COMMITTER_NAME"] = "Test User"
        pb.environment()["GIT_COMMITTER_EMAIL"] = "test@example.com"
        val p = pb.start()
        val exit = p.waitFor()
        if (exit != 0) {
            val err = p.errorStream.bufferedReader().readText()
            error("git ${args.joinToString(" ")} failed: $err")
        }
    }

    @Test
    fun matchesGitNegationAnchorsAndPatternOrder(): Unit = runBlocking {
        fixture { tempFolder ->
            val srcRepo = File(tempFolder, "src-repo")
            srcRepo.mkdirs()
            runGit(srcRepo, "init")

            // .gitignore
            File(srcRepo, ".gitignore").writeText("*.env\nsecret/\ntemp/\n")
            runGit(srcRepo, "add", ".gitignore")
            runGit(srcRepo, "commit", "-m", "Initial commit")

            // 创建 ignored 文件
            File(srcRepo, "a.env").writeText("A_ENV=1")
            val nested = File(srcRepo, "nested")
            nested.mkdirs()
            File(nested, "b.env").writeText("B_ENV=2")

            val secretDir = File(srcRepo, "secret")
            secretDir.mkdirs()
            File(secretDir, "key.txt").writeText("SECRET_KEY")

            val tempDir = File(srcRepo, "temp")
            tempDir.mkdirs()
            File(tempDir, "dump.log").writeText("DUMP")

            // .worktreeinclude 规则：
            // *.env 包含全部 env 文件
            // !a.env 否定排除 a.env
            // /secret/key.txt 根锚定匹配
            val includeRules = """
                # 匹配所有 env
                *.env
                # 排除 a.env
                !a.env
                # 根锚定规则
                /secret/key.txt
            """.trimIndent()

            val service = WorkspaceIncludeService()
            val candidates = service.computeCandidates(srcRepo.absolutePath, includeRules)

            // nested/b.env 符合 *.env 且未被排除
            assertTrue(candidates.contains("nested/b.env"))
            // a.env 被否定规则排除
            assertFalse(candidates.contains("a.env"))
            // /secret/key.txt 锚定命中
            assertTrue(candidates.contains("secret/key.txt"))
            // temp/dump.log 未匹配
            assertFalse(candidates.contains("temp/dump.log"))
        }
    }

    @Test
    fun copiesOnlyIgnoredIntersectionWithoutOrdinaryUntracked(): Unit = runBlocking {
        fixture { tempFolder ->
            val srcRepo = File(tempFolder, "src-repo")
            srcRepo.mkdirs()
            val tgtRepo = File(tempFolder, "tgt-repo")
            tgtRepo.mkdirs()

            runGit(srcRepo, "init")
            File(srcRepo, ".gitignore").writeText("ignored.txt\n")
            runGit(srcRepo, "add", ".gitignore")
            runGit(srcRepo, "commit", "-m", "Initial commit")

            // 1. 真实 ignored 文件
            File(srcRepo, "ignored.txt").writeText("I am ignored")
            // 2. 普通未跟踪文件（未列入 .gitignore）
            File(srcRepo, "ordinary_untracked.txt").writeText("I am ordinary untracked")

            // .worktreeinclude 试图把两者都列入
            val includeRules = "ignored.txt\nordinary_untracked.txt\n"

            val service = WorkspaceIncludeService()
            val candidates = service.computeCandidates(srcRepo.absolutePath, includeRules)

            // 只有 A ∩ B (真实 ignored 与 include 规则交集) 才会成为候选
            assertTrue(candidates.contains("ignored.txt"))
            assertFalse(candidates.contains("ordinary_untracked.txt"), "普通未跟踪文件不能通过 include 规则被复制")

            // 执行复制
            val copyResult = service.copyIncludeFiles(srcRepo.absolutePath, tgtRepo.absolutePath, candidates)

            assertTrue(copyResult.copied.any { it.relativePath == "ignored.txt" })
            assertFalse(File(tgtRepo, "ordinary_untracked.txt").exists())
            assertTrue(File(tgtRepo, "ignored.txt").exists())
            assertEquals("I am ignored", File(tgtRepo, "ignored.txt").readText())
        }
    }

    @Test
    fun skipsSymlinkParentsAndExistingTargets(): Unit = runBlocking {
        fixture { tempFolder ->
            val srcRepo = File(tempFolder, "src-repo")
            srcRepo.mkdirs()
            val tgtRepo = File(tempFolder, "tgt-repo")
            tgtRepo.mkdirs()

            // 1. 符号链接文件
            val realFile = File(srcRepo, "real.txt")
            realFile.writeText("REAL")
            val symlinkFile = File(srcRepo, "symlink_file.txt")
            Files.createSymbolicLink(symlinkFile.toPath(), realFile.toPath())

            // 2. 符号链接父目录
            val realDir = File(srcRepo, "real_dir")
            realDir.mkdirs()
            File(realDir, "child.txt").writeText("CHILD")
            val symlinkDir = File(srcRepo, "symlink_dir")
            Files.createSymbolicLink(symlinkDir.toPath(), realDir.toPath())

            // 3. 目标端已存在的目标文件
            val targetExisting = File(tgtRepo, "already_there.txt")
            targetExisting.writeText("OLD_TARGET_CONTENT")
            val srcExisting = File(srcRepo, "already_there.txt")
            srcExisting.writeText("NEW_SOURCE_CONTENT")

            val service = WorkspaceIncludeService()
            val candidates = setOf("symlink_file.txt", "symlink_dir/child.txt", "already_there.txt")

            val copyResult = service.copyIncludeFiles(srcRepo.absolutePath, tgtRepo.absolutePath, candidates)

            // symlink_file.txt 被跳过
            assertTrue(copyResult.skipped.any { it.relativePath == "symlink_file.txt" && it.reason == SkipReason.SYMLINK_OR_SYMLINK_PARENT })
            // symlink_dir/child.txt 父级为符号链接被跳过
            assertTrue(copyResult.skipped.any { it.relativePath == "symlink_dir/child.txt" && it.reason == SkipReason.SYMLINK_OR_SYMLINK_PARENT })
            // already_there.txt 目标已存在被跳过且不覆盖
            assertTrue(copyResult.skipped.any { it.relativePath == "already_there.txt" && it.reason == SkipReason.ALREADY_EXISTS })
            assertEquals("OLD_TARGET_CONTENT", targetExisting.readText(), "目标存在时一律不覆盖")
        }
    }

    @Test
    fun loadsCopiedOverrideWithPerDirectoryPrecedence(): Unit = runBlocking {
        fixture { tempFolder ->
            val projectDir = File(tempFolder, "project")
            projectDir.mkdirs()

            // dirA: 同时存在 AGENTS.md 与 AGENTS.override.md
            val dirA = File(projectDir, "dirA")
            dirA.mkdirs()
            File(dirA, AgentsFileLoader.FILE_NAME).writeText("Base A")
            File(dirA, AgentsFileLoader.OVERRIDE_FILE_NAME).writeText("Override A")

            // dirB: 仅存在 AGENTS.md
            val dirB = File(projectDir, "dirB")
            dirB.mkdirs()
            File(dirB, AgentsFileLoader.FILE_NAME).writeText("Base B")

            // dirC: 仅存在 AGENTS.override.md
            val dirC = File(projectDir, "dirC")
            dirC.mkdirs()
            File(dirC, AgentsFileLoader.OVERRIDE_FILE_NAME).writeText("Override C")

            val loader = AgentsFileLoader()

            // 1. 指令链加载：每一级目录 AGENTS.override.md 优先于同目录 AGENTS.md，只加载一个
            val chain = loader.loadInstructionChain(projectDir.absolutePath)
            val fileNames = chain.map { File(it.path).name to it.content }

            assertTrue(fileNames.contains(AgentsFileLoader.OVERRIDE_FILE_NAME to "Override A"))
            assertFalse(fileNames.any { it.first == AgentsFileLoader.FILE_NAME && it.second == "Base A" }, "dirA 不应同时加载 Base A")

            assertTrue(fileNames.contains(AgentsFileLoader.FILE_NAME to "Base B"))
            assertTrue(fileNames.contains(AgentsFileLoader.OVERRIDE_FILE_NAME to "Override C"))

            // 2. 子树懒发现：从 dirA 内部文件向上访问
            val subA = loader.discoverSubtree(projectDir.absolutePath, File(dirA, "some_file.kt").absolutePath)
            assertNotNull(subA)
            assertEquals("Override A", subA.content)
            assertEquals(AgentsFileLoader.OVERRIDE_FILE_NAME, File(subA.path).name)

            // 从 dirB 内部文件向上访问
            val subB = loader.discoverSubtree(projectDir.absolutePath, File(dirB, "other_file.kt").absolutePath)
            assertNotNull(subB)
            assertEquals("Base B", subB.content)
            assertEquals(AgentsFileLoader.FILE_NAME, File(subB.path).name)
        }
    }

    @Test
    fun filtersDeniedInstructionsBeforeDiscoveryDeduplication(): Unit = runBlocking {
        fixture { tempFolder ->
            val projectDir = File(tempFolder, "project")
            projectDir.mkdirs()

            // 根目录 AGENTS.md
            File(projectDir, AgentsFileLoader.FILE_NAME).writeText("Root Instructions")

            // 敏感子目录 confidential/AGENTS.override.md
            val confidentialDir = File(projectDir, "confidential")
            confidentialDir.mkdirs()
            val deniedOverride = File(confidentialDir, AgentsFileLoader.OVERRIDE_FILE_NAME)
            deniedOverride.writeText("Confidential override rules")

            val deepFile = File(confidentialDir, "nested/code.kt")
            deepFile.parentFile.mkdirs()
            deepFile.writeText("fun code() = 42")

            val deniedCanonPath = deniedOverride.canonicalFile.toPath()
            val loader = AgentsFileLoader(
                isPathDenied = { it == deniedCanonPath }
            )

            // 1. 懒发现：访问 confidential/nested/code.kt
            // 敏感 override 被黑名单过滤，先于去重，因此继续向上回退发现 root 的 AGENTS.md
            val discovered = loader.discoverSubtree(projectDir.absolutePath, deepFile.absolutePath)
            // 因为上一级直至 projectDir（不含），若中间没有其它，则返回 null 或上一有效指令
            // 验证 confidential/AGENTS.override.md 绝不泄露
            if (discovered != null) {
                assertNotEquals(deniedOverride.canonicalPath, File(discovered.path).canonicalPath)
            }

            // 2. 指令链：confidential 下被拒绝的文件不得出现在加载链中
            val chain = loader.loadInstructionChain(projectDir.absolutePath)
            assertFalse(chain.any { File(it.path).canonicalPath == deniedOverride.canonicalPath })
            assertTrue(chain.any { File(it.path).canonicalPath == File(projectDir, AgentsFileLoader.FILE_NAME).canonicalPath })
        }
    }
}
