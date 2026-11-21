# JA 2.5.2 — Music library and player

Full-screen Music browsing for phones and Android TV, with JA's gold focus styling. Home shelves expose Top Tracks, Albums, Artists, Featured Playlists and New Releases; Explore browses Deezer genres. Search separates Songs, Artists, Albums and Playlists. Artist pages offer top songs, albums and similar artists. Albums and playlists expose track lists; Load More follows provider pagination. Metadata is cached for five minutes in a bounded 64-response memory cache. Catalogue availability varies by region and the provider.

JA Library stores local playlists, liked songs and saved artists/albums/playlists. Create, rename, delete, add/remove and move playlist songs through the song menu. These operations never mutate the Deezer account. Play All and Shuffle build a queue of up to 500 songs. Native player offers previous/play/pause/next, seek, shuffle/repeat, queue and song menus. Playback/foreground protection and the encrypted-session vault from 2.5.1b remain in place. Temporary audio cache remains 64 MiB. Video playback retains audio ownership.

Lyrics opens a separate screen, follows authenticated provider-supplied timed lines when available, and supports manual reading; unsupported songs/accounts display an unavailable message. Lyrics are not fetched from unrelated sites. Lyric results are discarded on leaving the screen; network requests have a bounded timeout. No lyrics or credentials are persisted.

Visualizer opens a full-screen MilkDrop-compatible WebGL 2 renderer (Butterchurn 2.6.7 + MIT presets 2.4.7). This is not the Windows MilkDrop executable. The native Media3 audio sink tees decoded PCM without changing the decoder buffer; a bounded 1024-sample bridge feeds the offline renderer. No microphone permission, account data, network scripts or audio downloads are exposed to WebView. Capture/rendering stops when hidden or closed. Rendering is capped at 25 fps/1280×720 with a modest mesh; unsupported WebGL devices show an explicit message while music continues. Next Visual changes presets. Included licenses are bundled with the APK; package tarballs are SHA-512 verified at build time.

Android Auto retains host-rendered audio browse and playback templates. Standard MediaLibrary browsing includes JA playlists and liked songs; visualizer/lyrics are phone/TV views. No car-host certification claim.

Validation: Java PCM regression (stereo mix, peak conversion, buffer preservation, expiration and disabled capture); pinned real WebGL/preset/portrait smoke test; Android 35 local playlist persistence/reorder and Compose navigation; 35-second slow-buffer foreground-service regression; repository regression suite and Android lint. Live Deezer account playback, timed lyric availability, Android TV focus and Android Auto remain device checks.


## Published validation

- Source/build commit: `101c6a30a12f6fd35cdc2d9ab55abe42db341a80`.
- Successful publication workflow: [44910992017](https://github.com/jdaraver2011/ja_app/actions/runs/44910992017).
- Android 35 emulator: **4 passed, 0 failures, 0 errors, 0 skipped**. Slow-buffer foreground test 46.008s; 700-song selection/Stop-state regression 4.904s; full-screen library/player navigation 6.673s; local playlist persistence/reorder 3.630s.
- Pinned WebGL renderer/preset/portrait, PCM conversion, repository regressions, APK compilation, strict Android lint and IPTV publication validation passed.
- Public latest release: [v2.5.2](https://github.com/jdaraver2011/ja_app/releases/tag/v2.5.2), not a draft/prerelease.
- APK: `com.example.ja`, version `2.5.2`, code `20502`; **561590684 bytes**.
- APK SHA-256: `ce073974eb32396b27abcbdf1a26ddd21d1e576906961ebed44dd1adadd474f7`.
- Downloaded public `ja-update.json` matches the package, version, size, SHA-256 and published APK asset. Persistent update signing was restored by the workflow. No Deezer credentials were used on the runner.
- Stop now clears stale UI metadata. A queue of up to 500 songs is selected around the chosen song in larger lists, preserving the requested track; a separate intent-side 100-song truncation was removed.

Install using **Settings > Update Now**, or [download the APK](https://github.com/jdaraver2011/ja_app/releases/download/v2.5.2/JA-update.apk). Device checks remain: real-account playback, lyrics for available songs, visualizer support/performance, TV remote navigation and Android Auto host behavior.
