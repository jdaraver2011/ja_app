# JA 2.5.3 — Music layout and visualizer diagnostics

Music artwork cards have transparent backgrounds on Home, Explore, Search, artist/album pages and saved-library grids. A white focus border remains for TV navigation; artwork shapes remain square/rounded or circular for artist portraits. Music and visualizer screens respect safe system-bar/cutout insets.

Library uses Tracks, Artists, Albums and Playlists sub-tabs. Tracks contains locally liked songs; Artists and Albums contain saved catalogue items; Playlists retains local creation/rename/edit/reorder and saved provider playlists. Existing preferences are preserved.

Visualizer diagnostics use JA_MUSIC_VIS: WebView readiness/hardware status, render readiness/errors, throttled frame/PCM heartbeats, audio age/channel count and signal peaks. Runtime/context-loss errors become visible instead of silently leaving a black screen. The waveform bridge is an explicitly accessible class. No credentials, signed audio URLs or lyrics are logged. A real Android WebView regression exercises the native waveform bridge and renderer in addition to the desktop WebGL and PCM tests. This does not establish the cause on the user's phone; a device capture is still required if black output persists.

## Device capture

After installing, start a song and confirm it is playing. Open Visualizer, wait 30 seconds, press Next Visual, wait another 15 seconds, return to the player, then stop capture with Ctrl+C:

```
adb logcat -c
adb logcat -v threadtime JA_MUSIC_VIS:I JA_MUSIC_LAB:I chromium:W AndroidRuntime:E "*:S" > JA-Music-253-Visualizer.txt
```

The same command on 2.5.2 captures existing playback/Chromium errors, but JA_MUSIC_VIS diagnostics are new in 2.5.3. Include phone model, Android version, whether music continues while the visualizer is black, and whether Next Visual changes anything. With multiple devices, put `-s DEVICE_SERIAL` immediately after `adb`.

Prepared as a device-test build; publication is disabled for this workflow.


## Build validation

[Workflow 44949745674](https://github.com/jdaraver2011/ja_app/actions/runs/44949745674) succeeded: compile, strict lint, desktop WebGL, PCM and repository regressions; **5 Android 35 tests passed, 0 failures/errors/skips**. The real Android WebView test received nonzero PCM and rendered frames (25.570s). Its log reported hardware acceleration enabled and PCM peak 64. This used synthetic audio, not a Deezer account or the user's phone. Library-tab navigation, local playlist persistence, large queue selection and slow-buffer foreground playback also passed. Test APK artifact 14725044403; ZIP SHA-256 `62e1421dee4d1ce047496d53b26e08dbe9ce6649a8bb56adef4977d1a9857c5d`.
