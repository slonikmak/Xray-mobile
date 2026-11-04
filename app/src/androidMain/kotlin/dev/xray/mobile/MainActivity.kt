package dev.xray.mobile

import android.os.Bundle
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(13, 17, 24)
        window.navigationBarColor = android.graphics.Color.rgb(16, 22, 30)
        val engine = OkHttp.create {
            // Redirects are checked by the shared loader before the next request.
            config { followRedirects(false); followSslRedirects(false) }
        }
        val app = SubscriptionApp(HttpSubscriptionSource(engine), AndroidSubscriptionStorage())
        setContent { XrayApp(app) }
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
