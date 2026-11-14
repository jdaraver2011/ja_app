package com.example.ja

import java.time.LocalDate

/** Server-side discovery filters; responses are also checked against the same calendar rule.
 * Keep this filename as a replacement for the old per-movie network resolver.
 * No country/type restriction and no individual release_dates requests are needed.
 */
internal fun movieReleaseParams(today: LocalDate = LocalDate.now()): Map<String, String> = mapOf(
    "primary_release_date.lte" to JaMovieReleaseRules.releasedThrough(today)
)

internal fun movieUpcomingParams(today: LocalDate = LocalDate.now()): Map<String, String> = mapOf(
    "primary_release_date.gte" to JaMovieReleaseRules.upcomingFrom(today)
)
