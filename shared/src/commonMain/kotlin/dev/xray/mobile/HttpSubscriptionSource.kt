package dev.xray.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

class HttpSubscriptionSource(engine: HttpClientEngine) : SubscriptionSource {
    private val client = HttpClient(engine) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; socketTimeoutMillis = 15_000 }
    }

    override suspend fun fetch(url: String): String = withTimeout(30_000.milliseconds) {
        var address = url
        repeat(6) {
            if (!isHttpsAddress(address)) throw SubscriptionError("Ссылка перенаправляет на недопустимый адрес.")
            val result = client.prepareGet(address).execute { response ->
                when (response.status.value) {
                    in 300..399 -> Pair(response.headers[HttpHeaders.Location]
                        ?: throw SubscriptionError("Сервер вернул некорректное перенаправление."), null)
                    in 200..299 -> {
                        val buffer = ByteArray(1_048_577)
                        val channel = response.bodyAsChannel()
                        var count = 0
                        while (count < buffer.size) {
                            val read = channel.readAvailable(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        if (count > 1_048_576) throw SubscriptionError("Ответ подписки слишком большой.")
                        Pair(null, buffer.decodeToString(0, count, throwOnInvalidSequence = true))
                    }
                    else -> throw SubscriptionError("Сервер подписки вернул ошибку HTTP ${response.status.value}.")
                }
            }
            if (result.second != null) return@withTimeout result.second!!
            address = URLBuilder(address).takeFrom(result.first!!).buildString()
        }
        throw SubscriptionError("Слишком много перенаправлений подписки.")
    }
}
