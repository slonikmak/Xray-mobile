package dev.xray.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class VpnSessionTest {
    @Test
    fun permissionDenialDoesNotStartVpn() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(false)
        assertEquals(VpnPhase.ERROR, session.state.value.phase)
        assertEquals(false, adapter.started)
    }

    @Test
    fun grantedPermissionStartsVpnAndChecksConnectivity() = runTest {
        val session = VpnSession(FakeVpnAdapter(), backgroundScope)
        val profile = Profile("Example", "synthetic")
        session.connect(profile)
        session.permissionResult(true)
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(CheckPhase.SUCCEEDED, session.state.value.check)
        assertEquals(profile, session.state.value.profile)
    }

    @Test
    fun failedCheckDoesNotClaimTheTunnelStopped() = runTest {
        val adapter = FakeVpnAdapter().apply { checkResult = false }
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(true)
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(CheckPhase.FAILED, session.state.value.check)
    }

    @Test
    fun timedOutCheckIsShownAsFailedWhileTunnelRemainsActive() = runTest {
        val adapter = FakeVpnAdapter().apply { timeoutCheck = true }
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(true)
        advanceTimeBy(5)
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(CheckPhase.FAILED, session.state.value.check)
    }

    @Test
    fun cancellationRejectsLateCheckAndRepeatedConnect() = runTest {
        val pending = CompletableDeferred<Boolean>()
        val adapter = FakeVpnAdapter().apply { checkPending = pending }
        val session = VpnSession(adapter, backgroundScope)
        val profile = Profile("Example", "synthetic")
        session.connect(profile)
        session.permissionResult(true)
        runCurrent()
        assertFalse(session.connect(profile))
        session.stop()
        runCurrent()
        pending.complete(true)
        runCurrent()
        assertEquals(VpnPhase.DISCONNECTED, session.state.value.phase)
        assertEquals(false, adapter.started)
    }

    @Test
    fun failedCleanupBlocksNewStartUntilStopSucceeds() = runTest {
        val adapter = FakeVpnAdapter().apply { failStop = true }
        val session = VpnSession(adapter, backgroundScope)
        val profile = Profile("Example", "synthetic")
        session.connect(profile)
        session.permissionResult(true)
        runCurrent()
        session.stop()
        runCurrent()
        assertEquals(true, session.state.value.cleanupRequired)
        assertFalse(session.connect(profile))
        adapter.failStop = false
        session.stop()
        runCurrent()
        assertEquals(VpnPhase.DISCONNECTED, session.state.value.phase)
    }

    @Test
    fun failedStartIsCleanedUpAndReported() = runTest {
        val adapter = FakeVpnAdapter().apply { failStart = true }
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(true)
        runCurrent()
        assertEquals(VpnPhase.ERROR, session.state.value.phase)
        assertEquals(false, adapter.started)
        assertEquals(false, session.state.value.cleanupRequired)
    }

    @Test
    fun cancellingSlowStartWaitsForCleanupBeforeAnotherConnection() = runTest {
        val pending = CompletableDeferred<Unit>()
        val adapter = FakeVpnAdapter().apply { startPending = pending }
        val session = VpnSession(adapter, backgroundScope)
        val profile = Profile("Example", "synthetic")
        session.connect(profile)
        session.permissionResult(true)
        runCurrent()
        session.stop()
        runCurrent()
        assertEquals(VpnPhase.DISCONNECTING, session.state.value.phase)
        assertFalse(session.connect(profile))
        pending.complete(Unit)
        runCurrent()
        assertEquals(VpnPhase.DISCONNECTED, session.state.value.phase)
        assertEquals(false, adapter.started)
    }

    @Test
    fun systemRevocationStopsTheTunnelAndReportsError() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(true)
        runCurrent()
        session.terminated("Разрешение отозвано")
        runCurrent()
        assertEquals(VpnPhase.ERROR, session.state.value.phase)
        assertEquals("Разрешение отозвано", session.state.value.error)
        assertEquals(false, adapter.started)
    }

    @Test
    fun restoredTerminalErrorRemainsVisibleUntilUserStartsAgain() = runTest {
        val session = VpnSession(FakeVpnAdapter(), backgroundScope, "VPN остановился")
        assertEquals(VpnPhase.ERROR, session.state.value.phase)
        assertEquals("VPN остановился", session.state.value.error)
        session.connect(Profile("Example", "synthetic"))
        assertEquals(VpnPhase.PERMISSION, session.state.value.phase)
        assertEquals(null, session.state.value.error)
    }

    @Test
    fun restoredCleanupFailureBlocksConnectionUntilRetried() = runTest {
        val session = VpnSession(FakeVpnAdapter(), backgroundScope, "Ошибка остановки", initialCleanupRequired = true)
        assertFalse(session.connect(Profile("Example", "synthetic")))
        session.stop()
        runCurrent()
        assertEquals(VpnPhase.DISCONNECTED, session.state.value.phase)
    }
}

private class FakeVpnAdapter : VpnAdapter {
    var started = false
    var checkResult = true
    var checkPending: CompletableDeferred<Boolean>? = null
    var failStop = false
    var failStart = false
    var timeoutCheck = false
    var startPending: CompletableDeferred<Unit>? = null
    override suspend fun start(profile: Profile) {
        started = true
        startPending?.let { withContext(NonCancellable) { it.await() } }
        if (failStart) error("start failed")
    }
    override suspend fun stop() { if (failStop) error("cleanup failed"); started = false }
    override suspend fun check(): Boolean = if (timeoutCheck) withTimeout(1) { delay(10); true } else checkPending?.await() ?: checkResult
}
