package dev.xray.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import libXray.DialerController
import libXray.LibXray
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class XrayVpnService : VpnService(), VpnAdapter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var session: VpnSession
        private set
    private val binder = LocalBinder()
    private var tunnel: ParcelFileDescriptor? = null
    private var coreTouched = false
    private var dnsInstalled = false
    private val engineLock = Mutex()

    inner class LocalBinder : Binder() { val service get() = this@XrayVpnService }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onCreate() {
        super.onCreate()
        val saved = getSharedPreferences("vpn-state", MODE_PRIVATE)
        val cleanupRequired = saved.getBoolean("cleanupRequired", false)
        val error = saved.getString("error", null) ?: if (cleanupRequired) "Предыдущее подключение прервано. Повторите остановку." else null
        session = VpnSession(this, scope, error, cleanupRequired)
        scope.launch {
            while (true) {
                delay(2_000)
                val previous = session.state.value
                if (previous.phase == VpnPhase.CONNECTED) {
                    val running = withContext(Dispatchers.IO) {
                        runCatching { call("getXrayState")["running"]?.jsonPrimitive?.content == "true" }.getOrDefault(false)
                    }
                    if (!running && session.state.value === previous) {
                        rememberError("VPN остановился из-за ошибки ядра.")
                        session.terminated("VPN остановился из-за ошибки ядра.")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (session.state.value.phase !in setOf(VpnPhase.PERMISSION, VpnPhase.CONNECTING, VpnPhase.CONNECTED)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(1, notification())
        } catch (_: Exception) {
            rememberError("Не удалось показать уведомление VPN.")
            session.terminated("Не удалось показать уведомление VPN.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        val pendingPermission = session.state.value.phase == VpnPhase.PERMISSION
        rememberError("Разрешение VPN было отозвано.")
        session.terminated("Разрешение VPN было отозвано.")
        if (pendingPermission) stopPendingPermission()
    }

    fun requestConnection(profile: Profile): Boolean {
        if (!session.connect(profile)) return false
        rememberCleanup(true)
        rememberError(null)
        return true
    }

    fun permissionDenied() {
        rememberError("Разрешение на VPN не получено.")
        session.permissionResult(false)
        stopPendingPermission()
    }

    fun failPending(message: String) {
        rememberError(message)
        session.terminated(message)
        stopPendingPermission()
    }

    fun cancelOrStop() {
        val pendingPermission = session.state.value.phase == VpnPhase.PERMISSION
        session.stop()
        if (pendingPermission) stopPendingPermission()
    }

    private fun stopPendingPermission() {
        rememberCleanup(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun rememberError(message: String?) {
        getSharedPreferences("vpn-state", MODE_PRIVATE).edit().apply {
            if (message == null) remove("error") else putString("error", message)
        }.commit()
    }

    private fun rememberCleanup(required: Boolean) {
        getSharedPreferences("vpn-state", MODE_PRIVATE).edit().putBoolean("cleanupRequired", required).commit()
    }

    override fun onDestroy() {
        val phase = session.state.value.phase
        val needsCleanup = phase in setOf(VpnPhase.CONNECTING, VpnPhase.CONNECTED, VpnPhase.DISCONNECTING) ||
            VpnSocketProtector.owner() === this
        if (needsCleanup) {
            rememberCleanup(true)
            if (phase in setOf(VpnPhase.CONNECTING, VpnPhase.CONNECTED) && getSharedPreferences("vpn-state", MODE_PRIVATE).getString("error", null) == null) {
                rememberError("VPN был неожиданно остановлен.")
            }
        }
        scope.cancel()
        if (needsCleanup) {
            val previousCleanup = VpnSocketProtector.pendingCleanup()
            lateinit var cleanup: Job
            cleanup = CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
                try {
                    previousCleanup?.join()
                    VpnSocketProtector.owner()?.takeIf { it !== this@XrayVpnService }?.cleanupEngine()
                    cleanupEngine()
                    VpnSocketProtector.finishCleanup(cleanup) { rememberCleanup(false) }
                } catch (_: Exception) {
                    rememberError("Не удалось остановить VPN. Повторите остановку.")
                }
            }
            VpnSocketProtector.trackCleanup(cleanup) { rememberCleanup(true) }
            cleanup.start()
        }
        super.onDestroy()
    }

    override suspend fun start(profile: Profile) = engineLock.withLock { try {
        VpnSocketProtector.install(this@XrayVpnService)
        withContext(Dispatchers.IO) {
        check(tunnel == null && !coreTouched)
        val outbound = call("convertShareLinksToXrayJson", buildJsonObject { put("text", profile.link) })["outbounds"]!!.jsonArray
        check(outbound.size == 1 && outbound[0].jsonObject["protocol"]?.jsonPrimitive?.content == "vless")
        LibXray.setDNS(VpnSocketProtector, "1.1.1.1:53")
        dnsInstalled = true
        val fd = Builder().setSession("Xray VPN").setMtu(1500)
            .addAddress("10.77.0.1", 30).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1")
            .establish() ?: error("VPN interface could not be created")
        tunnel = fd
        val config = buildJsonObject {
            put("env", buildJsonObject { put("xray.tun.fd", fd.fd.toString()) })
            put("log", buildJsonObject { put("loglevel", "none") })
            put("inbounds", buildJsonArray { add(buildJsonObject {
                put("protocol", "tun")
                // Android supplies the actual TUN fd; a name avoids forbidden interface enumeration.
                put("settings", buildJsonObject { put("name", "tun0"); put("mtu", 1500) })
            }) })
            put("outbounds", outbound)
        }
        coreTouched = true
        call("runXray", buildJsonObject { put("xrayJson", config.toString()) })
        check(call("getXrayState")["running"]?.jsonPrimitive?.content == "true")
    } } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        rememberError("Не удалось запустить VPN.")
        throw e
    } }

    override suspend fun stop() {
        try {
            VpnSocketProtector.awaitCleanup()
            VpnSocketProtector.owner()?.takeIf { it !== this }?.cleanupEngine()
            cleanupEngine()
            rememberCleanup(false)
            val saved = getSharedPreferences("vpn-state", MODE_PRIVATE)
            if (saved.getString("error", null) == "Не удалось остановить VPN. Повторите остановку.") rememberError(null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            rememberError("Не удалось остановить VPN. Повторите остановку.")
            rememberCleanup(true)
            throw e
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun cleanupEngine() = engineLock.withLock {
        withContext(Dispatchers.IO) {
            if (coreTouched) {
                call("stopXray")
                coreTouched = false
            }
            if (dnsInstalled) {
                LibXray.resetDNS()
                dnsInstalled = false
            }
            tunnel?.close()
            tunnel = null
            VpnSocketProtector.clear(this@XrayVpnService)
        }
    }

    override suspend fun check(): Boolean = withTimeout(12_000) {
        withContext(Dispatchers.IO) {
            val manager = getSystemService(ConnectivityManager::class.java)
            var vpn = manager.allNetworks.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            repeat(20) {
                if (vpn == null) {
                    delay(100)
                    vpn = manager.allNetworks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
                }
            }
            val connection = (vpn ?: return@withContext false).openConnection(URL("https://www.google.com/generate_204")) as HttpsURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.responseCode == 204
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun call(method: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject {
        val request = buildJsonObject { put("apiVersion", 3); put("method", method); put("payload", payload) }
        val response = Json.parseToJsonElement(LibXray.invoke(request.toString())).jsonObject
        check(response["success"]?.jsonPrimitive?.content == "true") { "Xray operation failed" }
        return response["data"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())
    }

    private fun notification(): Notification {
        val channel = "xray-vpn"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "VPN", NotificationManager.IMPORTANCE_LOW),
        )
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, channel).setContentTitle("Xray VPN")
            .setContentText("Управление VPN-соединением").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(intent).setOngoing(true).build()
    }
}

private object VpnSocketProtector : DialerController {
    @Volatile private var service: XrayVpnService? = null
    @Volatile private var cleanup: Job? = null
    private var registered = false

    @Synchronized fun install(current: XrayVpnService) {
        if (!registered) {
            LibXray.registerDialerController(this)
            registered = true
        }
        service = current
    }

    @Synchronized fun clear(current: XrayVpnService) {
        if (service === current) service = null
    }

    fun owner(): XrayVpnService? = service

    @Synchronized fun trackCleanup(job: Job, markPending: () -> Unit) {
        cleanup = job
        markPending()
    }

    @Synchronized fun finishCleanup(job: Job, markComplete: () -> Unit) {
        if (cleanup === job) markComplete()
    }

    fun pendingCleanup(): Job? = cleanup

    suspend fun awaitCleanup() { cleanup?.join() }

    override fun protectFd(fd: Long): Boolean = service?.protect(fd.toInt()) == true
}
