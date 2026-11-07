package xyz.mederi.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import xyz.mederi.api.exception.MederiWorkspaceException

/**
 * 内部不可序列化工作区验证根凭证。
 * 只能由 Manager / Driver 核对 registry / canonical / git 身份后产生，UI/REST 无法反序列化伪造。
 */
class VerifiedWorkspaceRoot internal constructor(
    val workspaceId: String,
    val projectId: String,
    val checkoutRoot: Path,
    val projectRelativePath: String,
    val workingDirectory: Path,
    val artifactDirectory: Path,
    val verifiedArtifactWritablePaths: List<Path>,
    val gitCommonDirectory: Path?,
    val generation: Long,
) {
    init {
        require(checkoutRoot.isAbsolute) { "checkoutRoot must be absolute: $checkoutRoot" }
        require(workingDirectory.isAbsolute) { "workingDirectory must be absolute: $workingDirectory" }
        require(artifactDirectory.isAbsolute) { "artifactDirectory must be absolute: $artifactDirectory" }
        require(workingDirectory.startsWith(checkoutRoot)) { "workingDirectory $workingDirectory must be under checkoutRoot $checkoutRoot" }

        if (Files.isSymbolicLink(checkoutRoot)) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.IDENTITY_CHANGED,
                message = "Workspace checkoutRoot is a symbolic link: $checkoutRoot"
            )
        }
        if (Files.isSymbolicLink(workingDirectory)) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.IDENTITY_CHANGED,
                message = "Workspace workingDirectory is a symbolic link: $workingDirectory"
            )
        }
    }

    /**
     * 运行时对宿主文件系统再次验证身份。
     * 若 checkoutRoot 或 workingDirectory 被外部替换为符号链接，或 gitCommonDirectory 不匹配，抛出 IDENTITY_CHANGED。
     */
    fun verifyIdentity(expectedGitCommonDir: String? = null) {
        if (Files.isSymbolicLink(checkoutRoot) || !Files.exists(checkoutRoot, NOFOLLOW_LINKS)) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.IDENTITY_CHANGED,
                message = "Workspace checkoutRoot was replaced or missing: $checkoutRoot"
            )
        }
        if (Files.isSymbolicLink(workingDirectory) || !Files.exists(workingDirectory, NOFOLLOW_LINKS)) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.IDENTITY_CHANGED,
                message = "Workspace workingDirectory was replaced or missing: $workingDirectory"
            )
        }
        if (expectedGitCommonDir != null && gitCommonDirectory != null) {
            val expected = File(expectedGitCommonDir).canonicalFile.toPath()
            if (expected != gitCommonDirectory) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.IDENTITY_CHANGED,
                    message = "Git repository identity changed: expected $expected, got $gitCommonDirectory"
                )
            }
        }
    }

    companion object {
        val EXACT_ARTIFACT_SUBPATHS = listOf(
            "plans",
            "plans-done",
            "plans-voided",
            "research",
            "notebook.md"
        )

        fun create(
            workspaceId: String,
            projectId: String,
            checkoutRoot: String,
            projectRelativePath: String = "",
            artifactDirectory: String,
            gitCommonDirectory: String? = null,
            generation: Long = 0L,
            additionalArtifactPaths: List<String> = emptyList(),
        ): VerifiedWorkspaceRoot {
            val fileCheckout = File(checkoutRoot)
            if (Files.isSymbolicLink(fileCheckout.toPath())) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.IDENTITY_CHANGED,
                    message = "Workspace checkout root is a symbolic link: $checkoutRoot"
                )
            }
            val canonCheckout = fileCheckout.canonicalFile.toPath()
            val cleanRel = projectRelativePath.trim().trim('/', '\\')
            val workingDir = if (cleanRel.isEmpty()) {
                canonCheckout
            } else {
                val subFile = File(canonCheckout.toFile(), cleanRel)
                if (Files.isSymbolicLink(subFile.toPath())) {
                    throw MederiWorkspaceException(
                        code = WorkspaceErrorCode.IDENTITY_CHANGED,
                        message = "Workspace working directory is a symbolic link: ${subFile.path}"
                    )
                }
                subFile.canonicalFile.toPath()
            }

            val canonArtifact = File(artifactDirectory).canonicalFile.toPath()
            val exactArtifacts = (EXACT_ARTIFACT_SUBPATHS + additionalArtifactPaths).map { sub ->
                canonArtifact.resolve(sub).toFile().canonicalFile.toPath()
            }.distinct()

            val canonCommon = gitCommonDirectory?.let { File(it).canonicalFile.toPath() }

            return VerifiedWorkspaceRoot(
                workspaceId = workspaceId,
                projectId = projectId,
                checkoutRoot = canonCheckout,
                projectRelativePath = cleanRel,
                workingDirectory = workingDir,
                artifactDirectory = canonArtifact,
                verifiedArtifactWritablePaths = exactArtifacts,
                gitCommonDirectory = canonCommon,
                generation = generation
            )
        }
    }
}
