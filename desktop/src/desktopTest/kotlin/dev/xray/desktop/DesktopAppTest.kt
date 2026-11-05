package dev.xray.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
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

}
