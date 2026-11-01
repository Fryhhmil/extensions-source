package eu.kanade.tachiyomi.extension.pt.lerfryhh

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class LerFryhhFactory : SourceFactory {
    override fun createSources(): List<Source> {
        val firstSource = LerFryhh("1")
        val extraSources = firstSource.preferences
            .getString(
                LerFryhhConstants.EXTRA_SOURCES_COUNT_PREF,
                LerFryhhConstants.EXTRA_SOURCES_COUNT_DEFAULT,
            )!!
            .toIntOrNull() ?: 0

        // LerFryhh("1"), LerFryhh("2"), LerFryhh("3"), ...
        return buildList(extraSources + 1) {
            add(firstSource)

            for (i in 0 until extraSources) {
                add(LerFryhh("${i + 2}"))
            }
        }
    }
}
