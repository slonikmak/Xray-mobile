package dev.xray.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val SAMPLE_SUBSCRIPTION = "dmxlc3M6Ly8wMDAwMDAwMC0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDBAZXhhbXBsZS50ZXN0OjQ0Mz90eXBlPXhodHRwJnNlY3VyaXR5PXRscyZzbmk9ZXhhbXBsZS50ZXN0JnBhdGg9JTJGdGVzdCNFeGFtcGxl"

private class MemoryStorage : SubscriptionStorage {
    private var content: String? = null
    override suspend fun read(): String? = content
    override suspend fun write(value: String) { content = value }
}

@OptIn(ExperimentalEncodingApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscriptionTest {
    @Test
    fun importedProfilesRemainAfterRestart() = runTest {
        val storage = MemoryStorage()
        val source = SubscriptionSource { SAMPLE_SUBSCRIPTION }
        val app = SubscriptionApp(source, storage)

        app.load("https://example.test/subscription")
        app.select(app.state.value.profiles.single().link)

        val restored = SubscriptionApp(source, storage)
        restored.restore()
        assertEquals("Example", restored.state.value.profiles.single().name)
        assertEquals(restored.state.value.profiles.single().link, restored.state.value.selectedLink)
    }

    @Test
    fun failedRefreshKeepsSavedProfilesAndSelection() = runTest {
        val storage = MemoryStorage()
        var response = SAMPLE_SUBSCRIPTION
        val source = SubscriptionSource { response }
        val app = SubscriptionApp(source, storage)
        app.load("https://example.test/subscription")
        val link = app.state.value.profiles.single().link
        app.select(link)

        response = "not base64!"
        app.load("https://example.test/subscription")
        assertNotNull(app.state.value.error)
        assertEquals(link, app.state.value.selectedLink)
        val restored = SubscriptionApp(source, storage)
        restored.restore()
        assertEquals(link, restored.state.value.selectedLink)
    }

    @Test
    fun refreshKeepsSelectionOnlyWhenTheProfileStillExists() = runTest {
        val app = SubscriptionApp(SubscriptionSource { SAMPLE_SUBSCRIPTION }, MemoryStorage())
        app.load("https://example.test/subscription")
        val link = app.state.value.profiles.single().link
        app.select(link)
        app.load("https://example.test/subscription")
        assertEquals(link, app.state.value.selectedLink)
    }

    @Test
    fun cancelledDownloadLeavesSavedDataUntouched() = runTest {
        val storage = MemoryStorage()
        val pending = CompletableDeferred<String>()
        var wait = false
        val app = SubscriptionApp(SubscriptionSource { if (wait) pending.await() else SAMPLE_SUBSCRIPTION }, storage)
        app.load("https://example.test/subscription")
        wait = true
        val job = launch { app.load("https://example.test/new") }
        runCurrent()
        assertEquals(true, app.state.value.loading)
        job.cancel()
        job.join()
        assertEquals(false, app.state.value.loading)
        assertNull(app.state.value.error)
        assertEquals("https://example.test/subscription", app.state.value.url)
    }

    @Test
    fun supportedProfilesKeepTheirParametersAndInvalidEntriesAreSkipped() = runTest {
        val xhttp = "vless://00000000-0000-4000-8000-000000000000@example.test:443?type=xhttp&security=tls&sni=example.test&path=%2Ftest#Long%20name"
        val reality = "vless://11111111-1111-4111-8111-111111111111@example.test:443?type=tcp&security=reality&flow=xtls-rprx-vision&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abcd&sni=example.test#Reality"
        val response = Base64.encode("$xhttp\n$reality\nvless://invalid\n".encodeToByteArray())
        val app = SubscriptionApp(SubscriptionSource { response }, MemoryStorage())
        app.load("https://example.test/subscription")
        assertEquals(listOf(xhttp, reality), app.state.value.profiles.map { it.link })
        assertEquals("Long name", app.state.value.profiles.first().name)
        assertEquals(1, app.state.value.skipped)
    }

    @Test
    fun rejectsNonHttpsSubscriptionBeforeDownloading() = runTest {
        var downloaded = false
        val app = SubscriptionApp(SubscriptionSource { downloaded = true; SAMPLE_SUBSCRIPTION }, MemoryStorage())
        app.load("http://example.test/subscription")
        assertEquals(false, downloaded)
        assertNotNull(app.state.value.error)
    }

    @Test
    fun oversizedDownloadIsRejectedAndExistingSubscriptionSurvives() = runBlocking {
        val engine = MockEngine { respond("x".repeat(1_048_577), HttpStatusCode.OK) }
        val http = HttpSubscriptionSource(engine)
        var oversized = false
        val app = SubscriptionApp(SubscriptionSource { if (oversized) http.fetch(it) else SAMPLE_SUBSCRIPTION }, MemoryStorage())
        app.load("https://example.test/subscription")
        val link = app.state.value.profiles.single().link
        app.select(link)
        oversized = true
        app.load("https://example.test/subscription")
        assertNotNull(app.state.value.error)
        assertEquals(link, app.state.value.selectedLink)
    }

    @Test
    fun followsRelativeHttpsRedirect() = runBlocking {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested.add(request.url.toString())
            when (request.url.encodedPath) {
                "/start" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/subscription"))
                "/subscription" -> respond(SAMPLE_SUBSCRIPTION, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val app = SubscriptionApp(HttpSubscriptionSource(engine), MemoryStorage())
        app.load("https://example.test/start")
        assertEquals(listOf("https://example.test/start", "https://example.test/subscription"), requested)
        assertEquals("Example", app.state.value.profiles.single().name)
    }

    @Test
    fun rejectsRedirectToHttp() = runBlocking {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://example.test/insecure"))
        }
        val app = SubscriptionApp(HttpSubscriptionSource(engine), MemoryStorage())
        app.load("https://example.test/start")
        assertEquals(1, requests)
        assertNotNull(app.state.value.error)
        Unit
    }

    @Test
    fun keepsSelectionWhenOnlyTheProfileNameChangesAndClearsItWhenProfileDisappears() = runTest {
        val first = "vless://00000000-0000-4000-8000-000000000000@example.test:443?type=xhttp&security=tls#First"
        val renamed = first.substringBefore('#') + "#Renamed"
        val other = "vless://11111111-1111-4111-8111-111111111111@example.test:443?type=xhttp&security=tls#Other"
        var response = Base64.encode(first.encodeToByteArray())
        val app = SubscriptionApp(SubscriptionSource { response }, MemoryStorage())
        app.load("https://example.test/subscription")
        app.select(first)
        response = Base64.encode(renamed.encodeToByteArray())
        app.load("https://example.test/subscription")
        assertEquals(renamed, app.state.value.selectedLink)
        response = Base64.encode(other.encodeToByteArray())
        app.load("https://example.test/subscription")
        assertNull(app.state.value.selectedLink)
    }

    @Test
    fun rejectsUnsupportedModesAndMalformedRealityKeys() = runTest {
        val badMode = "vless://00000000-0000-4000-8000-000000000000@example.test:443?type=xhttp&security=tls&mode=unknown#One"
        val badKey = "vless://11111111-1111-4111-8111-111111111111@example.test:443?type=tcp&security=reality&flow=xtls-rprx-vision&pbk=bad&sni=example.test#Two"
        val response = Base64.encode("$badMode\n$badKey".encodeToByteArray())
        val app = SubscriptionApp(SubscriptionSource { response }, MemoryStorage())
        app.load("https://example.test/subscription")
        assertEquals(emptyList(), app.state.value.profiles)
        assertNotNull(app.state.value.error)
    }

    @Test
    fun decodesProfileNamesExactlyOnce() = runTest {
        val link = "vless://00000000-0000-4000-8000-000000000000@example.test:443?type=xhttp&security=tls#100%25%20ready"
        val app = SubscriptionApp(SubscriptionSource { Base64.encode(link.encodeToByteArray()) }, MemoryStorage())
        app.load("https://example.test/subscription")
        assertEquals("100% ready", app.state.value.profiles.single().name)
    }

    @Test
    fun timeoutPreservesPreviousSubscription() = runTest {
        var timesOut = false
        val app = SubscriptionApp(SubscriptionSource {
            if (timesOut) withTimeout(1) { delay(100); SAMPLE_SUBSCRIPTION } else SAMPLE_SUBSCRIPTION
        }, MemoryStorage())
        app.load("https://example.test/subscription")
        timesOut = true
        app.load("https://example.test/subscription")
        assertEquals("Example", app.state.value.profiles.single().name)
        assertNotNull(app.state.value.error)
    }

    @Test
    fun responseAtTheSizeLimitIsAccepted() = runBlocking {
        val engine = MockEngine { respond("x".repeat(1_048_576), HttpStatusCode.OK) }
        assertEquals(1_048_576, HttpSubscriptionSource(engine).fetch("https://example.test/subscription").length)
    }
}
