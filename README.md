# JA — current project

The uploaded project declares **2.0.2 (20002)**, despite the ZIP filename containing 2.0.0. This documentation consolidation does not change executable code or the version.

JA uses Kotlin/Jetpack Compose, TMDB metadata, and embedded GeckoView playback on Android phones and Android TV. Minimum Android API 26; target API 35. VidSrc is the primary source; source-specific integration includes VidFast.

## Build

The checked-in configuration uses Java 17, Gradle 9.6.0, AGP 9.5.1, Kotlin/Compose compiler 2.4.21, compile SDK 37.2, GeckoView 160.0.20261104181552 and Media3 1.11.1. Keep the repository's existing Actions secrets and signing configuration.

Run **Build JA APK** on your testing branch. The APK artifact is **JA-2.0.2-debug**. Local build: configure local.properties using local.properties.example, then run `gradle assembleDebug lintDebug` with the configured SDK and toolchain. The workflow reports lint separately with continue-on-error; a green workflow alone does not establish zero lint errors.

## Current behavior and test status

- User verified successful compilation and installation, the 20% menu/details sizing, and centered genre Load More buttons.
- The two main navigation bars keep their original sizing.
- TV frame-rate and output-resolution matching are enabled by default on TV, with persisted settings.
- Experimental stream-quality matching targets the physically applied TV output via the JW API. It requires supported player APIs and confirmed playback. VidFast is excluded from this path.
- User reports stream quality remains Auto and subtitles are inconsistently disabled. These remain open defects, not verified fixes.
- Catalog ID downloads provide a source catalog; membership does not guarantee a playable stream or establish its quality.

## Documentation

- [Playback review and proposed improvements](docs/PLAYER_REVIEW.md)
- [Historical notes](docs/HISTORY.md) — all 10 former root Markdown files preserved verbatim, including the former README.

Use this cleaned source tree in a fresh checkout/testing branch. Copying it over an existing checkout does not delete obsolete root Markdown files; remove the old BRANDING_UPDATE.md, PLAYBACK_SETUP.md, CHANGED-FILES-review2.md through CHANGED-FILES-review6.md, and VALIDATION-review5.md / VALIDATION-review6.md after confirming docs/HISTORY.md is present. README.md is replaced.
