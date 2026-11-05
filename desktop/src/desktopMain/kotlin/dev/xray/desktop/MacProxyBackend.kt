package dev.xray.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.BindException
import java.net.ConnectException
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private interface MacLibC : Library {
    fun kill(pid: Int, signal: Int): Int
    fun getpgid(pid: Int): Int
}

internal class MacChild(
    private val process: Process,
    private val childId: Long,
    private val executable: Path,
    private val removeConfig: () -> Unit,
    scope: CoroutineScope,
) : ProxyProcess {
    private val exited = CompletableDeferred<Int>()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                val code = process.waitFor()
                val child = ProcessHandle.of(childId).orElse(null)
                if (child != null && child.isAlive) {
                    val command = child.info().command().orElseThrow()
                    check(Path.of(command).toRealPath() == executable.toRealPath())
                    val libc = Native.load("System", MacLibC::class.java)
                    check(libc.getpgid(childId.toInt()) == childId.toInt())
                    check(libc.kill(-childId.toInt(), 9) == 0 || !child.isAlive)
                    withTimeout(5_000) { while (child.isAlive) delay(20) }
                }
                exited.complete(code)
            } catch (e: Exception) { exited.completeExceptionally(e) }
        }
    }

    suspend fun exitCode(): Int = exited.await()
    fun isRunning(): Boolean = !exited.isCompleted && process.isAlive
    // shortcut: listener checks are snapshots; use socket handoff if Xray supports exclusive ownership.
    fun ownsListeners(): Boolean {
        val netstat = ProcessBuilder("/usr/sbin/netstat", "-an", "-p", "tcp")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val listeners = CompletableFuture.supplyAsync {
            val result = mutableListOf<String>()
            netstat.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size >= 6 && fields[5] == "LISTEN" &&
                        (fields[3].endsWith(".10808") || fields[3].endsWith(".10809")) && result.size < 3) result.add(fields[3])
                }
            }
            result
        }
        val visible = try { listeners.get(2, TimeUnit.SECONDS) } catch (_: Exception) {
            netstat.destroyForcibly()
            return false
        }
        if (!netstat.waitFor(2, TimeUnit.SECONDS) || netstat.exitValue() != 0 ||
            visible.sorted() != listOf("127.0.0.1.10808", "127.0.0.1.10809")) {
            netstat.destroyForcibly()
            return false
        }
        val command = ProcessBuilder("/usr/sbin/lsof", "-nP", "-iTCP:10808,10809", "-sTCP:LISTEN", "-Fpn")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!command.waitFor(2, TimeUnit.SECONDS)) {
            command.destroyForcibly()
            command.waitFor()
            return false
        }
        val bytes = command.inputStream.use { it.readNBytes(64 * 1024 + 1) }
        if (bytes.size > 64 * 1024 || command.exitValue() != 0 || !isRunning()) return false
        val lines = bytes.toString(Charsets.UTF_8).lineSequence()
        var owner = ""
        val owned = mutableSetOf<String>()
        for (line in lines) when {
            line.startsWith("p") -> owner = line.drop(1)
            line.startsWith("n") -> {
                if (owner != childId.toString()) return false
                owned.add(line)
            }
        }
        return listOf("n127.0.0.1:10808", "n127.0.0.1:10809").all(owned::contains)
    }
    fun closeStreams() { process.inputStream.close(); process.outputStream.close() }
    override suspend fun awaitExit() { exitCode() }
    override suspend fun stop() {
        try { process.outputStream.write("stop\n".toByteArray()); process.outputStream.flush() } catch (_: Exception) { }
        withTimeout(5_000) { exitCode() }
    }
    override suspend fun cleanup() {
        exitCode()
        removeConfig()
        process.inputStream.close()
        process.outputStream.close()
    }
}

private class UnidentifiedSupervisor(private val process: Process) : ProxyProcess {
    override suspend fun awaitExit() { withContext(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) }; error("Cannot confirm child exit") }
    override suspend fun stop() { runCatching { process.outputStream.close() }; awaitExit() }
    override suspend fun cleanup() { error("Cannot confirm child exit") }
}

private class CleanupOnly(private val remove: () -> Unit) : ProxyProcess {
    override suspend fun awaitExit() = Unit
    override suspend fun stop() = Unit
    override suspend fun cleanup() { remove() }
}

class MacProxyBackend(
    private val profiles: ProfileApp,
    private val storage: MacProfileStorage,
    private val executable: Path,
    private val supervisor: Path,
    private val scope: CoroutineScope,
    private val probe: (suspend () -> Boolean)? = null,
) : ProxyBackend {
    private val client = HttpClient.newBuilder().proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", 10809)))
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()

    override suspend fun start(profileId: String, cancellation: StartupCancellation): ProxyProcess = withContext(Dispatchers.IO) {
        recover()
        cancellation.check()
        checkPorts()
        val temporary = storage.createRuntimeDirectory()
        val remove = { storage.removeRuntimeDirectory(temporary) }
        try {
            verifyExecutable(executable, XRAY_SHA256)
            val privateExecutable = storage.copyRuntimeExecutable(temporary, executable)
            val clear = profiles.runtimeConfig(profileId)
            val config = try { storage.writeRuntimeConfig(temporary, clear) } finally { clear.fill(0) }
            val preflight = startVerified(privateExecutable, config, preflight = true, remove)
            val code = try { withTimeout(15_000) { preflight.exitCode() } } catch (e: Exception) {
                try { preflight.stop() } catch (_: Exception) { throw ProxyStartFailure(preflight) }
                throw e
            }
            preflight.closeStreams()
            check(code == 0) { "Xray preflight failed" }
            val service = cancellation.launch { startVerified(privateExecutable, config, preflight = false, remove) }
            try {
                withTimeout(5_000) {
                    while (true) {
                        cancellation.check()
                        check(service.isRunning()) { "Xray exited during startup" }
                        if (service.ownsListeners()) break
                        delay(100)
                    }
                }
                service
            } catch (e: Exception) {
                try { service.stop() } catch (_: Exception) { throw ProxyStartFailure(service) }
                throw e
            }
        } catch (e: ProxyStartFailure) {
            throw e
        } catch (e: Exception) {
            try { remove() } catch (_: Exception) { throw ProxyStartFailure(CleanupOnly(remove)) }
            throw e
        }
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        val prepared = storage.prepareSupervisor(supervisor)
        val lock = ProcessBuilder(prepared.toString(), storage.runtimeLock().toString(), "--hold-lock")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val line = CompletableFuture.supplyAsync { lock.inputReader().readLine() }.get(5, TimeUnit.SECONDS)
            check(line == "LOCKED") { "Xray session is already in use" }
            storage.removeStaleRuntimeDirectories()
        } finally {
            lock.outputStream.close()
            if (!lock.waitFor(5, TimeUnit.SECONDS)) { lock.destroyForcibly(); lock.waitFor() }
            lock.inputStream.close()
        }
    }

    override suspend fun check(): Boolean = try {
        if (probe != null) probe.invoke() else withTimeout(5_000) {
            val request = HttpRequest.newBuilder(URI("https://www.gstatic.com/generate_204"))
                .timeout(Duration.ofSeconds(5)).GET().build()
            suspendCancellableCoroutine { continuation ->
                val future = client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                future.whenComplete { response, error ->
                    if (error != null) continuation.resumeWithException(IllegalStateException("Proxy check failed"))
                    else continuation.resume(response.statusCode() == 204)
                }
                continuation.invokeOnCancellation { future.cancel(true) }
            }
        }
    } catch (_: TimeoutCancellationException) { false }
    catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    private fun startVerified(executable: Path, config: Path, preflight: Boolean, remove: () -> Unit): MacChild {
        verifyExecutable(executable, XRAY_SHA256)
        val prepared = storage.prepareSupervisor(supervisor)
        val arguments = mutableListOf(prepared.toString(), storage.runtimeLock().toString(), executable.toString(), "run")
        if (preflight) arguments.add("-test")
        arguments.addAll(listOf("-config", config.toString()))
        val process = ProcessBuilder(arguments).directory(executable.parent.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val line = try { CompletableFuture.supplyAsync { process.inputReader().readLine() }.get(5, TimeUnit.SECONDS) }
        catch (_: Exception) {
            runCatching { process.outputStream.close() }
            throw ProxyStartFailure(UnidentifiedSupervisor(process))
        }
        if (line == "NO_CHILD") {
            process.waitFor()
            process.inputStream.close()
            process.outputStream.close()
            error("Could not start Xray supervisor")
        }
        val childId = line?.takeIf { it.startsWith("READY ") }?.removePrefix("READY ")?.toLongOrNull()
        if (childId == null || childId !in 1..Int.MAX_VALUE.toLong()) {
            runCatching { process.outputStream.close() }
            throw ProxyStartFailure(UnidentifiedSupervisor(process))
        }
        return MacChild(process, childId, executable, remove, scope)
    }
}

private fun checkPorts() {
    val sockets = mutableListOf<ServerSocket>()
    try {
        for (port in listOf(10808, 10809)) {
            val listening = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
                true
            } catch (_: ConnectException) { false }
            if (listening) throw BindException("Local proxy port is in use")
            val socket = ServerSocket()
            sockets.add(socket)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
        }
    } finally { sockets.forEach { it.close() } }
}
