package eu.kanade.tachiyomi.extension.pt.lerfryhh

object LerFryhhConstants {
    // Filtros que o usuário pode ligar/desligar.
    // Os *valores* são as chaves esperadas pelo código; só os rótulos são traduzidos.
    const val TOGGLED_FILTERS_PREF = "toggledFilters"

    val filterPrefEntries = arrayOf(
        "Ordenação",
        "Formato",
        "Bibliotecas",
        "Situação da leitura",
        "Gêneros",
        "Tags",
        "Coleções",
        "Idiomas",
        "Status de publicação",
        "Nota mínima",
        "Classificação etária",
        "Escritores",
        "Ano de lançamento",
    )

    val filterPrefEntriesValue = arrayOf(
        "Sort Options",
        "Format",
        "Libraries",
        "Read Status",
        "Genres",
        "Tags",
        "Collections",
        "Languages",
        "Publication Status",
        "Rating",
        "Age Rating",
        "Writers",
        "ReleaseYearRange",
    )

    val defaultFilterPrefEntries = filterPrefEntriesValue.toSet()

    const val CUSTOM_SOURCE_NAME_PREF = "customSourceName"
    const val NO_SMART_FILTER_SELECTED = "Nenhum filtro inteligente"

    /**
     * Números sentinela que o Kavita usa quando não há volume/capítulo de verdade.
     * Ver `ParserConstants.cs` no servidor.
     */
    const val LOOSE_LEAF_VOLUME = -100_000
    const val SPECIAL_VOLUME = 100_000
    const val DEFAULT_CHAPTER_NUMBER = -100_000

    const val CHAPTER_NAME_TEMPLATE_PREF = "chapterNameTemplate"
    const val CHAPTER_NAME_TEMPLATE_DEFAULT = "Capítulo {number}"

    /** Quantidade de fontes extras além da principal. Zero = uma única fonte. */
    const val EXTRA_SOURCES_COUNT_PREF = "extraSourcesCount"
    const val EXTRA_SOURCES_COUNT_DEFAULT = "0"
    val extraSourcesCountEntries = arrayOf("0", "1", "2", "3", "4", "5")
}
