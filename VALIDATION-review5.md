# Review5 validation

## Passed locally

- Full `assembleDebug` using Java 17, Gradle 8.11.1, Android platform 35,
  Android build tools 34/35, and the declared GeckoView
  128.0.20240725162350 dependency. Final source revision: BUILD SUCCESSFUL.
- APK manifest inspection: package com.example.ja, versionName
  1.1.6-review5, versionCode 11605, compileSdk 35.
- JaBlockerLifecycleStateTest: selection generations, stale callbacks,
  lifecycle invalidation, Off -> uBlock and independent extension IDs.
- JaStartupBarrierTest: startup ordering, deadline, failure, disposal/retry
  and concurrent callback cases.
- JaFrameRateSamplerTest: actual one-second device trace, older five-second
  cadence, seeks, counter resets, duplicates, stalls and changing cadence.
- Source status JavaScript behavioral tests.
- Workflow YAML, Android manifest and resource XML parsing.
- GeckoView APIs checked against the source archive for the exact 128 dependency.

The local compile used a placeholder TMDB key solely for compilation. The APK
is not distributed in this source package. GitHub builds use your repository's
real TMDB_API_KEY secret. No key, local build output or signing material is included.

## Not verified on a device

- Whether the installed uBlock version's filter engine initializes successfully.
- Whether it fixes the captured filter-list compilation timeout.
- Dashboard/logger interaction and remote focus on the onn TV.
- Runtime mode switching and the new Off-and-retry action during actual playback.

Gecko onReady and enabled metadata are not filter-health signals. The app
labels this explicitly. This release adds lifecycle handling, failure reporting
and management access; it does not claim verified engine/add-on compatibility.
No Gecko engine upgrade, filter weakening, whitelist, or suspension-setting
change was made.
