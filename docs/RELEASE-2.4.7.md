# JA v2.4.7

Confirmed layout: groups left, guide right; last-channel preview on IPTV entry; OK opens fullscreen immediately; hold-to-move in every IPTV group.

- Collapsible vertical group sidebar. Left from the leftmost channel/star control opens groups; Right/OK from groups enters the guide. Left from groups returns focus to the main IPTV tab. Touch users have a Groups button.
- Preview above the guide resumes the last successfully playing channel. If it is unavailable, a favorite or the first catalog channel is selected. Navigating the guide does not retune; OK/tap on a channel opens it fullscreen. Selecting the preview opens its current channel fullscreen. Back returns to the guide/preview.
- The player is owned above the preview/fullscreen view presentation. Changing presentation does not prepare a second player or reconnect the stream. Preview stops when leaving IPTV and pauses when JA is backgrounded. Existing live-only feed rejection/recovery remains.
- Hold remote OK to pick up a channel, Up/Down to move, OK to save, Back to cancel. The initial hold release does not immediately drop the channel. No visible movement arrows. Touch supports long-press and vertical drag, committing on release. Each group persists its own stable-ID order; filtered edits retain hidden channels and temporary outages do not discard stored IDs.
- Native Search, Preferred ZIP and IPTV Search are navigation targets until explicitly selected. OK/tap activates editing and the keyboard; focus/hover alone does not create an input connection. IME actions and Back end editing. Browser-hosted third-party forms are not changed.

Checks: existing regressions plus movement/save/cancel, filtered merges, outage handling, duplicate rejection and group isolation. Signed build, lint and catalog publication gate are required. Real-device layout/focus/gesture/playback testing remains required.

Device checks: navigate groups/guide/main menu with Left/Right; open IPTV twice and after app restart; verify last confirmed channel; compare OPEN log entries before/after preview/fullscreen to detect unexpected reconnects; short OK must open fullscreen, long OK must only pick up; test movement at both ends, save/cancel and every group; test touch drag, phone portrait/landscape, background pause/resume, live recovery; focus Search/ZIP without keyboard then select and enter text.

ADB: `adb logcat -v threadtime "JA_LIVE_TV:I" "JA_DETAILS_NAV:I" "*:S" > JA-v2.4.7-Test.txt`
