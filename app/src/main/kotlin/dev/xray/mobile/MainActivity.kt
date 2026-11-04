package dev.xray.mobile

import android.os.Bundle
import android.util.AtomicFile
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

class MainActivity : ComponentActivity() {
    private var vpnService: XrayVpnService? = null
    private val vpnSession = mutableStateOf<VpnSession?>(null)
    private var bound = false
    private var pendingPermissionResult: Boolean? = null
    private var permissionSessionId: Long? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            vpnService = (binder as XrayVpnService.LocalBinder).service
            vpnSession.value = vpnService?.session
            pendingPermissionResult?.let { result -> pendingPermissionResult = null; handlePermission(result) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            vpnSession.value = null
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handlePermission(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("vpnPermissionSession") == true) {
            permissionSessionId = savedInstanceState.getLong("vpnPermissionSession")
        }
        if (savedInstanceState?.containsKey("vpnPermissionResult") == true) {
            pendingPermissionResult = savedInstanceState.getBoolean("vpnPermissionResult")
        }
        window.statusBarColor = android.graphics.Color.rgb(13, 17, 24)
        window.navigationBarColor = android.graphics.Color.rgb(16, 22, 30)
        val engine = OkHttp.create {
            // Redirects are checked by the shared loader before the next request.
            config { followRedirects(false); followSslRedirects(false) }
        }
        val app = SubscriptionApp(HttpSubscriptionSource(engine), AndroidSubscriptionStorage())
        bound = bindService(Intent(this, XrayVpnService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent { XrayApp(app, vpnSession.value, ::requestVpn) { lifecycleScope.launch { vpnService?.cancelOrStop() } } }
    }

    private fun requestVpn(profile: Profile) {
        val service = vpnService ?: return
        if (permissionSessionId != null) return
        lifecycleScope.launch {
            withContext(NonCancellable) {
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || permissionSessionId != null) return@withContext
                val sessionId = service.requestConnection(profile) ?: return@withContext
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    service.cancelOrStop()
                    return@withContext
                }
                permissionSessionId = sessionId
                try {
                    startForegroundService(Intent(this@MainActivity, XrayVpnService::class.java))
                    val intent = VpnService.prepare(this@MainActivity)
                    if (intent == null) handlePermission(true) else permission.launch(intent)
                } catch (_: Exception) {
                    permissionSessionId = null
                    service.failPending("Не удалось запросить разрешение VPN.", sessionId)
                }
            }
        }
    }

    private fun handlePermission(granted: Boolean) {
        val service = vpnService
        if (service == null) { pendingPermissionResult = granted; return }
        val sessionId = permissionSessionId ?: return
        permissionSessionId = null
        service.handlePermission(granted, sessionId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingPermissionResult?.let { outState.putBoolean("vpnPermissionResult", it) }
        permissionSessionId?.let { outState.putLong("vpnPermissionSession", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    private inner class AndroidSubscriptionStorage : SubscriptionStorage {
        private val file = AtomicFile(File(noBackupFilesDir, "subscription.json"))

        override suspend fun read(): String? = if (file.baseFile.exists()) file.openRead().use { it.readBytes().decodeToString() } else null

        override suspend fun write(value: String) {
            val stream = file.startWrite()
            try {
                stream.write(value.encodeToByteArray())
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }
}
