# JA playback configuration

JA uses TMDB to browse titles and resolve IMDb IDs via the TMDB `/movie/{id}/external_ids` or `/tv/{id}/external_ids` endpoint. An IMDb ID is not a TMDB ID.

Configure these repository **Actions secrets** before building:

- `TMDB_API_KEY`: TMDB API key.
- `AUTHORIZED_EMBED_HOST`: hostname of a provider that permits you to embed its content.
- `AUTHORIZED_EMBED_TEMPLATE`: HTTPS embed URL template for that provider.

Supported template tokens: `{imdb_id}`, `{id}` (TMDB ID), `{type}` (`movie` or `tv`), `{season}`, `{episode}`. Example for a permitted provider: `https://media.example.com/embed/{type}/{imdb_id}`. For a provider with separate TV URL conventions, adjust the resolver to use separate templates; the example is not a guarantee of provider compatibility.

The app only builds an embed URL. It does not test content availability, license rights, or bypass DRM. Never put a private API key into a publicly distributed APK if it grants sensitive access; BuildConfig values are extractable.

After updating secrets, rerun GitHub Actions and download the new `JA-debug-apk` artifact.
