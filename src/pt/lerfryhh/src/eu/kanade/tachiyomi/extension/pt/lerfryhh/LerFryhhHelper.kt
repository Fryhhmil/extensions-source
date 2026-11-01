package eu.kanade.tachiyomi.extension.pt.lerfryhh

import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.ChapterDto
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.PaginationInfo
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.SeriesDto
import eu.kanade.tachiyomi.extension.pt.lerfryhh.dto.VolumeDto
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val WHITESPACE_REGEX = "\\s+".toRegex()

class LerFryhhHelper {
    val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
        useArrayPolymorphism = true
        prettyPrint = true
    }
    inline fun <reified T : Enum<T>> safeValueOf(type: String): T = java.lang.Enum.valueOf(T::class.java, type)
    val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSS", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun parseDate(dateAsString: String): Long = dateFormatter.parse(dateAsString)?.time ?: 0

    fun hasNextPage(response: Response): Boolean {
        val paginationHeader = response.header("Pagination")
        var hasNextPage = false
        if (!paginationHeader.isNullOrEmpty()) {
            val paginationInfo = json.decodeFromString<PaginationInfo>(paginationHeader)
            hasNextPage = paginationInfo.currentPage + 1 > paginationInfo.totalPages
        }
        return !hasNextPage
    }

    fun getIdFromUrl(url: String): Int = url.split("/").last().toInt()

    fun createSeriesDto(obj: SeriesDto, baseUrl: String, apiKey: String): SManga = SManga.create().apply {
        url = "$baseUrl/Series/${obj.id}"
        title = obj.name
        // Deprecated: description = obj.summary
        thumbnail_url = "$baseUrl/image/series-cover?seriesId=${obj.id}&apiKey=$apiKey"
    }
    class CompareChapters {
        companion object : Comparator<SChapter> {
            override fun compare(a: SChapter, b: SChapter): Int {
                if (a.chapter_number < 1.0 && b.chapter_number < 1.0) {
                    // Both are volumes, multiply by 100 and do normal sort
                    return if ((a.chapter_number * 100) < (b.chapter_number * 100)) {
                        1
                    } else {
                        -1
                    }
                } else {
                    if (a.chapter_number < 1.0 && b.chapter_number >= 1.0) {
                        // A is volume, b is not. A should sort first
                        return 1
                    } else if (a.chapter_number >= 1.0 && b.chapter_number < 1.0) {
                        return -1
                    }
                }
                if (a.chapter_number < b.chapter_number) return 1
                if (a.chapter_number > b.chapter_number) return -1
                return 0
            }
        }
    }

    /**
     * Monta o capítulo a partir do DTO.
     *
     * O Kavita marca com números sentinela os volumes que não são volumes de verdade
     * ([LerFryhhConstants.LOOSE_LEAF_VOLUME] para capítulos avulsos,
     * [LerFryhhConstants.SPECIAL_VOLUME] para especiais) e os capítulos sem numeração
     * ([LerFryhhConstants.DEFAULT_CHAPTER_NUMBER]). Sem tratá-los, o sentinela vaza para
     * o nome do capítulo (o famigerado "Volume -100000 Chapter 12").
     */
    fun chapterFrom(obj: ChapterDto, volume: VolumeDto, nameTemplate: String): SChapter = SChapter.create().apply {
        val parsedNumber = obj.number.toFloatOrNull()
        val hasChapterNumber = parsedNumber != null &&
            parsedNumber != LerFryhhConstants.DEFAULT_CHAPTER_NUMBER.toFloat()
        val hasVolume = volume.number != 0 &&
            volume.number != LerFryhhConstants.LOOSE_LEAF_VOLUME &&
            volume.number != LerFryhhConstants.SPECIAL_VOLUME
        val specialName = obj.title.ifBlank { obj.range }.trim()

        url = obj.id.toString()
        date_upload = parseDate(obj.created)
        scanlator = "${obj.pages} páginas"

        when {
            // Especiais (extras, one-shots, ...): usam o próprio nome
            obj.isSpecial || volume.number == LerFryhhConstants.SPECIAL_VOLUME -> {
                name = specialName.ifEmpty { "Especial" }
                chapter_number = 0f
            }
            // Volume em arquivo único: não existe número de capítulo
            !hasChapterNumber && hasVolume -> {
                name = "Volume ${formatNumber(volume.number.toFloat())}"
                // Número codificado para o rastreamento continuar funcionando
                chapter_number = volume.number.toFloat() / 10000
            }
            !hasChapterNumber -> {
                name = specialName.ifEmpty { "Capítulo" }
                chapter_number = 0f
            }
            else -> {
                name = renderChapterName(nameTemplate, parsedNumber, obj, volume.takeIf { hasVolume })
                chapter_number = parsedNumber
            }
        }
    }

    private fun renderChapterName(
        template: String,
        number: Float,
        obj: ChapterDto,
        volume: VolumeDto?,
    ): String {
        // O Kavita repete o número em `title` quando o capítulo não tem título próprio
        val title = obj.title.trim()
            .takeUnless { it.isEmpty() || it.trimStart('0') == obj.number.trimStart('0') }
            .orEmpty()

        return template
            .replace("{number}", formatNumber(number))
            .replace("{volume}", volume?.let { formatNumber(it.number.toFloat()) }.orEmpty())
            .replace("{title}", title)
            .replace("{pages}", obj.pages.toString())
            // Placeholders vazios deixam espaços e separadores soltos para trás
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .trim('-', '–', ':')
            .trim()
            .ifEmpty { "Capítulo ${formatNumber(number)}" }
    }

    private fun formatNumber(number: Float): String = if (number == number.toInt().toFloat()) number.toInt().toString() else number.toString()
}
