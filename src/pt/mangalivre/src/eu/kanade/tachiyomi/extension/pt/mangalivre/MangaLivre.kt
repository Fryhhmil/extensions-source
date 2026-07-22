package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaLivre :
    HttpSource(),
    ConfigurableSource {

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override val supportsLatest: Boolean = true

    // O `signatureInterceptor` injeta a assinatura `x-toon-signature` (token de
    // /api/seed) em toda chamada à API; o `decryptInterceptor` descriptografa as
    // respostas de conteúdo (Rabbit/CryptoJS). Ver comentários abaixo.
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::signatureInterceptor)
        .addInterceptor(::decryptInterceptor)
        .rateLimit(2, 1.seconds) { it.host == baseUrlHost }
        .build()

    // Cliente sem nossos interceptors, usado para buscar o bundle JS e o /api/seed
    // sem recursão nem passar pelo rate limit acima.
    private val bootstrapClient: OkHttpClient get() = network.client

    private val apiUrl: String = "$baseUrl/api"

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "*/*")
        .add("Accept-Language", "pt-BR,en-US;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")

    // ============================== Popular =======================================

    private val popularFilter = FilterList(
        listOf(
            OrderByFilter(options = listOf("" to "popular")),
            OrderDirectionFilter(options = listOf("" to "desc")),
        ),
    )

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", popularFilter)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Latest =======================================

    private val latestFilter = FilterList(
        listOf(
            OrderByFilter(options = listOf("" to "updated")),
            OrderDirectionFilter(options = listOf("" to "desc")),
        ),
    )

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(page, "", latestFilter)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Search =======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/mangas/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> {
                    url.addQueryParameter("sortBy", filter.selected())
                }
                is OrderDirectionFilter -> {
                    url.addQueryParameter("sortOrder", filter.selected())
                }
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseJson<WrapperDto>()
        val mangas = dto.mangas.map { it.toSManga(useAlternativeTitle) }
        return MangasPage(mangas, dto.hasNextPage)
    }

    // ============================== Details =======================================

    override fun getMangaUrl(manga: SManga): String {
        val base = downloadUiBaseUrl
        return if (downloadUiEnabled && base.isNotBlank()) {
            "${base.trimEnd('/')}/download-ui/${manga.url}"
        } else {
            "$baseUrl/${manga.url}"
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/manga-by-slug/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseJson<MangaDto>().toSManga(useAlternativeTitle)

    // ============================== Chapters =======================================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = response.parseJson<MangaDto>().toSChapterList()

    // A chapter.url é "/{slug}/{number}#{json}". O trecho antes do '#' é a página do
    // leitor no site.
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url.substringBefore("#")}"

    // ============================== Pages =======================================

    override fun pageListRequest(chapter: SChapter): Request {
        val dto = chapter.url.substringAfterLast("#").parseAs<ChapterReferenceDto>()
        return GET("$apiUrl/mangas/${dto.mangaId}/chapters/${dto.chapterId}", headers)
    }

    override fun pageListParse(response: Response): List<Page> = response.parseJson<PageDto>().toPageList()

    override fun imageUrlParse(response: Response): String = ""

    // ============================== Filters =======================================

    override fun getFilterList(): FilterList = FilterList(
        listOf(
            OrderByFilter(
                "Ordem",
                listOf(
                    "Mais Visualizados" to "popular",
                    "Lançamentos" to "release",
                    "Última Atualização" to "updated",
                    "Melhor Avaliação" to "rating",
                    "A-Z" to "title",
                ),
            ),
            Filter.Separator(),
            OrderDirectionFilter(
                "Direção",
                listOf(
                    "↑ Decrescente" to "desc",
                    "↓ Crescente" to "asc",
                ),
            ),
        ),
    )

    val useAlternativeTitle: Boolean get() =
        preferences.getBoolean(ALTERNATIVE_TITLE_PREF, false)

    private val downloadUiEnabled: Boolean get() =
        preferences.getBoolean(DOWNLOAD_UI_ENABLED_PREF, false)

    private val downloadUiBaseUrl: String get() =
        preferences.getString(DOWNLOAD_UI_URL_PREF, "").orEmpty().trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = ALTERNATIVE_TITLE_PREF
            title = "Titulo alternativo"
            summary = buildString {
                append("Use titulos alternativos como principal quando disponivel.")
                append(" Essa opção não tem efeito sobre obras já adicionadas na sua bibilioteca")
            }
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = DOWNLOAD_UI_ENABLED_PREF
            title = "Abrir interface de download em lotes"
            summary = buildString {
                append("Quando ativado, \"Abrir na WebView\" da obra abre a interface de download")
                append(" em lotes (/download-ui/<slug>) na URL configurada abaixo.")
                append(" Se a URL estiver vazia, abre a página normal.")
            }
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = DOWNLOAD_UI_URL_PREF
            title = "URL da interface de download"
            summary = "Host base, ex.: http://192.168.0.10:8080"
            dialogMessage = "Digite só o host base (sem /download-ui). O slug é anexado automaticamente."
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    // ============================== Assinatura (x-toon-signature) ==================

    /**
     * O ToonLivre passou a exigir uma assinatura em toda chamada à API. O valor é um
     * JWT devolvido por `GET /api/seed` (validade ~30 min) que o cliente apenas
     * reenvia no header. Tanto o **nome** do header (rotaciona a cada release web)
     * quanto o valor são descobertos em runtime: o nome sai do bundle JS do site e o
     * valor do `/api/seed`. Em caso de 403 "Acesso negado", reextrai tudo e repete
     * a requisição uma vez.
     */
    private fun signatureInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost || request.url.encodedPath.endsWith("/api/seed")) {
            return chain.proceed(request)
        }

        val response = chain.proceed(request.signed())
        if (response.code == 403 && response.looksDenied()) {
            response.close()
            invalidateAuth()
            return chain.proceed(request.signed())
        }
        return response
    }

    private fun Request.signed(): Request = newBuilder().header(authHeaderName(), authToken()).build()

    private fun Response.looksDenied(): Boolean = try {
        peekBody(MAX_PEEK).string().let { body ->
            body.contains("requiresTurnstile", ignoreCase = true) ||
                body.contains("Acesso negado", ignoreCase = true) ||
                body.contains("aplicativo oficial", ignoreCase = true)
        }
    } catch (_: Exception) {
        false
    }

    private fun authToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpMs - REFRESH_MARGIN_MS) return it }
        synchronized(authLock) {
            val current = System.currentTimeMillis()
            cachedToken?.let { if (current < tokenExpMs - REFRESH_MARGIN_MS) return it }
            bootstrapClient.newCall(GET("$apiUrl/seed", headers)).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException(SEED_FAIL_MESSAGE)
                val token = resp.body.string().parseAs<SeedTokenDto>().token
                cachedToken = token
                tokenExpMs = jwtExpMs(token)
                return token
            }
        }
    }

    private fun jwtExpMs(token: String): Long = try {
        val payload = token.split(".")[1]
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING), Charsets.UTF_8)
        decoded.parseAs<JwtExpDto>().exp.takeIf { it > 0 }?.times(1000)
            ?: (System.currentTimeMillis() + FALLBACK_TTL_MS)
    } catch (_: Exception) {
        System.currentTimeMillis() + FALLBACK_TTL_MS
    }

    private fun authHeaderName(): String {
        ensureBundleMeta()
        return cachedHeaderName ?: DEFAULT_HEADER
    }

    private fun invalidateAuth() {
        synchronized(authLock) {
            cachedToken = null
            tokenExpMs = 0L
        }
        synchronized(bundleLock) { bundleMetaLoaded = false }
    }

    /**
     * Raspagem única (cacheada) do bundle JS de entrada para descobrir o nome do
     * header de assinatura e as constantes que compõem a passphrase de
     * descriptografia (que podem versionar, ex. `...::v8` → `::v9`). Se qualquer
     * passo falhar, os defaults conhecidos são usados.
     */
    private fun ensureBundleMeta() {
        if (bundleMetaLoaded) return
        synchronized(bundleLock) {
            if (bundleMetaLoaded) return
            try {
                val html = bootstrapClient.newCall(GET("$baseUrl/", headers)).execute()
                    .use { it.body.string() }
                val bundlePath = BUNDLE_REGEX.find(html)?.groupValues?.get(1) ?: return
                val bundleUrl = baseUrl.toHttpUrl().resolve(bundlePath)?.toString() ?: return
                val js = bootstrapClient.newCall(GET(bundleUrl, headers)).execute()
                    .use { it.body.string() }

                AUTH_HEADER_REGEX.find(js)?.groupValues?.get(1)?.let { cachedHeaderName = it }
                extractPassphraseConstants(js)?.let {
                    cachedSalt1 = it[0]
                    cachedSalt2 = it[1]
                    cachedPrefix = it[2]
                }
                bundleMetaLoaded = true
            } catch (_: Exception) {
                // mantém defaults; tenta de novo numa próxima chamada
            }
        }
    }

    private fun extractPassphraseConstants(js: String): List<String>? {
        val anchor = js.indexOf("getUTCDate")
        if (anchor < 0) return null
        val window = js.substring(anchor, minOf(anchor + 400, js.length))
        val consts = SPLIT_REGEX.findAll(window).map { it.groupValues[1] }.take(3).toList()
        return consts.takeIf { it.size == 3 }
    }

    // ============================== Descriptografia (Rabbit) =======================

    /**
     * As respostas de conteúdo vêm cifradas como `{"<hexkey>":"U2FsdGVk..."}`
     * (CryptoJS Rabbit, formato OpenSSL). Este interceptor detecta o blob, decifra
     * com a passphrase do dia e substitui o corpo pelo JSON claro, de forma
     * transparente para os `*Parse` acima. Respostas em texto puro passam intactas.
     */
    private fun decryptInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (chain.request().url.host != baseUrlHost || !response.isSuccessful) {
            return response
        }
        // Lê o corpo uma única vez e sempre reconstrói a Response (o corpo original é
        // consumido por .string()). Se não for um pacote cifrado, devolve o mesmo
        // conteúdo intacto; se for, devolve o JSON já decifrado.
        val originalBody = response.body
        val mediaType = originalBody.contentType()
        val body = originalBody.string()
        val blob = ENC_BLOB_REGEX.find(body)?.groupValues?.get(1)
        val decrypted = blob?.let { decryptBlob(it) }
        val content = decrypted ?: body
        val newMediaType = if (decrypted != null) JSON_MEDIA_TYPE else mediaType
        return response.newBuilder()
            .body(content.toResponseBody(newMediaType))
            .build()
    }

    /**
     * A passphrase depende da data, mas o servidor a calcula no fuso horário dele
     * (aparentemente o do Brasil, não UTC), então perto da virada do dia a data UTC
     * pode divergir da do servidor por 1 dia. Tentamos as datas candidatas (UTC de
     * hoje, ontem e amanhã) e ficamos com a que decifra para um JSON válido.
     */
    private fun decryptBlob(blob: String): String? {
        for (passphrase in candidatePassphrases()) {
            val plain = try {
                RabbitCipher.decrypt(blob, passphrase)
            } catch (_: Exception) {
                continue
            }
            if (plain.trimStart().startsWith("{")) return plain
        }
        return null
    }

    private fun candidatePassphrases(): List<String> {
        ensureBundleMeta()
        val salt1 = cachedSalt1 ?: DEFAULT_SALT1
        val salt2 = cachedSalt2 ?: DEFAULT_SALT2
        val prefix = cachedPrefix ?: DEFAULT_PREFIX
        return intArrayOf(0, -1, 1).map { offset ->
            prefix + sha256Hex(utcDate(offset) + salt1 + salt2).substring(0, 8)
        }
    }

    private fun sha256Hex(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun utcDate(offsetDays: Int): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.add(Calendar.DAY_OF_MONTH, offsetDays)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(calendar.time)
    }

    // ============================== Helper =======================================

    /**
     * Lê o corpo como JSON. Se vier HTML (Cloudflare / erro) ou vazio, falha com
     * mensagem clara em vez de estourar no parser.
     */
    private inline fun <reified T> Response.parseJson(): T {
        val peek = peekBody(MAX_PEEK).string().trimStart()
        if (peek.isEmpty() || peek.startsWith("<")) {
            close()
            throw IOException(NON_JSON_MESSAGE)
        }
        return parseAs<T>()
    }

    // Estado de assinatura/descriptografia (compartilhado entre threads do OkHttp).
    @Volatile private var cachedToken: String? = null

    @Volatile private var tokenExpMs: Long = 0L

    @Volatile private var cachedHeaderName: String? = null

    @Volatile private var cachedSalt1: String? = null

    @Volatile private var cachedSalt2: String? = null

    @Volatile private var cachedPrefix: String? = null

    @Volatile private var bundleMetaLoaded: Boolean = false

    private val authLock = Any()

    private val bundleLock = Any()

    companion object {
        private const val ALTERNATIVE_TITLE_PREF = "alternativeTitlePref"
        private const val DOWNLOAD_UI_ENABLED_PREF = "downloadUiEnabledPref"
        private const val DOWNLOAD_UI_URL_PREF = "downloadUiUrlPref"

        private const val MAX_PEEK = 1024L

        private const val REFRESH_MARGIN_MS = 120_000L
        private const val FALLBACK_TTL_MS = 25 * 60_000L

        // Defaults conhecidos (2026-07-21); usados se a raspagem do bundle falhar.
        private const val DEFAULT_HEADER = "x-toon-signature"
        private const val DEFAULT_SALT1 = "toonlivre.com::v8"
        private const val DEFAULT_SALT2 = "t8_4v2_b"
        private const val DEFAULT_PREFIX = "Magnesium-Strike-Astonish3"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val BUNDLE_REGEX = """<script[^>]+src="([^"]*/assets/index-[^"]+\.js)"""".toRegex()
        private val AUTH_HEADER_REGEX = """append\("(x-[\w-]+)",await""".toRegex()
        private val SPLIT_REGEX = """"([^"]*)"\.split\(""\)""".toRegex()
        private val ENC_BLOB_REGEX = """"(U2FsdGVk[A-Za-z0-9+/=]+)"""".toRegex()

        private const val NON_JSON_MESSAGE =
            "Resposta não-JSON (Cloudflare ou verificação pendente). Abra a fonte na WebView do app e tente de novo."
        private const val SEED_FAIL_MESSAGE =
            "Falha ao obter a assinatura de acesso (/api/seed). Verifique a conexão e tente novamente."
    }
}
