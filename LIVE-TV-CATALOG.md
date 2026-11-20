# JA verified U.S. live channel catalog

Sources: https://github.com/iptv-org/api and https://github.com/iptv-org/iptv (CC0 metadata), plus https://github.com/Free-TV/IPTV and its https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8 playlist. Only explicitly U.S. Free-TV entries are imported from the worldwide playlist. Original names/logos/URLs remain attributed to those sources. No videos are stored here.

A daily workflow samples every candidate adaptive stream twice, at least twelve seconds apart. Only advancing HLS or dynamic DASH streams are included; completed and frozen playlists are excluded. Short rolling live windows are allowed. A failed source download or unusually empty scan preserves the last successful catalog. Availability at scan time is not a guarantee of future playback, geography or segment decoding.

Existing IDs are preserved by exact URL, canonical ID or unique exact name matches; unmatched source identities use a stable free-tv namespace. Duplicate channel streams are merged and keep source provenance in the scan report. The report separates Free-TV candidates, duplicate entries, passing streams and uniquely added verified channels. VLC/URL user-agent and referrer options are retained. App clients download catalog.json with a daily cache and manual refresh; favorites are not overwritten.
