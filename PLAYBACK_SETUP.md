# JA playback (TMDB ID mode)

JA uses TMDB numeric IDs directly for movie and TV episode embed URL generation. IMDb lookup is not required for the default playback path.

Default URL patterns (illustrative, subject to provider permission and availability):

- Movie: `https://vidsrc.sh/embed/movie/{id}`
- TV episode: `https://vidsrc.sh/embed/tv/{id}/{season}/{episode}`

`AUTHORIZED_EMBED_TEMPLATE` and `AUTHORIZED_EMBED_HOST` remain supported as overrides. If you previously configured the IMDb template in GitHub Actions secrets, **remove or update it** or it will override the new default. To use the default, remove both secrets, then rebuild.

JA opens HTML embed pages with WebView, not ExoPlayer. It enables JavaScript, DOM storage, and HTML5 fullscreen, and reports main-frame WebView errors. Provider availability, redirects, and playback rights are not guaranteed.

To build: commit the updated Kotlin files to GitHub and run the existing Build JA Android APK workflow.
