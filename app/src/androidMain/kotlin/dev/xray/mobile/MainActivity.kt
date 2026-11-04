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
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

class MainActivity : ComponentActivity() {
    private var vpnService: XrayVpnService? = null
    private val vpnSession = mutableStateOf<VpnSession?>(null)
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            vpnService = (binder as XrayVpnService.LocalBinder).service
            vpnSession.value = vpnService?.session
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            vpnSession.value = null
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (vpnService?.session?.state?.value?.phase == VpnPhase.PERMISSION) {
            if (result.resultCode == Activity.RESULT_OK) startAuthorizedVpn()
            else vpnService?.session?.permissionResult(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(13, 17, 24)
        window.navigationBarColor = android.graphics.Color.rgb(16, 22, 30)
        val engine = OkHttp.create {
            // Redirects are checked by the shared loader before the next request.
            config { followRedirects(false); followSslRedirects(false) }
        }
        val app = SubscriptionApp(HttpSubscriptionSource(engine), AndroidSubscriptionStorage())
        bound = bindService(Intent(this, XrayVpnService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent { XrayApp(app, vpnSession.value, ::requestVpn) }
    }

    private fun requestVpn(profile: Profile) {
        val session = vpnService?.session ?: return
        if (!session.connect(profile)) return
        val intent = VpnService.prepare(this)
        if (intent == null) startAuthorizedVpn() else permission.launch(intent)
    }

    private fun startAuthorizedVpn() {
        val session = vpnService?.session ?: return
        if (session.state.value.phase != VpnPhase.PERMISSION) return
        try {
            startForegroundService(Intent(this, XrayVpnService::class.java))
            session.permissionResult(true)
        } catch (_: Exception) {
            session.terminated("Не удалось запустить VPN-сервис.")
        }
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
