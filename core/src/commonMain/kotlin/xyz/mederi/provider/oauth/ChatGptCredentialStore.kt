package xyz.mederi.provider.oauth

import com.sun.security.auth.module.NTSystem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The config root is caller-selected; in-memory Mederi never writes to a default home directory. */
internal class ChatGptCredentialStore(private val directory: File?) {
    private val memory = ConcurrentHashMap<String, ChatGptCredential>()
    private val json = Json { ignoreUnknownKeys = true }
    private var memoryHost: String? = null
    private val privateFileAttribute = directory?.let {
        val path = Files.createDirectories(it.toPath())
        check(!Files.isSymbolicLink(path)) { "OAuth storage cannot be a symbolic link" }
        val fileStore = Files.getFileStore(path)
        when {
            fileStore.supportsFileAttributeView("posix") -> {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            }
            fileStore.supportsFileAttributeView("acl") -> {
                val view = aclView(path)
                // An elevated Windows token can create objects owned by the Administrators group.
                val owner = view.owner.let { if (it is GroupPrincipal) currentWindowsPrincipal(path) else it }
                check(owner !is GroupPrincipal) { "OAuth storage must be owned by an individual account" }
                val entries = listOf(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                    .build())
                protectAcl(view, entries)
                requireAcl(view, entries)
                OwnerOnlyAcl(entries)
            }
            else -> error("Cannot protect OAuth credential storage")
        }
    }

    private class OwnerOnlyAcl(private val entries: List<AclEntry>) : FileAttribute<List<AclEntry>> {
        override fun name() = "acl:acl"
        override fun value() = entries
    }

    private fun aclView(path: Path): AclFileAttributeView =
        checkNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)) {
            "Cannot protect OAuth credential storage"
        }

    private fun currentWindowsPrincipal(path: Path): UserPrincipal {
        val identity = NTSystem()
        val name = checkNotNull(identity.name?.takeIf(String::isNotBlank)) { "Cannot resolve Windows account" }
        val domain = identity.domain
        val account = if (domain.isNullOrBlank()) name else "$domain\\$name"
        return path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(account)
    }

    private fun protectAcl(view: AclFileAttributeView, entries: List<AclEntry>) {
        val owner = entries.single().principal()
        if (view.owner is GroupPrincipal) view.owner = owner
        check(view.owner == owner) { "Cannot protect OAuth credential storage" }
        if (view.acl != entries) view.acl = entries
    }

    private fun requireAcl(view: AclFileAttributeView, entries: List<AclEntry>) {
        check(view.acl == entries && view.owner == entries.single().principal()) {
            "Cannot protect OAuth credential storage"
        }
    }

    fun read(providerId: String): ChatGptCredential? {
        val file = file(providerId) ?: return memory[providerId]
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        check(!Files.isSymbolicLink(file.toPath())) { "OAuth credential cannot be a symbolic link" }
        return try { json.decodeFromString(file.readText()) } catch (_: Exception) {
            throw IllegalStateException("Cannot read ChatGPT credential storage")
        }
    }

    fun write(providerId: String, credential: ChatGptCredential) {
        val file = file(providerId)
        if (file == null) memory[providerId] = credential
        else writeProtected(file, json.encodeToString(credential))
    }

    fun delete(providerId: String) {
        memory.remove(providerId)
        file(providerId)?.let { Files.deleteIfExists(it.toPath()) }
    }

    @Synchronized
    fun hostId(): String {
        if (directory == null) return memoryHost ?: newHostId().also { memoryHost = it }
        val file = File(directory, "host.json")
        return locked("host") {
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                check(!Files.isSymbolicLink(file.toPath())) { "OAuth host file cannot be a symbolic link" }
                json.decodeFromString<ChatGptHost>(file.readText()).hostId
            } else newHostId().also { writeProtected(file, json.encodeToString(ChatGptHost(it))) }
        }
    }

    /** OS advisory lock serializes rotating refresh tokens across Mederi processes. */
    fun <T> locked(providerId: String, block: () -> T): T {
        if (directory == null) return block()
        val path = File(directory, "${safeId(providerId)}.lock").toPath()
        check(!Files.isSymbolicLink(path)) { "OAuth lock cannot be a symbolic link" }
        val attribute = checkNotNull(privateFileAttribute)
        return synchronized(sharedFileLocks.computeIfAbsent(path.toAbsolutePath().normalize().toString()) { Any() }) {
            val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            FileChannel.open(path, options, attribute).use { channel ->
                if (attribute is OwnerOnlyAcl) {
                    val view = aclView(path)
                    protectAcl(view, attribute.value())
                    requireAcl(view, attribute.value())
                } else {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
                }
                channel.lock().use { block() }
            }
        }
    }

    private fun file(providerId: String): File? = directory?.let { File(it, "${safeId(providerId)}.json") }
    private fun safeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid OAuth provider ID" }
        return id
    }
    private fun newHostId() = "urn:uuid:${UUID.randomUUID()}"

    companion object { private val sharedFileLocks = ConcurrentHashMap<String, Any>() }

    private fun writeProtected(file: File, body: String) {
        check(!Files.isSymbolicLink(file.toPath())) { "OAuth credential cannot be a symbolic link" }
        val parent = file.parentFile.toPath()
        val attribute = checkNotNull(privateFileAttribute)
        val scratch = Files.createTempFile(parent, ".oauth-", ".tmp", attribute)
        try {
            if (attribute is OwnerOnlyAcl) {
                val view = aclView(scratch)
                if (view.owner is GroupPrincipal) view.owner = attribute.value().single().principal()
                requireAcl(view, attribute.value())
            }
            Files.writeString(scratch, body, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
            Files.move(scratch, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(scratch) }
    }
}
