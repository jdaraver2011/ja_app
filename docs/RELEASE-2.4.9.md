# JA 2.4.9 (20409)

Based on v2.4.8, without the Music Lab experiment.

## Navigation and wording

- Back in Movie/TV content focuses the selected sub-button. Back from that row focuses the selected main tab; these are separate navigation steps, not a timed double press. The existing double-Back exit gate applies at the main row.
- Back in IPTV Groups returns to the IPTV main tab. Back in the guide opens Groups. Down from main navigation goes to the selected sub-row, or the IPTV preview; Down from the preview focuses the playing channel (switching to United States if necessary to expose it).
- Back from the Genre/Network jump dialog closes it and focuses the selected sub-button. Right/Close retains the previous row-return behavior.
- Remove the release/CAM explanatory lines from Movies and the network description. Add Movie Genres and TV Genres headings using the Popular heading typography.
- Capitalize the Search description and use Title, Cast, Director or Genre in the search box. Move TMDB attribution from Search to Settings.

## WeatherStar presentation and channel

- Settings > Weather > Use WeatherStar 4000+ card and dashboard selects the bundled widescreen-enhanced WeatherStar view; off retains the native NWS dashboard. The compact card retains readable conditions and identifies WeatherStar when selected. Both presentations use NOAA/NWS data, not independent competing forecasts.
- The pinned MIT jacaudi/ws4kp client is built into the APK, in static mode; no separately hosted server or browser install is needed. Source commit: 1ccc90154f0fc9d5da1df0405f814bad435f27d7.
- Saved Settings ZIP supplies location/coordinates. Location, Sound, Alerts, Refresh and Fullscreen controls are available. Forecasts refresh every 15 minutes while open. Weather audio starts muted; Sound enables it and adjusts volume, with Android audio focus and lifecycle cleanup.
- Channel 1, JA Local Weather, is a continuous rendered weather channel, not a finite video. It is available in United States, Weather and With EPG. A one-time migration inserts it first in Favorites while preserving existing order; it can subsequently be removed/reordered normally. Guide entries identify continuous local weather, not a broadcaster schedule.
- Channel 1 is accessible beside Refresh in either weather dashboard. Fullscreen weather-channel controls switch to the previous/next channel.
- Only the CC BY 4.0 track Sunny Afternoon (netbymatt, Latest Observations) is redistributed. The separate noncommercial album is excluded. MIT and music attribution ship with the client assets.

## Validation

Production navigation rules have a JVM regression test. The bundled client is built from a fixed commit and its music file is checked against the Git LFS SHA-256. CI runs existing regressions, signed APK compilation, strict lint and the IPTV catalog publication gate. Remote focus, phone/TV layout, WeatherStar network loading, audio and continuous playback require device verification.

For device review: `adb logcat -v threadtime JA_WEATHERSTAR:V JA_LIVE_TV:V AndroidRuntime:E '*:S'`.
