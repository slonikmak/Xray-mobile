package dev.xray.desktop

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HttpSubscriptionSourceTest {
    @Test
    fun httpsRejectsUntrustedCertificatesRedirectsAndOversizedBodies(): Unit = runBlocking {
        val directory = Files.createTempDirectory("xray-test-tls")
        val storePath = directory.resolve("test.p12")
        val password = "synthetic-password".toCharArray()
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1", "-keystore", storePath.toString(),
            "-storepass", String(password), "-keypass", String(password), "-storetype", "PKCS12", "-noprompt")
            .redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        check(process.waitFor() == 0)
        val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(storePath).use { load(it, password) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, trusts.trustManagers, null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverContext)
            createContext("/ok") { exchange ->
                val data = "synthetic".toByteArray()
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            }
            createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "/ok")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/large") { exchange ->
                val data = ByteArray(2 * 1024 * 1024 + 1)
                exchange.sendResponseHeaders(200, data.size.toLong())
                runCatching { exchange.responseBody.use { it.write(data) } }
            }
            start()
        }
        try {
            val url = "https://127.0.0.1:${server.address.port}"
            val trusted = HttpSubscriptionSource(clientContext)
            assertContentEquals("synthetic".toByteArray(), withTimeout(5_000) { trusted.fetch("$url/ok") })
            for (path in listOf("/redirect?secret", "/large?secret")) {
                val error = assertFailsWith<IllegalArgumentException> { withTimeout(5_000) { trusted.fetch(url + path) } }
                assertFalse(error.toString().contains("secret"))
            }
            assertFailsWith<IllegalArgumentException> { withTimeout(5_000) { HttpSubscriptionSource().fetch("$url/ok") } }
        } finally {
            server.stop(0)
            Files.deleteIfExists(storePath)
            Files.deleteIfExists(directory)
        }
    }
}
