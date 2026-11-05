package dev.xray.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

enum class ProxyPhase { DISCONNECTED, STARTING, RUNNING, CONNECTED, STOPPING, FAILED }
enum class ProxyHealth { UNKNOWN, CHECKING, RETRYING, REACHABLE, FAILED }
data class ProxyState(
    val phase: ProxyPhase = ProxyPhase.DISCONNECTED,
    val health: ProxyHealth = ProxyHealth.UNKNOWN,
    val activeProfileId: String? = null,
    val error: String? = null,
    val cleanupRequired: Boolean = false,
)

interface ProxyBackend {
    suspend fun start(profileId: String, cancellation: StartupCancellation): ProxyProcess
    suspend fun check(): Boolean
}
class StartupCancellation {
    private var cancelled = false
    @Synchronized fun cancel() { cancelled = true }
    @Synchronized fun check() { check(!cancelled) { "Startup cancelled" } }
    @Synchronized fun <T> launch(block: () -> T): T { check(); return block() }
}
class ProxyStartFailure(val process: ProxyProcess) : Exception()

interface ProxyProcess {
    suspend fun awaitExit()
    suspend fun stop()
    suspend fun cleanup()
}

private sealed interface ProxyEvent {
    data class Connect(val id: String, val reply: CompletableDeferred<Boolean>) : ProxyEvent
    data class Disconnect(val reply: CompletableDeferred<Boolean>) : ProxyEvent
    data class Started(val generation: Long, val process: ProxyProcess?, val failed: Boolean) : ProxyEvent
    data class Checked(val generation: Long, val reachable: Boolean) : ProxyEvent
    data class Exited(val generation: Long, val confirmed: Boolean) : ProxyEvent
    data class Stopped(val generation: Long, val success: Boolean, val unexpected: Boolean) : ProxyEvent
}

class ProxySession(private val backend: ProxyBackend, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(ProxyState())
    val state = mutableState.asStateFlow()
    private val events = Channel<ProxyEvent>(Channel.UNLIMITED)
    private val loop = scope.launch {
        var generation = 0L
        var active: ProxyProcess? = null
        var checkJob: Job? = null
        var startingCancellation: StartupCancellation? = null
        var consecutiveFailures = 0

        fun stop(process: ProxyProcess, token: Long, unexpected: Boolean) {
            scope.launch {
                val success = try {
                    if (!unexpected) process.stop()
                    process.cleanup()
                    true
                } catch (_: TimeoutCancellationException) { false }
                catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                events.send(ProxyEvent.Stopped(token, success, unexpected))
            }
        }

        for (event in events) when (event) {
            is ProxyEvent.Connect -> {
                val accepted = active == null && !mutableState.value.cleanupRequired &&
                    mutableState.value.phase in setOf(ProxyPhase.DISCONNECTED, ProxyPhase.FAILED)
                if (accepted) {
                    val token = ++generation
                    consecutiveFailures = 0
                    val cancelled = StartupCancellation()
                    startingCancellation = cancelled
                    mutableState.value = ProxyState(phase = ProxyPhase.STARTING, activeProfileId = event.id)
                    scope.launch {
                        val result = try { ProxyEvent.Started(token, backend.start(event.id, cancelled), false) }
                        catch (_: TimeoutCancellationException) { ProxyEvent.Started(token, null, true) }
                        catch (e: CancellationException) { throw e }
                        catch (e: ProxyStartFailure) { ProxyEvent.Started(token, e.process, true) }
                        catch (_: Exception) { ProxyEvent.Started(token, null, true) }
                        events.send(result)
                    }
                }
                event.reply.complete(accepted)
            }
            is ProxyEvent.Started -> if (event.generation == generation && mutableState.value.phase in setOf(ProxyPhase.STARTING, ProxyPhase.STOPPING)) {
                val cancelled = mutableState.value.phase == ProxyPhase.STOPPING
                startingCancellation = null
                if (event.process == null) mutableState.value = if (cancelled) ProxyState() else ProxyState(phase = ProxyPhase.FAILED, error = "Could not start Xray")
                else {
                    active = event.process
                    if (cancelled) {
                        stop(event.process, ++generation, unexpected = false)
                        continue
                    }
                    mutableState.value = if (event.failed) mutableState.value.copy(phase = ProxyPhase.FAILED,
                        error = "Xray startup could not be cleaned up", cleanupRequired = true)
                    else mutableState.value.copy(phase = ProxyPhase.RUNNING, health = ProxyHealth.CHECKING)
                    val token = generation
                    scope.launch {
                        val confirmed = try { event.process.awaitExit(); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                        events.send(ProxyEvent.Exited(token, confirmed))
                    }
                    if (!event.failed) checkJob = scope.launch {
                        while (true) {
                            val reachable = try { backend.check() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                            events.send(ProxyEvent.Checked(token, reachable))
                            delay(30_000)
                        }
                    }
                }
            }
            is ProxyEvent.Checked -> if (event.generation == generation && active != null &&
                mutableState.value.phase in setOf(ProxyPhase.RUNNING, ProxyPhase.CONNECTED)) {
                consecutiveFailures = if (event.reachable) 0 else consecutiveFailures + 1
                mutableState.value = mutableState.value.copy(
                    phase = if (event.reachable || (mutableState.value.phase == ProxyPhase.CONNECTED && consecutiveFailures < 2))
                        ProxyPhase.CONNECTED else ProxyPhase.RUNNING,
                    health = if (event.reachable) ProxyHealth.REACHABLE else if (consecutiveFailures < 2) ProxyHealth.RETRYING else ProxyHealth.FAILED,
                )
            }
            is ProxyEvent.Disconnect -> {
                val process = active
                val starting = mutableState.value.phase == ProxyPhase.STARTING
                val accepted = starting || (process != null && mutableState.value.phase in setOf(ProxyPhase.RUNNING, ProxyPhase.CONNECTED, ProxyPhase.FAILED))
                if (starting) {
                    startingCancellation?.cancel()
                    mutableState.value = mutableState.value.copy(phase = ProxyPhase.STOPPING, health = ProxyHealth.UNKNOWN)
                }
                else if (accepted) {
                    checkJob?.cancel()
                    mutableState.value = mutableState.value.copy(phase = ProxyPhase.STOPPING, health = ProxyHealth.UNKNOWN, error = null)
                    stop(requireNotNull(process), ++generation, unexpected = false)
                }
                event.reply.complete(accepted)
            }
            is ProxyEvent.Exited -> if (event.generation == generation && active != null &&
                (mutableState.value.phase in setOf(ProxyPhase.RUNNING, ProxyPhase.CONNECTED) ||
                    (mutableState.value.phase == ProxyPhase.FAILED && mutableState.value.cleanupRequired))) {
                checkJob?.cancel()
                if (!event.confirmed) mutableState.value = mutableState.value.copy(phase = ProxyPhase.FAILED, health = ProxyHealth.UNKNOWN,
                    error = "Could not confirm Xray exit", cleanupRequired = true)
                else {
                    mutableState.value = mutableState.value.copy(phase = ProxyPhase.STOPPING, health = ProxyHealth.UNKNOWN)
                    stop(requireNotNull(active), ++generation, unexpected = true)
                }
            }
            is ProxyEvent.Stopped -> if (event.generation == generation && mutableState.value.phase == ProxyPhase.STOPPING) {
                if (event.success) {
                    active = null
                    mutableState.value = if (event.unexpected) ProxyState(phase = ProxyPhase.FAILED, error = "Xray exited") else ProxyState()
                } else mutableState.value = mutableState.value.copy(phase = ProxyPhase.FAILED, error = "Could not stop and clean up Xray", cleanupRequired = true)
            }
        }
    }.also { it.invokeOnCompletion { events.cancel() } }

    suspend fun connect(profileId: String): Boolean {
        val reply = CompletableDeferred<Boolean>()
        if (events.trySend(ProxyEvent.Connect(profileId, reply)).isFailure) return false
        return select { reply.onAwait { it }; loop.onJoin { false } }
    }
    suspend fun disconnect(): Boolean {
        val reply = CompletableDeferred<Boolean>()
        if (events.trySend(ProxyEvent.Disconnect(reply)).isFailure) return false
        return select { reply.onAwait { it }; loop.onJoin { false } }
    }

    suspend fun shutdown(): Boolean {
        if (state.value.phase == ProxyPhase.STARTING) disconnect()
        val current = state.first { it.phase != ProxyPhase.STOPPING }
        if (current.activeProfileId == null) return true
        if (!disconnect()) return false
        return state.first { it.phase != ProxyPhase.STOPPING }.activeProfileId == null
    }
}
