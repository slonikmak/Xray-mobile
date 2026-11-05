package dev.xray.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import org.junit.Rule
import org.junit.Test

class DesktopAppTest {
    private val unavailableApp = ProfileApp(object : ProfileStorage {
        override fun read(): String? = error("unavailable")
        override fun write(value: String) = error("unavailable")
    })

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unavailableRecoveryCanBeRetriedWithoutClaimingStopped() {
        var retried = false
        compose.setContent { DesktopApp(unavailableApp, recoveryPending = true, recoveryUnavailable = true, retryRecovery = { retried = true }) }
        compose.onNodeWithText("Unconfirmed").assertIsDisplayed()
        compose.onNodeWithText("Retry recovery").performClick()
        kotlin.test.assertTrue(retried)
    }

    @Test
    fun missingRuntimeRequestsRestartInsteadOfOfferingIneffectiveRetry() {
        var exited = false
        compose.setContent { DesktopApp(unavailableApp, recoveryPending = true, recoveryUnavailable = true, recoveryRetryable = false,
            exitWithoutRecovery = { exited = true }) }
        compose.onNodeWithText("Bundled runtime is unavailable; previous session cleanup is not confirmed. Restart the packaged app to recover.").assertIsDisplayed()
        compose.onAllNodesWithText("Retry recovery").assertCountEquals(0)
        compose.onNodeWithText("Exit without recovery").performClick()
        kotlin.test.assertTrue(exited)
    }

    @Test
    fun recoveryDoesNotClaimStoppedOrAllowProfileDeletion() {
        val app = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val profile = app.importProfile("Example", """{"outbounds":[{"protocol":"vless","streamSettings":{}}]}""")
        app.select(profile.id)
        compose.setContent { DesktopApp(app, recoveryPending = true, recoveryFailed = true) }
        compose.onNodeWithText("Unconfirmed").assertIsDisplayed()
        compose.onNodeWithText("Could not recover the previous session. Retrying; check runtime files and permissions.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Details").performClick()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.onNodeWithText("Proxy stop is not confirmed").assertIsDisplayed()
    }

    @Test
    fun firstFailedCheckIsVisibleWhileWaitingForRetry() {
        val app = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val profile = app.importProfile("Example", """{"outbounds":[{"protocol":"vless","streamSettings":{}}]}""")
        app.select(profile.id)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = object : ProxyProcess {
                override suspend fun awaitExit(): Unit = kotlinx.coroutines.awaitCancellation()
                override suspend fun stop() = Unit
                override suspend fun cleanup() = Unit
            }
            override suspend fun check() = false
        }, scope)
        try {
            compose.setContent { DesktopApp(app, proxySession = session) }
            compose.onNodeWithContentDescription("Connect").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Failed · retry pending")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Latest check failed; retry scheduled").assertIsDisplayed()
        } finally { scope.cancel() }
    }

    @Test
    fun userCanNavigateEmptyScreens() {
        compose.setContent { DesktopApp(unavailableApp) }
        compose.onNodeWithText("Proxy is off").assertIsDisplayed()
        compose.onNodeWithText("Not running").assertIsDisplayed()
        compose.onNodeWithText("Not verified").assertIsDisplayed()
        compose.onNodeWithText("No profile selected").performClick()
        compose.onNodeWithText("No profiles yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Details").performClick()
        compose.onNodeWithText("No profile selected").assertIsDisplayed()
        compose.onNodeWithText("Runtime not available").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Proxy is off").assertIsDisplayed()
    }
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keyboardCanOpenDetailsAndUnavailableActionsAreDisabled() {
        compose.setContent { DesktopApp(unavailableApp) }
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Details").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Runtime not available").assertIsDisplayed()
        compose.onNodeWithText("Import profile").assertIsNotEnabled()
    }

    @Test
    fun userCanReviewAndImportAProfile() {
        val app = ProfileApp(object : ProfileStorage {
            private var content: String? = null
            override fun read() = content
            override fun write(value: String) { content = value }
        })
        compose.setContent { DesktopApp(app) }
        compose.onNodeWithText("No profile selected").performClick()
        compose.onNodeWithText("Import profile").performClick()
        compose.onNodeWithContentDescription("Profile name").performTextInput("Work")
        compose.onNodeWithContentDescription("Xray configuration").performTextInput(
            """{"dns":{},"outbounds":[{"protocol":"vless","streamSettings":{}}]}"""
        )
        compose.onNodeWithText("Review import").performClick()
        compose.onNodeWithText("dns").assertIsDisplayed()
        compose.onNodeWithText("Save profile").performClick()
        compose.onNodeWithText("Work").assertIsDisplayed()
    }

    @Test
    fun cancelingAReviewedImportDoesNotSaveIt() {
        val app = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        compose.setContent { DesktopApp(app) }
        compose.onNodeWithText("No profile selected").performClick()
        compose.onNodeWithText("Import profile").performClick()
        compose.onNodeWithContentDescription("Profile name").performTextInput("Unsaved")
        compose.onNodeWithContentDescription("Xray configuration").performTextInput(
            """{"outbounds":[{"protocol":"vless","streamSettings":{}}]}"""
        )
        compose.onNodeWithText("Review import").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Unsaved").assertDoesNotExist()
        compose.onNodeWithText("No profiles yet").assertIsDisplayed()
    }

    @Test
    fun renameFailureIsVisibleInsideTheDialog() {
        val app = ProfileApp(object : ProfileStorage {
            private var content: String? = null
            override fun read() = content
            override fun write(value: String) { content = value }
        })
        val profile = app.importProfile("Work", """{"outbounds":[{"protocol":"vless","streamSettings":{}}]}""")
        app.select(profile.id)
        compose.setContent { DesktopApp(app) }
        compose.onNodeWithContentDescription("Details").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Save").performClick()
        compose.onNode(hasText("Profile name must be 1–100 bytes") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun deletionRequiresConfirmationAndClearsSelection() {
        val app = ProfileApp(object : ProfileStorage {
            private var content: String? = null
            override fun read() = content
            override fun write(value: String) { content = value }
        })
        val name = "Long profile " + "x".repeat(80)
        val profile = app.importProfile(name, """{"outbounds":[{"protocol":"vless","streamSettings":{}}]}""")
        app.select(profile.id)
        compose.setContent { DesktopApp(app) }
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithContentDescription("Details").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("No profile selected").assertIsDisplayed()
    }

    @Test
    fun userCanPreviewAndSaveOneSubscriptionCandidate() {
        val app = ProfileApp(object : ProfileStorage {
            private var content: String? = null
            override fun read() = content
            override fun write(value: String) { content = value }
        })
        val source = SubscriptionSource { "vless://id@example.invalid:443?type=xhttp&security=tls#Synthetic".toByteArray() }
        compose.setContent { DesktopApp(app, source) }
        compose.onNodeWithText("No profile selected").performClick()
        compose.onNodeWithText("Import profile").performClick()
        compose.onNodeWithText("Xray JSON").performClick()
        compose.onNodeWithText("Subscription or VLESS").performClick()
        compose.onNodeWithContentDescription("Subscription or VLESS link").performTextInput("https://example.invalid/subscription")
        compose.onNodeWithText("Load profiles").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Synthetic")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Synthetic").performClick()
        compose.onNodeWithText("XHTTP/TLS").assertIsSelected()
        compose.onNodeWithText("Subscription or VLESS").performClick()
        compose.onAllNodesWithText("Subscription or VLESS")[1].performClick()
        compose.onNodeWithText("XHTTP/TLS").assertIsSelected()
        compose.onNodeWithText("Save profile").performClick()
        compose.onNodeWithText("Synthetic").assertIsDisplayed()
    }

    @Test
    fun changingTheSourcePreventsAnOldResultFromReplacingTheNewOne() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val app = ProfileApp(object : ProfileStorage {
            private var content: String? = null
            override fun read() = content
            override fun write(value: String) { content = value }
        })
        val source = SubscriptionSource { url ->
            if (url.endsWith("old")) {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                "vless://id@example.invalid:443?type=xhttp&security=tls#Old".toByteArray()
            } else "vless://id@example.invalid:443?type=xhttp&security=tls#New".toByteArray()
        }
        compose.setContent { DesktopApp(app, source) }
        compose.onNodeWithText("No profile selected").performClick()
        compose.onNodeWithText("Import profile").performClick()
        compose.onNodeWithText("Xray JSON").performClick()
        compose.onNodeWithText("Subscription or VLESS").performClick()
        compose.onNodeWithContentDescription("Subscription or VLESS link").performTextInput("https://example.invalid/old")
        compose.onNodeWithText("Load profiles").performClick()
        compose.waitUntil(5_000) { started.isCompleted }
        compose.onNodeWithContentDescription("Subscription or VLESS link").performTextReplacement("https://example.invalid/new")
        compose.onNodeWithText("Load profiles").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("New")).fetchSemanticsNodes().isNotEmpty() }
        release.complete(Unit)
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Save profile").performClick()
        compose.onNodeWithText("New").assertIsDisplayed()
        compose.onNodeWithText("Old").assertDoesNotExist()
    }

    @Test
    fun failedSubscriptionSaveClearsTheSelectionAndAsksToReload() {
        val storage = object : ProfileStorage {
            var fail = true
            override fun read(): String? = null
            override fun write(value: String) { check(!fail) }
        }
        val app = ProfileApp(storage)
        val source = SubscriptionSource { "vless://id@example.invalid:443?type=xhttp&security=tls#Synthetic".toByteArray() }
        compose.setContent { DesktopApp(app, source) }
        compose.onNodeWithText("No profile selected").performClick()
        compose.onNodeWithText("Import profile").performClick()
        compose.onNodeWithText("Xray JSON").performClick()
        compose.onNodeWithText("Subscription or VLESS").performClick()
        compose.onNodeWithContentDescription("Subscription or VLESS link").performTextInput("https://example.invalid/subscription")
        compose.onNodeWithText("Load profiles").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Synthetic")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Synthetic").performClick()
        compose.onNodeWithText("Save profile").performClick()
        compose.onNodeWithText("Load profiles again to retry.").assertIsDisplayed()
        compose.onNodeWithText("Synthetic").assertDoesNotExist()
        storage.fail = false
        compose.onNodeWithText("Load profiles").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Synthetic")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Could not save profiles").assertDoesNotExist()
    }

    @Test
    fun reloadingImmediatelyRemovesOldCandidates() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val app = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        var calls = 0
        val source = SubscriptionSource {
            calls++
            if (calls == 2) { started.complete(Unit); release.await() }
            "vless://id@example.invalid:443?type=xhttp&security=tls#Old".toByteArray()
        }
        compose.setContent { DesktopApp(app, source) }
        compose.onNodeWithText("No profile selected").performClick()
        compose.onNodeWithText("Import profile").performClick()
        compose.onNodeWithText("Xray JSON").performClick()
        compose.onNodeWithText("Subscription or VLESS").performClick()
        compose.onNodeWithContentDescription("Subscription or VLESS link").performTextInput("https://example.invalid/")
        compose.onNodeWithText("Load profiles").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Old")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Load profiles").performClick()
        compose.waitUntil(5_000) { started.isCompleted }
        compose.onNodeWithText("Old").assertDoesNotExist()
        compose.onNodeWithText("Cancel loading").performClick()
        release.complete(Unit)
    }

    @Test
    fun connectionShowsProcessAndHealthAndCanDisconnect() {
        val app = ProfileApp(object : ProfileStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        val profile = app.importProfile("Example", """{"outbounds":[{"protocol":"vless","streamSettings":{}}]}""")
        app.select(profile.id)
        val exited = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ProxySession(object : ProxyBackend {
            override suspend fun start(profileId: String, cancellation: StartupCancellation) = object : ProxyProcess {
                override suspend fun awaitExit() { exited.await() }
                override suspend fun stop() { exited.complete(Unit); exited.await() }
                override suspend fun cleanup() = Unit
            }
            override suspend fun check() = true
        }, scope)
        try {
            compose.setContent { DesktopApp(app, proxySession = session) }
            compose.onNodeWithContentDescription("Connect").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Reachable")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Running").assertIsDisplayed()
            compose.onNodeWithContentDescription("Details").performClick()
            compose.onNodeWithText("SOCKS5 127.0.0.1:10808 · HTTP 127.0.0.1:10809").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Disconnect").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Proxy is off")).fetchSemanticsNodes().isNotEmpty() }
        } finally { scope.cancel() }
    }

}
