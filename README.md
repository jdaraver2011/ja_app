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
