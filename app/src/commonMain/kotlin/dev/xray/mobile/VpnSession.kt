package dev.xray.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VpnPhase { DISCONNECTED, PERMISSION, CONNECTING, CONNECTED, DISCONNECTING, ERROR }
enum class CheckPhase { NOT_CHECKED, CHECKING, SUCCEEDED, FAILED }

data class VpnState(
    val phase: VpnPhase = VpnPhase.DISCONNECTED,
    val check: CheckPhase = CheckPhase.NOT_CHECKED,
    val profile: Profile? = null,
    val error: String? = null,
    val cleanupRequired: Boolean = false,
)

interface VpnAdapter {
    suspend fun start(profile: Profile)
    suspend fun stop()
    suspend fun check(): Boolean
}

class VpnSession(private val adapter: VpnAdapter, private val scope: CoroutineScope, initialError: String? = null, initialCleanupRequired: Boolean = false) {
    private val mutableState = MutableStateFlow(if (initialError == null && !initialCleanupRequired) VpnState() else
        VpnState(phase = VpnPhase.ERROR, error = initialError, cleanupRequired = initialCleanupRequired))
    val state = mutableState.asStateFlow()
    private var startJob: Job? = null
    private var checkJob: Job? = null
    private var generation = 0

    fun connect(profile: Profile): Boolean {
        if (state.value.phase != VpnPhase.DISCONNECTED && state.value.phase != VpnPhase.ERROR) return false
        if (state.value.cleanupRequired) return false
        mutableState.value = VpnState(phase = VpnPhase.PERMISSION, profile = profile)
        return true
    }

    fun permissionResult(granted: Boolean) {
        if (state.value.phase != VpnPhase.PERMISSION) return
        if (!granted) {
            mutableState.value = VpnState(phase = VpnPhase.ERROR, error = "Разрешение на VPN не получено.")
            return
        }
        val profile = state.value.profile ?: return
        val token = ++generation
        mutableState.value = state.value.copy(phase = VpnPhase.CONNECTING)
        startJob = scope.launch {
            try {
                adapter.start(profile)
                if (generation != token) return@launch
                mutableState.value = state.value.copy(phase = VpnPhase.CONNECTED, check = CheckPhase.CHECKING)
                checkJob = scope.launch {
                    val passed = try { adapter.check() } catch (_: TimeoutCancellationException) { false } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    if (generation == token && state.value.phase == VpnPhase.CONNECTED) {
                        mutableState.value = state.value.copy(check = if (passed) CheckPhase.SUCCEEDED else CheckPhase.FAILED)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation != token) return@launch
                mutableState.value = state.value.copy(phase = VpnPhase.DISCONNECTING)
                try {
                    adapter.stop()
                    mutableState.value = VpnState(phase = VpnPhase.ERROR, error = "Не удалось запустить VPN.")
                } catch (_: Exception) {
                    mutableState.value = VpnState(phase = VpnPhase.ERROR, error = "Не удалось остановить VPN. Повторите остановку.", cleanupRequired = true)
                }
            }
        }
    }

    fun stop() = stopWithError(null)

    fun terminated(message: String) = stopWithError(message)

    private fun stopWithError(message: String?) {
        if (state.value.phase == VpnPhase.PERMISSION) {
            generation++
            mutableState.value = if (message == null) VpnState() else VpnState(phase = VpnPhase.ERROR, error = message)
            return
        }
        if (state.value.phase == VpnPhase.DISCONNECTED || state.value.phase == VpnPhase.DISCONNECTING ||
            (state.value.phase == VpnPhase.ERROR && !state.value.cleanupRequired)) return
        generation++
        val pendingStart = startJob
        pendingStart?.cancel()
        checkJob?.cancel()
        mutableState.value = state.value.copy(phase = VpnPhase.DISCONNECTING, check = CheckPhase.NOT_CHECKED)
        scope.launch {
            pendingStart?.join()
            try {
                adapter.stop()
                mutableState.value = if (message == null) VpnState() else VpnState(phase = VpnPhase.ERROR, error = message)
            } catch (_: Exception) {
                mutableState.value = VpnState(phase = VpnPhase.ERROR, error = "Не удалось остановить VPN. Повторите остановку.", cleanupRequired = true)
            }
        }
    }
}
