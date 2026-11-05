package dev.xray.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class ProfileAppTest {
    @Test
    fun importPreviewListsDiscardedSectionsWithoutExposingTheirContents() {
        val preview = ProfileApp.previewImport(
            """{"dns":{"secret":"private"},"outbounds":[{"protocol":"freedom"},{"protocol":"vless","streamSettings":{"network":"raw"}}],"private-key":"private"}"""
        )
        assertEquals(listOf("dns", "other outbounds", "other top-level sections"), preview.droppedSections)
        assertFalse(preview.toString().contains("private"))
    }
    @Test
    fun importRejectsAmbiguousAndUnsafeConfigurations() {
        val invalid = listOf(
            "null", "[]", "{}", "{\"outbounds\":[]}",
            """{"outbounds":[{"protocol":"vless","streamSettings":{}},{"protocol":"vless","streamSettings":{}}]}""",
            """{"outbounds":[{"protocol":"vless","Protocol":"freedom","streamSettings":{}}]}""",
            """{"outbounds":[{"protocol":"vless","streamSettings":{"network":"raw","Network":"xhttp"}}]}""",
            """{"outbounds":[{"protocol":"vless","streamSettings":{"tlsSettings":{"certificateFile":"private"}}}]}""",
            """{"outbounds":[{"protocol":"vless","streamSettings":{"network":"ws"}}]}""",
            """{"outbounds":[{"protocol":"vless","streamSettings":{},"settings":{"vnext":[{"address":"@local"}]}}]}""",
            """{"outbounds":[{"protocol":"vless","streamSettings":{"xhttpSettings":{"downloadSettings":{}}}}]}""",
            "[".repeat(70) + "0" + "]".repeat(70),
            " ".repeat(2 * 1024 * 1024 + 1),
            """{"outbounds":[{"protocol":"vless","streamSettings":{}}]} {}""",
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { ProfileApp.previewImport(it) } }
    }

    @Test
    fun profilesSurviveRestartAndFailedWritesLeavePreviousStateIntact() {
        val storage = MemoryStorage()
        val app = ProfileApp(storage)
        val json = """{"outbounds":[{"protocol":"vless","settings":{"id":"private-token"},"streamSettings":{}}]}"""
        val first = app.importProfile("First", json)
        val second = app.importProfile("Second", json)
        app.select(first.id)
        app.rename(first.id, "Renamed")
        val restored = ProfileApp(storage)
        assertEquals(listOf("Renamed", "Second"), restored.state.profiles.map { it.name })
        assertEquals(first.id, restored.state.selectedId)
        assertFalse(restored.state.toString().contains("private-token"))
        storage.failWrites = true
        assertFailsWith<IllegalStateException> { restored.delete(first.id) }
        assertEquals(first.id, restored.state.selectedId)
        storage.failWrites = false
        restored.delete(first.id)
        assertEquals(null, restored.state.selectedId)
        assertEquals(listOf(second.id), ProfileApp(storage).state.profiles.map { it.id })
    }

    @Test
    fun corruptStorageIsNotOverwritten() {
        val storage = MemoryStorage().apply { bytes = "corrupt" }
        val app = ProfileApp(storage)
        assertEquals("Could not restore profiles", app.state.error)
        assertFailsWith<IllegalStateException> {
            app.importProfile("Example", """{"outbounds":[{"protocol":"vless","streamSettings":{}}]}""")
        }
        assertEquals("corrupt", storage.bytes)
    }

    @Test
    fun largestAllowedImportCanBeRestored() {
        val storage = MemoryStorage()
        val empty = """{"outbounds":[{"protocol":"vless","settings":{"value":""},"streamSettings":{}}]}"""
        val input = empty.replace("\"value\":\"\"", "\"value\":\"" + "x".repeat(2 * 1024 * 1024 - empty.length) + "\"")
        val app = ProfileApp(storage)
        app.importProfile("Large", input)
        assertEquals(listOf("Large"), ProfileApp(storage).state.profiles.map { it.name })
    }

    @Test
    fun activeProfileCannotBeDeleted() {
        var active: String? = null
        val app = ProfileApp(MemoryStorage()) { it == active }
        val profile = app.importProfile("Example", """{"outbounds":[{"protocol":"vless","streamSettings":{}}]}""")
        active = profile.id
        assertFailsWith<IllegalStateException> { app.delete(profile.id) }
        assertEquals(listOf(profile), app.state.profiles)
        active = null
        app.delete(profile.id)
        assertEquals(emptyList(), app.state.profiles)
    }

    private class MemoryStorage : ProfileStorage {
        var bytes: String? = null
        var failWrites = false
        override fun read(): String? = bytes
        override fun write(value: String) {
            check(!failWrites)
            bytes = value
        }
    }

}
