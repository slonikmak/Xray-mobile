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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val session = VpnSession(this, scope)
    private val binder = LocalBinder()
    private var tunnel: ParcelFileDescriptor? = null
    private var coreTouched = false
    private var dnsInstalled = false

    inner class LocalBinder : Binder() { val service get() = this@XrayVpnService }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            while (true) {
                delay(2_000)
                val previous = session.state.value
                if (previous.phase == VpnPhase.CONNECTED) {
                    val running = withContext(Dispatchers.IO) {
                        runCatching { call("getXrayState")["running"]?.jsonPrimitive?.content == "true" }.getOrDefault(false)
                    }
                    if (!running && session.state.value === previous) session.terminated("VPN остановился из-за ошибки ядра.")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(1, notification())
        } catch (_: Exception) {
            session.terminated("Не удалось показать уведомление VPN.")
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        session.terminated("Разрешение VPN было отозвано.")
    }

    override fun onDestroy() {
        scope.cancel()
        runBlocking { runCatching { stop() } }
        super.onDestroy()
    }

    override suspend fun start(profile: Profile) = withContext(Dispatchers.IO) {
        check(tunnel == null && !coreTouched)
        val outbound = call("convertShareLinksToXrayJson", buildJsonObject { put("text", profile.link) })["outbounds"]!!.jsonArray
        check(outbound.size == 1 && outbound[0].jsonObject["protocol"]?.jsonPrimitive?.content == "vless")
        val controller = DialerController { fd -> protect(fd.toInt()) }
        LibXray.registerDialerController(controller)
        LibXray.setDNS(controller, "1.1.1.1:53")
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
    }

    override suspend fun stop() {
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
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
