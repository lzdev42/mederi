package xyz.mederi.workspace

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.store.sqlite.SqliteDiffStore
import xyz.mederi.store.sqlite.SqliteSessionStore
import xyz.mederi.store.sqlite.SqliteWorkspaceStore
import xyz.mederi.tools.diff.FileChange
import xyz.mederi.tools.diff.FileChangeStatus
import xyz.mederi.tools.diff.TurnDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceStoreTest {

    private fun createInMemoryDb(): xyz.mederi.store.db.DatabaseManager {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MederiDataDatabase.Schema.create(driver)
        Mederi.migrateDataDb(driver)
        return xyz.mederi.store.db.DatabaseManager(driver)
    }

    @Test
    fun migratesLegacyDatabaseWithoutLosingSessionsOrDiffs() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // 模拟 v3 遗留数据库结构（无 workspace 列与工作区表）
        driver.execute(
            null,
            """
            CREATE TABLE sessions (
                id              TEXT NOT NULL PRIMARY KEY,
                project_id      TEXT NOT NULL,
                title           TEXT NOT NULL DEFAULT '',
                status          TEXT NOT NULL DEFAULT 'IDLE',
                agent_mode      TEXT NOT NULL DEFAULT 'AUTONOMOUS',
                ai_model        TEXT,
                reasoning_level TEXT,
                api_key_id      TEXT,
                env             TEXT NOT NULL DEFAULT '{}',
                todos           TEXT NOT NULL DEFAULT '[]',
                active_skills   TEXT NOT NULL DEFAULT '[]',
                draft_text      TEXT,
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL
            );
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            CREATE TABLE diffs (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id      TEXT NOT NULL,
                message_id      TEXT,
                changes_json    TEXT NOT NULL,
                unified_diff    TEXT NOT NULL,
                created_at      TEXT NOT NULL
            );
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            CREATE TABLE message_history (
                session_id    TEXT NOT NULL,
                seq           INTEGER NOT NULL,
                message_id    TEXT,
                role          TEXT NOT NULL,
                content       TEXT NOT NULL,
                payload       TEXT NOT NULL,
                created_at    TEXT NOT NULL,
                model_id      TEXT,
                duration_ms   INTEGER,
                finish_reason TEXT,
                status        TEXT,
                PRIMARY KEY (session_id, seq)
            );
            """.trimIndent(),
            0
        )

        // 插入旧数据
        driver.execute(
            null,
            """
            INSERT INTO sessions (id, project_id, title, status, agent_mode, env, todos, active_skills, created_at, updated_at)
            VALUES ('sess_legacy', 'proj_legacy', 'Legacy Title', 'IDLE', 'AUTONOMOUS', '{}', '[]', '[]', '2026-11-01T00:00:00Z', '2026-11-01T00:00:00Z');
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            INSERT INTO diffs (session_id, message_id, changes_json, unified_diff, created_at)
            VALUES ('sess_legacy', 'msg_1', '[]', '--- a' || char(10) || '+++ b', '2026-11-01T00:00:00Z');
            """.trimIndent(),
            0
        )
        driver.execute(null, "PRAGMA user_version = 3", 0)

        // 执行迁移
        Mederi.migrateDataDb(driver)

        // 验证版本号已推进到当前版本
        val version = driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0)?.toInt() ?: 0 else 0
                )
            },
            0
        ).value
        assertEquals(Mederi.DATA_DB_VERSION, version)

        // 验证旧会话数据无损，新字段具有默认兼容值
        val db = xyz.mederi.store.db.DatabaseManager(driver)

        // 验证旧会话数据无损，新字段具有默认兼容值
        val sessionStore = SqliteSessionStore(db)
        val session = sessionStore.get("sess_legacy")
        assertNotNull(session)
        assertEquals("Legacy Title", session.title)
        assertEquals("proj_legacy", session.projectId)
        assertNull(session.workspaceId)
        assertNull(session.associatedWorktreeId)
        assertNull(session.archivedAt)
        assertEquals(false, session.pinned)
        assertTrue(session.subagentConfigs.isEmpty())
        assertNull(session.subagentMaxConcurrent)

        // 验证旧 Diff 数据无损
        val diffStore = SqliteDiffStore(db)
        val diffs = diffStore.list("sess_legacy")
        assertEquals(1, diffs.size)
        assertEquals("--- a\n+++ b", diffs[0].unifiedDiff)
        assertNull(diffs[0].workspaceId)
        assertNull(diffs[0].checkoutRootAtCreation)

        // 验证新表 workspaces 与 workspace_operations 正常读写
        val workspaceStore = SqliteWorkspaceStore(db)
        val ws = Workspace(
            id = "ws_test",
            projectId = "proj_legacy",
            kind = WorkspaceKind.LOCAL,
            checkoutRoot = "/test/root",
            projectRelativePath = "",
            state = WorkspaceState.READY,
            generation = 1L,
            createdAt = "2026-11-02T00:00:00Z",
            lastUsedAt = "2026-11-02T00:00:00Z"
        )
        workspaceStore.saveWorkspace(ws)
        val loadedWs = workspaceStore.getWorkspace("ws_test")
        assertNotNull(loadedWs)
        assertEquals(ws, loadedWs)
    }

    @Test
    fun roundTripsSelectionPlanAndPhasePostimages() = runBlocking {
        val db = createInMemoryDb()
        val store = SqliteWorkspaceStore(db)

        val pendingSelection = PendingWorkspaceSelection(
            selection = WorkspaceSelection(
                kind = WorkspaceKind.MANAGED_WORKTREE,
                baseRef = "refs/heads/main",
                carryLocalChanges = true,
                environmentId = "env_node"
            ),
            resolvedBaseOid = "abcdef1234567890",
            sourceCheckoutId = "ws_source",
            environmentId = "env_node",
            environmentVersion = 2L,
            environmentHash = "hash_xyz"
        )

        val workspace = Workspace(
            id = "ws_managed_1",
            projectId = "proj_1",
            kind = WorkspaceKind.MANAGED_WORKTREE,
            checkoutRoot = "/worktrees/ws_managed_1/checkout",
            projectRelativePath = "sub/proj",
            gitCommonDirectory = "/repo/.git",
            baseCommit = "abcdef1234567890",
            associatedSessionId = "sess_100",
            state = WorkspaceState.READY,
            generation = 3L,
            lastSnapshotId = "snap_42",
            createdAt = "2026-11-02T01:00:00Z",
            lastUsedAt = "2026-11-02T02:00:00Z",
            pendingSelection = pendingSelection,
            taskBranchName = "mederi/task-100"
        )

        store.saveWorkspace(workspace)
        val loadedWorkspace = store.getWorkspace("ws_managed_1")
        assertNotNull(loadedWorkspace)
        assertEquals(workspace, loadedWorkspace)
        assertEquals(pendingSelection, loadedWorkspace.pendingSelection)
        assertEquals("mederi/task-100", loadedWorkspace.taskBranchName)

        val plan = WorkspaceOperationPlan.HandoffPlan(
            requestHash = "req_hash_1",
            permissionVersion = 5L,
            participatingWorkspaceIds = listOf("ws_source", "ws_managed_1"),
            participatingPaths = listOf("/repo", "/worktrees/ws_managed_1/checkout"),
            expectedGenerations = mapOf("ws_source" to 2L, "ws_managed_1" to 3L),
            sourceWorkspaceId = "ws_source",
            targetWorkspaceId = "ws_managed_1",
            targetKind = WorkspaceKind.MANAGED_WORKTREE,
            taskBranchName = "mederi/task-100",
            expectedSourceHeadOid = "oid_src",
            expectedTargetHeadOid = "oid_tgt",
            sourceTransferSnapshotId = "snap_transfer",
            sourcePreimageSnapshotId = "snap_src_pre",
            targetPreimageSnapshotId = "snap_tgt_pre",
            acceptedTargetSnapshotId = "snap_tgt_post",
            gitRefMutations = listOf(
                GitRefMutation(
                    ref = "refs/heads/mederi/task-100",
                    expectedOldOid = null,
                    newOid = "oid_src"
                )
            ),
            stopAffectedSessions = true,
            previewToken = "token_preview_xyz"
        )

        val phaseRecord = WorkspacePhaseRecord(
            phase = WorkspacePhase.MATERIALIZE_TARGET,
            startedAt = "2026-11-02T02:00:00Z",
            finishedAt = "2026-11-02T02:00:05Z",
            affectedPaths = listOf(
                PhasePathRecord(
                    repoRelativePath = "src/Main.kt",
                    preExists = false,
                    preType = null,
                    preHash = null,
                    preMode = null,
                    postExists = true,
                    postType = "FILE",
                    postHash = "hash_file_content",
                    postMode = 420 // 0644
                )
            ),
            createdByOperation = true,
            expectedHeadOid = "oid_src",
            expectedHeadRef = "refs/heads/mederi/task-100",
            phaseFingerprint = "fingerprint_phase_postimage"
        )

        val resolution = RecoveryResolution(
            strategy = RecoveryStrategy.KEEP_VERIFIED_CURRENT,
            snapshotId = "snap_src_pre",
            keepWorkspaceId = "ws_source",
            resolvedAt = "2026-11-02T02:05:00Z"
        )

        val operation = WorkspaceOperation(
            id = "op_handoff_1",
            projectId = "proj_1",
            sessionId = "sess_100",
            workspaceId = "ws_managed_1",
            kind = WorkspaceOperationKind.HANDOFF,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = "handoff:req_1",
            requestHash = "req_hash_1",
            sourceWorkspaceId = "ws_source",
            targetWorkspaceId = "ws_managed_1",
            sourceSnapshotId = "snap_transfer",
            targetSnapshotId = "snap_tgt_post",
            cancelRequested = false,
            progressCompleted = 100L,
            progressTotal = 100L,
            error = null,
            version = 7L,
            createdAt = "2026-11-02T02:00:00Z",
            updatedAt = "2026-11-02T02:05:00Z",
            parentOperationId = null,
            operationPlan = plan,
            phaseRecords = listOf(phaseRecord),
            recoveryResolution = resolution
        )

        store.saveOperation(operation)
        val loadedOperation = store.getOperation("op_handoff_1")
        assertNotNull(loadedOperation)
        assertEquals(operation, loadedOperation)
        assertEquals(plan, loadedOperation.operationPlan)
        assertEquals(listOf(phaseRecord), loadedOperation.phaseRecords)
        assertEquals(resolution, loadedOperation.recoveryResolution)
    }

    @Test
    fun commitsBindingAndOperationInOneTransaction() = runBlocking {
        val db = createInMemoryDb()
        val sessionStore = SqliteSessionStore(db)
        val workspaceStore = SqliteWorkspaceStore(db)

        val session = Session(
            id = "sess_commit_1",
            projectId = "proj_1",
            title = "Commit Test",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = null,
            reasoningLevel = null,
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z"
        )
        sessionStore.insert(session)

        val newWorkspace = Workspace(
            id = "ws_target",
            projectId = "proj_1",
            kind = WorkspaceKind.MANAGED_WORKTREE,
            checkoutRoot = "/worktrees/ws_target/checkout",
            projectRelativePath = "",
            state = WorkspaceState.READY,
            generation = 1L,
            createdAt = "2026-11-02T00:00:00Z",
            lastUsedAt = "2026-11-02T00:00:00Z"
        )

        val plan = WorkspaceOperationPlan.CreatePlan(
            requestHash = "req_create_1",
            selection = WorkspaceSelection(WorkspaceKind.MANAGED_WORKTREE),
            targetPath = "/worktrees/ws_target/checkout"
        )

        val operation = WorkspaceOperation(
            id = "op_create_1",
            projectId = "proj_1",
            sessionId = "sess_commit_1",
            workspaceId = "ws_target",
            kind = WorkspaceOperationKind.CREATE,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = "create:req_1",
            requestHash = "req_create_1",
            operationPlan = plan,
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z"
        )

        val success = workspaceStore.commitBinding(
            sessionId = "sess_commit_1",
            expectedWorkspaceId = null,
            expectedGeneration = null,
            newWorkspace = newWorkspace,
            associatedWorktreeId = "ws_target",
            operation = operation
        )
        assertTrue(success)

        val updatedSession = sessionStore.get("sess_commit_1")
        assertNotNull(updatedSession)
        assertEquals("ws_target", updatedSession.workspaceId)
        assertEquals("ws_target", updatedSession.associatedWorktreeId)

        val loadedWorkspace = workspaceStore.getWorkspace("ws_target")
        assertNotNull(loadedWorkspace)
        assertEquals(WorkspaceState.READY, loadedWorkspace.state)

        val loadedOperation = workspaceStore.getOperation("op_create_1")
        assertNotNull(loadedOperation)
        assertEquals(WorkspaceOperationState.SUCCEEDED, loadedOperation.state)
    }

    @Test
    fun rejectsStaleGenerationWithoutPartialUpdates() = runBlocking {
        val db = createInMemoryDb()
        val sessionStore = SqliteSessionStore(db)
        val workspaceStore = SqliteWorkspaceStore(db)

        val session = Session(
            id = "sess_stale",
            projectId = "proj_1",
            title = "Stale Test",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = null,
            reasoningLevel = null,
            workspaceId = "ws_old",
            associatedWorktreeId = "ws_old",
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z"
        )
        sessionStore.insert(session)

        val oldWorkspace = Workspace(
            id = "ws_old",
            projectId = "proj_1",
            kind = WorkspaceKind.MANAGED_WORKTREE,
            checkoutRoot = "/worktrees/ws_old/checkout",
            projectRelativePath = "",
            state = WorkspaceState.READY,
            generation = 2L,
            createdAt = "2026-11-02T00:00:00Z",
            lastUsedAt = "2026-11-02T00:00:00Z"
        )
        workspaceStore.saveWorkspace(oldWorkspace)

        val newWorkspaceCandidate = oldWorkspace.copy(generation = 3L)
        val plan = WorkspaceOperationPlan.ActionPlan(
            requestHash = "act_1",
            workspaceId = "ws_old",
            environmentId = "env_1",
            actionId = "test",
            environmentVersion = 1L
        )
        val operation = WorkspaceOperation(
            id = "op_stale_1",
            projectId = "proj_1",
            sessionId = "sess_stale",
            workspaceId = "ws_old",
            kind = WorkspaceOperationKind.ACTION,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = "act:1",
            requestHash = "act_1",
            operationPlan = plan,
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z"
        )

        // 预期代次为 1L，实际已有代次为 2L -> 必须抛错并拒绝写入
        val ex = assertFailsWith<MederiWorkspaceException> {
            workspaceStore.commitBinding(
                sessionId = "sess_stale",
                expectedWorkspaceId = "ws_old",
                expectedGeneration = 1L, // 过期代次！
                newWorkspace = newWorkspaceCandidate,
                associatedWorktreeId = "ws_old",
                operation = operation
            )
        }
        assertEquals(WorkspaceErrorCode.STALE_GENERATION, ex.code)

        // 验证原子性：会话未更新，工作区未更新，操作未存入
        val sessionAfter = sessionStore.get("sess_stale")
        assertNotNull(sessionAfter)
        assertEquals("ws_old", sessionAfter.workspaceId)

        val wsAfter = workspaceStore.getWorkspace("ws_old")
        assertNotNull(wsAfter)
        assertEquals(2L, wsAfter.generation)

        val opAfter = workspaceStore.getOperation("op_stale_1")
        assertNull(opAfter)
    }

    @Test
    fun preservesWorkspaceFieldsDuringComposeSave() = runBlocking {
        val db = createInMemoryDb()
        val sessionStore = SqliteSessionStore(db)

        val session = Session(
            id = "sess_compose",
            projectId = "proj_1",
            title = "Compose Test",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = null,
            reasoningLevel = null,
            apiKeyId = null,
            draftText = null,
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            workspaceId = "ws_custom_1",
            associatedWorktreeId = "wt_assoc_1",
            archivedAt = "2026-11-02T05:00:00Z",
            pinned = true
        )
        sessionStore.insert(session)

        // UI 保存输入框 compose 设置
        sessionStore.updateComposeState(
            id = "sess_compose",
            agentMode = AgentMode.APPROVAL,
            aiModel = null,
            reasoningLevel = null,
            apiKeyId = "key_new",
            draftText = "Draft in progress"
        )

        val loaded = sessionStore.get("sess_compose")
        assertNotNull(loaded)
        assertEquals(AgentMode.APPROVAL, loaded.agentMode)
        assertEquals("key_new", loaded.apiKeyId)
        assertEquals("Draft in progress", loaded.draftText)

        // 核心断言：workspaceId / associatedWorktreeId / archivedAt / pinned 完整保留
        assertEquals("ws_custom_1", loaded.workspaceId)
        assertEquals("wt_assoc_1", loaded.associatedWorktreeId)
        assertEquals("2026-11-02T05:00:00Z", loaded.archivedAt)
        assertEquals(true, loaded.pinned)
    }
}
