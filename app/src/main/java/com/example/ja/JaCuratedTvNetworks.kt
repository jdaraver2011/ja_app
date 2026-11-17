package com.example.ja

import java.util.Locale

/** Deliberate U.S. broadcast/cable/streaming selection, not the full TMDB directory. */
internal object JaCuratedTvNetworks {
    private data class Entry(val label: String, val tmdbId: Int?, val aliases: Set<String> = setOf(label))
    private val entries = listOf(
        Entry("ABC", 2),
        Entry("CBS", 16),
        Entry("The CW", 71, setOf("The CW", "CW")),
        Entry("FOX", 19, setOf("FOX", "Fox")),
        Entry("NBC", 6),
        Entry("Telemundo", 193),
        Entry("Univision", 28),
        Entry("A&E", 129),
        Entry("Adult Swim", 80),
        Entry("AMC", 174),
        Entry("Animal Planet", 91),
        Entry("BET", 24),
        Entry("Bravo", 74),
        Entry("Cartoon Network", 56),
        Entry("CNN", 59),
        Entry("Comedy Central", 47),
        Entry("Discovery", 64, setOf("Discovery", "Discovery Channel")),
        Entry("Disney Channel", 54),
        Entry("Disney XD", 44),
        Entry("E!", 76),
        Entry("ESPN", 29),
        Entry("Food Network", 143),
        Entry("Freeform", 75),
        Entry("FX", 88),
        Entry("FXX", 1035),
        Entry("Hallmark Channel", 384),
        Entry("HBO", 49),
        Entry("HGTV", 210),
        Entry("History", 65, setOf("History", "History Channel")),
        Entry("Investigation Discovery", 244),
        Entry("Lifetime", 34),
        Entry("MTV", 33),
        Entry("National Geographic", 43),
        Entry("Nickelodeon", 13),
        Entry("OWN", 1465, setOf("OWN", "Oprah Winfrey Network")),
        Entry("Paramount Network", 207),
        Entry("Showtime", 67),
        Entry("Starz", 318),
        Entry("Syfy", 77),
        Entry("TBS", 68),
        Entry("TLC", 84),
        Entry("TNT", 41),
        Entry("TV Land", 397),
        Entry("USA Network", 30),
        Entry("AMC+", 4661),
        Entry("Apple TV+", 2552, setOf("Apple TV+", "Apple TV")),
        Entry("BET+", null),
        Entry("Discovery+", 4353),
        Entry("Disney+", 2739),
        Entry("HBO Max", 3186),
        Entry("Hulu", 453),
        Entry("Max", 6783),
        Entry("Netflix", 213),
        Entry("Paramount+", 4330),
        Entry("Peacock", 3353),
        Entry("Prime Video", 1024, setOf("Prime Video", "Amazon Prime Video", "Amazon")),
        Entry("The Roku Channel", null, setOf("The Roku Channel", "Roku Channel")),
        Entry("Tubi", null)
    )
    private fun normalized(value: String): String = value.trim().lowercase(Locale.ROOT)
    fun select(directory: List<TmdbNetwork>): List<TmdbNetwork> = entries.mapNotNull { entry ->
        val names = entry.aliases.map(::normalized).toSet()
        val matches = directory.filter { normalized(it.name) in names }
        // Pin ambiguous broadcast/cable names to their U.S. TMDB IDs.
        // For platforms without a pinned ID, accept only an unambiguous match.
        val match = if (entry.tmdbId != null) matches.firstOrNull { it.id == entry.tmdbId }
            else matches.singleOrNull()
        match?.copy(name = entry.label)
    }.distinctBy { it.id }
}
