# Changes from review5

- MainActivity.kt: remove only the Preparing player panel; retain startup gating.
- app/build.gradle.kts: GeckoView 160.0.20261104181552, Media3 1.11.1,
  Room 2.8.5 / metadata reader 2.4.21, compile SDK 37.2, minimum SDK 26,
  built-in Kotlin/legacy-kapt, matching diagnostic
  constant and app version 1.1.6-review6 / 11606. Target SDK remains 35.
- build.gradle.kts: AGP 9.5.1, legacy-kapt 9.5.1 and Compose compiler 2.4.21.
- JaGeckoAdBlockers.kt: GeckoView 160 typed install/optional/update permission
  delegates; replace obsolete reflection-based permission response handling.
- GeckoExperimentalPlayer.kt: update stale API comments for the new engine.
- .github/workflows/build-apk.yml: SDK 37.2/build tools 36, Gradle 9.6.0,
  and review6 artifact name.
- README.md and VALIDATION-review6.md: installation, validation and device checks.

All source files are included. Previous review5 blocker tools/lifecycle handling,
review4 frame matching, icons and TV banner remain in the project.
