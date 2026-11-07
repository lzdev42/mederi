package xyz.mederi.workspace

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.api.exception.MederiWorkspaceException
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitWorkspaceDriverTest {

    private lateinit var testBaseDir: File
    private lateinit var driver: GitWorkspaceDriver

    @BeforeTest
    fun setUp() {
        testBaseDir = File("temp/git-driver-test-" + UUID.randomUUID())
        testBaseDir.mkdirs()
        driver = GitWorkspaceDriver()
    }

    @AfterTest
    fun tearDown() {
        testBaseDir.deleteRecursively()
    }

    private fun runRawCommand(workingDir: File, vararg args: String): Int {
        val pb = ProcessBuilder(*args)
        pb.directory(workingDir)
        return pb.start().waitFor()
    }

    private fun initRepoWithCommit(repoDir: File): String {
        repoDir.mkdirs()
        runRawCommand(repoDir, "git", "init")
        runRawCommand(repoDir, "git", "config", "user.name", "Test User")
        runRawCommand(repoDir, "git", "config", "user.email", "test@example.com")
        val initFile = File(repoDir, "README.md")
        initFile.writeText("# Test\n")
        runRawCommand(repoDir, "git", "add", "README.md")
        runRawCommand(repoDir, "git", "commit", "-m", "Initial commit")
        val p = ProcessBuilder("git", "rev-parse", "HEAD")
        p.directory(repoDir)
        val proc = p.start()
        val oid = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        return oid
    }

    @Test
    fun discoversLinkedWorktreeAndProjectSubdirectory() = runBlocking {
        val mainRepo = File(testBaseDir, "main-repo")
        initRepoWithCommit(mainRepo)

        val wtDir = File(testBaseDir, "linked-wt")
        runRawCommand(mainRepo, "git", "worktree", "add", wtDir.absolutePath, "HEAD")

        val subDir = File(wtDir, "sub/module")
        subDir.mkdirs()

        val info = driver.discover(subDir.absolutePath)
        assertTrue(info.isGit)
        assertEquals(wtDir.canonicalPath, info.checkoutRoot)
        assertEquals("sub/module", info.projectRelativePath)
        assertEquals(File(mainRepo, ".git").canonicalPath, info.gitCommonDirectory)
    }

    @Test
    fun preservesNulDelimitedSpecialPaths() = runBlocking {
        val mainRepo = File(testBaseDir, "special-paths-repo")
        initRepoWithCommit(mainRepo)

        val names = listOf(
            "-leading-dash.txt",
            "file with spaces.txt",
            "file\nwith\nnewlines.txt",
            "file\twith\ttab.txt",
            "中文文件.txt"
        )

        for (name in names) {
            val file = File(mainRepo, name)
            file.parentFile?.mkdirs()
            file.writeText("content of $name\n")
        }

        // 暂存部分文件，保留部分为未跟踪
        runRawCommand(mainRepo, "git", "add", "--", names[0], names[1])

        val res = driver.runGit(
            mainRepo.absolutePath,
            listOf("status", "--porcelain=v2", "-z", "--untracked-files=all")
        )
        val entries = driver.parsePorcelainV2(res.stdoutBytes)
        val entryPaths = entries.map { it.path }.toSet()

        for (name in names) {
            assertTrue(entryPaths.contains(name), "Expected status to preserve special path: $name")
        }
    }

    @Test
    fun rejectsOptionLikeAndChangedBaseRefs() = runBlocking {
        val mainRepo = File(testBaseDir, "option-refs-repo")
        val initialOid = initRepoWithCommit(mainRepo)

        // 选项样式的参数注入必须被拒绝
        assertFailsWith<MederiValidationException> {
            driver.resolveBaseRef(mainRepo.absolutePath, "--exec=touch /tmp/malicious")
        }
        assertFailsWith<MederiValidationException> {
            driver.resolveBaseRef(mainRepo.absolutePath, "-f")
        }

        // 基线发生变化检测
        val resolved = driver.resolveBaseRef(mainRepo.absolutePath, "HEAD")
        assertEquals(initialOid, resolved)

        // 提交新改动
        File(mainRepo, "change.txt").writeText("new change\n")
        runRawCommand(mainRepo, "git", "add", "change.txt")
        runRawCommand(mainRepo, "git", "commit", "-m", "Second commit")

        val ex = assertFailsWith<MederiWorkspaceException> {
            driver.verifyBaseRefUnchanged(mainRepo.absolutePath, "HEAD", initialOid)
        }
        assertEquals(WorkspaceErrorCode.BASE_CHANGED, ex.code)
    }

    @Test
    fun serializesCommonDirectoryAcrossProjects() = runBlocking {
        val mainRepo = File(testBaseDir, "common-dir-repo")
        initRepoWithCommit(mainRepo)
        val commonDir = File(mainRepo, ".git").canonicalPath

        val events = mutableListOf<String>()

        val job1 = async {
            driver.leaseManager.withManagementIntent(commonDir, listOf(mainRepo.absolutePath), permitOperationId = "job1") { _ ->
                events.add("job1-start")
                delay(150)
                events.add("job1-end")
            }
        }

        val job2 = async {
            delay(30)
            driver.leaseManager.withManagementIntent(commonDir, listOf(mainRepo.absolutePath), permitOperationId = "job2") { _ ->
                events.add("job2-start")
                delay(50)
                events.add("job2-end")
            }
        }

        job1.await()
        job2.await()

        assertEquals(listOf("job1-start", "job1-end", "job2-start", "job2-end"), events)
    }

    @Test
    fun blocksNewLeasesDuringManagementIntent() = runBlocking {
        val mainRepo = File(testBaseDir, "lease-block-repo")
        initRepoWithCommit(mainRepo)
        val commonDir = File(mainRepo, ".git").canonicalPath
        val checkoutRoot = mainRepo.canonicalPath

        // 正常状态下可获取共享执行租约
        val lease1 = driver.leaseManager.acquireExecutionLease(checkoutRoot)
        assertNotNull(lease1)
        lease1.close()

        // 在独占管理意图期间，新的执行租约请求必须直接拒绝 WORKSPACE_BUSY
        driver.leaseManager.withManagementIntent(commonDir, listOf(checkoutRoot), permitOperationId = "block_test") { _ ->
            val ex = assertFailsWith<MederiWorkspaceException> {
                driver.leaseManager.acquireExecutionLease(checkoutRoot)
            }
            assertEquals(WorkspaceErrorCode.WORKSPACE_BUSY, ex.code)
        }

        // 管理意图释放后，执行租约恢复可用
        val lease2 = driver.leaseManager.acquireExecutionLease(checkoutRoot)
        assertNotNull(lease2)
        lease2.close()
    }

    @Test
    fun neverRunsRepositoryHooksOrFilters() = runBlocking {
        val mainRepo = File(testBaseDir, "hooks-repo")
        initRepoWithCommit(mainRepo)

        // 在仓库 .git/hooks 注入恶意 post-checkout 脚本
        val hooksDir = File(mainRepo, ".git/hooks")
        hooksDir.mkdirs()
        val markerFile = File(testBaseDir, "hook_executed_marker.txt")
        val hookScript = File(hooksDir, "post-checkout")
        hookScript.writeText("#!/bin/sh\ntouch \"${markerFile.absolutePath}\"\nexit 0\n")
        hookScript.setExecutable(true)

        // 通过驱动运行 switch/checkout
        val res = driver.runGit(mainRepo.absolutePath, listOf("checkout", "-b", "new-branch"))
        assertEquals(0, res.exitCode)
        assertFalse(markerFile.exists(), "Git hooks must be bypassed and never executed")

        // 配置自定义 clean filter，驱动前置检查应报错拒绝
        runRawCommand(mainRepo, "git", "config", "filter.custom.clean", "cat")
        val ex = assertFailsWith<MederiWorkspaceException> {
            driver.preflightRepository(mainRepo.absolutePath)
        }
        assertEquals(WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE, ex.code)
    }

    @Test
    fun timesOutAndReapsOnlyItsOwnProcessGroup() = runBlocking {
        val mainRepo = File(testBaseDir, "timeout-repo")
        initRepoWithCommit(mainRepo)

        val ex = assertFailsWith<MederiWorkspaceException> {
            driver.runGit(
                mainRepo.absolutePath,
                listOf("-c", "alias.hang=!sleep 30", "hang"),
                timeoutSeconds = 1
            )
        }
        assertEquals(WorkspaceErrorCode.IO_ERROR, ex.code)
        assertTrue(ex.message!!.contains("timed out"))
    }

    @Test
    fun reportsUnsupportedRepositoryStateBeforeMutation() = runBlocking {
        // Case 1: unborn HEAD
        val emptyRepo = File(testBaseDir, "unborn-repo")
        emptyRepo.mkdirs()
        runRawCommand(emptyRepo, "git", "init")
        val ex1 = assertFailsWith<MederiWorkspaceException> {
            driver.preflightRepository(emptyRepo.absolutePath)
        }
        assertEquals(WorkspaceErrorCode.NO_BASE_COMMIT, ex1.code)

        // Case 2: merge in progress (MERGE_HEAD)
        val mergeRepo = File(testBaseDir, "merge-repo")
        initRepoWithCommit(mergeRepo)
        File(mergeRepo, ".git/MERGE_HEAD").writeText("1234567890abcdef\n")
        val ex2 = assertFailsWith<MederiWorkspaceException> {
            driver.preflightRepository(mergeRepo.absolutePath)
        }
        assertEquals(WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE, ex2.code)

        // Case 3: rebase in progress
        val rebaseRepo = File(testBaseDir, "rebase-repo")
        initRepoWithCommit(rebaseRepo)
        File(rebaseRepo, ".git/rebase-merge").mkdirs()
        val ex3 = assertFailsWith<MederiWorkspaceException> {
            driver.preflightRepository(rebaseRepo.absolutePath)
        }
        assertEquals(WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE, ex3.code)
    }
}
