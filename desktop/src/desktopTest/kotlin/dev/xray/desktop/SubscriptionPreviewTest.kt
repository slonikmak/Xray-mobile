package dev.xray.desktop

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import java.util.Base64
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class SubscriptionPreviewTest {
    @Test
    fun explicitLinkCanBePreviewedAndSavedOnceWithoutExposingCredentials() = runTest {
        val storage = object : ProfileStorage {
            var content: String? = null
            override fun read() = content
            override fun write(value: String) { content = value }
        }
        val profiles = ProfileApp(storage)
        val previews = SubscriptionPreviews(profiles, SubscriptionSource { error("No network expected") }, this)
        val candidates = previews.load("vless://private-id@example.invalid:443?type=xhttp&security=tls#Synthetic")
        assertEquals("Synthetic", candidates.single().name)
        assertEquals("XHTTP/TLS", candidates.single().transport)
        assertFalse(candidates.toString().contains("private-id"))
        assertEquals(0, profiles.state.profiles.size)
        previews.save(candidates.single().id, "Saved")
        assertEquals(listOf("Saved"), profiles.state.profiles.map { it.name })
        assertEquals(null, profiles.state.selectedId)
        assertFailsWith<IllegalStateException> { previews.save(candidates.single().id, "Again") }
    }
    @Test
    fun encodedSubscriptionPreservesTransportAndExpires() = runTest {
        val storage = object : ProfileStorage {
            var content: String? = null
            override fun read() = content
            override fun write(value: String) { content = value }
        }
        var time = 0L
        val link = "vless://id@example.invalid:443?type=raw&security=reality&pbk=key&fp=chrome&flow=xtls-rprx-vision#First"
        val second = "vless://id2@example.invalid:443?type=xhttp&security=tls&sni=example.invalid&path=%2Ftest&mode=packet-up#Second"
        val body = Base64.getEncoder().encode("$link\n$second".toByteArray())
        val app = SubscriptionPreviews(ProfileApp(storage), SubscriptionSource { body }, this) { time }
        val candidates = app.load("https://example.invalid/subscription")
        assertEquals(listOf("RAW/REALITY", "XHTTP/TLS"), candidates.map { it.transport })
        app.save(candidates[1].id, "Second")
        assertTrue(storage.content!!.contains("packet-up"))
        assertFalse(storage.content!!.contains("id@example.invalid"))
        val again = app.load(link)
        time = 300_001
        assertFailsWith<IllegalStateException> { app.save(again.single().id, "Expired") }
    }

    @Test
    fun invalidSourcesAndAmbiguousLinksAreRejectedWithoutLeakingTheirContents() = runTest {
        val profiles = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val app = SubscriptionPreviews(profiles, SubscriptionSource { error("Network must not be called") }, this)
        for (input in listOf(
            "http://example.invalid/private", "https://user:private@example.invalid/", "https://example.invalid/#private",
            "vless://private@example.invalid:443?type=xhttp&type=raw&security=tls",
            "vless://private@example.invalid:443?type=xhttp&security=tls&allowInsecure=1",
            "vless://private@example.invalid:443?type=xhttp&security=tls&path=%2Ffoo;bar",
            "vless://private@example.invalid:443?type=raw&security=reality&pbk=key",
        )) {
            val error = assertFailsWith<IllegalArgumentException> { app.load(input) }
            assertFalse(error.toString().contains("private"))
        }
    }

    @Test
    fun cancelAndReplacementPreventLateResultsFromBeingImported() = runTest {
        val profiles = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val link = "vless://id@example.invalid:443?type=xhttp&security=tls#Late"
        val app = SubscriptionPreviews(profiles, SubscriptionSource {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            link.toByteArray()
        }, this)
        val old = async { runCatching { app.load("https://example.invalid/secret") } }
        started.await()
        app.clear()
        val fresh = app.load("vless://new@example.invalid:443?type=xhttp&security=tls#New")
        release.complete(Unit)
        yield()
        assertTrue(old.await().isFailure)
        app.save(fresh.single().id, "New")
        assertEquals(listOf("New"), profiles.state.profiles.map { it.name })
    }

    @Test
    fun responseAndLinkLimitsAreEnforced() = runTest {
        val profiles = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val link = "vless://id@example.invalid:443?type=xhttp&security=tls#Profile"
        val tooMany = SubscriptionPreviews(profiles, SubscriptionSource { (link + "\n").repeat(101).toByteArray() }, this)
        assertFailsWith<IllegalArgumentException> { tooMany.load("https://example.invalid/") }
        val tooLarge = SubscriptionPreviews(profiles, SubscriptionSource { ByteArray(2 * 1024 * 1024 + 1) }, this)
        assertFailsWith<IllegalArgumentException> { tooLarge.load("https://example.invalid/") }
        assertFailsWith<IllegalArgumentException> { tooMany.load(link + "x".repeat(65536)) }
    }

    @Test
    fun networkSourceRejectsUnsafeUrlsBeforeConnecting() = runTest {
        val source = HttpSubscriptionSource()
        for (url in listOf("http://example.invalid/private", "https://user:private@example.invalid/", "https://example.invalid/#private")) {
            val failure = assertFailsWith<IllegalArgumentException> { source.fetch(url) }
            assertFalse(failure.toString().contains("private"))
        }
    }

    @Test
    fun supportsRealityIpv6AndStrictXmuxAndRejectsUnsafeParameters() = runTest {
        val profiles = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val app = SubscriptionPreviews(profiles, SubscriptionSource { error("No network expected") }, this)
        val reality = "vless://id@[2001:db8::1]:443?type=tcp&security=reality&pbk=key&fp=chrome&sid=abcd#IPv6"
        assertEquals("RAW/REALITY", app.load(reality).single().transport)
        val xmux = "vless://id@example.invalid:443?type=xhttp&security=tls&extra=%7B%22xmux%22%3A%7B%22maxConcurrency%22%3A%2216-32%22%7D%7D"
        assertEquals("XHTTP/TLS", app.load(xmux).single().transport)
        for (suffix in listOf("&unknown=secret", "&path=%FF", "&extra=%7B%22xmux%22%3A%7B%7D%2C%22XMUX%22%3A%7B%7D%7D")) {
            assertFailsWith<IllegalArgumentException> { app.load("vless://id@example.invalid:443?type=xhttp&security=tls$suffix") }
        }
    }

    @Test
    fun timeoutInvalidatesExistingPreview() = runTest {
        val profiles = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val app = SubscriptionPreviews(profiles, SubscriptionSource { delay(16_000); byteArrayOf() }, this)
        val previous = app.load("vless://id@example.invalid:443?type=xhttp&security=tls")
        assertFailsWith<IllegalArgumentException> { app.load("https://example.invalid/") }
        assertFailsWith<IllegalStateException> { app.save(previous.single().id, "Expired") }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun expiryClearsVisibleCandidatesWithoutWaitingForSave() = runTest {
        val profiles = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val app = SubscriptionPreviews(profiles, SubscriptionSource { byteArrayOf() }, this)
        app.load("vless://id@example.invalid:443?type=xhttp&security=tls#Example")
        assertEquals(1, app.state.value.candidates.size)
        advanceTimeBy(300_001)
        runCurrent()
        assertEquals(emptyList(), app.state.value.candidates)
        assertTrue(app.state.value.expired)
    }

}
