package dev.xray.desktop

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.util.Locale
import java.util.UUID

data class ImportPreview(val droppedSections: List<String>)

data class ProfileInfo(val id: String, val name: String)
data class ProfileState(val profiles: List<ProfileInfo> = emptyList(), val selectedId: String? = null, val error: String? = null)

interface ProfileStorage {
    fun read(): String?
    fun write(value: String)
}

private data class StoredProfile(val info: ProfileInfo, val outbound: JsonObject)

class ProfileApp(private val storage: ProfileStorage) {
    private var records = emptyList<StoredProfile>()
    private var selectedId: String? = null
    private var failedToRestore = false
    var state = ProfileState()
        private set

    init {
        try {
            storage.read()?.let { text ->
                val data = JsonParser.parseString(text).asJsonObject
                val restored = data.getAsJsonArray("profiles").map { element ->
                    val item = element.asJsonObject
                    val id = item.get("id").stringOrNull() ?: error("Invalid profile")
                    require(UUID.fromString(id).toString() == id)
                    val name = validName(item.get("name").stringOrNull() ?: error("Invalid profile"))
                    val config = JsonObject().apply { add("outbounds", JsonArray().apply { add(item.get("outbound")) }) }
                    StoredProfile(ProfileInfo(id, name), inspectConfig(config).first)
                }
                require(restored.map { it.info.id }.distinct().size == restored.size)
                val selectedValue = data.get("selectedId")
                val selected = if (selectedValue == null || selectedValue.isJsonNull) null else
                    selectedValue.stringOrNull() ?: error("Invalid selection")
                require(selected == null || restored.any { it.info.id == selected })
                records = restored
                selectedId = selected
                updateState()
            }
        } catch (_: Exception) {
            failedToRestore = true
            state = ProfileState(error = "Could not restore profiles")
        }
    }

    @Synchronized
    fun importProfile(name: String, input: String): ProfileInfo {
        val profile = ProfileInfo(UUID.randomUUID().toString(), validName(name))
        val outbound = inspectImport(input).first
        save(records + StoredProfile(profile, outbound), selectedId)
        return profile
    }

    @Synchronized
    fun select(id: String) {
        require(records.any { it.info.id == id }) { "Profile not found" }
        save(records, id)
    }

    @Synchronized
    fun rename(id: String, name: String) {
        require(records.any { it.info.id == id }) { "Profile not found" }
        val checked = validName(name)
        save(records.map { if (it.info.id == id) it.copy(info = it.info.copy(name = checked)) else it }, selectedId)
    }

    @Synchronized
    fun delete(id: String) {
        require(records.any { it.info.id == id }) { "Profile not found" }
        save(records.filterNot { it.info.id == id }, if (selectedId == id) null else selectedId)
    }

    private fun save(next: List<StoredProfile>, selection: String?) {
        check(!failedToRestore) { "Profile storage is unavailable" }
        val data = JsonObject().apply {
            add("profiles", JsonArray().apply {
                next.forEach { record -> add(JsonObject().apply {
                    addProperty("id", record.info.id)
                    addProperty("name", record.info.name)
                    add("outbound", record.outbound)
                }) }
            })
            addProperty("selectedId", selection)
        }
        try { storage.write(data.toString()) } catch (_: Exception) { throw IllegalStateException("Could not save profiles") }
        records = next
        selectedId = selection
        updateState()
    }

    private fun updateState() { state = ProfileState(records.map { it.info }, selectedId) }

    companion object {
        fun previewImport(input: String): ImportPreview = inspectImport(input).second
    }
}

private fun validName(value: String): String = value.trim().also {
    require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 100 && it.none(Char::isISOControl)) { "Profile name must be 1–100 bytes" }
}

private val knownSections = setOf("inbounds", "routing", "dns", "policy", "log", "api", "stats", "reverse", "transport", "observatory", "burstObservatory", "browserForwarder", "metrics")
private val outboundFields = setOf("protocol", "tag", "settings", "streamSettings", "mux", "targetStrategy")
private val streamFields = setOf("network", "security", "realitySettings", "tlsSettings", "rawSettings", "tcpSettings", "xhttpSettings", "splithttpSettings")
private val forbiddenFields = setOf("proxysettings", "dialerproxy", "sockopt", "sendthrough", "certificates", "masterkeylog", "keylog", "keylogfile", "downloadsettings", "reverse", "finalmask", "browserforwarder", "domainsocket", "interface")

internal fun inspectImport(input: String): Pair<JsonObject, ImportPreview> {
    require(input.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Configuration exceeds 2 MiB" }
    try {
        JsonReader(StringReader(input)).use { reader ->
            reader.strictness = Strictness.STRICT
            checkJson(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        val config = JsonParser.parseString(input).objectOrNull() ?: throw IllegalArgumentException()
        return inspectConfig(config)
    } catch (_: Exception) {
        throw IllegalArgumentException("Configuration is invalid, ambiguous, or uses unsupported dependencies")
    }
}

private fun inspectConfig(config: JsonObject): Pair<JsonObject, ImportPreview> {
    val outbounds = config.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw IllegalArgumentException()
    require(outbounds.size() > 0)
    val outboundObjects = outbounds.map { it.objectOrNull() ?: throw IllegalArgumentException() }
    val selected = outboundObjects.filter {
        val protocol = it.get("protocol").stringOrNull()
        require(!protocol.isNullOrEmpty())
        protocol.equals("vless", ignoreCase = true)
    }.single()
    require(selected.keySet().all { it in outboundFields })
    val stream = selected.get("streamSettings")?.objectOrNull() ?: throw IllegalArgumentException()
    require(stream.keySet().all { it in streamFields })
    stream.get("network")?.let { require(it.stringOrNull() in setOf("raw", "tcp", "xhttp", "splithttp")) }
    stream.get("security")?.let { require(it.stringOrNull() in setOf("reality", "tls", "none", "")) }
    require(!hasUnsafeDependency(selected))
    val dropped = config.keySet().filter { it != "outbounds" }.map { if (it in knownSections) it else "other top-level sections" }.toMutableList()
    if (outbounds.size() > 1) dropped.add("other outbounds")
    return selected.deepCopy().apply { addProperty("protocol", "vless"); addProperty("tag", "selected-vless") } to ImportPreview(dropped.sorted())
}

private fun JsonElement?.objectOrNull(): JsonObject? = if (this != null && isJsonObject) asJsonObject else null
private fun JsonElement?.stringOrNull(): String? = if (this != null && isJsonPrimitive && asJsonPrimitive.isString) asString else null

internal fun checkJson(reader: JsonReader, depth: Int) {
    require(depth <= 64)
    when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val keys = mutableSetOf<String>()
            while (reader.hasNext()) {
                require(keys.add(reader.nextName().lowercase(Locale.ROOT)))
                checkJson(reader, depth + 1)
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            while (reader.hasNext()) checkJson(reader, depth + 1)
            reader.endArray()
        }
        else -> reader.skipValue()
    }
}

private fun hasUnsafeDependency(value: JsonElement): Boolean = when {
    value.isJsonArray -> value.asJsonArray.any(::hasUnsafeDependency)
    value.isJsonObject -> value.asJsonObject.entrySet().any { (key, child) ->
        val name = key.lowercase(Locale.ROOT)
        name in forbiddenFields || name.endsWith("file") || name.endsWith("filepath") ||
            (name == "address" && child.stringOrNull()?.let { '/' in it || '\\' in it || it.startsWith('@') } == true) ||
            hasUnsafeDependency(child)
    }
    else -> false
}
