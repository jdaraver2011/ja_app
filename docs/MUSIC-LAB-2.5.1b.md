# Music test 2.5.1b — foreground service fix

Based on 2.5.1a. Same package/signing/version code 20501 so the stable v2.5.1 APK can still be reinstalled. No public update publication. Music UI, Deezer requests, stream decoding and the 64 MiB cache are unchanged.

The supplied device logs show PLAYER_STATE=READY followed about 28 seconds later by ForegroundServiceDidNotStartInTimeException. Direct UI playback created the session without registering it in MediaLibraryService; no controller bind had guaranteed Media3 notification listeners were attached.

Changes:
- Register the media session immediately with addSession(), independent of phone/car/browser clients.
- For an authenticated QUEUE start, show a bootstrap foreground notification before player/network preparation. Media3 uses the same notification ID/channel and takes over normal controls/lifecycle, without duplicate notices.
- Report foreground-start refusal as a sanitized error rather than starting unprotected playback.
- Android instrumentation regression starts local playback without a browser/controller bind, checks service session registration, injects a synthetic WAV that takes 35 seconds to open, verifies foreground protection beyond the observed 30-second deadline, then checks actual playback progress and a single notification. It needs no Deezer login and does not verify Deezer authorization or music sound.

Device retest: install test APK; Music > use saved account; play the same failing tracks for at least 2 minutes, then check full-track completion, seeking, next track, screen-off/background playback and Stop. Expected diagnostic events: SESSION_REGISTERED count=1, FOREGROUND_READY sessionAdded=true, AUDIO_RESOLVED, PLAYER_STATE state=3. Capture JA_MUSIC_LAB and AndroidRuntime if a new failure occurs. Full-track account playback and Android Auto behavior still need device verification.

## Build verification

[Workflow 44868461692](https://github.com/jdaraver2011/ja_app/actions/runs/44868461692) passed regressions, persistent-key signed APK compilation, lint and Android 35 emulator instrumentation. The Android report confirms 1 test, 0 failures, 0 errors, 0 skipped, 41.313 seconds. Built source commit: `5e96702a75ac7efcab119a6969b09a00011fcb44`.

The downloaded archive matched GitHub's artifact SHA-256. The APK manifest was independently verified: package `com.example.ja`, version `2.5.1b`, code `20501`, min SDK 26, target SDK 35. APK size 561,258,748 bytes; SHA-256 `29e73fadf7b81bcd007a1dbd392b448ace14919a8bbec9aa14468d81c90e3948`.

Real-account Deezer playback and physical Android Auto tests remain pending. Latest stable public release was confirmed unchanged at v2.5.1.
