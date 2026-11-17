# JA — current project

Current testing version: **2.1.4 (20104)**, based on v2.1.3.

JA uses Kotlin/Jetpack Compose, TMDB metadata, and embedded GeckoView playback on Android phones and Android TV. Minimum Android API 26; target API 35. VidSrc is the primary source; source-specific integration includes VidFast.

## Build

The checked-in configuration uses Java 17, Gradle 9.6.0, AGP 9.5.1, Kotlin/Compose compiler 2.4.21, compile SDK 37.2, GeckoView 160.0.20261104181552 and Media3 1.11.1. Keep the repository's existing Actions secrets and signing configuration.

Run **Build JA APK** on your testing branch. The APK artifact is **JA-2.1.4-debug**. Local build: configure local.properties using local.properties.example, then run `gradle assembleDebug lintDebug` with the configured SDK and toolchain. The workflow reports lint separately with continue-on-error; a green workflow alone does not establish zero lint errors. See [update setup](UPDATE-SETUP.md) for persistent signing and publishing. Publish only when the build is ready for devices.

## Current behavior and test status

- v2.1.4 restricts TV Networks to a curated U.S. commercial broadcast, major cable and streaming-platform selection. Both newly fetched directories and v2.1.3 saved directories are filtered, so an update does not require clearing app data. Names are matched against TMDB; ambiguous U.S. broadcasters/cable networks are pinned by ID, and unavailable/ambiguous unpinned platforms are omitted rather than matched to an unrelated network. Rows remain alphabetical, independently populated and paginated.
- Selected labels: A&E, ABC, Adult Swim, AMC, AMC+, Animal Planet, Apple TV+, BET, BET+, Bravo, Cartoon Network, CBS, CNN, Comedy Central, Discovery, Discovery+, Disney Channel, Disney XD, Disney+, E!, ESPN, Food Network, FOX, Freeform, FX, FXX, Hallmark Channel, HBO, HBO Max, HGTV, History, Hulu, Investigation Discovery, Lifetime, Max, MTV, National Geographic, NBC, Netflix, Nickelodeon, OWN, Paramount Network, Paramount+, Peacock, Prime Video, Showtime, Starz, Syfy, TBS, Telemundo, The CW, The Roku Channel, TLC, TNT, Tubi, TV Land, Univision, USA Network.
- The branch-scoped release-v2.1.4 workflow runs the existing build with publication enabled for this requested release. It is temporary and will be removed after completion. Publishing runs now require lint to pass, and same-repository release tags point at the built commit.

- v2.1.3 adds TV > Networks next to Genres. All named network IDs from TMDB's official TV network export are sorted alphabetically, retaining distinct IDs even when names match. Network membership uses TMDB's `discover/tv?with_networks=ID`, independently for each row; a series can appear in multiple matching networks and is deduplicated only within one row. This is the TV network association, not a production-studio or current streaming-availability filter.
- The Networks page reuses genre poster sizing/focus styling and centered Load More buttons. It initially loads toward 50 shows per visible row (up to three pages), then one page per Load More. Details/playback return preserves retained network/list scroll positions and title focus. Requests are cancellable and limited to three concurrent row loads, with a bounded 96-row memory cache.
- The network directory downloads only when Networks is opened, is cached on-device, and refreshes against TMDB's daily published export. A previous-day file and saved directory provide fallback. This is separate from the VidSrc daily-ID download setting. Hidden rows do not fetch their show lists. No catalogue scan runs while the Networks screen is disposed for playback.
- v2.1.3 has been reviewed at source level. APK compilation, lint, live TMDB export decoding/API responses, phone and TV rendering/navigation tests are pending the GitHub build/device test.

- v2.1.2 adds ScottsMedia as the third source and moves CineJoy to fourth: VidSrc, VidFast, ScottsMedia, CineJoy. Movie URL: `https://player.scottsmedia.online/embed/movie/{tmdbId}?autoplay=1`. TV URL: `https://player.scottsmedia.online/embed/tv/{tmdbId}/{season}/{episode}?autoplay=1`, following the user's instruction to assume the VidSrc endpoint shape. Provider compatibility and the assumed TV URL require device testing. TV season preferences can learn ScottsMedia after confirmed playback.

- v2.1.2 remembers the enabled source that starts a TV episode and tries it first for later episodes in the same series/season. Preferences persist on the device; each season (including Specials) is independent. Movies retain the default order. Explicit source failure forgets a matching season preference; unavailable/disabled choices are never injected into playback. Remaining sources retain their existing fallback order. No network preflight was added.
- Settings > Preferred playback sources > Reset preferred sources clears season preferences and legacy post-reset choices together; watch progress is preserved. A later successful episode teaches that season again. Legacy playback-history rankings remain ignored.
- v2.1.2 includes a pure Java regression suite in the workflow for season isolation, source promotion and fallback/reset ordering. Compilation, lint and device verification are pending the new GitHub run.

- v2.1.1 restores the TMDB backdrop behind only the upper Movie/TV poster/title/overview section. It crops from the right, applies a heavy dark gradient and fades into the page at the bottom. This section scrolls with the existing content; cast, seasons and episodes keep the plain background. Existing element sizing, layout order and navigation bars are retained. Titles without artwork retain the normal background.
- User verified the v2.1.1 in-app update and details backdrop on an Android phone.

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
