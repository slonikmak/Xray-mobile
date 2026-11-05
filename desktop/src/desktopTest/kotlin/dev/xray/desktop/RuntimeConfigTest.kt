package dev.xray.desktop

import com.google.gson.JsonParser
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeConfigTest {
    @Test
    fun runtimeUsesOnlyTheSelectedVlessAndLoopbackListeners() {
        val app = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val json = """{"inbounds":[{"listen":"0.0.0.0"}],"routing":{"rules":[{"outboundTag":"direct"}]},"outbounds":[{"protocol":"freedom","tag":"direct"},{"protocol":"vless","settings":{"id":"synthetic"},"streamSettings":{"network":"xhttp","security":"tls"}}]}"""
        val profile = app.importProfile("Example", json)
        val text = app.runtimeConfig(profile.id).toString(Charsets.UTF_8)
        val config = JsonParser.parseString(text).asJsonObject
        val inbounds = config.getAsJsonArray("inbounds")
        assertEquals(2, inbounds.size())
        assertEquals(listOf("127.0.0.1", "127.0.0.1"), inbounds.map { it.asJsonObject.get("listen").asString })
        assertEquals(listOf(10808, 10809), inbounds.map { it.asJsonObject.get("port").asInt })
        assertEquals(1, config.getAsJsonArray("outbounds").size())
        assertEquals("selected-vless", config.getAsJsonArray("outbounds")[0].asJsonObject.get("tag").asString)
        assertTrue(text.contains("synthetic"))
        assertFalse(text.contains("0.0.0.0"))
        assertFalse(text.contains("freedom"))
        assertFalse(text.contains("direct"))
    }

    @Test
    fun executableVerificationRejectsChangedBytesAndSymlinks() {
        val dir = Files.createTempDirectory("xray-runtime-test")
        val binary = dir.resolve("xray")
        val link = dir.resolve("link")
        try {
            Files.writeString(binary, "synthetic executable")
            val expected = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)).joinToString("") { "%02x".format(it) }
            verifyExecutable(binary, expected)
            Files.writeString(binary, "modified executable")
            assertFailsWith<IllegalStateException> { verifyExecutable(binary, expected) }
            if (!System.getProperty("os.name").startsWith("Windows")) {
                Files.createSymbolicLink(link, binary.fileName)
                assertFailsWith<IllegalStateException> { verifyExecutable(link, expected) }
            }
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(binary)
            Files.deleteIfExists(dir)
        }
    }
}
