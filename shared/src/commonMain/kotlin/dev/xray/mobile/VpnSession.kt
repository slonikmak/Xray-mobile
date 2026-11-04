package dev.xray.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.random.Random

const val VPN_CLEANUP_ERROR = "Не удалось остановить VPN. Повторите остановку."

enum class VpnPhase { DISCONNECTED, PERMISSION, CONNECTING, CONNECTED, SWITCHING, DISCONNECTING, ERROR }
enum class CheckPhase { NOT_CHECKED, CHECKING, SUCCEEDED, FAILED }
enum class VpnStopResult { IGNORED, PERMISSION, STOPPING }

data class VpnState(
    val phase: VpnPhase = VpnPhase.DISCONNECTED,
    val check: CheckPhase = CheckPhase.NOT_CHECKED,
    val profile: Profile? = null,
    val error: String? = null,
    val cleanupRequired: Boolean = false,
    val sessionId: Long? = null,
)

interface VpnAdapter {
    suspend fun start(profile: Profile)
    suspend fun stop()
    suspend fun stopForSwitch() = stop()
    suspend fun check(): Boolean
}

private sealed interface VpnEvent {
    data class Connect(val profile: Profile, val reply: CompletableDeferred<Boolean>) : VpnEvent
    data class Permission(val granted: Boolean, val sessionId: Long, val reply: CompletableDeferred<Boolean>) : VpnEvent
    data class Switch(val profile: Profile, val reply: CompletableDeferred<Boolean>) : VpnEvent
    data class Stop(val error: String?, val expectedSession: Long?, val reply: CompletableDeferred<VpnStopResult>) : VpnEvent
    data class Started(val generation: Long, val succeeded: Boolean) : VpnEvent
    data class Switched(val generation: Long, val profile: Profile, val succeeded: Boolean) : VpnEvent
    data class Stopped(val generation: Long, val succeeded: Boolean) : VpnEvent
    data class Checked(val generation: Long, val succeeded: Boolean) : VpnEvent
}

class VpnSession(private val adapter: VpnAdapter, private val scope: CoroutineScope, initialError: String? = null, initialCleanupRequired: Boolean = false) {
    private val mutableState = MutableStateFlow(if (initialError == null && !initialCleanupRequired) VpnState() else
        VpnState(phase = VpnPhase.ERROR, error = initialError, cleanupRequired = initialCleanupRequired))
    val state = mutableState.asStateFlow()
    private val events = Channel<VpnEvent>(Channel.UNLIMITED)
    private val loop = scope.launch {
        var generation = Random.nextLong()
        var startJob: Job? = null
        var checkJob: Job? = null
        var stopError: String? = null

        fun start(profile: Profile, token: Long): Job = scope.launch {
            val succeeded = try { adapter.start(profile); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            events.trySend(VpnEvent.Started(token, succeeded))
        }

        fun stop(token: Long, pending: Job?) {
            scope.launch {
                pending?.join()
                val succeeded = try { adapter.stop(); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                events.trySend(VpnEvent.Stopped(token, succeeded))
            }
        }

        for (event in events) {
            when (event) {
                is VpnEvent.Connect -> {
                    val accepted = mutableState.value.phase in setOf(VpnPhase.DISCONNECTED, VpnPhase.ERROR) && !mutableState.value.cleanupRequired
                    if (accepted) mutableState.value = VpnState(phase = VpnPhase.PERMISSION, profile = event.profile, sessionId = ++generation)
                    event.reply.complete(accepted)
                }
                is VpnEvent.Permission -> {
                    val accepted = mutableState.value.phase == VpnPhase.PERMISSION && mutableState.value.sessionId == event.sessionId
                    if (accepted) {
                        if (!event.granted) mutableState.value = VpnState(phase = VpnPhase.ERROR, error = "Разрешение на VPN не получено.")
                        else {
                            val profile = requireNotNull(mutableState.value.profile)
                            val token = generation
                            mutableState.value = mutableState.value.copy(phase = VpnPhase.CONNECTING)
                            startJob = start(profile, token)
                        }
                    }
                    event.reply.complete(accepted)
                }
                is VpnEvent.Switch -> {
                    val accepted = mutableState.value.phase == VpnPhase.CONNECTED
                    if (accepted) {
                        val token = ++generation
                        checkJob?.cancel()
                        mutableState.value = mutableState.value.copy(phase = VpnPhase.SWITCHING, check = CheckPhase.NOT_CHECKED, sessionId = token)
                        startJob = scope.launch {
                            val succeeded = try { adapter.stopForSwitch(); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                            events.trySend(VpnEvent.Switched(token, event.profile, succeeded))
                        }
                    }
                    event.reply.complete(accepted)
                }
                is VpnEvent.Stop -> {
                    val phase = mutableState.value.phase
                    val matches = event.expectedSession == null || event.expectedSession == mutableState.value.sessionId
                    val result = when {
                        !matches -> VpnStopResult.IGNORED
                        phase == VpnPhase.PERMISSION -> {
                            generation++
                            mutableState.value = if (event.error == null) VpnState() else VpnState(phase = VpnPhase.ERROR, error = event.error)
                            VpnStopResult.PERMISSION
                        }
                        phase == VpnPhase.DISCONNECTING && event.error != null -> {
                            stopError = event.error
                            VpnStopResult.STOPPING
                        }
                        phase == VpnPhase.DISCONNECTED || phase == VpnPhase.DISCONNECTING ||
                            (phase == VpnPhase.ERROR && !mutableState.value.cleanupRequired) -> VpnStopResult.IGNORED
                        else -> {
                            val token = ++generation
                            stopError = event.error
                            val pending = startJob
                            pending?.cancel()
                            checkJob?.cancel()
                            mutableState.value = mutableState.value.copy(phase = VpnPhase.DISCONNECTING, check = CheckPhase.NOT_CHECKED, sessionId = token)
                            stop(token, pending)
                            VpnStopResult.STOPPING
                        }
                    }
                    event.reply.complete(result)
                }
                is VpnEvent.Started -> if (event.generation == generation && mutableState.value.phase == VpnPhase.CONNECTING) {
                    if (!event.succeeded) {
                        mutableState.value = mutableState.value.copy(phase = VpnPhase.DISCONNECTING)
                        stopError = "Не удалось запустить VPN."
                        stop(generation, null)
                    } else {
                        mutableState.value = mutableState.value.copy(phase = VpnPhase.CONNECTED, check = CheckPhase.CHECKING)
                        val token = generation
                        checkJob = scope.launch {
                            while (true) {
                                val succeeded = try { adapter.check() } catch (_: TimeoutCancellationException) { false } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                                events.trySend(VpnEvent.Checked(token, succeeded))
                                delay(30_000)
                            }
                        }
                    }
                }
                is VpnEvent.Switched -> if (event.generation == generation && mutableState.value.phase == VpnPhase.SWITCHING) {
                    if (!event.succeeded) mutableState.value = mutableState.value.copy(phase = VpnPhase.ERROR, error = VPN_CLEANUP_ERROR, cleanupRequired = true)
                    else {
                        mutableState.value = VpnState(phase = VpnPhase.CONNECTING, profile = event.profile, sessionId = generation)
                        startJob = start(event.profile, generation)
                    }
                }
                is VpnEvent.Stopped -> if (event.generation == generation && mutableState.value.phase == VpnPhase.DISCONNECTING) {
                    mutableState.value = if (!event.succeeded) VpnState(phase = VpnPhase.ERROR, error = VPN_CLEANUP_ERROR, cleanupRequired = true)
                    else if (stopError != null) VpnState(phase = VpnPhase.ERROR, profile = mutableState.value.profile, error = stopError)
                    else VpnState()
                }
                is VpnEvent.Checked -> if (event.generation == generation && mutableState.value.phase == VpnPhase.CONNECTED) {
                    mutableState.value = mutableState.value.copy(check = if (event.succeeded) CheckPhase.SUCCEEDED else CheckPhase.FAILED)
                }
            }
        }
    }.also { job -> job.invokeOnCompletion { events.cancel() } }

    private suspend fun <T> request(default: T, event: (CompletableDeferred<T>) -> VpnEvent): T {
        val reply = CompletableDeferred<T>()
        if (events.trySend(event(reply)).isFailure) return default
        return select { reply.onAwait { it }; loop.onJoin { default } }
    }

    suspend fun connect(profile: Profile): Boolean = request(false) { VpnEvent.Connect(profile, it) }
    suspend fun permissionResult(granted: Boolean, sessionId: Long): Boolean = request(false) { VpnEvent.Permission(granted, sessionId, it) }
    suspend fun switchTo(profile: Profile): Boolean = request(false) { VpnEvent.Switch(profile, it) }
    suspend fun stop(): VpnStopResult = request(VpnStopResult.IGNORED) { VpnEvent.Stop(null, null, it) }
    suspend fun terminated(message: String, expectedSession: Long? = null): VpnStopResult =
        request(VpnStopResult.IGNORED) { VpnEvent.Stop(message, expectedSession, it) }
}
