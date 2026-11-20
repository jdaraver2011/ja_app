# JA v2.4.8

- Removed the Groups / IPTV / search / Refresh header row. The preview and main navigation remain.
- Magnifying-glass search lives beside the Groups heading and opens an explicit search dialog for the selected group. Selecting the icon activates text entry; focusing it alone never opens the keyboard. An active filter appears in the preview information. The group-name/count link beside the preview also opens Groups for touch users.
- Hold OK or long-press a channel name or EPG cell to open a right-side channel menu: Watch fullscreen, Add/Remove Favorites, Reorder channels, Refresh channels and guide, Close. The initiating held-key release is swallowed so it cannot activate Watch. Reorder begins only after choosing that action, retaining per-group save/cancel behavior. Phone users choose Reorder and then drag the channel.
- Groups focus cannot escape upward or leftward. Up from the first group reaches Search, and Up on Search stays inside Groups. Right or Back returns to the guide. OK selects a group without leaving the sidebar. Back from a focused guide opens/focuses Groups. Main navigation remains reachable by Up from the guide/preview; its ordinary Back/exit behavior remains.
- The confirmed playing channel is filled yellow independently of the white focus outline; favorite status remains a leftmost star indicator, with toggling available through the channel menu.
- Channel rows are 48dp high, with inline logo and single-line channel name. Program cells use one title line; program time details remain beside the preview. The visible timeline spans two hours at every width and scrolls through the remaining 24-hour horizon. Sidebar/size changes preserve the viewed time, and a thin yellow marker tracks now. Viewport program composition plus neighboring cells is retained for remote navigation.
- The preview/fullscreen single-player design, live-only feed validation/recovery, cached/concurrent guide loading, saved favorites and channel orders remain.

Checks include geometry/remapping, Groups key boundaries, held-key suppression, existing reorder/save/cancel tests, regressions, signed build, strict lint and catalog publication gate. Device layout and focus/playback testing remain required.

Device checks: compare channel density and two-hour scale with Groups open/closed; try Up on Search/first group and Down on the final group; Back from guide and Groups; hold OK and release without selecting an action; toggle favorites; choose Reorder then move/save/cancel and restart; focus elsewhere while checking yellow playing marker; search and clear a filter; scroll later times and through long shows; enter/leave fullscreen without reconnecting.

ADB: `adb logcat -v threadtime "JA_LIVE_TV:I" "*:S" > JA-v2.4.8-Test.txt`
