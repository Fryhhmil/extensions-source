package eu.kanade.tachiyomi.extension.pt.mangalivre.auth

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class LoginCredentials(
    val email: String,
    val password: String,
)

class StoredSession(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val csrfToken: String,
)

interface SessionStore {
    fun load(): StoredSession?

    fun save(session: StoredSession)

    fun clear()
}

/**
 * Keeps the logged-in session, which lets the reader answer without a Turnstile challenge.
 *
 * The tokens live in [store] instead of the app's cookie jar: the site answers 401 even on public
 * endpoints when it receives a refused token, so a stale cookie left in the jar would break browsing.
 *
 * The site rotates every token on refresh, so renewals are serialized: a second refresh with an
 * already rotated token could invalidate the session.
 */
class ToonLivreAuth(
    baseUrl: String,
    private val authClient: OkHttpClient,
    private val headers: () -> Headers,
    private val store: SessionStore,
    private val credentials: () -> LoginCredentials?,
) {
    private val origin = baseUrl.trimEnd('/')
    private val host = baseUrl.toHttpUrl().host
    private val lock = Any()

    private var session: StoredSession? = null
    private var restored = false
    private var generation = 0L
    private var rejected: LoginCredentials? = null
    private var receivedCookies = ""

    @Volatile
    var lastFailure: String? = null
        private set

    /** Application interceptor: sends the request logged in, renewing a session the site refuses. */
    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != host) return chain.proceed(request)
        val login = credentials() ?: return chain.proceed(request)

        val first = currentGeneration(login) ?: return chain.proceed(request)
        val response = chain.proceed(request.withCsrf())
        if (!response.isSessionRefused()) return response
        response.close()

        if (!renew(first) { refreshOrLogin(login) }) return chain.proceed(request)
        val second = synchronized(lock) { generation }
        val retried = chain.proceed(request.withCsrf())
        if (!retried.isSessionRefused()) return retried
        retried.close()

        renew(second) { login(login) }
        val last = chain.proceed(request.withCsrf())
        if (last.isSessionRefused() && synchronized(lock) { session?.accessToken.isNullOrEmpty() }) {
            lastFailure = "O login foi aceito, mas o app não entregou os cookies da sessão (recebidos: ${receivedCookies.ifEmpty { "nenhum" }})."
        }
        return last
    }

    /** Network interceptor: runs after the app's cookie jar, so its cookies stay on the request. */
    fun attachCookies(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = synchronized(lock) { session }
        if (request.url.host != host || current == null) return chain.proceed(request)

        val own = listOf(ACCESS_TOKEN to current.accessToken, CSRF_TOKEN to current.csrfToken)
            .filter { (_, value) -> value.isNotEmpty() }
        if (own.isEmpty()) return chain.proceed(request)
        val others = request.header("Cookie")?.split("; ").orEmpty()
            .filterNot { cookie -> own.any { (name, _) -> cookie.startsWith("$name=") } }
        val cookie = (others + own.map { (name, value) -> "$name=$value" }).joinToString("; ")
        return chain.proceed(request.newBuilder().header("Cookie", cookie).build())
    }

    fun forgetSession() = synchronized(lock) {
        session = null
        restored = true
        generation++
        rejected = null
        lastFailure = null
        store.clear()
    }

    private fun Request.withCsrf(): Request {
        val csrf = synchronized(lock) { session?.csrfToken }
        return if (method == "GET" || csrf.isNullOrEmpty()) this else newBuilder().header(CSRF_HEADER, csrf).build()
    }

    private fun Response.isSessionRefused(): Boolean = code == 401 || (code == 403 && peekBody(MAX_PEEK).string().contains(TURNSTILE_MARKER))

    private fun currentGeneration(login: LoginCredentials): Long? = synchronized(lock) {
        if (!restored) {
            restored = true
            session = store.load()
        }
        val current = session
        val alive = current != null && System.currentTimeMillis() < current.accessExpiresAt
        if (alive || attempt { refreshOrLogin(login) }) generation else null
    }

    /** If another thread already renewed since the refusal, its result is used instead. */
    private fun renew(refusedGeneration: Long, renewal: () -> Unit): Boolean = synchronized(lock) {
        generation != refusedGeneration || attempt(renewal)
    }

    /** A failed login must not break browsing, which works without one: the reader shows the reason. */
    private fun attempt(renewal: () -> Unit): Boolean = try {
        renewal()
        lastFailure = null
        true
    } catch (e: IOException) {
        lastFailure = e.message
        session = null
        store.clear()
        false
    }

    private fun refreshOrLogin(login: LoginCredentials) {
        if (!refresh()) login(login)
    }

    private fun refresh(): Boolean {
        val current = session ?: return false
        if (System.currentTimeMillis() >= current.refreshExpiresAt) return false

        val request = Request.Builder()
            .url("$origin$REFRESH_PATH")
            .headers(authHeaders())
            .apply { if (current.refreshToken.isNotEmpty()) header("Cookie", "$REFRESH_TOKEN=${current.refreshToken}") }
            .post(EMPTY_BODY)
            .build()
        authClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            accept(response)
            return true
        }
    }

    private fun login(login: LoginCredentials) {
        rejected?.let {
            if (it.email == login.email && it.password == login.password) throw IOException(REJECTED_MESSAGE)
        }

        val body = buildJsonObject {
            put("email", JsonPrimitive(login.email))
            put("password", JsonPrimitive(login.password))
        }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("$origin$LOGIN_PATH")
            .headers(authHeaders())
            .post(body)
            .build()
        authClient.newCall(request).execute().use { response ->
            if (response.code == 400 || response.code == 401) {
                // Repeating a refused password on every request could get the account locked.
                rejected = login
                throw IOException(REJECTED_MESSAGE)
            }
            if (!response.isSuccessful) throw IOException("O login no ToonLivre falhou (HTTP ${response.code}).")
            accept(response)
        }
    }

    private fun authHeaders(): Headers = headers().newBuilder().set("Origin", origin).build()

    /**
     * The site accepted the request, so the session exists even when its cookies can't be read:
     * some hosts fold every Set-Cookie into one header, like iOS networking, or keep the cookies to
     * themselves and send them on their own. Tokens that can't be read stay empty.
     */
    private fun accept(response: Response) {
        val cookies = response.headers("Set-Cookie")
            .flatMap { it.split(FOLDED_COOKIES) }
            .mapNotNull { Cookie.parse(response.request.url, it) }
            .associateBy { it.name }
        receivedCookies = cookies.keys.joinToString()

        val fallbackExpiry = System.currentTimeMillis() + FALLBACK_LIFETIME_MS
        val access = cookies[ACCESS_TOKEN]
        val refresh = cookies[REFRESH_TOKEN]
        val renewed = StoredSession(
            access?.value.orEmpty(),
            access?.expiresAt ?: fallbackExpiry,
            refresh?.value.orEmpty(),
            refresh?.expiresAt ?: fallbackExpiry,
            cookies[CSRF_TOKEN]?.value.orEmpty(),
        )
        session = renewed
        generation++
        store.save(renewed)
    }

    private companion object {
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val CSRF_TOKEN = "csrf_token"
        const val CSRF_HEADER = "x-csrf-token"
        const val LOGIN_PATH = "/api/auth/login"
        const val REFRESH_PATH = "/api/auth/refresh"
        const val TURNSTILE_MARKER = "requiresTurnstile"
        const val MAX_PEEK = 1024L
        const val FALLBACK_LIFETIME_MS = 12 * 60 * 60 * 1000L
        val FOLDED_COOKIES = Regex(""",\s*(?=[^;,=\s]+=)""")
        const val REJECTED_MESSAGE = "O ToonLivre recusou o login. Confira o e-mail e a senha nas configurações da extensão."
        val JSON = "application/json".toMediaType()
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody()
    }
}
