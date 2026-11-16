# JA — current project

Current testing version: **2.1.0 (20100)**, based on v2.0.10.

JA uses Kotlin/Jetpack Compose, TMDB metadata, and embedded GeckoView playback on Android phones and Android TV. Minimum Android API 26; target API 35. VidSrc is the primary source; source-specific integration includes VidFast.

## Build

The checked-in configuration uses Java 17, Gradle 9.6.0, AGP 9.5.1, Kotlin/Compose compiler 2.4.21, compile SDK 37.2, GeckoView 160.0.20261104181552 and Media3 1.11.1. Keep the repository's existing Actions secrets and signing configuration.

Run **Build JA APK** on your testing branch. The APK artifact is **JA-2.1.0-debug**. Local build: configure local.properties using local.properties.example, then run `gradle assembleDebug lintDebug` with the configured SDK and toolchain. The workflow reports lint separately with continue-on-error; a green workflow alone does not establish zero lint errors. See [update setup](UPDATE-SETUP.md) for persistent signing and publishing. Publish only when the build is ready for devices.

## Current behavior and test status

- v2.1.0 reduces menu/details content a further 5% from the previously verified 80% size (76% of the original size). Text, posters, spacing and focus bounds scale together.
- The two main navigation bars keep their original sizing.
- TV frame-rate and output-resolution matching are enabled by default on TV, with persisted settings.
- Stream-quality matching uses an accessible JW API when available, otherwise the custom menu adapter. Three v2.0.7 device tests successfully selected 1080p with user-confirmed visual improvements; subtitles were verified off. The successful tests used the menu adapter, not a direct JW API.
- v2.1.0 conceals automatic quality menus with extension-owned CSS and a transaction-scoped clipping fallback, preserving the existing open/focus/click/settle delays. Manual input restores the menu and cancels delayed selection/cleanup. Live GeckoView testing of concealment and the extra 5% sizing remains required.
- User confirmed a successful in-app installation from v2.0.9 to v2.0.10. Future updates must use the same permanent signing keystore and a higher versionCode.
- Catalog ID downloads provide a source catalog; membership does not guarantee a playable stream or establish its quality.

## Documentation

- [Playback review and proposed improvements](docs/PLAYER_REVIEW.md)
- [Historical notes](docs/HISTORY.md) — all 10 former root Markdown files preserved verbatim, including the former README.

Use this cleaned source tree in a fresh checkout/testing branch. Copying it over an existing checkout does not delete obsolete root Markdown files; remove the old BRANDING_UPDATE.md, PLAYBACK_SETUP.md, CHANGED-FILES-review2.md through CHANGED-FILES-review6.md, and VALIDATION-review5.md / VALIDATION-review6.md after confirming docs/HISTORY.md is present. README.md is replaced.
