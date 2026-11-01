package eu.kanade.tachiyomi.extension.pt.lerfryhh

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.AuthenticationDto
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.FilterComparison
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.FilterField
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.FilterStatementDto
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.FilterV2Dto
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MangaFormat
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MetadataAgeRatings
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MetadataCollections
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MetadataGenres
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MetadataLanguages
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MetadataLibrary
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MetadataPayload
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MetadataPeople
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MetadataPubStatus
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.MetadataTag
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.PersonRole
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.SeriesDto
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.SeriesMetadataDto
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.ServerInfoDto
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.SmartFilter
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.SortFieldEnum
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.SortOptions
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.VolumeDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.TriState.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.source.model.Filter.TriState.Companion.STATE_INCLUDE
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import rx.Single
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.net.ConnectException
import java.security.MessageDigest
import java.util.Locale

class LerFryhh(private val suffix: String = "") :
    HttpSource(),
    ConfigurableSource,
    UnmeteredSource {
    private val helper = LerFryhhHelper()
    override val client: OkHttpClient =
        network.client.newBuilder()
            .dns(Dns.SYSTEM)
            .build()
    override val id by lazy {
        val key = "${"lerfryhh_$suffix"}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }
    internal val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    override val name = "${LerFryhhInt.SOURCE_NAME} (${preferences.getString(LerFryhhConstants.CUSTOM_SOURCE_NAME_PREF, suffix)})"
    override val lang = "pt-BR"
    override val supportsLatest = true
    private val apiUrl by lazy { getPrefApiUrl() }
    override val baseUrl by lazy { getPrefBaseUrl() }
    private val address by lazy { getPrefAddress() } // Address for the Kavita OPDS url. Should be http(s)://host:(port)/api/opds/api-key
    private val apiKey by lazy { getPrefApiKey() }
    private var jwtToken = "" // * JWT Token for authentication with the server. Stored in memory.
    private val logTag = """LerFryhh_${"[$suffix]_" + preferences.getString(LerFryhhConstants.CUSTOM_SOURCE_NAME_PREF, "[$suffix]")!!.replace(' ', '_')}"""
    private var isLogged = false // Used to know if login was correct and not send login requests anymore
    private val json: Json by injectLazy()

    private var series = emptyList<SeriesDto>() // Acts as a cache

    private inline fun <reified T> Response.parseAs(): T = use {
        if (it.code == 401) {
            Log.e(logTag, "Http error 401 - Not authorized: ${it.request.url}")
            Throwable("Http error 401 - Not authorized: ${it.request.url}")
        }

        if (it.peekBody(Long.MAX_VALUE).string().isEmpty()) {
            Log.e(logTag, "Empty body String for request url: ${it.request.url}")
            throw EmptyRequestBody(
                "Body of the response is empty. RequestUrl=${it.request.url}\nPlease check your kavita instance is up to date",
                Throwable("Error. Request body is empty"),
            )
        }
        json.decodeFromString(it.body.string())
    }

    /**
     * Custom implementation for fetch popular, latest and search
     * Handles and logs errors to provide a more detailed exception to the users.
     */
    private fun fetch(request: Request): Observable<MangasPage> {
        return client.newCall(request)
            .asObservableSuccess()
            .onErrorResumeNext { throwable ->
                // Get Http code
                val field = throwable.javaClass.getDeclaredField("code")
                field.isAccessible = true // Make the field accessible
                try {
                    var code = field.get(throwable) // Get the value of the code property
                    Log.e(logTag, "Error fetching manga: ${throwable.message}", throwable)
                    if (code as Int !in intArrayOf(401, 201, 500)) {
                        code = 500
                    }
                    return@onErrorResumeNext Observable.error(IOException("Http Error: $code\n ${LerFryhhMessages.httpError(code)}\n${LerFryhhMessages.CHECK_VERSION}"))
                } catch (e: Exception) {
                    Log.e(logTag, e.toString(), e)
                    return@onErrorResumeNext Observable.error(e)
                }
            }
            .map { response ->
                popularMangaParse(response)
            }
    }

    override fun fetchPopularManga(page: Int) = fetch(popularMangaRequest(page))

    override fun fetchLatestUpdates(page: Int) = fetch(latestUpdatesRequest(page))

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = fetch(searchMangaRequest(page, query, filters))

    override fun popularMangaParse(response: Response): MangasPage {
        try {
            val result = response.parseAs<List<SeriesDto>>()
            series = result
            val mangaList = result.map { item -> helper.createSeriesDto(item, apiUrl, apiKey) }
            return MangasPage(mangaList, helper.hasNextPage(response))
        } catch (e: Exception) {
            Log.e(logTag, "Unhandled exception", e)
            throw IOException(LerFryhhMessages.CHECK_VERSION)
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        if (!isLogged) {
            doLogin()
        }
        val payload = buildFilterBody(currentFilter)
        return POST(
            "$apiUrl/series/all-v2?pageNumber=$page&pageSize=20",
            headersBuilder().build(),
            payload.toRequestBody(JSON_MEDIA_TYPE),
        )
    }

    override fun latestUpdatesRequest(page: Int): Request {
        if (!isLogged) {
            doLogin()
        }
        // Hardcode exclude epubs
        val filter = FilterV2Dto(sortOptions = SortOptions(SortFieldEnum.LastChapterAdded.type, false))
        filter.statements.add(FilterStatementDto(FilterComparison.NotContains.type, FilterField.Formats.type, "3"))
        val payload = json.encodeToJsonElement(filter).toString()
        return POST(
            "$apiUrl/series/all-v2?pageNumber=$page&pageSize=20",
            headersBuilder().build(),
            payload.toRequestBody(JSON_MEDIA_TYPE),
        )
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val newFilter = MetadataPayload() // need to reset it or will double
        val smartFilterFilter = filters.find { it is SmartFiltersFilter }
        // If a SmartFilter selected, apply its filter and return that
        if (smartFilterFilter?.state != 0 && smartFilterFilter != null) {
            val index = try {
                smartFilterFilter?.state as Int - 1
            } catch (e: Exception) {
                Log.e(logTag, e.toString(), e)
                0
            }

            val filter: SmartFilter = smartFilters[index]
            val payload = buildJsonObject {
                put("EncodedFilter", filter.filter)
            }
            // Decode selected filters
            val request = POST(
                "$apiUrl/filter/decode",
                headersBuilder().build(),
                payload.toString().toRequestBody(JSON_MEDIA_TYPE),
            )
            client.newCall(request).execute().use {
                if (it.code == 200) {
                    // Hardcode exclude epub
                    val decodedFilter = json.decodeFromString<FilterV2Dto>(it.body.string())
                    decodedFilter.statements.add(FilterStatementDto(FilterComparison.NotContains.type, FilterField.Formats.type, "3"))

                    // Make request with selected filters
                    return POST(
                        "$apiUrl/series/all-v2?pageNumber=$page&pageSize=20",
                        headersBuilder().build(),
                        json.encodeToJsonElement(decodedFilter).toString().toRequestBody(JSON_MEDIA_TYPE),
                    )
                } else {
                    Log.e(logTag, "Failed to decode SmartFilter: ${it.code}\n" + it.message)
                    throw IOException(LerFryhhMessages.SMART_FILTER_ERROR)
                }
            }
        }
        // Else apply user filters

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    if (filter.state != null) {
                        newFilter.sorting = filter.state!!.index + 1
                        newFilter.sorting_asc = filter.state!!.ascending
                    }
                }

                is StatusFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state) {
                            newFilter.readStatus.add(content.name)
                        }
                    }
                }

                is ReleaseYearRangeGroup -> {
                    filter.state.forEach { content ->
                        if (content.state.isNotEmpty()) {
                            if (content.name == "Min") {
                                newFilter.releaseYearRangeMin = content.state.toInt()
                            }
                            if (content.name == "Max") {
                                newFilter.releaseYearRangeMax = content.state.toInt()
                            }
                        }
                    }
                }

                is GenreFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state == STATE_INCLUDE) {
                            newFilter.genres_i.add(genresListMeta.find { it.title == content.name }!!.id)
                        } else if (content.state == STATE_EXCLUDE) {
                            newFilter.genres_e.add(genresListMeta.find { it.title == content.name }!!.id)
                        }
                    }
                }

                is UserRating -> {
                    newFilter.userRating = filter.state
                }

                is TagFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state == STATE_INCLUDE) {
                            newFilter.tags_i.add(tagsListMeta.find { it.title == content.name }!!.id)
                        } else if (content.state == STATE_EXCLUDE) {
                            newFilter.tags_e.add(tagsListMeta.find { it.title == content.name }!!.id)
                        }
                    }
                }

                is AgeRatingFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state == STATE_INCLUDE) {
                            newFilter.ageRating_i.add(ageRatingsListMeta.find { it.title == content.name }!!.value)
                        } else if (content.state == STATE_EXCLUDE) {
                            newFilter.ageRating_e.add(ageRatingsListMeta.find { it.title == content.name }!!.value)
                        }
                    }
                }

                is FormatsFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state) {
                            newFilter.formats.add(MangaFormat.valueOf(content.name).ordinal)
                        }
                    }
                }

                is CollectionFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state == STATE_INCLUDE) {
                            newFilter.collections_i.add(collectionsListMeta.find { it.title == content.name }!!.id)
                        } else if (content.state == STATE_EXCLUDE) {
                            newFilter.collections_e.add(collectionsListMeta.find { it.title == content.name }!!.id)
                        }
                    }
                }

                is LanguageFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state == STATE_INCLUDE) {
                            newFilter.language_i.add(languagesListMeta.find { it.title == content.name }!!.isoCode)
                        } else if (content.state == STATE_EXCLUDE) {
                            newFilter.language_e.add(languagesListMeta.find { it.title == content.name }!!.isoCode)
                        }
                    }
                }

                is LibrariesFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state == STATE_INCLUDE) {
                            newFilter.libraries_i.add(libraryListMeta.find { it.name == content.name }!!.id)
                        } else if (content.state == STATE_EXCLUDE) {
                            newFilter.libraries_e.add(libraryListMeta.find { it.name == content.name }!!.id)
                        }
                    }
                }

                is PubStatusFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state) {
                            newFilter.pubStatus.add(pubStatusListMeta.find { it.title == content.name }!!.value)
                        }
                    }
                }

                is WriterPeopleFilterGroup -> {
                    filter.state.forEach { content ->
                        if (content.state) {
                            newFilter.peopleWriters.add(peopleListMeta.find { it.name == content.name }!!.id)
                        }
                    }
                }

                else -> {}
            }
        }
        newFilter.seriesNameQuery = query
        currentFilter = newFilter
        return popularMangaRequest(page)
    }

    /*
     * MANGA DETAILS (metadata about series)
     * **/

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val serieId = helper.getIdFromUrl(manga.url)
        return client.newCall(GET("$apiUrl/series/metadata?seriesId=$serieId", headersBuilder().build()))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val serieId = helper.getIdFromUrl(manga.url)
        val foundSerie = series.find { dto -> dto.id == serieId }
        return GET(
            "$baseUrl/library/${foundSerie!!.libraryId}/series/$serieId",
            headersBuilder().build(),
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<SeriesMetadataDto>()

        val existingSeries = series.find { dto -> dto.id == result.seriesId }
        if (existingSeries != null) {
            val manga = helper.createSeriesDto(existingSeries, apiUrl, apiKey)
            manga.url = "$apiUrl/Series/${result.seriesId}"
            manga.artist = result.coverArtists.joinToString { it.name }
            manga.description = result.summary
            manga.author = result.writers.joinToString { it.name }
            manga.genre = result.genres.joinToString { it.title }
            manga.thumbnail_url = "$apiUrl/image/series-cover?seriesId=${result.seriesId}&apiKey=$apiKey"

            return manga
        }
        val serieDto = client.newCall(GET("$apiUrl/Series/${result.seriesId}", headersBuilder().build()))
            .execute()
            .parseAs<SeriesDto>()

        return SManga.create().apply {
            url = "$apiUrl/Series/${result.seriesId}"
            artist = result.coverArtists.joinToString { it.name }
            description = result.summary
            author = result.writers.joinToString { it.name }
            genre = result.genres.joinToString { it.title }
            title = serieDto.name
            thumbnail_url = "$apiUrl/image/series-cover?seriesId=${result.seriesId}&apiKey=$apiKey"
            status = when (result.publicationStatus) {
                4 -> SManga.PUBLISHING_FINISHED
                2 -> SManga.COMPLETED
                0 -> SManga.ONGOING
                3 -> SManga.CANCELLED
                1 -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    /*
     * CHAPTER LIST
     * **/
    private val chapterNameTemplate: String
        get() = preferences.getString(
            LerFryhhConstants.CHAPTER_NAME_TEMPLATE_PREF,
            LerFryhhConstants.CHAPTER_NAME_TEMPLATE_DEFAULT,
        )!!.ifBlank { LerFryhhConstants.CHAPTER_NAME_TEMPLATE_DEFAULT }

    override fun chapterListRequest(manga: SManga): Request {
        val url = "$apiUrl/Series/volumes?seriesId=${helper.getIdFromUrl(manga.url)}"
        return GET(url, headersBuilder().build())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        try {
            val volumes = response.parseAs<List<VolumeDto>>()
            val template = chapterNameTemplate

            return volumes
                .flatMap { volume -> volume.chapters.map { helper.chapterFrom(it, volume, template) } }
                .sortedWith(LerFryhhHelper.CompareChapters)
        } catch (e: Exception) {
            Log.e(logTag, "Unhandled exception parsing chapters. Send logs to kavita devs", e)
            throw IOException(LerFryhhMessages.CHAPTERS_PARSE_ERROR)
        }
    }

    /**
     * Fetches the "url" of each page from the chapter
     * **/
    override fun pageListRequest(chapter: SChapter): Request = GET("$apiUrl/${chapter.url}", headersBuilder().build())

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val chapterId = chapter.url
        val numPages = chapter.scanlator?.replace(" pages", "")?.toInt()
        val numPages2 = "$numPages".toInt() - 1
        val pages = mutableListOf<Page>()
        for (i in 0..numPages2) {
            pages.add(
                Page(
                    index = i,
                    imageUrl = "$apiUrl/Reader/image?chapterId=$chapterId&page=$i&extractPdf=true&apiKey=$apiKey",
                ),
            )
        }
        return Observable.just(pages)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not used")

    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")

    override fun imageUrlParse(response: Response): String = ""

    /*
     * FILTERING
     **/

    private var currentFilter: MetadataPayload = MetadataPayload()

    /** Some variable names already exist. im not good at naming add Meta suffix */
    private var genresListMeta = emptyList<MetadataGenres>()
    private var tagsListMeta = emptyList<MetadataTag>()
    private var ageRatingsListMeta = emptyList<MetadataAgeRatings>()
    private var peopleListMeta = emptyList<MetadataPeople>()
    private var pubStatusListMeta = emptyList<MetadataPubStatus>()
    private var languagesListMeta = emptyList<MetadataLanguages>()
    private var libraryListMeta = emptyList<MetadataLibrary>()
    private var collectionsListMeta = emptyList<MetadataCollections>()
    private var smartFilters = emptyList<SmartFilter>()
    private val personRoles = listOf("Writer")

    /**
     * Loads the enabled filters if they are not empty so tachiyomi can show them to the user
     */
    override fun getFilterList(): FilterList {
        val toggledFilters = getToggledFilters()

        val filters = try {
            val peopleInRoles = mutableListOf<List<MetadataPeople>>()
            personRoles.map { role ->
                val peoplesWithRole = mutableListOf<MetadataPeople>()
                peopleListMeta.map {
                    if (it.role == helper.safeValueOf<PersonRole>(role).role) {
                        peoplesWithRole.add(it)
                    }
                }
                peopleInRoles.add(peoplesWithRole)
            }

            val filtersLoaded = mutableListOf<Filter<*>>()

            if (sortableList.isNotEmpty() and toggledFilters.contains("Sort Options")) {
                filtersLoaded.add(
                    SortFilter(sortableList.map { it.first }.toTypedArray()),
                )
                if (smartFilters.isNotEmpty()) {
                    filtersLoaded.add(
                        SmartFiltersFilter(smartFilters.map { it.name }.toTypedArray()),

                    )
                }
            }
            if (toggledFilters.contains("Read Status")) {
                filtersLoaded.add(
                    StatusFilterGroup(
                        listOf(
                            "notRead",
                            "inProgress",
                            "read",
                        ).map { StatusFilter(it) },
                    ),
                )
            }
            if (toggledFilters.contains("ReleaseYearRange")) {
                filtersLoaded.add(
                    ReleaseYearRangeGroup(
                        listOf("Min", "Max").map { ReleaseYearRange(it) },
                    ),
                )
            }

            if (genresListMeta.isNotEmpty() and toggledFilters.contains("Genres")) {
                filtersLoaded.add(
                    GenreFilterGroup(genresListMeta.map { GenreFilter(it.title) }),
                )
            }
            if (tagsListMeta.isNotEmpty() and toggledFilters.contains("Tags")) {
                filtersLoaded.add(
                    TagFilterGroup(tagsListMeta.map { TagFilter(it.title) }),
                )
            }
            if (ageRatingsListMeta.isNotEmpty() and toggledFilters.contains("Age Rating")) {
                filtersLoaded.add(
                    AgeRatingFilterGroup(ageRatingsListMeta.map { AgeRatingFilter(it.title) }),
                )
            }
            if (toggledFilters.contains("Format")) {
                filtersLoaded.add(
                    FormatsFilterGroup(
                        listOf(
                            "Image",
                            "Archive",
                            "Pdf",
                            "Unknown",
                        ).map { FormatFilter(it) },
                    ),
                )
            }
            if (collectionsListMeta.isNotEmpty() and toggledFilters.contains("Collections")) {
                filtersLoaded.add(
                    CollectionFilterGroup(collectionsListMeta.map { CollectionFilter(it.title) }),
                )
            }
            if (languagesListMeta.isNotEmpty() and toggledFilters.contains("Languages")) {
                filtersLoaded.add(
                    LanguageFilterGroup(languagesListMeta.map { LanguageFilter(it.title) }),
                )
            }
            if (libraryListMeta.isNotEmpty() and toggledFilters.contains("Libraries")) {
                filtersLoaded.add(
                    LibrariesFilterGroup(libraryListMeta.map { LibraryFilter(it.name) }),
                )
            }
            if (pubStatusListMeta.isNotEmpty() and toggledFilters.contains("Publication Status")) {
                filtersLoaded.add(
                    PubStatusFilterGroup(pubStatusListMeta.map { PubStatusFilter(it.title) }),
                )
            }
            if (pubStatusListMeta.isNotEmpty() and toggledFilters.contains("Rating")) {
                filtersLoaded.add(
                    UserRating(),
                )
            }

            // Escritores — os demais papéis (desenhista, letrista, editor, ...) foram removidos
            if (peopleInRoles[0].isNotEmpty() and toggledFilters.contains("Writers")) {
                filtersLoaded.addAll(
                    listOf<Filter<*>>(
                        PeopleHeaderFilter(""),
                        PeopleSeparatorFilter(),
                        PeopleHeaderFilter("PESSOAS"),
                        WriterPeopleFilterGroup(
                            peopleInRoles[0].map { WriterPeopleFilter(it.name) },
                        ),
                    ),
                )
            }
            filtersLoaded
        } catch (e: Exception) {
            Log.e(logTag, "[FILTERS] Error while creating filter list", e)
            emptyList()
        }
        return FilterList(filters)
    }

    /**
     * Returns a FilterV2Dto encoded as a json string with values taken from filter
     */
    private fun buildFilterBody(filter: MetadataPayload): String {
        val filterDto = FilterV2Dto()
        filterDto.sortOptions.sortField = filter.sorting
        filterDto.sortOptions.isAscending = filter.sorting_asc

        // Fields that support contains and not contains statements
        val containsAndNotTriplets = listOf(
            Triple(FilterField.Libraries, filter.libraries_i, filter.libraries_e),
            Triple(FilterField.Tags, filter.tags_i, filter.tags_e),
            Triple(FilterField.Languages, filter.language_i, filter.genres_e),
            Triple(FilterField.AgeRating, filter.ageRating_i, filter.ageRating_e),
            Triple(FilterField.Genres, filter.genres_i, filter.genres_e),
            Triple(FilterField.CollectionTags, filter.collections_i, filter.collections_e),
        )
        filterDto.addContainsNotTriple(containsAndNotTriplets)
        // Fields that have must contains statements
        val peoplePairs = listOf(
            Pair(FilterField.Writers, filter.peopleWriters),
            Pair(FilterField.PublicationStatus, filter.pubStatus),
        )
        filterDto.addPeople(peoplePairs)

        // Customized statements
        filterDto.addStatement(FilterComparison.Contains, FilterField.Formats, filter.formats)
        filterDto.addStatement(FilterComparison.Matches, FilterField.SeriesName, filter.seriesNameQuery)
        // Hardcoded statement to filter out epubs:
        filterDto.addStatement(FilterComparison.NotContains, FilterField.Formats, "3")
        if (filter.readStatus.isNotEmpty()) {
            filter.readStatus.forEach {
                if (it == "notRead") {
                    filterDto.addStatement(FilterComparison.Equal, FilterField.ReadProgress, "0")
                } else if (it == "inProgress") {
                    filterDto.addStatement(FilterComparison.GreaterThan, FilterField.ReadProgress, "0")
                    filterDto.addStatement(FilterComparison.LessThan, FilterField.ReadProgress, "100")
                } else if (it == "read") {
                    filterDto.addStatement(FilterComparison.Equal, FilterField.ReadProgress, "100")
                }
            }
        }
        // todo: check statement
        // filterDto.addStatement(FilterComparison.GreaterThanEqual, FilterField.UserRating, filter.userRating.toString())
        if (filter.releaseYearRangeMin != 0) {
            filterDto.addStatement(FilterComparison.GreaterThan, FilterField.ReleaseYear, filter.releaseYearRangeMin.toString())
        }

        if (filter.releaseYearRangeMax != 0) {
            filterDto.addStatement(FilterComparison.LessThan, FilterField.ReleaseYear, filter.releaseYearRangeMax.toString())
        }
        return json.encodeToJsonElement(filterDto).toString()
    }

    class LoginErrorException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable) : this(null, cause)
    }

    class OpdsurlExistsInPref(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable) : this(null, cause)
    }

    class EmptyRequestBody(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable) : this(null, cause)
    }

    class LoadingFilterFailed(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
        constructor(cause: Throwable) : this(null, cause)
    }

    override fun headersBuilder(): Headers.Builder {
        if (jwtToken.isEmpty()) {
            doLogin()
            if (jwtToken.isEmpty()) throw LoginErrorException(LerFryhhMessages.LOGIN_TOKEN_EMPTY)
        }
        return Headers.Builder()
            .add("User-Agent", "Tachiyomi Kavita v${AppInfo.getVersionName()}")
            .add("Content-Type", "application/json")
            .add("Authorization", "Bearer $jwtToken")
    }

    private fun setupLoginHeaders(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Tachiyomi Kavita v${AppInfo.getVersionName()}")
        .add("Content-Type", "application/json")
        .add("Authorization", "Bearer $jwtToken")

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val opdsAddressPref = screen.editTextPreference(
            ADDRESS_TITLE,
            "OPDS url",
            "",
            LerFryhhMessages.PREF_OPDS_SUMMARY,
        )
        val enabledFiltersPref = MultiSelectListPreference(screen.context).apply {
            key = LerFryhhConstants.TOGGLED_FILTERS_PREF
            title = LerFryhhMessages.PREF_FILTERS_TITLE
            summary = LerFryhhMessages.PREF_FILTERS_SUMMARY
            entries = LerFryhhConstants.filterPrefEntries
            entryValues = LerFryhhConstants.filterPrefEntriesValue
            setDefaultValue(LerFryhhConstants.defaultFilterPrefEntries)
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val checkValue = newValue as Set<String>
                preferences.edit()
                    .putStringSet(LerFryhhConstants.TOGGLED_FILTERS_PREF, checkValue)
                    .commit()
            }
        }
        val customSourceNamePref = EditTextPreference(screen.context).apply {
            key = LerFryhhConstants.CUSTOM_SOURCE_NAME_PREF
            title = LerFryhhMessages.PREF_CUSTOM_SOURCE_TITLE
            summary = LerFryhhMessages.PREF_CUSTOM_SOURCE_SUMMARY
            setOnPreferenceChangeListener { _, newValue ->
                val res = preferences.edit()
                    .putString(LerFryhhConstants.CUSTOM_SOURCE_NAME_PREF, newValue.toString())
                    .commit()
                Toast.makeText(
                    screen.context,
                    LerFryhhMessages.RESTART_APP,
                    Toast.LENGTH_LONG,
                ).show()
                Log.v(logTag, "[Preferences] Successfully modified custom source name: $newValue")
                res
            }
        }
        val chapterNameTemplatePref = EditTextPreference(screen.context).apply {
            key = LerFryhhConstants.CHAPTER_NAME_TEMPLATE_PREF
            title = "Nome dos capítulos"
            summary = "Modelo usado para nomear os capítulos. " +
                "Marcadores: {number}, {title}, {volume}, {pages}. " +
                "Padrão: ${LerFryhhConstants.CHAPTER_NAME_TEMPLATE_DEFAULT}"
            dialogTitle = "Nome dos capítulos"
            setDefaultValue(LerFryhhConstants.CHAPTER_NAME_TEMPLATE_DEFAULT)
        }

        val extraSourcesPref = ListPreference(screen.context).apply {
            key = LerFryhhConstants.EXTRA_SOURCES_COUNT_PREF
            title = "Fontes extras"
            summary = "Quantas fontes adicionais criar, para conectar em mais de um servidor. " +
                "Atual: %s. Requer reiniciar o app."
            entries = LerFryhhConstants.extraSourcesCountEntries
            entryValues = LerFryhhConstants.extraSourcesCountEntries
            setDefaultValue(LerFryhhConstants.EXTRA_SOURCES_COUNT_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val res = preferences.edit()
                    .putString(LerFryhhConstants.EXTRA_SOURCES_COUNT_PREF, newValue.toString())
                    .commit()
                Toast.makeText(
                    screen.context,
                    LerFryhhMessages.RESTART_APP,
                    Toast.LENGTH_LONG,
                ).show()
                res
            }
        }

        screen.addPreference(customSourceNamePref)
        screen.addPreference(opdsAddressPref)
        screen.addPreference(chapterNameTemplatePref)
        screen.addPreference(enabledFiltersPref)
        // Só a fonte principal decide quantas fontes existem.
        if (suffix == "1") {
            screen.addPreference(extraSourcesPref)
        }
    }

    private fun androidx.preference.PreferenceScreen.editTextPreference(
        preKey: String,
        title: String,
        default: String,
        summary: String,
        isPassword: Boolean = false,
    ): EditTextPreference = EditTextPreference(context).apply {
        key = preKey
        this.title = title
        val input = preferences.getString(title, null)
        this.summary = if (input == null || input.isEmpty()) summary else input
        this.setDefaultValue(default)
        dialogTitle = title

        if (isPassword) {
            setOnBindEditTextListener {
                it.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        setOnPreferenceChangeListener { _, newValue ->
            try {
                val opdsUrlInPref = opdsUrlInPreferences(newValue.toString()) // We don't allow hot have multiple sources with same ip or domain
                if (opdsUrlInPref.isNotEmpty()) {
                    // TODO("Add option to allow multiple sources with same url at the cost of tracking")
                    preferences.edit().putString(title, "").apply()

                    Toast.makeText(
                        context,
                        LerFryhhMessages.PREF_OPDS_DUPLICATED + ": " + opdsUrlInPref,
                        Toast.LENGTH_LONG,
                    ).show()
                    throw OpdsurlExistsInPref(LerFryhhMessages.PREF_OPDS_DUPLICATED + opdsUrlInPref)
                }

                val res = preferences.edit().putString(title, newValue as String).commit()
                Toast.makeText(
                    context,
                    LerFryhhMessages.RESTART_APP,
                    Toast.LENGTH_LONG,
                ).show()
                setupLogin(newValue)
                Log.v(logTag, "[Preferences] Successfully modified OPDS URL")
                res
            } catch (e: OpdsurlExistsInPref) {
                Log.e(logTag, "Url exists in a different sourcce")
                false
            } catch (e: Exception) {
                Log.e(logTag, "Unrecognised error", e)
                false
            }
        }
    }

    private fun getPrefBaseUrl(): String = preferences.getString("BASEURL", "")!!
    private fun getPrefApiUrl(): String = preferences.getString("APIURL", "")!!
    private fun getPrefKey(): String = preferences.getString("APIKEY", "")!!
    private fun getToggledFilters() = preferences.getStringSet(LerFryhhConstants.TOGGLED_FILTERS_PREF, LerFryhhConstants.defaultFilterPrefEntries)!!

    // We strip the last slash since we will append it above
    private fun getPrefAddress(): String {
        var path = preferences.getString(ADDRESS_TITLE, "")!!
        if (path.isNotEmpty() && path.last() == '/') {
            path = path.substring(0, path.length - 1)
        }
        return path
    }

    private fun getPrefApiKey(): String {
        // http(s)://host:(port)/api/opds/api-key
        val existingKey = preferences.getString("APIKEY", "")
        return existingKey!!.ifEmpty { preferences.getString(ADDRESS_TITLE, "")!!.split("/opds/")[1] }
    }

    companion object {
        private const val ADDRESS_TITLE = "Address"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }

    /*
     * LOGIN
     **/

    /** Preferências de outra instância da fonte, identificada pelo seu sufixo. */
    private fun sharedPreferencesOf(sourceSuffix: String): SharedPreferences {
        val key = "${"lerfryhh_$sourceSuffix"}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val sourceId = (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE

        return Injekt.get<Application>().getSharedPreferences("source_$sourceId", 0x0000)
    }

    /**
     * Used to check if a url is configured already in any of the sources
     * This is a limitation needed for tracking.
     * **/
    private fun opdsUrlInPreferences(url: String): String {
        fun getCleanedApiUrl(url: String): String = "${url.split("/api/").first()}/api"

        // A fonte principal ("1") guarda quantas fontes extras existem.
        val sourceCount = 1 + (
            sharedPreferencesOf("1")
                .getString(
                    LerFryhhConstants.EXTRA_SOURCES_COUNT_PREF,
                    LerFryhhConstants.EXTRA_SOURCES_COUNT_DEFAULT,
                )!!
                .toIntOrNull() ?: 0
            )

        for (sourceId in 1..sourceCount) {
            val preferences = sharedPreferencesOf(sourceId.toString())
            val prefApiUrl = preferences.getString("APIURL", "")!!

            if (prefApiUrl.isNotEmpty()) {
                if (prefApiUrl == getCleanedApiUrl(url)) {
                    if (sourceId.toString() != suffix) {
                        return preferences.getString(LerFryhhConstants.CUSTOM_SOURCE_NAME_PREF, sourceId.toString())!!
                    }
                }
            }
        }
        return ""
    }

    private fun setupLogin(addressFromPreference: String = "") {
        Log.v(logTag, "[Setup Login] Starting setup")
        val validAddress = address.ifEmpty { addressFromPreference }
        val tokens = validAddress.split("/api/opds/")
        val apiKey = tokens[1]
        val baseUrlSetup = tokens[0].replace("\n", "\\n")

        if (baseUrlSetup.toHttpUrlOrNull() == null) {
            Log.e(logTag, "Invalid URL $baseUrlSetup")
            throw Exception("""${LerFryhhMessages.LOGIN_INVALID_URL}: $baseUrlSetup""")
        }
        preferences.edit().putString("BASEURL", baseUrlSetup).apply()
        preferences.edit().putString("APIKEY", apiKey).apply()
        preferences.edit().putString("APIURL", "$baseUrlSetup/api").apply()
        Log.v(logTag, "[Setup Login] Setup successful")
    }

    private fun doLogin() {
        if (address.isEmpty()) {
            Log.e(logTag, "OPDS URL is empty or null")
            throw IOException(LerFryhhMessages.PREF_OPDS_MISSING_ADDRESS)
        }
        if (address.split("/opds/").size != 2) {
            throw IOException(LerFryhhMessages.PREF_OPDS_BAD_URL)
        }
        if (jwtToken.isEmpty()) setupLogin()
        Log.v(logTag, "[Login] Starting login")
        val request = POST(
            "$apiUrl/Plugin/authenticate?apiKey=${getPrefKey()}&pluginName=Tachiyomi-Kavita",
            setupLoginHeaders().build(),
            "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
        )
        client.newCall(request).execute().use {
            val peekbody = it.peekBody(Long.MAX_VALUE).toString()

            if (it.code == 200) {
                try {
                    jwtToken = it.parseAs<AuthenticationDto>().token
                    isLogged = true
                } catch (e: Exception) {
                    Log.e(logTag, "Possible outdated kavita", e)
                    throw IOException(LerFryhhMessages.LOGIN_TOKEN_PARSE)
                }
            } else {
                if (it.code == 500) {
                    Log.e(logTag, "[LOGIN] login failed. There was some error -> Code: ${it.code}.Response message: ${it.message} Response body: $peekbody.")
                    throw LoginErrorException(LerFryhhMessages.LOGIN_FAILED)
                } else {
                    Log.e(logTag, "[LOGIN] login failed. Authentication was not successful -> Code: ${it.code}.Response message: ${it.message} Response body: $peekbody.")
                    throw LoginErrorException(LerFryhhMessages.LOGIN_FAILED)
                }
            }
        }
        Log.v(logTag, "[Login] Login successful")
    }

    init {
        if (apiUrl.isNotBlank()) {
            Single.fromCallable {
                // Login
                doLogin()
                try { // Get current version
                    val requestUrl = "$apiUrl/Server/server-info"
                    val serverInfoDto = client.newCall(GET(requestUrl, headersBuilder().build()))
                        .execute()
                        .parseAs<ServerInfoDto>()
                    Log.e(
                        logTag,
                        "Extension version: code=${AppInfo.getVersionCode()}  name=${AppInfo.getVersionName()}" +
                            " - - Kavita version: ${serverInfoDto.kavitaVersion} - - Lang:${Locale.getDefault()}",
                    ) // this is not a real error. Using this so it gets printed in dump logs if there's any error
                } catch (e: EmptyRequestBody) {
                    Log.e(logTag, "Extension version: code=${AppInfo.getVersionCode()} - name=${AppInfo.getVersionName()}")
                } catch (e: Exception) {
                    Log.e(logTag, "Tachiyomi version: code=${AppInfo.getVersionCode()} - name=${AppInfo.getVersionName()}", e)
                }
                try { // Load Filters
                    // Genres
                    Log.v(logTag, "[Filter] Fetching filters ")
                    client.newCall(GET("$apiUrl/Metadata/genres", headersBuilder().build()))
                        .execute().use { response ->

                            genresListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(logTag, "[Filter] Error decoding JSON for genres filter -> ${response.body}", e)
                                emptyList()
                            }
                        }
                    // tagsListMeta
                    client.newCall(GET("$apiUrl/Metadata/tags", headersBuilder().build()))
                        .execute().use { response ->
                            tagsListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(logTag, "[Filter] Error decoding JSON for tagsList filter", e)
                                emptyList()
                            }
                        }
                    // age-ratings
                    client.newCall(GET("$apiUrl/Metadata/age-ratings", headersBuilder().build()))
                        .execute().use { response ->
                            ageRatingsListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    logTag,
                                    "[Filter] Error decoding JSON for age-ratings filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    // collectionsListMeta
                    client.newCall(GET("$apiUrl/Collection", headersBuilder().build()))
                        .execute().use { response ->
                            collectionsListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    logTag,
                                    "[Filter] Error decoding JSON for collectionsListMeta filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    // languagesListMeta
                    client.newCall(GET("$apiUrl/Metadata/languages", headersBuilder().build()))
                        .execute().use { response ->
                            languagesListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    logTag,
                                    "[Filter] Error decoding JSON for languagesListMeta filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    // libraries
                    client.newCall(GET("$apiUrl/Library", headersBuilder().build()))
                        .execute().use { response ->
                            libraryListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    logTag,
                                    "[Filter] Error decoding JSON for libraries filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    // peopleListMeta
                    client.newCall(GET("$apiUrl/Metadata/people", headersBuilder().build()))
                        .execute().use { response ->
                            peopleListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    logTag,
                                    "error while decoding JSON for peopleListMeta filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    client.newCall(GET("$apiUrl/Metadata/publication-status", headersBuilder().build()))
                        .execute().use { response ->
                            pubStatusListMeta = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    logTag,
                                    "error while decoding JSON for publicationStatusListMeta filter",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    client.newCall(GET("$apiUrl/filter", headersBuilder().build()))
                        .execute().use { response ->
                            smartFilters = try {
                                response.body.use { json.decodeFromString(it.string()) }
                            } catch (e: Exception) {
                                Log.e(
                                    logTag,
                                    "error while decoding JSON for smartfilters",
                                    e,
                                )
                                emptyList()
                            }
                        }
                    Log.v(logTag, "[Filter] Successfully loaded metadata tags from server")
                } catch (e: Exception) {
                    throw LoadingFilterFailed("Failed Loading Filters", e.cause)
                }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                    {},
                    { tr ->
                        // Avoid polluting logs with traces of exception
                        if (tr is EmptyRequestBody || tr is LoginErrorException) {
                            Log.e(logTag, "error while doing initial calls\n${tr.cause}")
                            return@subscribe
                        }
                        if (tr is ConnectException) { // avoid polluting logs with traces of exception
                            Log.e(logTag, "Error while doing initial calls\n${tr.cause}")
                            return@subscribe
                        }
                        Log.e(logTag, "error while doing initial calls", tr)
                    },
                )
        }
    }
}
