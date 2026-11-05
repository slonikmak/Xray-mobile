package dev.xray.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private interface SecurityFramework : Library {
    fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
    fun SecItemAdd(attributes: Pointer, result: Pointer?): Int
    fun SecItemUpdate(query: Pointer, attributes: Pointer): Int
}

private interface CoreFoundation : Library {
    fun CFStringCreateWithCString(allocator: Pointer?, value: String, encoding: Int): Pointer
    fun CFDataCreate(allocator: Pointer?, bytes: Pointer, length: NativeLong): Pointer
    fun CFDataGetLength(data: Pointer): NativeLong
    fun CFDataGetBytePtr(data: Pointer): Pointer
    fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: NativeLong, keyCallbacks: Pointer?, valueCallbacks: Pointer?): Pointer
    fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer)
    fun CFRelease(value: Pointer)
}

private class MacKeychain(private val service: String) {
    private val securityLibrary = NativeLibrary.getInstance("/System/Library/Frameworks/Security.framework/Security")
    private val foundationLibrary = NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    private val security = Native.load("/System/Library/Frameworks/Security.framework/Security", SecurityFramework::class.java)
    private val cf = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CoreFoundation::class.java)
    private fun key(name: String): Pointer = securityLibrary.getGlobalVariableAddress(name).getPointer(0)

    private fun <T> withQuery(returnData: Boolean = false, block: (Pointer) -> T): T {
        val serviceValue = cf.CFStringCreateWithCString(null, service, 0x08000100)
        val accountValue = cf.CFStringCreateWithCString(null, System.getProperty("user.name"), 0x08000100)
        val query = cf.CFDictionaryCreateMutable(null, NativeLong(0), null, null)
        try {
            cf.CFDictionarySetValue(query, key("kSecClass"), key("kSecClassGenericPassword"))
            cf.CFDictionarySetValue(query, key("kSecAttrService"), serviceValue)
            cf.CFDictionarySetValue(query, key("kSecAttrAccount"), accountValue)
            if (returnData) cf.CFDictionarySetValue(query, key("kSecReturnData"), foundationLibrary.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0))
            return block(query)
        } finally {
            cf.CFRelease(query)
            cf.CFRelease(accountValue)
            cf.CFRelease(serviceValue)
        }
    }

    fun read(): ByteArray? = withQuery(returnData = true) { query ->
        val result = PointerByReference()
        when (security.SecItemCopyMatching(query, result)) {
            -25300 -> null
            0 -> {
                val data = result.value ?: error("Keychain returned no data")
                try {
                    val length = cf.CFDataGetLength(data).toLong()
                    check(length == 32L) { "Invalid profile protection key" }
                    cf.CFDataGetBytePtr(data).getByteArray(0, length.toInt())
                } finally { cf.CFRelease(data) }
            }
            else -> error("Could not access Keychain")
        }
    }

    fun add(bytes: ByteArray) = withQuery { query ->
        val memory = Memory(bytes.size.toLong())
        memory.write(0, bytes, 0, bytes.size)
        val data = cf.CFDataCreate(null, memory, NativeLong(bytes.size.toLong()))
        try {
            cf.CFDictionarySetValue(query, key("kSecValueData"), data)
            check(security.SecItemAdd(query, null) == 0) { "Could not save Keychain key" }
        } finally {
            cf.CFRelease(data)
            memory.clear()
        }
    }
}

class MacProfileStorage(private val directory: Path, service: String = "dev.xray.multiplatform.desktop.profiles") : ProfileStorage, AutoCloseable {
    private val file = directory.resolve("profiles.dat")
    private val channel: FileChannel
    private val lock: FileLock
    private val key: ByteArray
    private val random = SecureRandom()
    private var closed = false

    init {
        check(System.getProperty("os.name").startsWith("Mac")) { "Profile storage requires macOS" }
        val privateDirectory = PosixFilePermissions.fromString("rwx------")
        val privateFile = PosixFilePermissions.fromString("rw-------")
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(privateDirectory))
        check(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Invalid profile directory" }
        val current = directory.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        check(Files.getOwner(directory, NOFOLLOW_LINKS) == current) { "Profile directory has a different owner" }
        Files.setPosixFilePermissions(directory, privateDirectory)
        val lockPath = directory.resolve(".instance.lock")
        channel = FileChannel.open(lockPath, setOf<OpenOption>(CREATE, READ, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(privateFile))
        try {
            Files.setPosixFilePermissions(lockPath, privateFile)
            lock = channel.tryLock() ?: error("Profile storage is already in use")
            val keychain = MacKeychain(service)
            key = keychain.read() ?: run {
                try {
                    Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    error("Profile key is missing")
                } catch (_: NoSuchFileException) { }
                ByteArray(32).also { generated ->
                    random.nextBytes(generated)
                    try { keychain.add(generated) } catch (e: Exception) { generated.fill(0); throw e }
                }
            }
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    override fun read(): String? {
        val attributes = try { Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS) } catch (_: NoSuchFileException) { return null }
        check(attributes.isRegularFile)
        val data = Files.readAllBytes(file)
        check(data.size >= 5 + 12 + 16 && data.copyOfRange(0, 5).contentEquals(MAGIC))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 5, 12))
        cipher.updateAAD(MAGIC)
        val clear = cipher.doFinal(data, 17, data.size - 17)
        return try { clear.toString(Charsets.UTF_8) } finally { clear.fill(0) }
    }

    override fun write(value: String) {
        val clear = value.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(MAGIC)
        val encrypted = try { cipher.doFinal(clear) } finally { clear.fill(0) }
        val temp = Files.createTempFile(directory, ".profiles-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        var moved = false
        try {
            FileChannel.open(temp, WRITE).use { output ->
                val buffer = ByteBuffer.wrap(MAGIC + nonce + encrypted)
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
            moved = true
        } finally {
            if (!moved) runCatching { Files.deleteIfExists(temp) }
        }
    }

    internal fun runtimeLock(): Path = directory.resolve(".session.lock")

    internal fun prepareSupervisor(source: Path): Path {
        check(Files.isRegularFile(source, NOFOLLOW_LINKS)) { "Xray supervisor is missing" }
        val temporary = Files.createTempFile(directory, ".supervisor-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.copy(source, temporary, REPLACE_EXISTING, NOFOLLOW_LINKS)
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("r-x------"))
            val target = directory.resolve(".supervisor")
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            return target
        } finally { Files.deleteIfExists(temporary) }
    }

    internal fun createRuntimeDirectory(): Path = Files.createTempDirectory(
        directory, "runtime-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    )

    internal fun writeRuntimeConfig(temporary: Path, bytes: ByteArray): Path {
        val config = temporary.resolve("config.json")
        FileChannel.open(config, setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { output ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) output.write(buffer)
            output.force(true)
        }
        return config
    }

    internal fun copyRuntimeExecutable(temporary: Path, source: Path): Path {
        val target = temporary.resolve("xray")
        Files.copy(source, target, NOFOLLOW_LINKS)
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r-x------"))
        verifyExecutable(target, XRAY_SHA256)
        return target
    }

    internal fun removeRuntimeDirectory(temporary: Path) {
        Files.deleteIfExists(temporary.resolve("config.json"))
        Files.deleteIfExists(temporary.resolve("xray"))
        Files.deleteIfExists(temporary)
    }

    internal fun removeStaleRuntimeDirectories() {
        Files.newDirectoryStream(directory, "runtime-*").use { entries ->
            for (entry in entries) {
                check(Files.isDirectory(entry, NOFOLLOW_LINKS)) { "Invalid runtime directory" }
                check(Files.getOwner(entry, NOFOLLOW_LINKS) == Files.getOwner(directory, NOFOLLOW_LINKS))
                removeRuntimeDirectory(entry)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        key.fill(0)
        try { lock.release() } finally { channel.close() }
    }

    companion object { private val MAGIC = "XRAY1".toByteArray(Charsets.US_ASCII) }
}
