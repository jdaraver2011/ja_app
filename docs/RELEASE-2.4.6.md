# JA v2.4.6

Based on stable v2.4.5; Music Lab is not included.

- IPTV is now a main tab immediately after TV; TV sub-tabs are Popular, Genres and Networks.
- The IPTV heading and channel search share a row, with white text. Removed the feed-check date/instruction line.
- Favorite stars precede channel names. Favorites > Reorder exposes up/down controls; Done closes them. Existing favorites migrate without losing membership; order persists by channel ID across catalog refreshes, app restarts and temporarily missing channels.
- Retain the loaded channel catalog, indexed guide, group position, horizontal guide position and channel list position through playback. Returning restores focus without unnecessarily jumping the list.
- Guide downloads belong to the parent screen scope, so opening a channel no longer restarts them. Cached schedules appear immediately; national and Pluto providers load concurrently with partial results. First-ever downloads still depend on provider speed and availability.
- XML alias matching uses reverse indexes and ignores dates for unmatched channels. Guide channel indexes are built off the UI thread. Timeline composition is limited to the visible time range plus neighboring programs for remote navigation.
- Avoid duplicate history reads on return, move favorites history reconciliation off Main, and mark immutable poster models for Compose skipping.

Checks: regression workflow, strict lint and IPTV catalog publication gate. New JVM coverage verifies favorite migration, order persistence through outages, removal/addition, duplicate cleanup and move boundaries.

Device verification: top-row IPTV location; search/header legibility; cold and warm With EPG load; scroll horizontally across long programs using a remote; reorder favorites with/without search, restart app, open a channel and Back, and verify focus/list/group/time position. Check Movie/TV detail Back focus and scrolling. Performance measurements require device testing; no universal frame-rate claim.

ADB: `adb logcat -v threadtime "JA_LIVE_TV:I" "JA_DETAILS_NAV:I" "*:S" > JA-v2.4.6-Test.txt`
