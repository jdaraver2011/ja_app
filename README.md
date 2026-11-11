# JA — GitHub build package

Version: **1.1.6-review1** / Android versionCode **11601**.
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
6. Open the completed run and download **JA-1.1.6-review1-debug** from Artifacts.
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

**An APK has not been compiled or installed in this workspace.** No Android SDK
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
