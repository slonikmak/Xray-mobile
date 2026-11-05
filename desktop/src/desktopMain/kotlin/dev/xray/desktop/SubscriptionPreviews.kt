package dev.xray.desktop

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

fun interface SubscriptionSource { suspend fun fetch(url: String): ByteArray }
data class SubscriptionCandidate(val id: String, val name: String, val transport: String)
data class SubscriptionPreviewState(val candidates: List<SubscriptionCandidate> = emptyList(), val expired: Boolean = false)
private data class Candidate(val metadata: SubscriptionCandidate, val config: String)

class SubscriptionPreviews(
    private val profiles: ProfileApp,
    private val source: SubscriptionSource,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val monitor = Any()
    private var generation = 0L
    private var loading: Job? = null
    private var expiry: Job? = null
    private var expiresAt = 0L
    private var candidates = emptyMap<String, Candidate>()
    private val mutableState = MutableStateFlow(SubscriptionPreviewState())
    val state = mutableState.asStateFlow()

    suspend fun load(value: String): List<SubscriptionCandidate> = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        val revision = synchronized(monitor) {
            clearLocked()
            loading = job
            generation
        }
        try {
            val loaded = withTimeout(15_000) {
                val text = value.trim()
                require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
                if (text.startsWith("vless://")) listOf(parseLink(text))
                else { validateSubscriptionUrl(text); decodeSubscription(source.fetch(text)) }
            }
            currentCoroutineContext().ensureActive()
            synchronized(monitor) {
                check(revision == generation) { "Subscription preview canceled or replaced" }
                loading = null
                candidates = loaded.associateBy { it.metadata.id }
                mutableState.value = SubscriptionPreviewState(loaded.map { it.metadata })
                expiresAt = now() + 5 * 60 * 1000
                expiry = scope.launch {
                    delay(5 * 60 * 1000L)
                    synchronized(monitor) { if (revision == generation) clearLocked(expired = true) }
                }
                loaded.map { it.metadata }
            }
        } catch (_: TimeoutCancellationException) {
            throw IllegalArgumentException("Subscription loading timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("Subscription is invalid or could not be loaded")
        } finally {
            synchronized(monitor) { if (revision == generation) loading = null }
        }
    }

    fun clear() { synchronized(monitor) { clearLocked() } }

    fun save(id: String, name: String): ProfileInfo {
        val candidate = synchronized(monitor) {
            if (now() >= expiresAt) clearLocked(expired = true)
            val result = candidates[id] ?: error("Subscription preview expired or changed")
            clearLocked()
            result
        }
        return profiles.importProfile(name, candidate.config)
    }

    private fun clearLocked(expired: Boolean = false) {
        generation++
        loading?.cancel()
        loading = null
        expiry?.cancel()
        expiry = null
        candidates = emptyMap()
        mutableState.value = SubscriptionPreviewState(expired = expired)
        expiresAt = 0
    }
}

internal fun validateSubscriptionUrl(value: String) {
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.isOpaque && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment.isNullOrEmpty())
}

private fun decodeSubscription(body: ByteArray): List<Candidate> {
    require(body.size <= 2 * 1024 * 1024)
    var content = decodeUtf8(body).trim()
    if (!content.startsWith("vless://")) {
        val encoded = content.filterNot(Char::isWhitespace)
        val decoded = sequenceOf(Base64.getDecoder() to Base64.getEncoder(), Base64.getUrlDecoder() to Base64.getUrlEncoder()).mapNotNull { (decoder, encoder) ->
            runCatching { decoder.decode(encoded) }.getOrNull()?.takeIf { bytes ->
                encoder.withoutPadding().encodeToString(bytes) == encoded.trimEnd('=')
            }
        }.firstOrNull() ?: throw IllegalArgumentException()
        content = decodeUtf8(decoded).trim()
    }
    val lines = content.lines().map(String::trim).filter(String::isNotEmpty)
    require(lines.isNotEmpty() && lines.size <= 100)
    return lines.map(::parseLink)
}

private fun parseLink(link: String): Candidate {
    require(link.toByteArray(Charsets.UTF_8).size <= 64 * 1024 && link.none(Char::isWhitespace))
    val uri = URI(link)
    require(uri.scheme == "vless" && !uri.isOpaque && uri.rawPath.isNullOrEmpty() && !uri.rawUserInfo.isNullOrEmpty())
    require(':' !in uri.rawUserInfo)
    val userId = decodeComponent(uri.rawUserInfo)
    require(userId.isNotEmpty() && userId.none(Char::isISOControl))
    val authority = uri.rawAuthority.substringAfterLast('@')
    val host: String
    val portText: String
    if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        require(end > 0 && authority.getOrNull(end + 1) == ':')
        host = authority.substring(1, end)
        portText = authority.substring(end + 2)
        require(':' in host && '%' !in host && InetAddress.getByName(host).address.size == 16)
    } else {
        val colon = authority.lastIndexOf(':')
        require(colon > 0)
        host = authority.substring(0, colon)
        portText = authority.substring(colon + 1)
    }
    require(host.isNotEmpty() && host.none { it.code > 127 || it.isISOControl() || it in "/\\@%" } && (':' !in host || authority.startsWith('[')))
    val port = portText.toIntOrNull() ?: throw IllegalArgumentException()
    require(port in 1..65535)
    val query = uri.rawQuery.orEmpty()
    require('+' !in query && ';' !in query)
    val parameters = mutableMapOf<String, String>()
    val allowed = setOf("type", "security", "encryption", "flow", "sni", "fp", "alpn", "pbk", "sid", "spx", "pqv", "path", "host", "mode", "extra", "ech", "pcs", "vcn")
    if (query.isNotEmpty()) for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val pieces = part.split('=', limit = 2)
        val key = decodeComponent(pieces[0])
        val value = decodeComponent(pieces.getOrElse(1) { "" })
        require(key in allowed && value.none(Char::isISOControl) && parameters.put(key, value) == null)
    }
    var network = parameters["type"] ?: "raw"
    if (network == "tcp") network = "raw"
    require(network == "raw" || network == "xhttp")
    val security = parameters["security"]
    require(security == "reality" || (network == "xhttp" && security == "tls"))
    require(parameters["encryption"] == null || parameters["encryption"] == "none")
    parameters["flow"]?.let { require(it.isEmpty() || (it == "xtls-rprx-vision" && network == "raw")) }
    val user = JsonObject().apply {
        addProperty("id", userId); addProperty("encryption", "none")
        parameters["flow"]?.let { addProperty("flow", it) }
    }
    val tls = JsonObject().apply {
        addProperty("fingerprint", parameters["fp"] ?: "chrome")
        for ((param, field) in mapOf("sni" to "serverName", "fp" to "fingerprint")) {
            parameters[param]?.let { require(it.isNotEmpty()); addProperty(field, it) }
        }
        parameters["alpn"]?.let { value ->
            val values = value.split(',')
            require(values.all { it.isNotEmpty() && it.none(Char::isWhitespace) })
            add("alpn", JsonArray().apply { values.forEach(::add) })
        }
    }
    if (security == "reality") {
        require(!parameters["pbk"].isNullOrEmpty() && !parameters["fp"].isNullOrEmpty())
        require(listOf("ech", "pcs", "vcn", "alpn").none(parameters::containsKey))
        for ((param, field) in mapOf("pbk" to "password", "sid" to "shortId", "spx" to "spiderX", "pqv" to "mldsa65Verify")) parameters[param]?.let { tls.addProperty(field, it) }
    } else {
        require(listOf("pbk", "sid", "spx", "pqv").none(parameters::containsKey))
        for ((param, field) in mapOf("ech" to "echConfigList", "pcs" to "pinnedPeerCertSha256", "vcn" to "verifyPeerCertByName")) parameters[param]?.let { tls.addProperty(field, it) }
    }
    val stream = JsonObject().apply {
        addProperty("network", network); addProperty("security", security); add("${security}Settings", tls)
        if (network == "xhttp") {
            add("xhttpSettings", JsonObject().apply {
                for (param in listOf("path", "host", "mode")) parameters[param]?.let { require(it.isNotEmpty()); addProperty(param, it) }
                parameters["mode"]?.let { require(it in setOf("auto", "packet-up", "stream-up", "stream-one")) }
                parameters["extra"]?.let { add("extra", parseExtra(it)) }
            })
        } else require(listOf("path", "host", "mode", "extra").none(parameters::containsKey))
    }
    val server = JsonObject().apply {
        addProperty("address", host); addProperty("port", port)
        add("users", JsonArray().apply { add(user) })
    }
    val config = JsonObject().apply { add("outbounds", JsonArray().apply { add(JsonObject().apply {
        addProperty("protocol", "vless"); add("settings", JsonObject().apply { add("vnext", JsonArray().apply { add(server) }) })
        add("streamSettings", stream)
    }) }) }
    inspectImport(config.toString())
    val rawName = uri.rawFragment?.let(::decodeComponent).orEmpty().ifEmpty { "Imported VLESS" }
    require(rawName.codePointCount(0, rawName.length) <= 80 && rawName.none(Char::isISOControl))
    val name = rawName.trim()
    require(name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= 100)
    return Candidate(SubscriptionCandidate(UUID.randomUUID().toString(), name, "${network.uppercase()}/${security.uppercase()}"), config.toString())
}

private fun parseExtra(input: String): JsonObject {
    JsonReader(StringReader(input)).use { reader ->
        reader.strictness = Strictness.STRICT
        checkJson(reader, 0)
        require(reader.peek() == JsonToken.END_DOCUMENT)
    }
    val extra = JsonParser.parseString(input).asJsonObject
    require(extra.keySet().all { it == "xmux" })
    extra.get("xmux")?.let { raw ->
        val xmux = raw.asJsonObject
        val fields = setOf("maxConcurrency", "maxConnections", "cMaxReuseTimes", "hMaxRequestTimes", "hMaxReusableSecs", "hKeepAlivePeriod")
        require(xmux.keySet().all { it in fields })
        for ((key, value) in xmux.entrySet()) {
            require(value.isJsonPrimitive)
            val primitive = value.asJsonPrimitive
            if (primitive.isNumber) {
                val n = primitive.asString.toLongOrNull() ?: throw IllegalArgumentException()
                require(n >= 0 || key == "hKeepAlivePeriod")
            } else {
                require(primitive.isString && key != "hKeepAlivePeriod")
                val parts = primitive.asString.split('-')
                require(parts.size == 2 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) })
                val a = parts[0].toLongOrNull() ?: throw IllegalArgumentException()
                val b = parts[1].toLongOrNull() ?: throw IllegalArgumentException()
                require(a <= b && b <= 0xffffffffL)
            }
        }
    }
    return extra
}

private fun decodeComponent(value: String): String {
    val result = ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            require(index + 2 < value.length)
            result.write(value.substring(index + 1, index + 3).toInt(16))
            index += 3
        } else {
            val codePoint = value.codePointAt(index)
            result.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            index += Character.charCount(codePoint)
        }
    }
    return decodeUtf8(result.toByteArray())
}

private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()
