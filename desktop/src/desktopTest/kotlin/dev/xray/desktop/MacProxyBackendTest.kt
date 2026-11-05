package dev.xray.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class MacProxyBackendTest {
    @Test
    fun anotherListenerClaimingPortDuringStartupIsNotAccepted(): Unit = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("aarch64", "arm64"))
        assumeTrue(runCatching { ServerSocket(10808).use { ServerSocket(10809).use { true } } }.getOrDefault(false))
        val directory = Files.createTempDirectory("xray-mac-port-race")
        val service = "dev.xray.multiplatform.test.${UUID.randomUUID()}"
        val binary = Path.of("build", "xrayResources", "common", "runtime", "xray").toAbsolutePath()
        val supervisor = binary.parent.resolve("xray-supervisor")
        val wrapper = directory.resolve("supervisor")
        val ready = directory.resolve("ready")
        val proceed = directory.resolve("proceed")
        fun quote(path: Path) = "'" + path.toString().replace("'", "'\\''") + "'"
        Files.writeString(wrapper, """#!/bin/sh
case " ${'$'}* " in *" -test "*) exec ${quote(supervisor)} "${'$'}@" ;; esac
case " ${'$'}* " in *" --hold-lock "*) exec ${quote(supervisor)} "${'$'}@" ;; esac
touch ${quote(ready)}
while [ ! -e ${quote(proceed)} ]; do sleep 0.05; done
exec ${quote(supervisor)} "${'$'}@"
""")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            MacProfileStorage(directory.resolve("profiles"), service).use { storage ->
                val profiles = ProfileApp(storage)
                val config = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":65534,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]},"streamSettings":{"network":"raw","security":"none"}}]}"""
                val profile = profiles.importProfile("Synthetic", config)
                val backend = MacProxyBackend(profiles, storage, binary, wrapper, scope) { true }
                val start = async(Dispatchers.IO) { runCatching { backend.start(profile.id, StartupCancellation()) } }
                withTimeout(10_000) { while (Files.notExists(ready)) delay(20) }
                ServerSocket(10809).use {
                    Files.createFile(proceed)
                    val result = withTimeout(15_000) { start.await() }
                    result.getOrNull()?.let { unexpected ->
                        unexpected.stop()
                        unexpected.cleanup()
                        error("Accepted a listener owned by another process")
                    }
                    assertTrue(result.isFailure)
                    assertTrue(!it.isClosed)
                }
            }
        } finally {
            if (Files.notExists(proceed)) Files.createFile(proceed)
            scope.cancel()
            ProcessBuilder("/usr/bin/security", "delete-generic-password", "-s", service, "-a", System.getProperty("user.name"))
                .redirectErrorStream(true).start().apply { inputStream.use { it.readBytes() }; waitFor() }
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun realXrayRoutesHttpAndSocksOverSyntheticLocalVless(): Unit = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("aarch64", "arm64"))
        val free = runCatching { ServerSocket(10808).use { ServerSocket(10809).use { true } } }.getOrDefault(false)
        assumeTrue("Local proxy test ports are occupied", free)
        val directory = Files.createTempDirectory("xray-mac-proxy-test")
        val service = "dev.xray.multiplatform.test.${UUID.randomUUID()}"
        val binary = Path.of("build", "xrayResources", "common", "runtime", "xray").toAbsolutePath()
        val supervisor = directory.resolve("packaged-supervisor")
        Files.copy(binary.parent.resolve("xray-supervisor"), supervisor)
        Files.setPosixFilePermissions(supervisor, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val serverPort = ServerSocket(0).use { it.localPort }
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val data = "synthetic-ok".toByteArray()
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            }
            start()
        }
        val serverConfig = directory.resolve("server.json")
        val id = "11111111-1111-4111-8111-111111111111"
        Files.writeString(serverConfig, """{"inbounds":[{"listen":"127.0.0.1","port":$serverPort,"protocol":"vless","settings":{"clients":[{"id":"$id"}],"decryption":"none"},"streamSettings":{"network":"raw","security":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","network":"tcp","ip":["127.0.0.1/32"],"port":"${http.address.port}"}]}}],"log":{"loglevel":"none"}}""")
        val server = ProcessBuilder(binary.toString(), "run", "-config", serverConfig.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        var child: ProxyProcess? = null
        try {
            waitForPort(serverPort)
            MacProfileStorage(directory.resolve("profiles"), service).use { storage ->
                val profiles = ProfileApp(storage)
                val config = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":$serverPort,"users":[{"id":"$id","encryption":"none"}]}]},"streamSettings":{"network":"raw","security":"none"}}]}"""
                val profile = profiles.importProfile("Synthetic", config)
                val backend = MacProxyBackend(profiles, storage, binary, supervisor, scope) { true }
                val stale = storage.createRuntimeDirectory()
                storage.writeRuntimeConfig(stale, "synthetic-secret".toByteArray())
                backend.recover()
                assertTrue(Files.notExists(stale))
                ServerSocket(10808).use { assertFailsWith<java.net.BindException> { backend.start(profile.id, StartupCancellation()) } }
                child = withTimeout(15_000) { backend.start(profile.id, StartupCancellation()) }
                waitForPort(10808)
                waitForPort(10809)
                assertFailsWith<IllegalStateException> { backend.start(profile.id, StartupCancellation()) }
                Socket("127.0.0.1", 10809).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write("GET http://127.0.0.1:${http.address.port}/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                    assertTrue(socket.getInputStream().bufferedReader().use { it.readTextUntilBody("synthetic-ok") })
                }
                Socket("127.0.0.1", 10808).use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    output.write(byteArrayOf(5, 1, 0))
                    assertContentEquals(byteArrayOf(5, 0), input.readNBytes(2))
                    val port = http.address.port
                    output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
                    val reply = input.readNBytes(10)
                    assertTrue(reply.size == 10 && reply[0] == 5.toByte() && reply[1] == 0.toByte())
                    output.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                    assertTrue(input.bufferedReader().use { it.readTextUntilBody("synthetic-ok") })
                }
                withTimeout(10_000) { child!!.stop(); child!!.cleanup() }
                child = null
                child = withTimeout(15_000) { backend.start(profile.id, StartupCancellation()) }
                waitForPort(10808)
                withTimeout(10_000) { child!!.stop(); child!!.cleanup() }
                child = null
            }
        } finally {
            child?.let { runCatching { withTimeout(10_000) { it.stop(); it.cleanup() } } }
            server.destroyForcibly()
            server.waitFor(5, TimeUnit.SECONDS)
            http.stop(0)
            scope.cancel()
            ProcessBuilder("/usr/bin/security", "delete-generic-password", "-s", service, "-a", System.getProperty("user.name"))
                .redirectErrorStream(true).start().apply { inputStream.use { it.readBytes() }; waitFor() }
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun waitForPort(port: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(30)
        }
        error("Local test port did not open")
    }

    private fun java.io.BufferedReader.readTextUntilBody(body: String): Boolean {
        val response = StringBuilder()
        while (!response.contains(body)) {
            val next = read()
            if (next == -1) return false
            response.append(next.toChar())
        }
        return true
    }
}
