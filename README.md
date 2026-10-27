# JA — Kotlin Android streaming starter

## Open in Android Studio
1. Install Android Studio with Android SDK 35, JDK 17 and a compatible Gradle environment.
2. Open this directory as a project. Let Android Studio download Gradle and dependencies.
3. Create `local.properties` with `sdk.dir` (Android Studio usually creates this) and `TMDB_API_KEY=...` using your TMDB v3 API key.
4. Run on a phone or Android TV emulator. The launcher includes a Leanback category and the UI uses focusable Compose buttons/cards.
5. Optional licensed embed integration: set `AUTHORIZED_EMBED_TEMPLATE` and `AUTHORIZED_EMBED_HOST` in local.properties. For example, `https://video.example.org/embed/{type}/{id}` with host `video.example.org`, only if you control or have authorization for that endpoint.

## Features
- Trending, popular movies, popular TV, multi-search, details, TV seasons/episodes
- Local favorites and watch entries with Room
- Native Media3 playback with resume positions saved on leaving the player
- Optional restricted WebView embed playback (no reliable resume-position tracking)
- Android phone/tablet/TV launcher compatibility

## Provider implementation
`PlaybackProvider` returns either `PlaybackSource.Native` (HTTPS stream) or `PlaybackSource.Embed` (HTTPS authorized embed). Add a provider to `PlaybackRegistry`. No unlicensed streams are bundled. A VidSrc-style adapter is represented by the generic embed template; do not use it without appropriate permission and content rights. No extraction, anti-bot bypass or DRM circumvention is implemented.

## Limitations / next steps
- This is a functional source-code starter, not a production-certified APK; build has not been run in this environment.
- Native resume writes position on player disposal; periodic checkpoints and cross-device sync would improve reliability.
- TV navigation uses basic Compose focus traversal, not a full custom TV-focused layout; validate on physical remote hardware.
- Search uses explicit submit; favorites are stored locally, not in a cloud account.
- Secure production deployment should proxy TMDB credentials through a backend (BuildConfig keys are extractable), add rate limiting, network security policy, accessibility QA, pagination, user accounts, and rights-aware content availability.
- Embedded content may refuse WebView playback, require consent, or open restricted navigation. Such restrictions must be respected.
- TMDB attribution and terms must be reviewed before publishing. TMDB is not a video host.
