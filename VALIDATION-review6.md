# Review6 validation

- Full `assembleDebug`: BUILD SUCCESSFUL using Java 17, Gradle 9.6.0,
  AGP 9.5.1, Kotlin 2.4.21 and installed Android SDK 37.2.
- Compiled against actual GeckoView 160.0.20261104181552, including its typed
  extension prompt API; no compile-SDK/minimum-SDK suppression.
- APK inspection: com.example.ja, versionName 1.1.6-review6, versionCode 11606,
  minimum API 26, target API 35. ARMv7 and ARM64 native libraries are included.
- Startup barrier, frame-rate sampler and blocker lifecycle Java tests passed.
- Source-status Node test and syntax checks of all player JavaScript passed.
- Workflow YAML and Android resource/manifest XML parsed successfully.
- Compared Room-generated database SQL and identity hash against review5:
  schema version 1, watch_items table unchanged, identity hash
  18240ef5f5dfeeaeec40662fc907f4a3 unchanged after Room 2.8.5 upgrade.
- Existing deprecation/nullability compiler warnings remain; they did not fail
  compilation. No device or emulator playback tests were performed.

The local validation build used a placeholder TMDB key and is not distributed.
GitHub builds with the repository's TMDB_API_KEY secret. Android lint is an
informational workflow step and was not run locally for this update.

Physical-device verification is still required for immediate playback, uBlock
filter readiness, Off/On transitions, TV remote controls and refresh matching.
The engine upgrade is not a confirmed fix for the earlier uBlock timeout.

Only the Preparing player UI panel was removed. Startup sequencing/recovery
remains; removing the panel does not make asynchronous initialization instant.
