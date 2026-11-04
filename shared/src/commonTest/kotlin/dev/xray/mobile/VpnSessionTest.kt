package dev.xray.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class VpnSessionTest {
    @Test
    fun simultaneousConnectCommandsAcceptOnlyOneProfile() = runTest {
        repeat(100) {
            val session = VpnSession(FakeVpnAdapter(), backgroundScope)
            val start = CompletableDeferred<Unit>()
            val requests = (0 until 32).map { index ->
                async(Dispatchers.Default) {
                    start.await()
                    session.connect(Profile("Server $index", "synthetic-$index"))
                }
            }
            start.complete(Unit)
            assertEquals(1, requests.awaitAll().count { it })
        }
    }

    @Test
    fun latePermissionResultCannotAuthorizeNewAttempt() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        val profile = Profile("Example", "synthetic")
        session.connect(profile)
        val oldId = requireNotNull(session.state.value.sessionId)
        session.stop()
        session.connect(profile)
        val newId = requireNotNull(session.state.value.sessionId)
        assertFalse(session.permissionResult(true, oldId))
        assertEquals(VpnPhase.PERMISSION, session.state.value.phase)
        assertEquals(false, adapter.started)
        session.permissionResult(true, newId)
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
    }

    @Test
    fun permissionDenialDoesNotStartVpn() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(false, requireNotNull(session.state.value.sessionId))
        assertEquals(VpnPhase.ERROR, session.state.value.phase)
        assertEquals(false, adapter.started)
    }

    @Test
    fun grantedPermissionStartsVpnAndChecksConnectivity() = runTest {
        val session = VpnSession(FakeVpnAdapter(), backgroundScope)
        val profile = Profile("Example", "synthetic")
        session.connect(profile)
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
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
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(CheckPhase.FAILED, session.state.value.check)
    }

    @Test
    fun connectionCheckUpdatesWhenInternetFailsAndRecovers() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        assertEquals(CheckPhase.SUCCEEDED, session.state.value.check)

        adapter.checkResult = false
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(CheckPhase.FAILED, session.state.value.check)

        adapter.checkResult = true
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(CheckPhase.SUCCEEDED, session.state.value.check)
    }

    @Test
    fun timedOutCheckIsShownAsFailedWhileTunnelRemainsActive() = runTest {
        val adapter = FakeVpnAdapter().apply { timeoutCheck = true }
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
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
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
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
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
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
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
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
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
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
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        session.terminated("Разрешение отозвано")
        runCurrent()
        assertEquals(VpnPhase.ERROR, session.state.value.phase)
        assertEquals("Разрешение отозвано", session.state.value.error)
        assertEquals(false, adapter.started)
    }

    @Test
    fun revocationDuringStopRemainsVisibleAfterCleanup() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        val pending = CompletableDeferred<Unit>()
        adapter.stopPending = pending
        session.stop()
        runCurrent()
        session.terminated("Разрешение отозвано")
        pending.complete(Unit)
        runCurrent()
        assertEquals(VpnPhase.ERROR, session.state.value.phase)
        assertEquals("Разрешение отозвано", session.state.value.error)
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

    @Test
    fun switchingWaitsForOldTunnelToStopBeforeStartingNewProfile() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        val first = Profile("First", "first")
        val second = Profile("Second", "second")
        session.connect(first)
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        val pendingStop = CompletableDeferred<Unit>()
        adapter.stopPending = pendingStop

        session.switchTo(second)
        runCurrent()
        assertEquals(VpnPhase.SWITCHING, session.state.value.phase)
        assertEquals(first, session.state.value.profile)
        assertEquals(listOf(first), adapter.startedProfiles)
        assertFalse(session.switchTo(second))
        pendingStop.complete(Unit)
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(second, session.state.value.profile)
        assertEquals(listOf(first, second), adapter.startedProfiles)
    }

    @Test
    fun failedStopDuringSwitchBlocksNewProfileUntilCleanupIsRetried() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        val first = Profile("First", "first")
        val second = Profile("Second", "second")
        session.connect(first)
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        adapter.failStop = true
        session.switchTo(second)
        runCurrent()
        assertEquals(VpnPhase.ERROR, session.state.value.phase)
        assertEquals(true, session.state.value.cleanupRequired)
        assertEquals(listOf(first), adapter.startedProfiles)
        assertFalse(session.connect(second))
        adapter.failStop = false
        session.stop()
        runCurrent()
        assertEquals(VpnPhase.DISCONNECTED, session.state.value.phase)
        assertEquals(listOf(first), adapter.startedProfiles)
    }

    @Test
    fun failedNewProfileDoesNotRestartOldProfileAutomatically() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        val first = Profile("First", "first")
        val second = Profile("Second", "second")
        session.connect(first)
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        adapter.failStart = true
        session.switchTo(second)
        runCurrent()
        assertEquals(VpnPhase.ERROR, session.state.value.phase)
        assertEquals(second, session.state.value.profile)
        assertEquals(listOf(first, second), adapter.startedProfiles)
        assertFalse(adapter.started)
        adapter.failStart = false
        session.connect(second)
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(listOf(first, second, second), adapter.startedProfiles)
    }

    @Test
    fun cancellingSwitchStopsWithoutStartingNewProfile() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        val first = Profile("First", "first")
        val second = Profile("Second", "second")
        session.connect(first)
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        val pendingStop = CompletableDeferred<Unit>()
        adapter.stopPending = pendingStop
        session.switchTo(second)
        runCurrent()
        session.stop()
        runCurrent()
        assertEquals(VpnPhase.DISCONNECTING, session.state.value.phase)
        assertFalse(session.switchTo(second))
        pendingStop.complete(Unit)
        runCurrent()
        assertEquals(VpnPhase.DISCONNECTED, session.state.value.phase)
        assertEquals(listOf(first), adapter.startedProfiles)
    }

    @Test
    fun oldConnectionCheckCannotOverwriteNewProfileState() = runTest {
        val pendingCheck = CompletableDeferred<Boolean>()
        val adapter = FakeVpnAdapter().apply { checkPending = pendingCheck }
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("First", "first"))
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        adapter.checkPending = null
        val second = Profile("Second", "second")
        session.switchTo(second)
        runCurrent()
        pendingCheck.complete(false)
        runCurrent()
        assertEquals(second, session.state.value.profile)
        assertEquals(CheckPhase.SUCCEEDED, session.state.value.check)
    }

    @Test
    fun slowPeriodicCheckDoesNotOverlapOrContinueAfterStop() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("Example", "synthetic"))
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()

        val pendingCheck = CompletableDeferred<Boolean>()
        adapter.checkPending = pendingCheck
        adapter.checkNonCancellable = true
        advanceTimeBy(30_000)
        runCurrent()
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(2, adapter.checkAttempts)

        session.stop()
        runCurrent()
        pendingCheck.complete(false)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(VpnPhase.DISCONNECTED, session.state.value.phase)
        assertEquals(CheckPhase.NOT_CHECKED, session.state.value.check)
        assertEquals(2, adapter.checkAttempts)
    }

    @Test
    fun latePeriodicCheckCannotChangeNewSessionAfterSwitch() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        val first = Profile("First", "first")
        val second = Profile("Second", "second")
        session.connect(first)
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()

        val oldCheck = CompletableDeferred<Boolean>()
        adapter.checkPending = oldCheck
        adapter.checkNonCancellable = true
        advanceTimeBy(30_000)
        runCurrent()
        adapter.checkPending = null
        session.switchTo(second)
        runCurrent()
        assertEquals(second, session.state.value.profile)
        assertEquals(CheckPhase.SUCCEEDED, session.state.value.check)

        oldCheck.complete(false)
        runCurrent()
        assertEquals(second, session.state.value.profile)
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(CheckPhase.SUCCEEDED, session.state.value.check)
    }

    @Test
    fun oldEngineFailureCannotStopNewSession() = runTest {
        val adapter = FakeVpnAdapter()
        val session = VpnSession(adapter, backgroundScope)
        session.connect(Profile("First", "first"))
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        val oldId = requireNotNull(session.state.value.sessionId)
        val pendingStop = CompletableDeferred<Unit>()
        adapter.stopPending = pendingStop
        val second = Profile("Second", "second")
        session.switchTo(second)
        runCurrent()
        assertEquals(VpnStopResult.IGNORED, session.terminated("Старое ядро остановилось", oldId))
        pendingStop.complete(Unit)
        runCurrent()
        assertEquals(VpnPhase.CONNECTED, session.state.value.phase)
        assertEquals(second, session.state.value.profile)
        assertEquals(null, session.state.value.error)
    }

    @Test
    fun manualRetryAfterFailedCheckStopsAndRestartsCurrentProfile() = runTest {
        val adapter = FakeVpnAdapter().apply { checkResult = false }
        val session = VpnSession(adapter, backgroundScope)
        val profile = Profile("First", "first")
        session.connect(profile)
        session.permissionResult(true, requireNotNull(session.state.value.sessionId))
        runCurrent()
        assertEquals(CheckPhase.FAILED, session.state.value.check)
        val pendingStop = CompletableDeferred<Unit>()
        adapter.stopPending = pendingStop
        adapter.checkResult = true
        session.switchTo(profile)
        runCurrent()
        assertEquals(VpnPhase.SWITCHING, session.state.value.phase)
        assertEquals(listOf(profile), adapter.startedProfiles)
        pendingStop.complete(Unit)
        runCurrent()
        assertEquals(CheckPhase.SUCCEEDED, session.state.value.check)
        assertEquals(listOf(profile, profile), adapter.startedProfiles)
    }
}

private class FakeVpnAdapter : VpnAdapter {
    var started = false
    var checkResult = true
    var checkPending: CompletableDeferred<Boolean>? = null
    var checkNonCancellable = false
    var checkAttempts = 0
    var failStop = false
    var failStart = false
    var timeoutCheck = false
    var startPending: CompletableDeferred<Unit>? = null
    var stopPending: CompletableDeferred<Unit>? = null
    val startedProfiles = mutableListOf<Profile>()
    override suspend fun start(profile: Profile) {
        started = true
        startedProfiles.add(profile)
        startPending?.let { withContext(NonCancellable) { it.await() } }
        if (failStart) error("start failed")
    }
    override suspend fun stop() { stopPending?.await(); if (failStop) error("cleanup failed"); started = false }
    override suspend fun check(): Boolean {
        checkAttempts++
        if (timeoutCheck) return withTimeout(1) { delay(10); true }
        val pending = checkPending ?: return checkResult
        return if (checkNonCancellable) withContext(NonCancellable) { pending.await() } else pending.await()
    }
}
