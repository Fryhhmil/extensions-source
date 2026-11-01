package eu.kanade.tachiyomi.extension.pt.lerfryhh

import eu.kanade.tachiyomi.extension.pt.lerfryhh.LerFryhhConstants.NO_SMART_FILTER_SELECTED
import eu.kanade.tachiyomi.source.model.Filter

class UserRating :
    Filter.Select<String>(
        "Nota mínima",
        arrayOf(
            "Qualquer",
            "1 estrela",
            "2 estrelas",
            "3 estrelas",
            "4 estrelas",
            "5 estrelas",
        ),
    )
class SmartFiltersFilter(smartFilters: Array<String>) : Filter.Select<String>("Filtros inteligentes", arrayOf(NO_SMART_FILTER_SELECTED) + smartFilters)
class SortFilter(sortables: Array<String>) : Filter.Sort("Ordenar por", sortables, Selection(0, true))

val sortableList = listOf(
    Pair("Nome", 1),
    Pair("Criado em", 2),
    Pair("Modificado em", 3),
    Pair("Adicionado em", 4),
    Pair("Tempo de leitura", 5),
    Pair("Ano de lançamento", 6),
)

class StatusFilter(name: String) : Filter.CheckBox(name, false)
class StatusFilterGroup(filters: List<StatusFilter>) : Filter.Group<StatusFilter>("Situação da leitura", filters)

class ReleaseYearRange(name: String) : Filter.Text(name)
class ReleaseYearRangeGroup(filters: List<ReleaseYearRange>) : Filter.Group<ReleaseYearRange>("Ano de lançamento", filters)
class GenreFilter(name: String) : Filter.TriState(name)
class GenreFilterGroup(genres: List<GenreFilter>) : Filter.Group<GenreFilter>("Gêneros", genres)

class TagFilter(name: String) : Filter.TriState(name)
class TagFilterGroup(tags: List<TagFilter>) : Filter.Group<TagFilter>("Tags", tags)

class AgeRatingFilter(name: String) : Filter.TriState(name)
class AgeRatingFilterGroup(ageRatings: List<AgeRatingFilter>) : Filter.Group<AgeRatingFilter>("Classificação etária", ageRatings)

class FormatFilter(name: String) : Filter.CheckBox(name, false)
class FormatsFilterGroup(formats: List<FormatFilter>) : Filter.Group<FormatFilter>("Formatos", formats)

class CollectionFilter(name: String) : Filter.TriState(name)
class CollectionFilterGroup(collections: List<CollectionFilter>) : Filter.Group<CollectionFilter>("Coleções", collections)

class LanguageFilter(name: String) : Filter.TriState(name)
class LanguageFilterGroup(languages: List<LanguageFilter>) : Filter.Group<LanguageFilter>("Idioma", languages)

class LibraryFilter(library: String) : Filter.TriState(library)
class LibrariesFilterGroup(libraries: List<LibraryFilter>) : Filter.Group<LibraryFilter>("Bibliotecas", libraries)

class PubStatusFilter(name: String) : Filter.CheckBox(name, false)
class PubStatusFilterGroup(status: List<PubStatusFilter>) : Filter.Group<PubStatusFilter>("Status de publicação", status)

class PeopleHeaderFilter(name: String) : Filter.Header(name)
class PeopleSeparatorFilter : Filter.Separator()

class WriterPeopleFilter(name: String) : Filter.CheckBox(name, false)
class WriterPeopleFilterGroup(peoples: List<WriterPeopleFilter>) : Filter.Group<WriterPeopleFilter>("Escritores", peoples)
