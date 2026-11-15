# Historical project notes

Archived verbatim from the uploaded project. These describe earlier releases, not current behavior or current validation. Use the root README for the current project.

---

## Original file: BRANDING_UPDATE.md

# JA Branding Update (v1.1 / versionCode 2)

- Main screen heading: `JA` (`MainActivity.kt`).
- Android launcher label: `JA` (`AndroidManifest.xml`).
- Existing package ID: `com.example.ja` (unchanged).
- Build version increased to 2 so a new build can update version 1.
- GitHub Actions checks for legacy branding before compiling.

This is source code, not a compiled APK. Upload to GitHub and run the workflow to compile.


---

## Original file: CHANGED-FILES-review2.md

# Changes from review1

- app/src/main/java/com/example/ja/JaStartupBarrier.java (new): one-shot startup gate.
- app/src/main/java/com/example/ja/GeckoExperimentalPlayer.kt: await cached installs,
  register all delegates before loading, remove 15-second load-anyway path,
  cancel pending startup on disposal, log startup stages and elapsed time.
- app/src/main/java/com/example/ja/MainActivity.kt: prewarm built-ins, show pending
  setup message and allow the longer first-run setup deadline.
- app/build.gradle.kts: version 1.1.6-review2 / 11602.
- .github/workflows/build-apk.yml: startup gate JVM tests and review2 artifact name.
- tests/JaStartupBarrierTest.java (new): asynchronous ordering and lifecycle tests.
- README.md: cause hypothesis, build instructions, validation limits and device test.

No JavaScript player asset changed from review1. Catalogue and resume behavior
are unchanged in this cold-start patch. This full ZIP includes every source file.


---

## Original file: CHANGED-FILES-review3.md

# Combined update: review3

Contains all review2 source and fixes, plus:

- AndroidManifest.xml: launcher, round icon and Android TV banner references.
- app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}: generated icons.
- app/src/main/res/mipmap-anydpi-v26: adaptive icon definitions.
- app/src/main/res/values/icon_colors.xml: black adaptive background.
- app/src/main/res/drawable-xhdpi/tv_banner.png: 320 x 180 Android TV banner.
- branding/ja-icon-original.png: original supplied image, unchanged.
- scripts/prepare_icons.py: repeatable icon resource generation.
- app/build.gradle.kts: version 1.1.6-review3 / 11603.
- .github/workflows/build-apk.yml: review3 artifact name.
- README.md: combined upload and validation instructions.

Keep app/src/main/java/com/example/ja/JaStartupBarrier.java and
 tests/JaStartupBarrierTest.java from review2; both are included in this ZIP.
Upload all contents together, including the resource directories.


---

## Original file: CHANGED-FILES-review4.md

# Changes from review3

- JaFrameRateSampler.java (new): validate one-second or five-second frame samples,
  require two consistent intervals, reset after seek/counter discontinuities.
- JaTvFrameRateMatcher.kt: use cadence sampler; reset when video dimensions change.
- MainActivity.kt: log installed build version under JA_STARTUP.
- tests/JaFrameRateSamplerTest.java (new): includes actual frame/time pairs from
  the user's ADB log and recovery/discontinuity cases.
- app/build.gradle.kts: version 1.1.6-review4 / 11604.
- .github/workflows/build-apk.yml: run cadence tests; label review4 APK artifact.
- README.md: install/version verification and actual display-match diagnostics.

Full source is included, with all review2 startup fixes and review3 icon assets.
No changes to JavaScript remote/autoplay behavior, source registry, catalogue
loading or resume logic in this frame-matching patch.


---

## Original file: CHANGED-FILES-review5.md

# Review5 changes relative to the uploaded improved branch

- JaGeckoAdBlockers.kt: lifecycle observer, serial operations, no cached Active
  success, version logs, status flow, initialization deadlines and page URLs.
- JaBlockerLifecycleState.java (new): selection generations and ready invalidation.
- JaBlockerTools.kt (new): scoped dashboard/logger Gecko session.
- MainActivity.kt: live Settings status, tool entry, blocker warning and explicit
  Off-and-retry recovery action.
- GeckoExperimentalPlayer.kt: page progress diagnostics.
- app/build.gradle.kts: 1.1.6-review5 / 11605 and Gecko dependency-version log field.
- tests/JaBlockerLifecycleStateTest.java (new): stale selection/lifecycle cases.
- .github/workflows/build-apk.yml: current test/build/artifact workflow.
- .github/workflows/test1.yml: removed; delete the old file in the GitHub branch.
- .gitignore: exclude local configuration, build outputs and compiler caches.
- README.md and VALIDATION-review5.md: build, diagnostics and scope.

Engine version, icon assets, source providers, genre rules, playback-control JS,
frame-matching and resume implementation are retained from the uploaded branch.


---

## Original file: CHANGED-FILES-review6.md

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


---

## Original file: PLAYBACK_SETUP.md

# JA playback (TMDB ID mode)

JA uses TMDB numeric IDs directly for movie and TV episode embed URL generation. IMDb lookup is not required for the default playback path.

Default URL patterns (illustrative, subject to provider permission and availability):

- Movie: `https://vidsrc.sh/embed/movie/{id}`
- TV episode: `https://vidsrc.sh/embed/tv/{id}/{season}/{episode}`

`AUTHORIZED_EMBED_TEMPLATE` and `AUTHORIZED_EMBED_HOST` remain supported as overrides. If you previously configured the IMDb template in GitHub Actions secrets, **remove or update it** or it will override the new default. To use the default, remove both secrets, then rebuild.

JA opens HTML embed pages with WebView, not ExoPlayer. It enables JavaScript, DOM storage, and HTML5 fullscreen, and reports main-frame WebView errors. Provider availability, redirects, and playback rights are not guaranteed.

To build: commit the updated Kotlin files to GitHub and run the existing Build JA Android APK workflow.


---

## Original file: README.md

# JA 1.1.6-review6 — GeckoView 160

VersionCode **11606**. Based on review5. Requires **Android 8.0 / API 26 or newer**.

## Requested changes

- Removed the app's **Preparing player… / First launch may take longer** panel.
- Upgraded GeckoView from 128.0.20240725162350 to stable
  **160.0.20261104181552**.
- Updated the required build toolchain to AGP 9.5.1, Gradle 9.6.0,
  compile SDK 37.2, built-in Kotlin / Compose compiler 2.4.21, and Media3 1.11.1.
  The target SDK remains 35; the new Gecko library requires minimum SDK 26.
- Replaced the GeckoView 128 extension-permission reflection shim with the
  typed GeckoView 160 API, including its three-field permission response.
- Retained startup ordering/deadlines, manual recovery, uBlock lifecycle/status
  and dashboard/logger tools, resume, icon assets and TV frame matching.

There is no replacement preparation dialog or forced delay. The player area may
remain black briefly while setup completes; Android Back still exits it. The
source loads after setup is ready. Resume/Start over and Playback needs attention
prompts remain because they require an action from the viewer. A provider can
still display its own loading spinner or controls.

## Build on GitHub

1. Extract this ZIP. Upload the **contents** of `JA-GitHub-Improved` to the root
   of your improved branch, including `.github`, `app/`, `scripts/` and `tests/`.
2. Delete `.github/workflows/test1.yml` if it is still present in the branch.
3. Keep the existing **TMDB_API_KEY** repository Actions secret.
4. Run **Actions > Build JA APK**, selecting your improved branch.
5. Download **JA-1.1.6-review6-debug** from the completed run and install its APK.
6. Confirm the installed version:

```powershell
adb shell dumpsys package com.example.ja | Select-String "versionName|versionCode"
```

Expected: `versionName=1.1.6-review6` and `versionCode=11606`.
Use your existing signing key to update in place. A different debug signing key
may require uninstalling the previous app; uninstalling clears local data.

## Build requirements and Android support

The published GeckoView 160 AAR declares compile SDK 37.2 and minimum Android
API 26. The project uses those real requirements without suppressing the
library's metadata check or overriding its manifest minimum. Your onn device's
Android 14 is supported; Android 6 and 7 can no longer install this version.

GitHub installs SDK platform android-37.2 and build tools 36.0.0, uses Java 17
and Gradle 9.6.0, and builds with Android Gradle Plugin 9.5.1. AGP's built-in
Kotlin replaces the old kotlin-android plugin, and its legacy-kapt adapter
uses Room 2.8.5 with the matching Kotlin metadata reader 2.4.21. The Compose
compiler matches Kotlin 2.4.21. The database schema/version and identity hash
remain unchanged from review5.
All Media3 modules are aligned to 1.11.1, the version used by GeckoView 160;
the old compile-35 compatibility force rules have been removed.

## Test playback and uBlock

First test a title with **Off**, then leave playback and enable **uBlock Origin**
in Settings. Check the status and open the same title again. A completed Gecko
extension startup still does not prove the extension's async filter engine is
healthy; the engine upgrade needs device testing against the previous timeout.
No whitelist, filter-list change, or network-suspension bypass was added.

The uBlock dashboard/logger button and explicit **Turn ad blocking Off and retry**
action from review5 are retained. Signed blockers are still installed from Mozilla.
App-owned extension installation permissions are restricted to the existing
uBlock/AdGuard IDs; private-mode and technical/interaction-data consent are not
granted. Updates requesting new permissions remain denied as before.

For diagnosis, capture from before app launch through playback:

```powershell
adb logcat -c
adb logcat -v threadtime > JA-review6-gecko160.txt
```

Stop with Ctrl+C. Review unfiltered logs before sharing. Check ENGINE_VERSION for
the declared Gecko dependency and runtime user agent, LIFECYCLE for the installed
add-on version, and FRAME_RATE_CHECK / result=APPLIED for actual TV matching.

Device checks: immediate playback, uBlock Off -> On -> Off, Resume/Start over,
TV remote play/pause/seek, subtitle/quality menus, frame matching and Back.
This upgrade does not promise to eliminate every provider or extension error.

## Validation

See **VALIDATION-review6.md** for compilation results and device-test limits.
The source ZIP contains no real TMDB key, generated APK, local SDK configuration,
compiler cache, keystore or downloaded build tools. GitHub builds with your secret.
Icon PNG/XML resources are already present; icon regeneration is not required.


---

## Original file: VALIDATION-review5.md

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


---

## Original file: VALIDATION-review6.md

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

