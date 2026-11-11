# Review4: frame matching, startup readiness and JA icon

**Version 1.1.6-review4 / versionCode 11604**. This full package combines:

- Frame cadence fix: accept the bridge's one-second reports instead of
  discarding them under the old 3.5-second minimum. Require two agreeing
  intervals; reject seek/counter discontinuities and reset on video dimension changes.
- All review2 cold-start readiness changes.
- All review3 neon-green JA launcher/adaptive/round icons and TV banner.
- A startup build-version log and JVM regression tests using the user's actual
  frame/time samples.

## Upload and verify this package

The installed app was confirmed as review1 (11601), so it did not yet contain
the review2 startup gate or review3 icon. Upload **all contents** of this folder
into the root of your Build Improved branch, including `.github`, `app/src/main/res`,
`app/src/main/java/com/example/ja/JaStartupBarrier.java`, the new
`JaFrameRateSampler.java`, and both Java tests. Do not upload the outer folder
as an extra nested repository directory. Keep the existing TMDB_API_KEY secret.

Run Actions > Build JA APK, explicitly select your improved branch, and download
**JA-1.1.6-review4-debug from that same run**. Extract and install the APK.

Verify the installed version in PowerShell:

```powershell
adb shell dumpsys package com.example.ja | Select-String "versionName|versionCode"
```

Expected: versionName=1.1.6-review4 and versionCode=11604. If a signature mismatch
prevents updating, use your existing signing key or uninstall the old app first;
uninstalling clears local app data.

## Check frame matching

Keep TV frame matching enabled. Play a title and collect:

```powershell
adb logcat -v time JA_STARTUP:I JA_FRAME_RATE:I JA_SOURCES:I "*:S"
```

Expected sequence after confirmed video start: FRAME_RATE_MEASURED,
FRAME_RATE_REQUESTED (or FRAME_RATE_ALREADY_MATCHED), then FRAME_RATE_CHECK.
Only result=APPLIED confirms the selected mode became active. The matcher may
log FRAME_RATE_UNAVAILABLE if the display lacks the target rate at the current
resolution. Android can also decline a requested mode; the update does not
force a mode the display doesn't advertise.

The one-second cadence should produce a stable estimate a few seconds after
video starts. Frame counts provide an estimate rather than authoritative encoded
frame-rate metadata. Existing 23.976/29.97 preference behavior remains unchanged.

## Validation

The cadence sampler compiled and passed JVM tests against the supplied log's
one-second trace, previous five-second sampling, seeks, counter resets,
changing cadence, duplicate reports, stalls and invalid data. Startup ordering
and playback-monitor tests also pass. Manifest/resources and workflow were
checked. **This complete Android APK has not been compiled or device-tested
here.** Review1 was confirmed to compile/install; review4 still needs GitHub's
Android compilation and your TV test.

The earlier combined package notes below describe retained features and
limitations. Use the review4 artifact and version checks above.

---

# Combined icon and cold-start GitHub update

**Version 1.1.6-review3 (11603)** contains the complete review2 cold-start
readiness fix plus the supplied neon-green JA launcher artwork.

## Upload this update

Upload this folder's **contents** into the root of your Build Improved branch,
including `.github`, the new Java files, and **all of app/src/main/res/**.
The root must contain `app/`, `scripts/`, `tests/`, and `settings.gradle.kts`.
Use the existing TMDB_API_KEY repository secret. Run Actions > Build JA APK
on your branch. Download **JA-1.1.6-review4-debug**, extract it, and install the APK.
Delete the old `.github/workflows/test1.yml` if it is still in your repository.

## Icon assets

The original supplied image is retained in `branding/ja-icon-original.png`.
Android launcher variants use its black interior and original green monogram;
the white presentation surround is excluded. The package includes five legacy
launcher densities, circular icons, padded Android 8+ adaptive icons, and a
320 x 180 Android TV banner with the artwork and JA name. Manifest references
are included. No external image URL or runtime download is needed.

To regenerate only these resources, install Pillow and run
`python scripts/prepare_icons.py`. Generation is **not** required to build:
the ready-made PNG and XML resources are already included.

## Validation for this update

Startup barrier JVM tests and existing playback monitor JavaScript tests pass.
All resource XML and workflow YAML parse; PNG sizes and manifest references
were checked, and circle/adaptive mask previews were inspected. Review3 has
not been compiled to an APK or tested on a device in this workspace. The user
confirmed that review1 compiled and installed; review2 device testing is pending.
The cold-start readiness fix is retained unchanged in review3.

After building, test the launcher icon on phone and TV, and try Play immediately
after a fresh install. The Preparing player message should remain until startup
requirements complete. Full review2 build and debugging notes follow below.

---

# JA — GitHub build package

Version: **1.1.6-review3** / Android versionCode **11603**.
Based on the uploaded `ja_app-testmultisrv1(1).zip`. This is an updated native
Kotlin / Jetpack Compose Android project, not an Expo replacement.

## Build an APK on GitHub

1. Extract this ZIP. Open the `JA-GitHub-Improved` folder.
2. Upload its **contents** to the root of your GitHub repository. The repository
   root must contain `settings.gradle.kts`, `app/`, `scripts/`, `tests/` and
   `.github/workflows/build-apk.yml`. Do not upload the ZIP itself as source.
   Ensure the hidden `.github` directory is included.
3. If updating the existing repository, delete the old
   `.github/workflows/test1.yml`; uploading files does not remove old workflows.
4. In GitHub, open **Settings > Secrets and variables > Actions > New repository
   secret**. Add `TMDB_API_KEY` containing your TMDB API key (not a bearer token).
   Existing secrets can remain. This package contains no real key.
5. Open **Actions > Build JA APK > Run workflow**, select the branch, then run it.
   Pushes to `main` or `master` also build automatically.
6. Open the completed run and download **JA-1.1.6-review4-debug** from Artifacts.
   Extract the artifact ZIP and install `app-debug.apk` on your Android device.

Optional secrets `AUTHORIZED_EMBED_TEMPLATE`, `AUTHORIZED_EMBED_HOST`,
`DIRECT_MEDIA_TEMPLATE`, and `DIRECT_MEDIA_HOST` are passed through for existing
configuration compatibility. The current registry still uses the three providers
already in the uploaded code; those optional values do not change its providers.

The workflow provisions Java 17, Android SDK 35, and Gradle 8.11.1. It uses the
existing AGP/Kotlin and library versions. It runs JavaScript tests, compiles a
debug APK, and produces a separate Android lint report. Lint findings are
reported without blocking the APK. There is no Gradle wrapper in this package;
the workflow installs the pinned Gradle version directly.

## Cold-start readiness fix (review2)

Review1 compiled and installed successfully in user testing, but playing
immediately after a fresh install stalled; waiting about 45 seconds first
allowed playback. Inspection found that the player loaded after 15 seconds even
if the runtime-wide blocker install was still running, and autoplay installation
was not awaited. This is a concrete startup race consistent with that symptom;
logs are still needed to establish whether it was the complete cause on-device.

Review2 removes that load-anyway path. Each session waits for all four built-in
extensions, successful blocker preparation (or confirmed Off mode), and all three
native bridge delegates. The app prewarms the same cached built-in installs at
launch. No fixed 45-second waiting period is introduced. A Preparing player
message appears while setup is pending; once ready, the page opens immediately.
A 90-second setup deadline offers manual recovery without starting a page during
an unfinished installation. The separate media-start timer begins after page
loading is requested. Closing the player cancels its readiness callbacks.

The existing blocker APIs report installation/enabling completion; they do not
prove every internal filter-list download is finished. This fix is not a promise
that all provider-related stalls are eliminated.

### Cold-start testing (carried forward from review2)

Upload the contents of this package to your **Build Improved** branch using the
same root layout as review1. It adds `JaStartupBarrier.java` and its test; include
those new files as well as the changed Kotlin files and workflow. Existing
repository secrets are reused. Run Actions > Build JA APK on that branch, then
download the JA-1.1.6-review4-debug artifact.

First test: fresh install, open JA, and press Play immediately. You should see
Preparing player while setup completes, then the page should open. Next test:
close/back out during preparation and reopen a title. No old page should load
behind the newly selected title. Subsequent playback should reuse ready installs.

If the problem persists, capture logs from app launch through the failed attempt:

```powershell
adb logcat -v time JA_STARTUP:I JA_ADBLOCK:I JA_GECKO:I JA_SOURCES:I "*:S"
```

If ADB sees several devices, add `-s DEVICE_SERIAL` after `adb`. Include the
PLAYER_PREPARING, PLAYER_BLOCKER_READY, PLAYER_MONITORS_READY, PLAYER_READY or
PLAYER_SETUP_FAILED messages. These distinguish setup delay from a provider
that stalls after the page has opened. Logs can contain title IDs or hostnames;
review them before sharing.

## Selected improvements

- Startup failures and timeouts offer **Retry**, **Next Server** when available,
  and **Back**. There is no automatic provider switch on a failure.
- Browser media activity alone no longer cancels startup recovery indefinitely.
- The source monitor requires a recognized player context, video dimensions of
  at least 480 x 270 and sustained time advancement before reporting a start.
- Embedded progress is saved about every five seconds, plus pause/page visibility
  events where delivered. A saved position of five seconds or more triggers
  **Resume** / **Start over** before playback.
- Resume seeks only after a usable seekable range is available. A source that
  never exposes a usable seekable range cannot be reliably resumed.
- A recognized player that remains unpaused without advancing for 30 seconds
  offers recovery. Deliberately paused playback is excluded.
- Native player progress is captured before release and saved outside the
  disappearing composable's coroutine scope.
- Genre rows are cached **in memory for the current app session**, shown
  immediately when revisiting a tab, and refreshed in the background. Cold
  loads show progressive rows. Up to three genre requests run concurrently.
  Existing primary-genre assignment and the 50-title row target remain.
- Per-genre request failures preserve other successful rows. Failed categories
  can be retried on the next visit. Cached rows survive refresh failures.
- One GitHub workflow, explicit app version, no build-time patch of source files.

## Validation and limits

Local checks passed: syntax for all four JavaScript assets, extension manifest
JSON, workflow YAML, build-property generation/escaping, and the behavioral
monitor tests in `tests/source-status.test.cjs`.

**Review2 has not been compiled into an APK or installed in this workspace.**
Review1 was confirmed by the user to compile and install. Review2's pure Java
startup barrier compiled locally and passed all six completion-order cases,
slow-blocker, deadline, failure, disposal/retry and concurrent-callback tests.
The existing source-monitor JavaScript behavioral tests also passed. No Android SDK
or Gradle installation was available here. GitHub Actions is the first full
Kotlin/Android build check. Live provider playback, Android TV focus, frame
matching, and resume need device testing. Heuristics reduce accidental ad
confirmation but cannot prove a video is the requested title. Provider changes
can affect availability, remote controls and seeking.

Progress can lose the last few seconds on abrupt process termination. The
catalogue cache does not survive a force-stop/relaunch. This update keeps the
existing screen layout and does not include an architecture rewrite, new
browser engine, dependency upgrades, or a new release signing setup.

## Before replacing your normal installed build

Keep a copy of the source and working APK. A fresh GitHub runner can generate
a different debug signing key. A debug APK signed with a different key cannot
update the existing installation. Uninstalling the
old app clears its local data; use the same signing key if that data must stay.
For long-term distribution, configure a persistent private signing keystore
instead of relying on the runner's debug key. Never commit keystores, API keys,
or `local.properties`.

## Device acceptance checks

- Phone and TV: launch a movie and a TV episode; check autoplay and remote keys.
- Simulate an unavailable source: verify the recovery prompt and manual choice.
- Watch for at least 15 seconds, leave, reopen, and verify both resume actions.
- Retry and switch sources after progress has been saved; check resume position.
- Pause for more than 30 seconds: recovery must not appear solely due to pause.
- Switch Genres > Popular > Genres: cached rows should appear immediately.
- Test a slow/offline catalogue refresh: existing rows should remain visible.
- Check subtitles, quality menus, orientation and frame matching on the onn TV.

## Local development

Install Android SDK 35, Java 17, and Gradle 8.11.1. Copy
`local.properties.example` to `local.properties`, fill in `sdk.dir` and
`TMDB_API_KEY`, then run `gradle assembleDebug` from this directory.
Run the monitor tests with `node tests/source-status.test.cjs`.
