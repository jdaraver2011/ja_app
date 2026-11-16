# JA v2.1.0 (20100) — internet updates from Settings

This version builds on v2.0.10. The APK artifact is JA-2.1.0-debug. Keep the same repository signing secrets that successfully updated v2.0.9 to v2.0.10. Publish v2.1.0 through Build JA APK to test installation from v2.0.10. No manual reinstall is needed for devices already using that permanent key.

## How remote updates work

Compilation produces an APK. When you explicitly publish that build, GitHub Releases hosts the signed APK and a small ja-update.json manifest. Any installed updater-enabled JA device with internet access can select Settings > Update Now. It checks the latest published non-prerelease release, downloads a newer APK, verifies SHA-256/size/package/version/signing certificate, and offers Install Update. Android handles the final installation confirmation.

Devices do not need to share your network. No ADB connection, router port forwarding or inbound device connection is needed. This is a device-initiated pull update, not a silent push installation. No scheduled background checks were added. Ordinary unmanaged Android/TV devices require the OS installation/unknown-source permission flow.

Existing pre-updater versions need one manual installation of this feature. After that, newer published versions can be installed from Settings. Successful same-key updates retain the app's local data. Uninstalling does not.

## 1. Decide the public download repository

The default local-build repository is jdaraver2011/ja_app. GitHub builds use the current repository unless the JA_UPDATE_REPOSITORY Actions variable overrides it. A public repository is required for this unauthenticated client.

If the source repository is private, create a separate PUBLIC releases-only repository such as OWNER/ja-updates, initialized with a README. This keeps source private, but the published APK/update metadata are publicly downloadable. Set the source repository's Actions variable JA_UPDATE_REPOSITORY to OWNER/ja-updates. If building locally, set JA_UPDATE_REPOSITORY=OWNER/ja-updates in local.properties too.

For a separate release repository, add JA_UPDATE_PUBLISH_TOKEN as an Actions secret in the source repository: a token limited to Contents read/write on the public release repository. For publishing to the same public repository, the workflow uses GITHUB_TOKEN. No GitHub token is embedded in the APK. Accounts/devices do not need GitHub login.

## 2. Use one persistent signing key

The previous workflow did not explicitly restore a persistent keystore. Do not assume all installed devices share a signing certificate. Android only accepts in-place updates signed with the installed app's key. This updater deliberately checks an exact current-signer match; signing-key rotation is not implemented.

Use the keystore that signed your currently installed APK whenever you have it. A certificate or APK cannot recover a lost private key. If the previous APK's key is unavailable, a one-time migration to a new consistently signed installation may be required; replacing a differently signed installation generally requires uninstalling and clears local app data. This package never uninstalls automatically.

Back up the selected keystore securely. Add these Actions secrets to the source repository BEFORE distributing the updater-enabled bootstrap build:

| Secret | Value |
|---|---|
| JA_KEYSTORE_BASE64 | Base64 of the existing signing keystore file |
| JA_KEYSTORE_PASSWORD | Keystore password |
| JA_KEY_ALIAS | Signing-key alias |
| JA_KEY_PASSWORD | Signing-key password |
| TMDB_API_KEY | Keep your existing value |

For an existing standard Android debug keystore, the alias is commonly androiddebugkey and both passwords android. Verify your actual keystore; do not assume those values for a custom/release key. The workflow continues producing the project's debug APK, signed by the persistent key when configured.

On Windows, you can copy an existing keystore's Base64 to the clipboard without posting it in chat:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\PATH\TO\YOUR\KEYSTORE")) | Set-Clipboard
```

Paste it directly into the JA_KEYSTORE_BASE64 secret. Keep the keystore/private-key contents out of source control and release assets. Do not send the private key here.

For local builds, the optional signing configuration reads JA_SIGNING_STORE (absolute keystore path), JA_KEYSTORE_PASSWORD, JA_KEY_ALIAS and JA_KEY_PASSWORD from the build environment. Without this configuration, Gradle uses its existing debug-key behavior.

## 3. Build and publish

1. In Actions, run Build JA APK on the intended branch. Leave Publish device update UNCHECKED for routine testing. Builds triggered by push do not publish updates.
2. Once that version is ready for devices, run the workflow with Publish device update CHECKED. Publishing requires the signing secrets; missing key configuration fails instead of publishing a randomly signed APK.
3. The workflow creates release vVERSION in the configured public repository with JA-update.apk and ja-update.json. SHA-256, size, package and versionCode are read from the actual built APK. Assets are uploaded to a draft first; the release is then published and marked latest, so devices do not see a half-uploaded update. Existing tags are not overwritten. If a publish is interrupted, remove its incomplete draft before retrying that version.
4. For every later update, increase versionCode and versionName in app/build.gradle.kts. A device only accepts an update with a higher versionCode. Do not publish a prerelease/draft if it should be served through this latest-stable path.

Optional publish grants this job Contents write permission. Organization/repository policies may restrict publication. A publish error does not make an APK available to devices; inspect the workflow and release page. Private or missing release endpoints are reported in JA rather than treated as 'up to date'.

No repository, secret or release was created/published by this file update. You must configure the destination and signing secrets in GitHub.

## 4. Device setup and testing

- First install the consistently signed v2.0.8 build normally on each test device. Confirm Settings shows App updates / installed version / Update Now.
- For an end-to-end update test, build and publish a later code such as 2.0.9 / 20009 with the SAME key. Press Update Now in the installed 2.0.8 app.
- After download verification, select Install Update. If prompted, allow JA to install apps from this source, return to JA and select Install Update again. Confirm the Android installer prompt.
- Reopen JA and confirm the new installed version, watched state/favorites, playback and Settings. Repeat on a phone and Android TV, including a device on another internet connection.
- Test no newer version, no internet, cancelled installation, denied install permission, insufficient storage and an intentionally mismatched signing key. A mismatched/corrupt/downgrade APK must not be offered for installation.

The updater does not report installation success just because the installer opened. The installed version shown after reopening is the result to verify. The locally built universal APK is about 521 MiB; download time depends on the connection. The updater checks for roughly twice the APK size plus a margin to allow download and installer staging. Downloads live in app-private cache and may be evicted; retry the download if needed. If the process is killed during a download, return to Settings and retry.

## Implementation

- New JaUpdateSettings.kt and JaAppUpdates.kt add the Settings button, download progress and standard Android installation flow.
- JaUpdateRules.java centralizes package/version, exact signer and checksum/length acceptance rules.
- REQUEST_INSTALL_PACKAGES and an unexported FileProvider expose only the updates cache directory to the installer through a temporary read grant.
- prepare_update_release.py generates release assets; restore_update_signing.py restores a configured key temporarily in CI. Neither embeds secrets in the client.
- GitHub API/metadata use HTTPS, size-limited reads and timeouts. APK download is streamed (maximum 1 GiB), cancellation-aware and checked before installation. The installed signer must exactly match the APK signer; arbitrary website APKs cannot pass this check without that key.

## Validation

v2.1.0: assembleDebug and lintDebug passed. All asset JavaScript syntax checks, 11 focused/regression suites and four update-release metadata tests passed. Coverage includes automatic quality timing, menu replacement/cloning, style restoration and manual takeover. Lint retains existing warnings. The menu mask and additional 5% sizing require device testing; a headless rendering check could not run in this environment, so live rendering has not been verified.

The user confirmed successful in-app installation from v2.0.9 to v2.0.10. No v2.1.0 device installation or release publication has been performed during preparation of this version.

## Official references

- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
- https://developer.android.com/reference/androidx/core/content/FileProvider
- https://docs.github.com/en/rest/releases/releases#get-the-latest-release

## Files included

- `.github/workflows/build-apk.yml`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/ja/JaAppUpdates.kt`
- `app/src/main/java/com/example/ja/JaUpdateRules.java`
- `app/src/main/java/com/example/ja/JaUpdateSettings.kt`
- `app/src/main/java/com/example/ja/MainActivity.kt`
- `app/src/main/res/xml/update_paths.xml`
- `scripts/configure_build.py`
- `scripts/prepare_update_release.py`
- `scripts/restore_update_signing.py`
- `tests/JaUpdateRulesTest.java`
- `tests/update-release.test.py`
