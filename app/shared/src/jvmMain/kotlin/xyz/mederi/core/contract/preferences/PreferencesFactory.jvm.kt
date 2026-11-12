package xyz.mederi.core.contract.preferences

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import xyz.mederi.config.MederiIdentity

actual fun defaultPreferencesStore(): PreferencesStore {
    val directory = Path.of(System.getProperty("user.home"), MederiIdentity.homeConfigRootName)
    val preferences = JsonFilePreferencesStore(directory.resolve("preferences.json").toString().toPath(), FileSystem.SYSTEM)
    return DesktopPreferencesStore(preferences, directory.resolve("cloudflare-api-token"))
}

private class DesktopPreferencesStore(
    private val preferences: PreferencesStore,
    private val tokenFile: Path,
) : PreferencesStore by preferences {
    private val mutex = Mutex()

    override suspend fun getString(key: String): String? =
        if (key == TOKEN_KEY) withContext(Dispatchers.IO) {
            mutex.withLock { if (Files.exists(tokenFile)) Files.readString(tokenFile) else null }
        } else preferences.getString(key)

    override suspend fun putString(key: String, value: String) {
        if (key != TOKEN_KEY) {
            preferences.putString(key, value)
            return
        }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val directory = Files.createDirectories(tokenFile.parent)
                val temp = Files.createTempFile(directory, ".cloudflare-api-token-", ".tmp")
                try {
                    // Apply permissions to the empty file before putting any credential into it.
                    restrictToOwner(temp)
                    Files.writeString(temp, value)
                    Files.move(temp, tokenFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
        }
    }

    override suspend fun remove(key: String) {
        if (key == TOKEN_KEY) withContext(Dispatchers.IO) {
            mutex.withLock { Files.deleteIfExists(tokenFile) }
        } else preferences.remove(key)
    }

    private fun restrictToOwner(file: Path) {
        val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"))
        } else {
            val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
                ?: throw IOException("File system does not support private credential files")
            acl.acl = listOf(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .build())
        }
    }

    private companion object {
        const val TOKEN_KEY = "tunnel.apiToken"
    }
}
