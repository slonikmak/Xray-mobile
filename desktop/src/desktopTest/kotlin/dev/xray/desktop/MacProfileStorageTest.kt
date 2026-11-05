package dev.xray.desktop

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class MacProfileStorageTest {
    @Test
    fun encryptedProfilesSurviveRestartWithoutPlaintextOnDisk() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val directory = Files.createTempDirectory("xray-profile-mac-test")
        val service = "dev.xray.multiplatform.test.${UUID.randomUUID()}"
        try {
            MacProfileStorage(directory, service).use { storage ->
                val app = ProfileApp(storage)
                val profile = app.importProfile("Example", """{"outbounds":[{"protocol":"vless","settings":{"id":"synthetic-private-token"},"streamSettings":{}}]}""")
                app.select(profile.id)
            }
            val disk = Files.readAllBytes(directory.resolve("profiles.dat"))
            assertFalse(disk.toString(Charsets.ISO_8859_1).contains("synthetic-private-token"))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("profiles.dat")))
            MacProfileStorage(directory, service).use { storage ->
                val app = ProfileApp(storage)
                assertEquals(listOf("Example"), app.state.profiles.map { it.name })
                assertTrue(app.state.selectedId != null)
            }
            disk[disk.lastIndex] = (disk.last().toInt() xor 1).toByte()
            Files.write(directory.resolve("profiles.dat"), disk)
            MacProfileStorage(directory, service).use { storage ->
                val app = ProfileApp(storage)
                assertEquals("Could not restore profiles", app.state.error)
            }
        } finally {
            ProcessBuilder("/usr/bin/security", "delete-generic-password", "-s", service, "-a", System.getProperty("user.name"))
                .redirectErrorStream(true).start().apply { inputStream.use { it.readBytes() }; check(waitFor() == 0) }
            Files.deleteIfExists(directory.resolve("profiles.dat"))
            Files.deleteIfExists(directory.resolve(".instance.lock"))
            Files.deleteIfExists(directory)
        }
    }
}
