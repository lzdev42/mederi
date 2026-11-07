package xyz.mederi.workspace

import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.api.exception.MederiWorkspaceException
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceSnapshotTest {

    private lateinit var testBaseDir: File
    private lateinit var snapshotsDir: File
    private lateinit var snapshotService: WorkspaceSnapshotService
    private lateinit var driver: GitWorkspaceDriver

    @BeforeTest
    fun setUp() {
        testBaseDir = File("temp/snapshot-test-" + UUID.randomUUID())
        testBaseDir.mkdirs()
        snapshotsDir = File(testBaseDir, "snapshots")
        snapshotsDir.mkdirs()
        driver = GitWorkspaceDriver()
        snapshotService = WorkspaceSnapshotService(snapshotsDir, driver)
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

    private fun initRepo(repoDir: File): String {
        repoDir.mkdirs()
        runRawCommand(repoDir, "git", "init")
        runRawCommand(repoDir, "git", "config", "user.name", "Test User")
        runRawCommand(repoDir, "git", "config", "user.email", "test@example.com")
        val initFile = File(repoDir, "init.txt")
        initFile.writeText("initial content\n")
        runRawCommand(repoDir, "git", "add", "init.txt")
        runRawCommand(repoDir, "git", "commit", "-m", "Initial commit")
        val p = ProcessBuilder("git", "rev-parse", "HEAD")
        p.directory(repoDir)
        val proc = p.start()
        val oid = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        return oid
    }

    @Test
    fun restoresStagedAndUnstagedBytesSeparately(): Unit = runBlocking {
        val sourceRepo = File(testBaseDir, "staged-unstaged-src")
        initRepo(sourceRepo)

        val file = File(sourceRepo, "file.txt")
        file.writeText("Content A\n")
        runRawCommand(sourceRepo, "git", "add", "file.txt")
        runRawCommand(sourceRepo, "git", "commit", "-m", "Commit A")

        // 暂存 Content B
        file.writeText("Content B\n")
        runRawCommand(sourceRepo, "git", "add", "file.txt")

        // 工作区修改为 Content C (未暂存)
        file.writeText("Content C\n")

        val manifest = snapshotService.captureSnapshot(sourceRepo.absolutePath, "ws_1", "test_staged_unstaged")

        val targetRepo = File(testBaseDir, "staged-unstaged-tgt")
        snapshotService.restoreToCleanCheckout(manifest.snapshotId, targetRepo.absolutePath)

        // 验证目标工作区文件是 Content C
        val targetFile = File(targetRepo, "file.txt")
        assertTrue(targetFile.exists())
        assertEquals("Content C\n", targetFile.readText())

        // 验证目标暂存区是 Content B
        val catStage = driver.runGit(targetRepo.absolutePath, listOf("cat-file", "-p", ":file.txt"))
        assertEquals(0, catStage.exitCode)
        assertEquals("Content B\n", catStage.stdoutString)
    }

    @Test
    fun restoresBinaryDeletionRenameAndExecutableMode(): Unit = runBlocking {
        val sourceRepo = File(testBaseDir, "binary-mode-src")
        initRepo(sourceRepo)

        // 1. 二进制文件
        val binaryBytes = byteArrayOf(0, 1, 2, 255.toByte(), 254.toByte(), 0, 127, -128)
        val binFile = File(sourceRepo, "data.bin")
        binFile.writeBytes(binaryBytes)

        // 2. 可执行脚本
        val scriptFile = File(sourceRepo, "run.sh")
        scriptFile.writeText("#!/bin/sh\necho test\n")
        scriptFile.setExecutable(true, false)

        // 3. 待删除文件
        val deleteFile = File(sourceRepo, "to_delete.txt")
        deleteFile.writeText("delete me later\n")

        // 4. 待重命名文件
        val renameFile = File(sourceRepo, "old_name.txt")
        renameFile.writeText("rename me\n")

        runRawCommand(sourceRepo, "git", "add", "data.bin", "run.sh", "to_delete.txt", "old_name.txt")
        runRawCommand(sourceRepo, "git", "commit", "-m", "Commit items")

        // 执行删除与重命名
        runRawCommand(sourceRepo, "git", "rm", "to_delete.txt")
        runRawCommand(sourceRepo, "git", "mv", "old_name.txt", "new_name.txt")

        val manifest = snapshotService.captureSnapshot(sourceRepo.absolutePath, "ws_2", "test_binary_modes")

        val targetRepo = File(testBaseDir, "binary-mode-tgt")
        snapshotService.restoreToCleanCheckout(manifest.snapshotId, targetRepo.absolutePath)

        // 验证二进制内容
        val tgtBin = File(targetRepo, "data.bin")
        assertTrue(tgtBin.exists())
        assertTrue(binaryBytes.contentEquals(tgtBin.readBytes()))

        // 验证可执行权限
        val tgtScript = File(targetRepo, "run.sh")
        assertTrue(tgtScript.exists())
        assertTrue(tgtScript.canExecute())

        // 验证删除与重命名
        val tgtDeleted = File(targetRepo, "to_delete.txt")
        assertFalse(tgtDeleted.exists())

        val tgtOld = File(targetRepo, "old_name.txt")
        val tgtNew = File(targetRepo, "new_name.txt")
        assertFalse(tgtOld.exists())
        assertTrue(tgtNew.exists())
        assertEquals("rename me\n", tgtNew.readText())
    }

    @Test
    fun restoresIndexOnlyBlobsAfterSourceGarbageCollection(): Unit = runBlocking {
        val sourceRepo = File(testBaseDir, "index-blob-src")
        initRepo(sourceRepo)

        // 仅暂存但未提交的新文件
        val secretFile = File(sourceRepo, "secret_blob.txt")
        secretFile.writeText("Secret staged only content without commit\n")
        runRawCommand(sourceRepo, "git", "add", "secret_blob.txt")

        val manifest = snapshotService.captureSnapshot(sourceRepo.absolutePath, "ws_3", "test_index_blob")

        // 在源仓库销毁未提交暂存并强制 GC
        runRawCommand(sourceRepo, "git", "reset", "--hard", "HEAD")
        runRawCommand(sourceRepo, "git", "clean", "-fdx")
        runRawCommand(sourceRepo, "git", "reflog", "expire", "--expire=now", "--all")
        runRawCommand(sourceRepo, "git", "prune", "--expire=now")
        runRawCommand(sourceRepo, "git", "gc", "--prune=now")

        // 恢复到全新的独立目标
        val targetRepo = File(testBaseDir, "index-blob-tgt")
        snapshotService.restoreToCleanCheckout(manifest.snapshotId, targetRepo.absolutePath)

        val restoredSecret = File(targetRepo, "secret_blob.txt")
        assertTrue(restoredSecret.exists())
        assertEquals("Secret staged only content without commit\n", restoredSecret.readText())

        // 暂存区应同样具备该 blob
        val catStage = driver.runGit(targetRepo.absolutePath, listOf("cat-file", "-p", ":secret_blob.txt"))
        assertEquals(0, catStage.exitCode)
        assertEquals("Secret staged only content without commit\n", catStage.stdoutString)
    }

    @Test
    fun restoresDetachedHeadWithoutSourceRepository(): Unit = runBlocking {
        val sourceRepo = File(testBaseDir, "detached-head-src")
        val headOid = initRepo(sourceRepo)

        val manifest = snapshotService.captureSnapshot(sourceRepo.absolutePath, "ws_4", "test_detached")

        // 恢复到干净目录
        val targetRepo = File(testBaseDir, "detached-head-tgt")
        snapshotService.restoreToCleanCheckout(manifest.snapshotId, targetRepo.absolutePath)

        val revHead = driver.runGit(targetRepo.absolutePath, listOf("rev-parse", "HEAD"))
        assertEquals(0, revHead.exitCode)
        assertEquals(headOid, revHead.stdoutString.trim())

        val logRes = driver.runGit(targetRepo.absolutePath, listOf("log", "-1", "--format=%s"))
        assertEquals(0, logRes.exitCode)
        assertEquals("Initial commit", logRes.stdoutString.trim())
    }

    @Test
    fun snapshotsIgnoredResidueAndEmptyDirectories(): Unit = runBlocking {
        val sourceRepo = File(testBaseDir, "ignored-residue-src")
        initRepo(sourceRepo)

        File(sourceRepo, ".gitignore").writeText("*.log\nbuild/\n")
        runRawCommand(sourceRepo, "git", "add", ".gitignore")
        runRawCommand(sourceRepo, "git", "commit", "-m", "Add ignore")

        // 忽略的文件
        val ignoredFile = File(sourceRepo, "output.log")
        ignoredFile.writeText("ignored log output\n")

        // 空目录
        val emptyDir = File(sourceRepo, "empty_dir/nested_empty")
        emptyDir.mkdirs()

        // 采用 PREIMAGE 捕获全量残留
        val manifest = snapshotService.captureSnapshot(
            sourceRepo.absolutePath,
            "ws_5",
            "test_preimage",
            captureType = SnapshotCaptureType.PREIMAGE
        )

        val targetRepo = File(testBaseDir, "ignored-residue-tgt")
        snapshotService.restoreToCleanCheckout(manifest.snapshotId, targetRepo.absolutePath)

        val restoredIgnored = File(targetRepo, "output.log")
        assertTrue(restoredIgnored.exists())
        assertEquals("ignored log output\n", restoredIgnored.readText())

        val restoredEmpty = File(targetRepo, "empty_dir/nested_empty")
        assertTrue(restoredEmpty.exists())
        assertTrue(restoredEmpty.isDirectory)
    }

    @Test
    fun preservesSymlinksWithoutFollowingTargets(): Unit = runBlocking {
        val sourceRepo = File(testBaseDir, "symlink-src")
        initRepo(sourceRepo)

        val realFile = File(sourceRepo, "real_target.txt")
        realFile.writeText("Target text\n")

        val linkFile = File(sourceRepo, "symlink_file.txt")
        Files.createSymbolicLink(linkFile.toPath(), Paths.get("real_target.txt"))

        val brokenLink = File(sourceRepo, "dangling_link.txt")
        Files.createSymbolicLink(brokenLink.toPath(), Paths.get("does_not_exist.txt"))

        val manifest = snapshotService.captureSnapshot(sourceRepo.absolutePath, "ws_6", "test_symlinks")

        val targetRepo = File(testBaseDir, "symlink-tgt")
        snapshotService.restoreToCleanCheckout(manifest.snapshotId, targetRepo.absolutePath)

        val restoredLink = File(targetRepo, "symlink_file.txt").toPath()
        assertTrue(Files.isSymbolicLink(restoredLink))
        assertEquals("real_target.txt", Files.readSymbolicLink(restoredLink).toString())

        val restoredBroken = File(targetRepo, "dangling_link.txt").toPath()
        assertTrue(Files.isSymbolicLink(restoredBroken))
        assertEquals("does_not_exist.txt", Files.readSymbolicLink(restoredBroken).toString())
    }

    @Test
    fun rejectsCorruptManifestAndTraversal(): Unit = runBlocking {
        val targetRepo = File(testBaseDir, "corrupt-tgt")

        // Case 1: 路径穿越 manifest
        val corruptDir1 = File(snapshotsDir, "snap_traversal")
        corruptDir1.mkdirs()
        File(corruptDir1, "manifest.json").writeText(
            """
            {
              "schemaVersion": 1,
              "snapshotId": "snap_traversal",
              "workspaceId": "ws_corrupt",
              "checkoutRoot": "/fake",
              "projectRelativePath": "",
              "headOid": "1234567890abcdef",
              "entries": [
                {
                  "repoRelativePath": "../../../etc/passwd",
                  "exists": true,
                  "kind": "FILE",
                  "sha256": "fake"
                }
              ],
              "indexBlobs": [],
              "reason": "test",
              "createdAt": "2026-11-02T00:00:00Z",
              "contentFingerprint": "fp"
            }
            """.trimIndent()
        )

        assertFailsWith<MederiValidationException> {
            snapshotService.restoreToCleanCheckout("snap_traversal", targetRepo.absolutePath)
        }

        // Case 2: 损坏的 blob hash
        val corruptDir2 = File(snapshotsDir, "snap_corrupt_blob")
        corruptDir2.mkdirs()
        val blobsDir = File(corruptDir2, "blobs")
        blobsDir.mkdirs()
        File(blobsDir, "declared_sha").writeText("Different actual content")
        File(corruptDir2, "manifest.json").writeText(
            """
            {
              "schemaVersion": 1,
              "snapshotId": "snap_corrupt_blob",
              "workspaceId": "ws_corrupt",
              "checkoutRoot": "/fake",
              "projectRelativePath": "",
              "headOid": "1234567890abcdef",
              "entries": [
                {
                  "repoRelativePath": "normal_file.txt",
                  "exists": true,
                  "kind": "FILE",
                  "sha256": "declared_sha"
                }
              ],
              "indexBlobs": [],
              "reason": "test",
              "createdAt": "2026-11-02T00:00:00Z",
              "contentFingerprint": "fp"
            }
            """.trimIndent()
        )

        assertFailsWith<MederiWorkspaceException> {
            snapshotService.restoreToCleanCheckout("snap_corrupt_blob", targetRepo.absolutePath)
        }
    }

    @Test
    fun preservesOriginalOnQuotaOrVerificationFailure(): Unit = runBlocking {
        val sourceRepo = File(testBaseDir, "preserve-orig-src")
        initRepo(sourceRepo)

        val criticalFile = File(sourceRepo, "critical.txt")
        criticalFile.writeText("Original untouched text\n")

        val manifest = snapshotService.captureSnapshot(sourceRepo.absolutePath, "ws_8", "test_quota")

        // 人为破坏 snapshot head.bundle
        val bundleFile = File(snapshotsDir, "${manifest.snapshotId}/head.bundle")
        bundleFile.writeText("Corrupted bundle bytes")

        val targetRepo = File(testBaseDir, "failed-restore-tgt")

        // 尝试恢复损坏快照必失败
        assertFailsWith<MederiWorkspaceException> {
            snapshotService.restoreToCleanCheckout(manifest.snapshotId, targetRepo.absolutePath)
        }

        // 验证源仓库未受任何损害
        assertTrue(criticalFile.exists())
        assertEquals("Original untouched text\n", criticalFile.readText())
    }

    @Test
    fun rollsBackOnlyJournalOwnedPostimagePaths(): Unit = runBlocking {
        val repo = File(testBaseDir, "rollback-journal-repo")
        initRepo(repo)

        // 1. 已存在文件
        val existingFile = File(repo, "existing.txt")
        existingFile.writeText("Existing before op\n")

        // 2. 将被操作修改的文件
        val modifiedFile = File(repo, "modified.txt")
        modifiedFile.writeText("Preimage content\n")
        runRawCommand(repo, "git", "add", "existing.txt", "modified.txt")
        runRawCommand(repo, "git", "commit", "-m", "Base commit")

        // 捕获 PREIMAGE 快照
        val preimage = snapshotService.captureSnapshot(
            repo.absolutePath,
            "ws_rollback",
            "preimage",
            captureType = SnapshotCaptureType.PREIMAGE
        )

        // 模拟操作阶段执行副作用：
        // 修改 modified.txt
        modifiedFile.writeText("Postimage modified content\n")

        // 创建新文件 created.txt
        val createdFile = File(repo, "created_by_op.txt")
        createdFile.writeText("New file from operation\n")

        // 外部并发/无关文件
        val externalFile = File(repo, "external_file.txt")
        externalFile.writeText("External change\n")

        // 构造 Journal 记录
        val journal = listOf(
            WorkspacePhaseRecord(
                phase = WorkspacePhase.MATERIALIZE_TARGET,
                startedAt = "2026-11-02T00:00:00Z",
                affectedPaths = listOf(
                    PhasePathRecord(
                        repoRelativePath = "modified.txt",
                        preExists = true,
                        postExists = true
                    ),
                    PhasePathRecord(
                        repoRelativePath = "created_by_op.txt",
                        preExists = false,
                        postExists = true
                    )
                )
            )
        )

        // 执行回滚
        snapshotService.rollbackCheckout(repo.absolutePath, preimage.snapshotId, journal)

        // 验证：
        // created_by_op.txt 必须被删除
        assertFalse(createdFile.exists())

        // modified.txt 必须恢复到 preimage 内容
        assertEquals("Preimage content\n", modifiedFile.readText())

        // existing.txt 必须原封不动
        assertEquals("Existing before op\n", existingFile.readText())

        // 关键断言：external_file.txt 未在 journal 中，绝对不能被删除或篡改！
        assertTrue(externalFile.exists())
        assertEquals("External change\n", externalFile.readText())
    }
}
