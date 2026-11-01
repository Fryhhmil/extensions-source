package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.pt.mangalivre.auth.LoginCredentials
import eu.kanade.tachiyomi.extension.pt.mangalivre.auth.SessionStore
import eu.kanade.tachiyomi.extension.pt.mangalivre.auth.StoredSession
import eu.kanade.tachiyomi.extension.pt.mangalivre.auth.ToonLivreAuth
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaLivre :
    KeiSource(),
    ConfigurableSource {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { auth.intercept(it) }
        .addNetworkInterceptor { auth.attachCookies(it) }
        .rateLimit(2, 1.seconds) { it.host == baseUrl.toHttpUrl().host }

    private val apiUrl: String get() = "$baseUrl/api"

    private val preferences by getPreferencesLazy()
    private val verificationMutex = Mutex()

    private val auth by lazy {
        ToonLivreAuth(
            baseUrl = baseUrl,
            // Without the app's cookie jar: the tokens stay in the preferences, see ToonLivreAuth.
            authClient = network.client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).build(),
            headers = { headers },
            store = PreferencesSessionStore(),
            credentials = {
                val email = preferences.getString(EMAIL_PREF, "").orEmpty().trim()
                val password = preferences.getString(PASSWORD_PREF, "").orEmpty()
                LoginCredentials(email, password).takeIf { email.isNotEmpty() && password.isNotEmpty() }
            },
        )
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Accept-Language", "pt-BR,en-US;q=0.9,en;q=0.8")

    // ============================== Popular =======================================

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(
        page,
        "",
        FilterList(
            listOf(
                OrderByFilter(options = listOf("" to SORT_POPULAR)),
                OrderDirectionFilter(options = listOf("" to DIRECTION_DESC)),
            ),
        ),
    )

    // ============================== Latest =======================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(
        page,
        "",
        FilterList(
            listOf(
                OrderByFilter(options = listOf("" to SORT_UPDATED)),
                OrderDirectionFilter(options = listOf("" to DIRECTION_DESC)),
            ),
        ),
    )

    // ============================== Search =======================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url =
            "$apiUrl/mangas/search"
                .toHttpUrl()
                .newBuilder()
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
        val dto = client.get(url.build(), Fetch.XHR).parseJson<WrapperDto>()
        val mangas = dto.mangas.map { it.toSManga(useAlternativeTitle) }
        return MangasPage(mangas, dto.hasNextPage)
    }

    // ============================== Details =======================================

    override fun getMangaUrl(manga: SManga): String {
        val downloadUiUrl = preferences.getString(DOWNLOAD_UI_URL_PREF, "").orEmpty().trim().trimEnd('/')
        return if (preferences.getBoolean(DOWNLOAD_UI_ENABLED_PREF, false) && downloadUiUrl.isNotEmpty()) {
            "$downloadUiUrl/download-ui/${manga.url}"
        } else {
            "$baseUrl/${manga.url}"
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = client.get("$apiUrl/manga-by-slug/${manga.url}", Fetch.XHR).parseJson<MangaDto>()
        return SMangaUpdate(
            manga = dto.toSManga(useAlternativeTitle),
            chapters = dto.toSChapterList(),
        )
    }

    // ============================== Pages =======================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}".toHttpUrl()
        val ref = chapter.memo.parseAs<ChapterReferenceDto>()
        val chapterNumber = chapterUrl.pathSegments.last { it.isNotEmpty() }

        return verificationMutex.withLock {
            fetchReaderAccess(ref)?.let { access ->
                return@withLock access.chapter.pages.toPageList(ref.mangaId, chapterNumber)
            }

            openVerificationWebView(chapterUrl.toString(), ref.mangaId, chapterNumber)
                .toPageList(ref.mangaId, chapterNumber)
        }
    }

    private suspend fun fetchReaderAccess(ref: ChapterReferenceDto): ReaderAccessResponseDto? {
        client.post(
            "$apiUrl/reader/chapter/access",
            // The site answers 404 without Origin, and the core only adds it to Chrome and Firefox user agents.
            headers = headers.newBuilder().set("Origin", baseUrl).build(),
            fetch = Fetch.XHR,
            body = ref.toJsonRequestBody(),
            ensureSuccess = false,
        ).use { response ->
            if (response.isSuccessful) return response.parseAs()

            val error = response.parseAs<ReaderAccessErrorDto>()
            // A refused login would only reach the same verification: tell the user what to fix.
            auth.lastFailure?.let { throw IOException(it) }
            if (response.code == 403 && error.error == READER_VERIFICATION_REQUIRED) return null
            throw IOException(error.error)
        }
    }

    private suspend fun openVerificationWebView(
        readerUrl: String,
        mangaId: String,
        chapterNumber: String,
    ): List<String> {
        val result = CompletableDeferred<List<String>>()
        val receiver =
            object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(
                    resultCode: Int,
                    resultData: Bundle?,
                ) {
                    val pages = resultData?.getStringArrayList(ReaderVerificationActivity.EXTRA_PAGES).orEmpty()
                    if (resultCode == ReaderVerificationActivity.RESULT_PAGES && pages.isNotEmpty()) {
                        result.complete(pages)
                    } else {
                        result.completeExceptionally(IOException("Verificação cancelada."))
                    }
                }
            }
        val intent =
            Intent().apply {
                component = ComponentName(EXTENSION_PACKAGE, ReaderVerificationActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ReaderVerificationActivity.EXTRA_URL, readerUrl)
                putExtra(ReaderVerificationActivity.EXTRA_MANGA_ID, mangaId)
                putExtra(ReaderVerificationActivity.EXTRA_CHAPTER_NUMBER, chapterNumber)
                putExtra(ReaderVerificationActivity.EXTRA_RECEIVER, receiver)
            }
        applicationContext.startActivity(intent)
        return withTimeout(VERIFICATION_TIMEOUT) { result.await() }
    }

    // ============================== Filters =======================================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        listOf(
            OrderByFilter(
                "Ordem",
                listOf(
                    "Mais Visualizados" to SORT_POPULAR,
                    "Lançamentos" to SORT_RELEASE,
                    "Última Atualização" to SORT_UPDATED,
                    "Melhor Avaliação" to SORT_RATING,
                    "A-Z" to SORT_TITLE,
                ),
            ),
            Filter.Separator(),
            OrderDirectionFilter(
                "Direção",
                listOf(
                    "↑ Decrescente" to DIRECTION_DESC,
                    "↓ Crescente" to DIRECTION_ASC,
                ),
            ),
        ),
    )

    val useAlternativeTitle: Boolean get() =
        preferences.getBoolean(ALTERNATIVE_TITLE_PREF, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context)
            .apply {
                key = ALTERNATIVE_TITLE_PREF
                title = "Titulo alternativo"
                summary =
                    buildString {
                        append("Use titulos alternativos como principal quando disponivel.")
                        append(" Essa opção não tem efeito sobre obras já adicionadas na sua biblioteca")
                    }
                setDefaultValue(false)
            }.also(screen::addPreference)

        EditTextPreference(screen.context)
            .apply {
                key = EMAIL_PREF
                title = "E-mail"
                summary = "E-mail da sua conta do ToonLivre. Com e-mail e senha, o login é automático e o leitor não pede verificação."
                setDefaultValue("")
                setOnBindEditTextListener { it.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT }
                setOnPreferenceChangeListener { _, _ ->
                    auth.forgetSession()
                    true
                }
            }.also(screen::addPreference)

        EditTextPreference(screen.context)
            .apply {
                key = PASSWORD_PREF
                title = "Senha"
                summary = "Senha da sua conta do ToonLivre. Fica salva neste aparelho e só é enviada ao site."
                setDefaultValue("")
                setOnBindEditTextListener { it.inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT }
                setOnPreferenceChangeListener { _, _ ->
                    auth.forgetSession()
                    true
                }
            }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context)
            .apply {
                key = DOWNLOAD_UI_ENABLED_PREF
                title = "Abrir interface de download em lotes"
                summary =
                    buildString {
                        append("Quando ativado, abrir a obra na WebView leva à interface de download em lotes")
                        append(" (/download-ui/<slug>) na URL abaixo. Com a URL vazia, abre a página normal.")
                    }
                setDefaultValue(false)
            }.also(screen::addPreference)

        EditTextPreference(screen.context)
            .apply {
                key = DOWNLOAD_UI_URL_PREF
                title = "URL da interface de download"
                summary = "Endereço do servidor, ex.: http://192.168.0.10:5000"
                dialogMessage = "Digite só o endereço do servidor (sem /download-ui). O slug é anexado automaticamente."
                setDefaultValue("")
            }.also(screen::addPreference)
    }

    private inner class PreferencesSessionStore : SessionStore {
        override fun load(): StoredSession? {
            val accessToken = preferences.getString(ACCESS_TOKEN_PREF, null) ?: return null
            val refreshToken = preferences.getString(REFRESH_TOKEN_PREF, null) ?: return null
            val csrfToken = preferences.getString(CSRF_TOKEN_PREF, null) ?: return null
            return StoredSession(
                accessToken,
                preferences.getLong(ACCESS_EXPIRES_PREF, 0L),
                refreshToken,
                preferences.getLong(REFRESH_EXPIRES_PREF, 0L),
                csrfToken,
            )
        }

        // commit: the site has already invalidated the old tokens, so they must not survive a crash.
        override fun save(session: StoredSession) {
            preferences.edit()
                .putString(ACCESS_TOKEN_PREF, session.accessToken)
                .putLong(ACCESS_EXPIRES_PREF, session.accessExpiresAt)
                .putString(REFRESH_TOKEN_PREF, session.refreshToken)
                .putLong(REFRESH_EXPIRES_PREF, session.refreshExpiresAt)
                .putString(CSRF_TOKEN_PREF, session.csrfToken)
                .commit()
        }

        override fun clear() {
            preferences.edit()
                .remove(ACCESS_TOKEN_PREF)
                .remove(ACCESS_EXPIRES_PREF)
                .remove(REFRESH_TOKEN_PREF)
                .remove(REFRESH_EXPIRES_PREF)
                .remove(CSRF_TOKEN_PREF)
                .commit()
        }
    }

    // ============================== Utilities =======================================

    private inline fun <reified T> Response.parseJson(): T {
        val peek = peekBody(MAX_PEEK).string().trimStart()
        if (peek.isEmpty() || peek.startsWith("<")) {
            close()
            throw IOException(NON_JSON_MESSAGE)
        }
        return parseAs<T>()
    }

    companion object {
        private val VERIFICATION_TIMEOUT = 2.minutes
        private const val EXTENSION_PACKAGE = "eu.kanade.tachiyomi.extension.pt.mangalivre"
        private const val CDN_HOST = "cdn.toonlivre.net"
        private const val PROXY_HOST = "slightly-free-mayfly.edgecompute.app"
        private val PAGE_NUMBER_REGEX = Regex("""_(\d+)\.[^.]+$""")

        private const val ALTERNATIVE_TITLE_PREF = "alternativeTitlePref"
        private const val DOWNLOAD_UI_ENABLED_PREF = "downloadUiEnabledPref"
        private const val DOWNLOAD_UI_URL_PREF = "downloadUiUrlPref"
        private const val EMAIL_PREF = "emailPref"
        private const val PASSWORD_PREF = "passwordPref"
        private const val ACCESS_TOKEN_PREF = "sessionAccessToken"
        private const val ACCESS_EXPIRES_PREF = "sessionAccessExpiresAt"
        private const val REFRESH_TOKEN_PREF = "sessionRefreshToken"
        private const val REFRESH_EXPIRES_PREF = "sessionRefreshExpiresAt"
        private const val CSRF_TOKEN_PREF = "sessionCsrfToken"
        private const val MAX_PEEK = 1024L
        private const val NON_JSON_MESSAGE =
            "Resposta não-JSON (Cloudflare ou header desatualizado). Abra a fonte na WebView do app e tente de novo."
        private const val READER_VERIFICATION_REQUIRED = "Reader verification required"

        private const val SORT_POPULAR = "popular"
        private const val SORT_RELEASE = "release"
        private const val SORT_UPDATED = "updated"
        private const val SORT_RATING = "rating"
        private const val SORT_TITLE = "title"
        private const val DIRECTION_DESC = "desc"
        private const val DIRECTION_ASC = "asc"
    }

    private fun String.toCdnImageUrl(): String? {
        val url = toHttpUrlOrNull() ?: return null
        val candidate =
            when (url.host) {
                CDN_HOST -> url
                PROXY_HOST -> url.queryParameter("url")?.toHttpUrlOrNull()
                else -> null
            } ?: return null

        return candidate.takeIf { it.isHttps && it.host == CDN_HOST }?.toString()
    }

    private fun String.isChapterImage(
        mangaId: String,
        chapterNumber: String,
    ): Boolean {
        val pathSegments = toHttpUrl().pathSegments
        return pathSegments.size >= 4 &&
            pathSegments[0] == "obras" &&
            pathSegments[1] == mangaId &&
            pathSegments[2] == chapterNumber &&
            pathSegments[3].isNotEmpty()
    }

    private fun List<String>.toPageList(
        mangaId: String,
        chapterNumber: String,
    ): List<Page> {
        val sortedUrls =
            asSequence()
                .mapNotNull { it.toCdnImageUrl() }
                .filter { it.isChapterImage(mangaId, chapterNumber) }
                .distinct()
                .sortedWith(compareBy<String>({ it.pageNumber() ?: Int.MAX_VALUE }, { it }))
                .toList()
        return sortedUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    private fun String.pageNumber(): Int? = toHttpUrl()
        .pathSegments
        .lastOrNull()
        ?.let(PAGE_NUMBER_REGEX::find)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
}
