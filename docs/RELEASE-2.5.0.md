# JA 2.5.0 (20500)

Based on v2.4.9.

- WeatherStar opens directly as a fullscreen, controls-free weather display. The added Location, Sound, Alerts, Refresh, Fullscreen and channel-switch toolbars and JA title strip are removed from the weather presentation. Back returns to the prior JA screen/guide.
- A JA-specific kiosk fit path scales the entire 854×480 widescreen image uniformly and centers it inside the actual WebView viewport. Black bars preserve the header, maps and bottom ticker when the screen ratio differs. Browser text zoom is fixed at 100%, scrolling/zoom gestures are disabled, and screen-awake/lifecycle cleanup is retained.
- Weather sound defaults on, including upgrades that never saved an explicit sound preference. Explicit saved mute choices remain respected. Temporary Android audio-focus loss mutes playback without overwriting that preference; sound resumes when focus returns.
- ZIP/location, WeatherStar sound and volume controls are in main Settings > Weather. The WeatherStar presentation toggle and sound toggle use the same full-row white focus outline as the other JA settings.
- IPTV group order starts Favorites, With EPG, United States, then the existing categories. Channel favorites and per-group channel ordering remain unchanged.
- Existing native NWS weather dashboard remains available via the presentation toggle. The WeatherStar channel remains available as Channel 1.

## Validation

The real bundled WeatherStar browser check tests sound enable/mute/volume and verifies the entire weather container stays within landscape, portrait, TV and compact-preview viewports while retaining its aspect ratio and hiding controls. Playlist/group-order regressions, existing tests, signed APK compilation, strict lint and the IPTV publication gate run before publishing. Android phone/TV fullscreen bounds, system-bar behavior and sound still require device verification.

Device log capture: `adb logcat -v threadtime JA_WEATHERSTAR:V JA_LIVE_TV:V AndroidRuntime:E '*:S'`.
