# v2.4.5musictest

Unpublished experiment based on v2.4.5. Enable Show Music tab in Settings, then use Music Lab. The previous Deezer website remains selectable.

This is an account/source feasibility build, not a completed full-track Deezer integration. Enter an ARL locally and press Connect, then test a Deezer track ID/link. No email/password collection or automated ARL extraction is implemented. Session validation uses Deezer's undocumented web gateway and may stop working.

The source probe requests FULL / MP3_128 / NONE from the signed-in account's licensed media endpoint. Rejected/unsupported responses are surfaced. There are no forged-CDN URLs, embedded decryption keys, encrypted-audio decoding, or preview substitutions. Full-track playback has NOT been verified. Encrypted-only delivery remains a blocker.

A foreground native ExoPlayer service handles returned playable sources, a 64 MiB evicting streaming cache, pause/seek, notification controls, noisy-output/audio focus and video handoff. Cache persists until eviction or sign-out. Session is AES-GCM encrypted with an Android Keystore key; neither ARLs nor audio URLs enter logs or Intents. Account/track titles are visible in the UI and notification. Sign out to remove session/cache.

The APK uses the stable package and versionCode 20405, with a test versionName. It replaces JA on the test device when signed with the same key. This is branch isolation, not a separately installed application. The build workflow calls the regular checks/build with publish_update=false and never publishes a device update.

## Device test

1. Capture only sanitized diagnostics: `adb logcat -c`, then `adb logcat -v threadtime JA_MUSIC_LAB:I '*:S' > JA-MusicTest.txt`.
2. Connect a session; test a track over 60 seconds. Record the on-screen result. Do not provide the ARL.
3. Only if AUDIO_RESOLVED and READY appear: verify playback passes 30 seconds, seek both directions, lock the phone, browse JA, then start Movie/TV/IPTV and verify music pauses.
4. Test rapid track replacement, Stop while resolving, and Sign out while resolving; stale responses must not start audio.
5. Reopen JA and Check saved session; expire/sign out externally and confirm rejection is shown without a preview fallback.

Implementation independently written after reviewing Deezer request shapes in bambanah/deemix packages/deezer-sdk/src/deezer.ts and gw.ts. No upstream code or keys bundled. No device session was available during development.
