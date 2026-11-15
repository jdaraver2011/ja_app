# Playback review — uploaded JA 2.0.2

## Scope and evidence

Reviewed the uploaded source, its build/workflow metadata, root documentation, native display/remote/source-status handoff, output-quality.js, and remote.js startup subtitle/quality flow. This is a source review and documentation cleanup, not a new playback implementation or a live-provider test. No device log was supplied. Exact runtime cause of Auto remaining selected is therefore unconfirmed.

## Quality: confirmed weaknesses

1. **API discovery is overly restrictive.** remote.js requires the active video to have a `.jwplayer[id]` ancestor before calling the page's jwplayer(host.id). A different wrapper or a JW-like custom player produces no API. Visual similarity to JW does not establish a genuine JW API.
2. **Interaction cancellation is too broad.** `userBusy` uses defaultsUserMenuTouched or qualityUserTouched. Remote navigation into any player controls, or a trusted pointer press on any `.jw-btn`, permanently ends the attempt. Opening settings to inspect quality during the attempt can itself cancel it.
3. **Dispatch is not acknowledgment.** GeckoExperimentalPlayer records qualityTargets immediately after posting a command. It does not distinguish delivered, supported, requested, verified or failed. The same height is not resent after a timeout/error within the same port.
4. **Readiness failures are collapsed.** output-quality.js waits up to 30 seconds, then merges missing API and unready levels into API_OR_LEVELS_NOT_READY. API exceptions terminate immediately; no bounded recovery for transient setup errors exists.
5. **Native gates can prevent any command.** TV settings, confirmed playback/document URL, exact sender-document match, window focus and physically applied requested display mode must all succeed. These are sensible protections, but the quality path lacks a reason log for each unmet gate.
6. **Output matching is not a fixed stream preference.** The existing matcher derives output from the current video. If Auto initially results in 720p and JA applies 720p, the quality target is 720p, not necessarily 1080p. This must be visible in diagnostics.

## Subtitles: confirmed weaknesses

- Native textTracks disabling is only a startup default: it stops 1.8 seconds after playing, or after 18 seconds. Late tracks can arrive after this window; a quality switch may also recreate tracks.
- HTML textTracks alone does not establish the state of a player's custom caption renderer.
- The JW path opens a menu, searches English labels and specific `.jw-row-label` elements, then clicks Off. It is sensitive to visibility, markup, localization and track readiness.
- `ccRequested` becomes true after a click. The later settle phase disables native tracks but never verifies `getCurrentCaptions() === 0`. Completion therefore conflates request with success.
- Menu activity can abort the startup preferences, and initialization is tied mainly to video-element discovery rather than each playlist item/source generation.

## Recommended implementation order

### 1. Capability discovery and explicit outcomes

Build one shared adapter for quality and captions. First try the closest player container; if absent, inspect documented JW instances and accept only an instance whose getContainer() contains the active video. Do not blindly choose player zero in a frame with multiple videos. Report NO_JW_GLOBAL, NO_MATCHING_PLAYER, QUALITY_API_UNAVAILABLE, CAPTIONS_API_UNAVAILABLE, LEVELS_NOT_READY and native gate failures separately. Keep exact-frame targeting; do not fix delivery by broadcasting mutations to all frames.

Add a request ID tied to source/document generation and native acknowledgment states: armed, API available, requested, verified, unsupported, timed out. Allow bounded readiness retries only while the same generation is active. Cancel on navigation or disposal. Never loop indefinitely or reload playback to force a setting.

### 2. Captions through the player API

When captionsList indicates actual tracks are ready, request setCurrentCaptions(0), then confirm getCurrentCaptions() returns 0. The official documentation specifies index zero as Off and recommends captionsList for defaults. An initial Off-only list is not evidence that all tracks have loaded.

Handle playlistItem/video replacement as a new initialization generation. During a short startup window, handle late caption-list updates and quality-induced track recreation. Stop default enforcement when the viewer explicitly chooses captions; merely opening controls should not be treated as a caption choice. Retain native textTracks as a bounded fallback for native captions, without claiming that it proves JW renderer state.

### 3. Quality after reliable discovery

Use levels readiness and confirmed output dimensions to choose the exact quality or best lower level. Await levelsChanged/getCurrentQuality verification; additionally report visualQuality and decoded dimensions to distinguish fixed selection from buffered video still at its previous resolution. Only an explicit quality selection should cancel matching; opening Settings should not.

Keep the existing frame-matching algorithm and source order. On unavailable API, report unsupported and preserve playback. A confirmed custom player needs its own supported adapter; class names alone are insufficient. Do not re-enable the old highest-quality DOM-click startup code as an indiscriminate fallback.

### 4. Consolidate startup code after adapter verification

The old highest-quality API/DOM functions and qualitySettle/qualityRetry branches remain in remote.js, although the initial quality phase now skips directly to captions. Remove this obsolete path after the unified adapter works. Keep one preference owner to prevent conflicting defaults and misleading success logs.

## Device evidence needed

Capture from before opening a title through 60 seconds of playback, initially without opening controls. Then inspect Quality and Captions. Record title, source, episode if applicable, and enabled TV settings.

```text
adb -s DEVICE_SERIAL logcat -v threadtime JA_QUALITY:I JA_TV_BRIDGE:I JA_FRAME_RATE:I JA_PERF:I *:S > JA-quality-captions.txt
```

Replace DEVICE_SERIAL using adb devices; omit -s when only one device is connected. Stop with Ctrl+C. In the current code, TARGET_SENT without ARMED suggests delivery/context trouble; ARMED followed by USER_CONTROL indicates cancellation; UNAVAILABLE points to discovery/readiness; REQUESTED without VERIFIED points to API acceptance/selection problems. Absence of TARGET_SENT needs native-gate diagnostics rather than assumptions about JW.

## Acceptance checks for the next implementation

Test delayed APIs, delayed caption tracks, nonstandard wrappers, multiple players, replaced video elements, source changes, explicit manual choices, missing quality levels, ignored API requests and cancellation. On-device checks must cover VidSrc, an unsupported provider and VidFast; startup/resume/seek/Back; subtitle Off verification; selected quality and decoded dimensions; and frame-drop comparison with the setting ON/OFF. Unit tests of selection logic cannot prove integration with the real provider.

## Official references

- https://docs.jwplayer.com/players/reference/captions-events
- https://docs.jwplayer.com/players/reference/setcurrentcaptions_index_
- https://docs.jwplayer.com/players/reference/getcurrentcaptions
- https://docs.jwplayer.com/players/reference/quality
- https://docs.jwplayer.com/players/reference/getqualitylevels
- https://docs.jwplayer.com/players/reference/setcurrentquality_index_
- https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Sharing_objects_with_page_scripts
