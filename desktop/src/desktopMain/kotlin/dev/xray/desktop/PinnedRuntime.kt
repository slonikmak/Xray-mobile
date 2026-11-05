package dev.xray.desktop

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal const val XRAY_VERSION = "26.10.31"
internal const val XRAY_SHA256 = "9ddc3b0d912a8539285918e4fead4b60bbc449acdb639450ba068bc7efb983b7"

internal fun verifyExecutable(path: Path, expected: String) {
    try {
        check(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile)
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                hash.update(bytes, 0, count)
            }
        }
        check(hash.digest().joinToString("") { "%02x".format(it) } == expected)
    } catch (_: Exception) {
        throw IllegalStateException("Bundled Xray is missing or failed verification")
    }
}
