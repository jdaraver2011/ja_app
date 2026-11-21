# JA Music Lab 2.5.1a

Separate test branch from stable v2.5.1. No latest release, update manifest or stable backend is changed.
Version name 2.5.1a; version code 20501; same application ID and update signing as v2.5.1. This installs over the stable APK on the test device. The v2.5.1 APK can replace it again without a version-code downgrade.

## Implemented

- Standard Android phone/TV Music tab: Home/Top Tracks, Search, Queue, Now Playing and Account.
- Deezer-hosted email sign-in in a secure, isolated WebView; captures the resulting own-account ARL cookie or desktop-login callback. JA never receives/stores the password. A masked manual ARL entry remains available if Deezer blocks embedded authentication, CAPTCHA, social sign-in or app-install redirects.
- Public Deezer chart/search metadata, album art and full track links/IDs. Preview URLs are never used.
- Real account gateway validation, track token and license token; MP3_128 BF_CBC_STRIPE FULL media requests. No legacy generated CDN URL, account spoofing or subscription-entitlement substitution.
- Native Media3 playback and in-memory stripe decoding; range seeks align to 6144-byte stripe boundaries. The first 2048 bytes in each stripe are decrypted. Truncated final stripes below 2048 bytes remain unchanged.
- 64 MiB evicting private cache upstream of the decoder, so encrypted media stays encrypted on disk. Sign out clears the session, WebView login cookies and music cache. No song exports or download folder.
- One MediaLibraryService/session for JA, media notification, headsets and car controls. Queue autoplay/skip, scrub, audio focus, headphone disconnect, background playback. Movie/TV/IPTV/weather audio gate pauses music; closing video does not unexpectedly resume it.
- Android Auto media browser fallback, search and shared queue, media voice search; API-8 SectionedItemTemplate browsing and MediaPlaybackTemplate now playing on compatible hosts. Phone sign-in only; no video/catalogue screens are exposed to the car host.
- Car App Library 1.8.0-rc01 is pinned for the API-8 media template test. Debug host validation permits DHU/test hosts; release builds reject hosts until a production allowlist is configured. This is not a production car-store release.

## Upstream review / attribution

JA's Kotlin/Java implementation was independently written using reviewed protocol/framing behavior. It does not embed the upstream Node/Rust apps, their CLI, patched APK, OAuth application secret, or Deezer Connect implementation.

- deemix, https://github.com/bambanah/deemix, pinned commit b611d63b058aaeb7bbb98e9984444c90eb613725: `packages/deezer-sdk/src/deezer.ts`, `gw.ts`, `packages/deemix/src/decryption.ts`, `utils/crypto.ts`. GPL-3.0 upstream; retain that license if incorporating upstream copyrighted source in future.
- pleezer, https://github.com/roderickvd/pleezer, pinned commit f305ab05eebd7380ebac36b51b5f5b776be18d1b: `src/arl.rs`, `gateway.rs`, gateway/user-data models and README. Sustainable Use License upstream. Its README requires a paid account; its old Connect pathway is deprecated. JA uses Deezer's own sign-in page rather than impersonating the third-party OAuth client used there.
- Android Auto implementation follows https://developer.android.com/training/cars/apps/media and https://developer.android.com/media/implement/surfaces/cars.

## Verification and device test

The Java decoder test covers independently calculated key derivation, fragmented 17-byte network reads, encrypted/clear stripes, EOF/partial stripe boundaries, closed streams and 330 randomized seek positions. CI also runs the existing JA regression suite, Android compilation and lint.

A successful APK build is not evidence of full-track authorization or real-device/car playback. No Deezer credentials or account session were available to the build runner. An account refusal is reported rather than replaced with preview audio.

1. Install test APK. Settings > Show Music tab. Music > Account > Sign in; finish provider login. If blocked, use your own ARL through Use ARL. Never post it in chat/logs.
2. Home or Search: select a track longer than two minutes. Verify playback past 30 seconds and through the entire track; queue advances to the next track.
3. Seek forward/backward, including past 30 seconds; pause/resume; headset buttons; screen off; switch Movies/TV/IPTV. Browsing preserves playback; video start pauses it.
4. Sign out during buffering, verify no late playback, cached audio removed; sign in again. Stop must stop immediately even if a car browser remains connected.
5. Android Auto: test with Desktop Head Unit or a parked compatible car. For sideload testing, enable Android Auto developer mode/unknown sources; API-8 media templates can require the host's media-template beta flag. Older hosts use the standard media-browser surface. Sign in on phone first, then verify browse/search/queue, album art, skip/pause and disconnect/reconnect. JA's phone/TV UI stays standard Android even while the car controls the same session.

PowerShell log capture with one selected Android device:

```powershell
adb -s DEVICE_SERIAL logcat -c
adb -s DEVICE_SERIAL logcat -v threadtime "JA_MUSIC_LAB:I" "AndroidRuntime:E" "*:S" > JA-Music-2.5.1a.txt
```

Only share the filtered log after checking it contains no session cookies, credentials or signed URLs. Expected events include SESSION_VALID, AUDIO_RESOLVED and PLAYER_STATE. Errors such as SESSION_EXPIRED_OR_INVALID, ACCOUNT_HAS_NO_PLAYBACK_LICENSE or AUDIO_HTTP_403 identify authorization/provider refusal. AUDIO_HTTP_404 still needs device investigation if returned by the legitimate endpoint.

Phone voice-intent smoke test (after sign-in):

```powershell
adb -s DEVICE_SERIAL shell am start -n com.example.ja/.MainActivity -a android.media.action.MEDIA_PLAY_FROM_SEARCH --es query "artist name"
adb -s DEVICE_SERIAL shell run-as com.example.ja du -sk cache/music-lab
```

The first command uses the same search entry point as a voice assistant. The second checks temporary cache size; it does not print credentials or audio URLs.

## Verified build

[Workflow 44865276250](https://github.com/jdaraver2011/ja_app/actions/runs/44865276250) passed all regression checks, persistent-key signed APK compilation and Android lint. Built source commit: `d74c18b5dd16a6486026e4183dcf0ba8600ed42d`.

The downloaded workflow archive matched GitHub's SHA-256 digest. The binary APK manifest was checked independently: `com.example.ja`, version `2.5.1a`, code `20501`, min SDK 26, target SDK 35. APK size: 561,258,332 bytes. APK SHA-256: `961b400e8e293b9414a2f64bdb74510c9925a8912cf033f2a35aa4d0fee3d33a`.

Full-track account authorization, audible playback, real seeking/queue behavior and Android Auto interaction still require the device checks above. The latest stable public release remains v2.5.1.
