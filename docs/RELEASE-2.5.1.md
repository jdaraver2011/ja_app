# JA 2.5.1 (20501)

Based on v2.5.0. The separate Deezer/Music Lab experiment is not included.

## Weather audio

Weather music now plays through the existing native Media3 engine instead of operating hidden browser controls. Bundled asset playback avoids WebView audio loading and autoplay behavior. Native playback handles repeat, lifecycle, audio focus and JA volume, and emits READY, PLAYING, PROGRESS and ERROR records under JA_WEATHER_AUDIO. Browser music is disabled to prevent competing audio owners. Audible output still requires device verification; a browser button/checkmark is not considered evidence of playback.

Settings > Weather offers Sunny Afternoon, Morning Fog and September Drizzle from netbymatt's CC BY 4.0 Latest Observations album, plus a device-audio-file picker. Selected files retain read access on that device and loop while weather is open; the file is not uploaded. Sound, track and volume choices are saved locally. A TV without a document picker can use the bundled choices.

## Weather presentation

Normal bottom-ticker observations and headers are centered across the full row, with the typing-width animation removed. Long alert text retains horizontal scrolling. The full-frame fit and controls-free fullscreen presentation remain.

Per the requested clarification, JA Local Weather appears only in Favorites, labeled 1 there. It is excluded from United States, Weather and With EPG. Other channels' group membership and saved ordering are retained.

The home weather card, native current-conditions panel and extended forecast use local WeatherStar condition graphics with day/night and precipitation mapping from the pinned upstream icon set. Static transparent PNGs use the source GIFs' first frame, reducing image network requests and animation work. NOAA/NWS still supplies conditions and forecast data.

## UI and startup

A new activity clears restored list/grid offsets and pending title focus, starts the primary navigation at Movies, and leaves newly created genre/network rows at their beginning. Returning from details continues to preserve in-session positions. The existing notification entry route is retained.

The native date/time header updates at minute boundaries and pauses while the UI is inactive. Movie visibility filtering is cached, visible genre rows reuse the filtered results, and Popular poster cells have a stable content type. No device-wide scrolling performance claim is made without profiling.

## Validation

The three bundled music files must match their pinned Git LFS SHA-256 values, contain valid MP3 streams longer than 30 seconds, and decode successfully before packaging. Browser checks cover weather viewport fit, centered ticker text and disabled browser audio. Condition-icon mappings have JVM checks. Existing regressions, signed APK compilation, strict lint and the IPTV publication gate run before publication.

Device log capture (enable sound, open weather for at least 15 seconds, then disable it and reopen):

```
adb logcat -v threadtime JA_WEATHER_AUDIO:V JA_STARTUP:I AndroidRuntime:E "*:S" > JA-v2.5.1-test.txt
```

Supabase is not configured in this release. Its desired role in JA has not yet been specified.
