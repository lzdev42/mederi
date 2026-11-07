package xyz.mederi.provider.oauth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The config root is caller-selected; in-memory Mederi never writes to a default home directory. */
internal class ChatGptCredentialStore(private val directory: File?) {
    private val memory = ConcurrentHashMap<String, ChatGptCredential>()
    private val json = Json { ignoreUnknownKeys = true }
    private var memoryHost: String? = null

    init {
        directory?.let {
            Files.createDirectories(it.toPath())
            check(!Files.isSymbolicLink(it.toPath())) { "OAuth storage cannot be a symbolic link" }
            if (Files.getFileStore(it.toPath()).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
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
        return synchronized(sharedFileLocks.computeIfAbsent(path.toAbsolutePath().normalize().toString()) { Any() }) {
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
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
        val posix = Files.getFileStore(parent).supportsFileAttributeView("posix")
        val scratch = if (posix) Files.createTempFile(parent, ".oauth-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        else Files.createTempFile(parent, ".oauth-", ".tmp")
        try {
            Files.writeString(scratch, body, StandardOpenOption.TRUNCATE_EXISTING)
            if (!posix) {
                check(scratch.toFile().setReadable(false, false) && scratch.toFile().setReadable(true, true) &&
                    scratch.toFile().setWritable(false, false) && scratch.toFile().setWritable(true, true)) {
                    "Cannot protect OAuth credential storage"
                }
            }
            Files.move(scratch, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(scratch) }
    }
}
