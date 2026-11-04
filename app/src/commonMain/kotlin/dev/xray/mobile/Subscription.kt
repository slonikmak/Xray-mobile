package dev.xray.mobile

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class Profile(val name: String, val link: String)

@Serializable
data class SubscriptionState(
    val url: String = "",
    val profiles: List<Profile> = emptyList(),
    val selectedLink: String? = null,
    val skipped: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

fun interface SubscriptionSource { suspend fun fetch(url: String): String }
interface SubscriptionStorage {
    suspend fun read(): String?
    suspend fun write(value: String)
}

class SubscriptionApp(private val source: SubscriptionSource, private val storage: SubscriptionStorage) {
    private val mutableState = MutableStateFlow(SubscriptionState())
    val state = mutableState.asStateFlow()

    suspend fun restore() {
        try {
            storage.read()?.let { mutableState.value = Json.decodeFromString(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.value = state.value.copy(error = "Не удалось восстановить сохранённую подписку.")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun load(url: String): Boolean {
        if (state.value.loading) return false
        val previous = state.value.copy(loading = false, error = null)
        mutableState.value = previous.copy(loading = true)
        return try {
            val address = url.trim()
            if (!isHttpsAddress(address)) throw SubscriptionError("Введите корректный HTTPS URL подписки.")
            val encoded = source.fetch(address).filterNot { it.isWhitespace() }
            val decoded = try {
                (if ('-' in encoded || '_' in encoded) Base64.UrlSafe else Base64).decode(encoded)
                    .decodeToString(throwOnInvalidSequence = true)
            } catch (_: Exception) {
                throw SubscriptionError("Неверный формат подписки.")
            }
            val lines = decoded.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val profiles = lines.mapNotNull(::parseProfile).distinctBy { profileIdentity(it.link) }
            if (profiles.isEmpty()) throw SubscriptionError("В подписке нет поддерживаемых профилей.")
            val next = SubscriptionState(url = address, profiles = profiles, skipped = lines.size - profiles.size,
                selectedLink = previous.selectedLink?.let { selected ->
                    profiles.firstOrNull { profileIdentity(it.link) == profileIdentity(selected) }?.link
                })
            currentCoroutineContext().ensureActive()
            storage.write(Json.encodeToString(next))
            mutableState.value = next
            true
        } catch (e: TimeoutCancellationException) {
            mutableState.value = previous
            currentCoroutineContext().ensureActive()
            mutableState.value = previous.copy(error = "Время загрузки подписки истекло. Попробуйте снова.")
            false
        } catch (e: CancellationException) {
            mutableState.value = previous
            throw e
        } catch (e: Exception) {
            mutableState.value = previous.copy(error = (e as? SubscriptionError)?.message
                ?: "Не удалось загрузить подписку. Проверьте ссылку и попробуйте снова.")
            false
        }
    }

    suspend fun select(link: String) {
        if (state.value.loading || state.value.profiles.none { it.link == link }) return
        val next = state.value.copy(selectedLink = link)
        try {
            storage.write(Json.encodeToString(next))
            mutableState.value = next
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.value = state.value.copy(error = "Не удалось сохранить выбор профиля.")
        }
    }
}

private data class ProfileIdentity(val user: String?, val host: String, val port: Int, val parameters: Map<String, List<String>>)

private fun profileIdentity(link: String): ProfileIdentity {
    val url = Url(link)
    return ProfileIdentity(url.user, url.host.lowercase(), url.port, url.parameters.entries().associate { it.key to it.value })
}

class SubscriptionError(message: String) : Exception(message)

fun isHttpsAddress(address: String): Boolean = try {
    val url = Url(address)
    address.startsWith("https://", ignoreCase = true) && url.protocol.name == "https" &&
        url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
        url.fragment.isEmpty() && url.port in 1..65535 && !address.any { it.isWhitespace() }
} catch (_: Exception) {
    false
}

@OptIn(ExperimentalEncodingApi::class)
private fun parseProfile(link: String): Profile? {
    return try {
    val url = Url(link)
    val params = url.parameters
    val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    val allowed = setOf("type", "security", "encryption", "flow", "sni", "fp", "alpn", "host", "path", "mode", "extra", "pbk", "sid", "spx", "headerType")
    val fingerprints = setOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "randomizednoalpn", "unsafe")
    val fingerprintValid = params["fp"] == null || params["fp"] in fingerprints
    val extraValid = params["extra"]?.let {
        runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.isEmpty() == true }.getOrDefault(false)
    } ?: true
    val supported = when (params["type"] to params["security"]) {
        "xhttp" to "tls" -> params["flow"].isNullOrEmpty() &&
            params["mode"] in listOf(null, "auto", "packet-up", "stream-up", "stream-one") &&
            listOf("pbk", "sid", "spx", "headerType").none { it in params }
        "tcp" to "reality" -> {
            val publicKey = params["pbk"].orEmpty().removeSuffix("=")
            val keyValid = Regex("[A-Za-z0-9_-]{43}").matches(publicKey) &&
                runCatching { Base64.UrlSafe.decode("$publicKey=").size == 32 }.getOrDefault(false)
            params["flow"] == "xtls-rprx-vision" && keyValid && !params["sni"].isNullOrBlank() &&
                (params["sid"] == null || Regex("(?:[0-9a-fA-F]{2}){0,8}").matches(params["sid"].orEmpty())) &&
                params["headerType"] in listOf(null, "none") && listOf("mode", "path", "host", "extra").none { it in params }
        }
        else -> false
    }
    if (url.protocol.name != "vless" || url.host.isBlank() || url.port !in 1..65535 || url.encodedPath !in listOf("", "/") ||
        !uuid.matches(url.user.orEmpty()) || !url.password.isNullOrEmpty() || !supported ||
        !fingerprintValid || !extraValid ||
        params["encryption"]?.let { it != "none" } == true ||
        params.names().any { it !in allowed || params.getAll(it)?.size != 1 }) return null
    Profile(url.fragment.ifBlank { url.host }, link)
    } catch (_: Exception) {
        null
    }
}
