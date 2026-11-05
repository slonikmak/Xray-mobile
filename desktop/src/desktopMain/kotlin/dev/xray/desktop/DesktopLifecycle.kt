package dev.xray.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DesktopWindowState(val visible: Boolean = true, val exiting: Boolean = false)

class DesktopLifecycle(
    private val session: () -> ProxySession?,
    private val scope: CoroutineScope,
    private val trayAvailable: Boolean,
    private val readyToExit: suspend () -> Boolean = { true },
    private val onExitFailed: () -> Unit = {},
    private val exitApplication: () -> Unit,
) {
    private val mutableState = MutableStateFlow(DesktopWindowState())
    val state = mutableState.asStateFlow()

    fun closeWindow() {
        if (trayAvailable) mutableState.value = mutableState.value.copy(visible = false)
        else exit()
    }

    fun openWindow() { mutableState.value = mutableState.value.copy(visible = true) }

    fun exit() {
        if (mutableState.value.exiting) return
        mutableState.value = mutableState.value.copy(exiting = true)
        scope.launch {
            val cleaned = try { readyToExit() && session()?.shutdown() != false }
            catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (cleaned) exitApplication()
            else {
                mutableState.value = DesktopWindowState(visible = true)
                onExitFailed()
            }
        }
    }
}
