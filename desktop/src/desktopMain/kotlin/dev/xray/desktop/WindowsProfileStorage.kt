package dev.xray.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

private interface SecurityDescriptor : StdCallLibrary {
    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl: WString, revision: Int, result: PointerByReference, size: Pointer?): Boolean
}

class WindowsProfileStorage(private val directory: Path) : ProfileStorage, AutoCloseable {
    private val file = directory.resolve("profiles.dat")
    private val lockChannel: FileChannel
    private val lock: FileLock

    init {
        check(System.getProperty("os.name").startsWith("Windows")) { "Protected profile storage requires Windows" }
        Files.createDirectories(directory)
        check(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Invalid profile directory" }
        securePath(directory, inherit = true)
        lockChannel = FileChannel.open(directory.resolve(".instance.lock"), CREATE, READ, WRITE)
        try {
            securePath(directory.resolve(".instance.lock"), inherit = false)
            lock = lockChannel.tryLock() ?: error("Profile storage is already in use")
        } catch (error: Exception) {
            lockChannel.close()
            throw error
        }
    }

    override fun read(): String? {
        val attributes = try { Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS) } catch (_: NoSuchFileException) { return null }
        require(attributes.isRegularFile)
        securePath(file, inherit = false)
        val clear = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file), 1)
        return try { clear.toString(Charsets.UTF_8) } finally { clear.fill(0) }
    }

    override fun write(value: String) {
        val clear = value.toByteArray(Charsets.UTF_8)
        val encrypted = try { Crypt32Util.cryptProtectData(clear, 1) } finally { clear.fill(0) }
        val temp = Files.createTempFile(directory, ".profiles-", ".tmp")
        var moved = false
        try {
            securePath(temp, inherit = false)
            FileChannel.open(temp, WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(encrypted)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
            moved = true
        } finally {
            if (!moved) runCatching { Files.deleteIfExists(temp) }
        }
    }

    override fun close() {
        try { lock.release() } finally { lockChannel.close() }
    }
}

private fun securePath(path: Path, inherit: Boolean) {
    val token = WinNT.HANDLEByReference()
    check(Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token))
    val sid = try { Advapi32Util.getTokenAccount(token.value).sidString } finally { Kernel32.INSTANCE.CloseHandle(token.value) }
    val descriptor = PointerByReference()
    val api = Native.load("Advapi32", SecurityDescriptor::class.java)
    val flags = if (inherit) "OICI" else ""
    val sddl = "D:P(A;$flags;FA;;;$sid)(A;$flags;FA;;;SY)"
    check(api.ConvertStringSecurityDescriptorToSecurityDescriptorW(WString(sddl), 1, descriptor, null))
    try {
        check(Advapi32.INSTANCE.SetFileSecurity(path.toString(), Int.MIN_VALUE or 4, descriptor.value))
    } finally {
        Kernel32.INSTANCE.LocalFree(descriptor.value)
    }
}
