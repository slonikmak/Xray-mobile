package dev.xray.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProxySessionTest {
    @Test
    fun unexpectedExitCleansUpBeforeReconnect() = runTest {
        val first = FakeProcess()
        val second = FakeProcess()
        var starts = 0
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = if (starts++ == 0) first else second
            override suspend fun check() = true
        }, backgroundScope)
        assertTrue(session.connect("first"))
        runCurrent()
        first.exited.complete(Unit)
        runCurrent()
        assertTrue(first.cleaned)
        assertEquals(ProxyPhase.FAILED, session.state.value.phase)
        assertTrue(session.connect("second"))
        runCurrent()
        assertEquals(ProxyPhase.CONNECTED, session.state.value.phase)
        assertEquals("second", session.state.value.activeProfileId)
    }

    @Test
    fun initialCheckDegradesAfterSecondFailureAndRecovers() = runTest {
        val child = FakeProcess()
        var reachable = false
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = child
            override suspend fun check() = reachable
        }, backgroundScope)
        assertTrue(session.connect("profile"))
        runCurrent()
        assertEquals(ProxyHealth.RETRYING, session.state.value.health)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(ProxyHealth.FAILED, session.state.value.health)
        reachable = true
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(ProxyPhase.CONNECTED, session.state.value.phase)
        assertEquals(ProxyHealth.REACHABLE, session.state.value.health)
    }

    @Test
    fun connectedHealthDegradesAfterTwoFailuresAndRecovers() = runTest {
        val child = FakeProcess()
        var reachable = true
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = child
            override suspend fun check() = reachable
        }, backgroundScope)
        assertTrue(session.connect("profile"))
        runCurrent()
        assertEquals(ProxyPhase.CONNECTED, session.state.value.phase)
        reachable = false
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(ProxyPhase.CONNECTED, session.state.value.phase)
        assertEquals(ProxyHealth.RETRYING, session.state.value.health)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(ProxyPhase.RUNNING, session.state.value.phase)
        assertEquals(ProxyHealth.FAILED, session.state.value.health)
        reachable = true
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(ProxyPhase.CONNECTED, session.state.value.phase)
        assertEquals(ProxyHealth.REACHABLE, session.state.value.health)
    }

    @Test
    fun cancellationDuringPreflightDoesNotLaunchService() = runTest {
        val preflight = CompletableDeferred<Unit>()
        var launched = false
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation): ProxyProcess {
                preflight.await()
                return cancellation.launch { launched = true; FakeProcess() }
            }
            override suspend fun check() = true
        }, backgroundScope)
        assertTrue(session.connect("profile"))
        assertTrue(session.disconnect())
        preflight.complete(Unit)
        runCurrent()
        assertFalse(launched)
        assertEquals(ProxyPhase.DISCONNECTED, session.state.value.phase)
    }

    @Test
    fun cancelDuringStartupStopsTheChildBeforeAllowingReconnect() = runTest {
        val starting = CompletableDeferred<ProxyProcess>()
        val child = FakeProcess()
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = starting.await()
            override suspend fun check() = true
        }, backgroundScope)
        assertTrue(session.connect("profile"))
        assertTrue(session.disconnect())
        assertEquals(ProxyPhase.STOPPING, session.state.value.phase)
        assertFalse(session.connect("other"))
        starting.complete(child)
        runCurrent()
        assertTrue(child.stopRequested)
        child.exited.complete(Unit)
        runCurrent()
        assertTrue(child.cleaned)
        assertEquals(ProxyPhase.DISCONNECTED, session.state.value.phase)
    }

    @Test
    fun startupTimeoutReportsFailure() = runTest {
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation): ProxyProcess = withTimeout(10) { awaitCancellation() }
            override suspend fun check() = true
        }, backgroundScope)
        assertTrue(session.connect("profile"))
        advanceTimeBy(11)
        runCurrent()
        assertEquals(ProxyPhase.FAILED, session.state.value.phase)
        assertTrue(session.shutdown())
    }

    @Test
    fun stopTimeoutAllowsRetry() = runTest {
        var retry = false
        val child = object : ProxyProcess {
            override suspend fun awaitExit(): Unit = awaitCancellation()
            override suspend fun stop() { if (!retry) withTimeout(10) { awaitCancellation() } }
            override suspend fun cleanup() = Unit
        }
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = child
            override suspend fun check() = true
        }, backgroundScope)
        assertTrue(session.connect("profile"))
        runCurrent()
        assertTrue(session.disconnect())
        advanceTimeBy(11)
        runCurrent()
        assertTrue(session.state.value.cleanupRequired)
        retry = true
        assertTrue(session.disconnect())
        runCurrent()
        assertEquals(ProxyPhase.DISCONNECTED, session.state.value.phase)
    }

    @Test
    fun shutdownWaitsForStartupAndConfirmedCleanup() = runTest {
        val starting = CompletableDeferred<ProxyProcess>()
        val child = FakeProcess()
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = starting.await()
            override suspend fun check() = true
        }, backgroundScope)
        assertTrue(session.connect("profile"))
        val shutdown = async { session.shutdown() }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        starting.complete(child)
        runCurrent()
        assertTrue(child.stopRequested)
        assertFalse(shutdown.isCompleted)
        child.exited.complete(Unit)
        runCurrent()
        assertTrue(shutdown.await())
        assertTrue(child.cleaned)
    }

    @Test
    fun processAndHealthAreDistinctAndDisconnectWaitsForCleanup() = runTest {
        val child = FakeProcess()
        var reachable = false
        val backend = object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation): ProxyProcess = child
            override suspend fun check(): Boolean = reachable
        }
        val session = ProxySession(backend, backgroundScope)
        assertTrue(session.connect("profile"))
        runCurrent()
        assertEquals(ProxyPhase.RUNNING, session.state.value.phase)
        assertEquals(ProxyHealth.RETRYING, session.state.value.health)
        assertFalse(session.connect("other"))
        reachable = true
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(ProxyPhase.CONNECTED, session.state.value.phase)
        assertEquals(ProxyHealth.REACHABLE, session.state.value.health)
        assertTrue(session.disconnect())
        runCurrent()
        assertEquals(ProxyPhase.STOPPING, session.state.value.phase)
        assertTrue(child.stopRequested)
        assertFalse(child.cleaned)
        child.exited.complete(Unit)
        runCurrent()
        assertEquals(ProxyPhase.DISCONNECTED, session.state.value.phase)
        assertTrue(child.cleaned)
    }

    @Test
    fun failedStopRetainsOwnershipUntilAConfirmedRetry() = runTest {
        val exited = CompletableDeferred<Unit>()
        var shouldFail = true
        val child = object : ProxyProcess {
            override suspend fun awaitExit() { exited.await() }
            override suspend fun stop() { check(!shouldFail); exited.await() }
            override suspend fun cleanup() = Unit
        }
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = child
            override suspend fun check() = true
        }, backgroundScope)
        assertTrue(session.connect("profile"))
        runCurrent()
        assertTrue(session.disconnect())
        runCurrent()
        assertTrue(session.state.value.cleanupRequired)
        assertFalse(session.connect("other"))
        shouldFail = false
        assertTrue(session.disconnect())
        exited.complete(Unit)
        runCurrent()
        assertEquals(ProxyPhase.DISCONNECTED, session.state.value.phase)
        assertFalse(session.state.value.cleanupRequired)
    }

    @Test
    fun failedStartupKeepsAnyUnstoppedChildOwned() = runTest {
        val child = FakeProcess()
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation): ProxyProcess = throw ProxyStartFailure(child)
            override suspend fun check() = true
        }, backgroundScope)
        assertTrue(session.connect("profile"))
        runCurrent()
        assertEquals(ProxyPhase.FAILED, session.state.value.phase)
        assertTrue(session.state.value.cleanupRequired)
        assertFalse(session.connect("other"))
        assertTrue(session.disconnect())
        child.exited.complete(Unit)
        runCurrent()
        assertTrue(child.cleaned)
        assertEquals(ProxyPhase.DISCONNECTED, session.state.value.phase)
    }

    private class FakeProcess : ProxyProcess {
        val exited = CompletableDeferred<Unit>()
        var stopRequested = false
        var cleaned = false
        override suspend fun awaitExit() { exited.await() }
        override suspend fun stop() { stopRequested = true; exited.await() }
        override suspend fun cleanup() { cleaned = true }
    }
}
