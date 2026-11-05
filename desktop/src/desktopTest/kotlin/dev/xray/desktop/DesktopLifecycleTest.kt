package dev.xray.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLifecycleTest {
    @Test
    fun exitWaitsForRecoveryBeforeClosing() = runTest {
        var appExited = false
        val recovered = CompletableDeferred<Boolean>()
        val lifecycle = DesktopLifecycle({ null }, backgroundScope, trayAvailable = true, readyToExit = { recovered.await() }) { appExited = true }
        lifecycle.exit()
        runCurrent()
        assertTrue(lifecycle.state.value.exiting)
        assertFalse(appExited)
        recovered.complete(true)
        runCurrent()
        assertTrue(appExited)
    }

    @Test
    fun exitDuringRecoveryKeepsWindowOpen() = runTest {
        var appExited = false
        var quitCancelled = false
        val lifecycle = DesktopLifecycle({ null }, backgroundScope, trayAvailable = true,
            readyToExit = { false }, onExitFailed = { quitCancelled = true }) { appExited = true }
        lifecycle.closeWindow()
        lifecycle.exit()
        runCurrent()
        assertTrue(lifecycle.state.value.visible)
        assertFalse(lifecycle.state.value.exiting)
        assertFalse(appExited)
        assertTrue(quitCancelled)
    }

    @Test
    fun failedExitReopensWindowAndAllowsRetry() = runTest {
        var refusesStop = true
        var appExited = false
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = object : ProxyProcess {
                override suspend fun awaitExit(): Unit = kotlinx.coroutines.awaitCancellation()
                override suspend fun stop() { check(!refusesStop) }
                override suspend fun cleanup() = Unit
            }
            override suspend fun check() = true
        }, backgroundScope)
        val lifecycle = DesktopLifecycle({ session }, backgroundScope, trayAvailable = true) { appExited = true }
        assertTrue(session.connect("profile"))
        runCurrent()
        lifecycle.closeWindow()
        lifecycle.exit()
        runCurrent()
        assertTrue(lifecycle.state.value.visible)
        assertFalse(lifecycle.state.value.exiting)
        assertTrue(session.state.value.cleanupRequired)
        assertFalse(appExited)
        refusesStop = false
        lifecycle.exit()
        runCurrent()
        assertTrue(appExited)
    }

    @Test
    fun withoutTrayClosingExitsNormally() = runTest {
        var appExited = false
        val lifecycle = DesktopLifecycle({ null }, backgroundScope, trayAvailable = false) { appExited = true }
        lifecycle.closeWindow()
        runCurrent()
        assertTrue(appExited)
    }

    @Test
    fun closingAndReopeningKeepsSessionRunningUntilExitCompletes() = runTest {
        val exited = CompletableDeferred<Unit>()
        var cleanup = false
        var appExited = false
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = object : ProxyProcess {
                override suspend fun awaitExit() { exited.await() }
                override suspend fun stop() { exited.await() }
                override suspend fun cleanup() { cleanup = true }
            }
            override suspend fun check() = true
        }, backgroundScope)
        val lifecycle = DesktopLifecycle({ session }, backgroundScope, trayAvailable = true) { appExited = true }
        assertTrue(session.connect("profile"))
        runCurrent()
        lifecycle.closeWindow()
        assertFalse(lifecycle.state.value.visible)
        assertEquals(ProxyPhase.CONNECTED, session.state.value.phase)
        lifecycle.openWindow()
        assertTrue(lifecycle.state.value.visible)
        lifecycle.exit()
        runCurrent()
        assertTrue(lifecycle.state.value.exiting)
        assertFalse(appExited)
        exited.complete(Unit)
        runCurrent()
        assertTrue(cleanup)
        assertTrue(appExited)
    }
}
