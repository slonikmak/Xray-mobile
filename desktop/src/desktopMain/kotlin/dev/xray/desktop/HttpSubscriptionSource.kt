package dev.xray.desktop

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpSubscriptionSource internal constructor(sslContext: SSLContext) : SubscriptionSource {
    constructor() : this(SSLContext.getDefault())

    private val client = HttpClient.newBuilder()
        .sslContext(sslContext)
        .proxy(ProxySelector.of(null))
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override suspend fun fetch(url: String): ByteArray {
        try { validateSubscriptionUrl(url) } catch (_: Exception) { throw IllegalArgumentException("Invalid HTTPS subscription URL") }
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().build()
        return suspendCancellableCoroutine { continuation ->
            val future = client.sendAsync(request, HttpResponse.BodyHandler { BoundedBody() })
            future.whenComplete { response, error ->
                if (error != null || response.statusCode() != 200) {
                    continuation.resumeWithException(IllegalArgumentException("Subscription HTTPS request failed"))
                } else continuation.resume(response.body())
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    }
}

private class BoundedBody : HttpResponse.BodySubscriber<ByteArray> {
    private val delegate = HttpResponse.BodySubscribers.ofByteArray()
    private lateinit var subscription: Flow.Subscription
    private var size = 0L
    private var failed = false
    override fun getBody(): CompletionStage<ByteArray> = delegate.body
    override fun onSubscribe(value: Flow.Subscription) { subscription = value; delegate.onSubscribe(value) }
    override fun onNext(items: List<ByteBuffer>) {
        if (failed) return
        size += items.sumOf { it.remaining().toLong() }
        if (size > 2 * 1024 * 1024) {
            failed = true
            subscription.cancel()
            delegate.onError(IllegalArgumentException("Subscription exceeds 2 MiB"))
        } else delegate.onNext(items)
    }
    override fun onError(error: Throwable) { if (!failed) delegate.onError(error) }
    override fun onComplete() { if (!failed) delegate.onComplete() }
}
