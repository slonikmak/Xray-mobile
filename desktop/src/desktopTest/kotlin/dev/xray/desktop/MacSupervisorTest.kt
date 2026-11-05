package dev.xray.desktop

import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class MacSupervisorTest {
    private val supervisor = Path.of("build", "xrayResources", "common", "runtime", "xray-supervisor").toAbsolutePath()

    @Test
    fun stoppingSupervisorWaitsForItsChild() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val directory = Files.createTempDirectory("xray-supervisor-test")
        val lock = directory.resolve("session.lock")
        val process = ProcessBuilder(supervisor.toString(), lock.toString(), "/bin/sleep", "60").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val pid = process.inputReader().readLine().removePrefix("READY ").toLong()
        try {
            val second = ProcessBuilder(supervisor.toString(), lock.toString(), "/bin/sleep", "60")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            assertEquals("NO_CHILD", second.inputReader().readLine())
            assertTrue(second.waitFor(5, TimeUnit.SECONDS))
            assertTrue(second.exitValue() != 0)
            assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            process.outputStream.use { it.write("stop\n".toByteArray()); it.flush() }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            process.destroyForcibly()
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            Files.deleteIfExists(lock)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun parentExitClosesThePipeAndStopsTheChild() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val directory = Files.createTempDirectory("xray-supervisor-test")
        val lock = directory.resolve("session.lock")
        val script = """import os,subprocess,sys
p=subprocess.Popen([sys.argv[1],sys.argv[2],'/bin/sleep','60'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
print(p.stdout.readline().decode().strip(),flush=True)
os._exit(0)
"""
        val controller = ProcessBuilder("/usr/bin/python3", "-c", script, supervisor.toString(), lock.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val pid = controller.inputReader().readLine().removePrefix("READY ").toLong()
        try {
            assertTrue(controller.waitFor(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            controller.destroyForcibly()
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            Files.deleteIfExists(lock)
            Files.deleteIfExists(directory)
        }
    }
}
