# Native music investigation — isolated experiment

This directory contains research notes. In v2.2.2 the independently written coordinator and simulated check runner are packaged under `app/src/main/java/com/example/ja/experiments/music/`. Settings can invoke the checks on-device and report JA_MUSIC_NATIVE logs. They use a fake player and do not replace the embedded web player. No native Deezer playback has been demonstrated in JA. The public v2.2.1 release is unchanged.

## Input and scope

Inspected the user-provided Deezer 9.0.26.3 (9002603) APKM through manifest parsing, DEX instruction inspection and selected Androguard DAD decompilation. APKM SHA-256:

`da064bfb8391b4a2f2ae60b158f76ea08b439b534156e2ec694e633ccf568807`

This is static analysis, plus review of user-supplied device diagnostics. There is no Android runtime attached to this workspace. Generated Java contains incorrect variable types and malformed expressions in places; it is analysis material, not compilable original source. Obfuscated names below identify this exact package version and are not stable APIs. No APK binaries, decompiled source, credentials, URLs with account tokens or native libraries are committed.

## Traced functions

| Location | Evidence | Interpretation / limit |
| --- | --- | --- |
| `classes2.dex`: `com.deezer.core.jukebox.JukeboxService.IIllIIIlIIIllII()` | Constructs a dependency container, selects an implementation of `IIIlIlllIlIIIIIl`, activates `MediaSessionCompat` | Player initialization is separate from the Android service. Selection depends on configuration; runtime selection has not been measured. |
| Same factory | Reads `feature_2025_reduced_buffer` and a Sony 360 Audio option | Buffering and optional renderer behavior are configurable. Do not assume a flag is enabled for the user's account. |
| `classes2.dex`: `IllIIlIlIII.<init>(...)` | Builds ExoPlayer components; optionally reflects `com.google.android.exoplayer2.ext.mpegh.MpeghAudioRenderer` | Confirms an ExoPlayer-based path and optional MPEG-H support. Reflection is optional, not proof the component exists or was used. |
| `classes2.dex`: `IllIIlIlIII.lIllIIIlIlIlll(track, position, flags...)` | Records the selected track, resets state and either requests a source asynchronously or processes an existing stream URL | Track selection and source retrieval are separate operations. A metadata track ID is insufficient by itself. |
| `classes2.dex`: `lllIlIllIIllIIl.lIllIIIlIlIlll(track, source, metadata, timing, ...)` | Checks track identity and current source; separate branch appends a source for a pending next track | Current-track and next-track handoff use different paths. Full queue semantics are not yet reconstructed. |
| `classes2.dex`: `IllIIlIlIII.llIlllIllIIIIl(...)` | Suppresses repeated current source, builds/attaches a media source and applies playback/resume state | A useful boundary for an independent JA source coordinator. |
| `classes4.dex`: `IIIlIIllllIIIl.lIllllIIIIII(...)` | Format-code 16 constructs `HlsMediaSource.Factory`; other path constructs a source with a custom data-source factory | HLS and a custom source path exist. The custom path is not reproduced; a generic HTTP URL adapter is not established as sufficient. |
| Device service dump | `JukeboxService` is foreground with a media notification | Supports the foreground-service architecture, not reuse of its implementation in JA. |
| Device session dump | Active session reports PLAYING without a session error | Confirms the installed native app's state at capture time. It does not establish JA compatibility. |

## Original prototype

`app/src/main/java/com/example/ja/experiments/music/JaMusicSourceCoordinator.java` is independently written Java for source handoff, not a port of Deezer's client. A resolver would supply an authorized HTTPS media source to this coordinator; no such Deezer resolver is implemented here. The player interface is a test boundary, not a Media3 adapter. No Android background service, authentication, next-track prefetch, DRM or audio decoder is implemented by this prototype.

It models selected-track resolution, deduplicated result delivery, resume-position handoff, pause during resolution and cancellation. It additionally uses request generations to reject stale results even when the same track is selected twice. Generation tokens are a JA design improvement; they were not established as Deezer's implementation. Video playback blocks music until the user explicitly resumes it.

All methods must run on one owner thread. READY means the source was handed to the adapter, not that audio was decoded or heard. A future asynchronous Android adapter must report actual decoder failures and respect service/audio-focus lifecycle separately. Avoid logging source URLs, which may carry credentials.

## Validation

Run with JDK 17 from the repository root:

```sh
javac -d /tmp/ja-music-prototype app/src/main/java/com/example/ja/experiments/music/JaMusicSourceCoordinator.java app/src/main/java/com/example/ja/experiments/music/JaMusicExperimentChecks.java
java -cp /tmp/ja-music-prototype com.example.ja.experiments.music.JaMusicExperimentChecks
```

Passed locally: previous-track result/error rejection, duplicate callback rejection, same-track generation isolation, resume position delivered before playback, pause during loading, video-gated playback/no automatic resume, cancellation after Stop, invalid-source rejection and synchronous adapter failure. Tests use a fake player and a reserved example domain. They do not contact Deezer and are not evidence of working native streaming.

## Remaining integration boundary

The unresolved prerequisite is a source provider that can obtain an account-authorized playable source and any required playback configuration without the installed Deezer application. The static analysis has not demonstrated that capability. A future Android prototype needs that provider, a real Media3 adapter, audio focus/notification lifecycle, error reporting and device testing before production integration. Ordinary ADB logcat captures cannot supply a complete implementation of the private protocol.

## v2.2.2 device test

Install the signed testing APK, then toggle Settings > Music > Show Music tab off/on. Restart JA to verify persistence. If music is already running, hiding the tab should leave playback alone; Stop Music remains in Settings. Run simulated Music checks and capture `adb logcat -v threadtime JA_MUSIC_NATIVE:I JA_MUSIC:I "*:S"`. Successful output ends with `RUN_COMPLETE mode=SIMULATED result=PASS nativeDeezerPlayback=false`. These are coordinator tests, not a test of a native Deezer source provider.
