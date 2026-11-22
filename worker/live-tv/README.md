# JA Live TV Cloudflare refresh

This Worker refreshes the app's existing public catalog after 5:00 AM Central and republishes it to the `live-tv-data/catalog.json` branch. JA's existing Live TV repository reads that path, so no per-release catalog URL change is required. New version branches should be created from the latest JA branch so the Worker source, deployment workflow, and Test view carry forward.

## Schedule

The Cron Trigger is `20 11 * * *` UTC (5:20 AM Central Standard Time; 6:20 AM Central Daylight Time). Cloudflare Cron Triggers use UTC.

## Cloudflare setup

The GitHub deployment workflow is manual. In GitHub Actions, run **Deploy JA Live TV Cloudflare Worker** on the branch containing these files. The repository needs these Actions secrets:

- `CLOUDFLARE_ACCOUNT_ID`
- `CLOUDFLARE_API_TOKEN`

The token should be scoped to the Cloudflare account and have Workers Editor permissions. Do not commit either value.

After deployment, set Worker runtime secrets in Cloudflare:

- `GITHUB_TOKEN`: fine-grained GitHub token scoped to `jdaraver2011/ja_app`, with Contents read/write and Metadata read. It allows the scheduled Worker to recreate `live-tv-data` if it is missing and update `catalog.json`.
- `ADMIN_TOKEN` (optional): protects the manual `POST /refresh` route.

The Worker reads the credential fields from the source page and looks for a playlist/download URL in its HTML. If the page only shows credentials and does not include a downloadable link in its HTML, set `PLAYLIST_URL_TEMPLATE` as a Worker variable using the provider's M3U URL format and `{username}` / `{password}` placeholders. The runtime token and URL template belong in Cloudflare, never in Git.

## Catalog and limits

The refresh filters U.S. entries, checks each candidate stream with a small ranged GET request, and publishes only channels returning a successful HTTP response. A failed refresh or an empty result leaves the previous catalog intact. A refresh with more than `MAX_CHANNELS` candidates (default 2,000) also fails safely rather than publishing a partial list.

All imported channels are tagged `test`, shown in JA as **Test**, and remain included in **United States**. The catalog is on a public GitHub branch because the app currently reads it without authentication. As a result, the stream URLs in the catalog are visible to anyone who can access the repository. Do not use this design for private credentials or streams that must be access-controlled.

## Limits before first refresh

The source page must actually return the labeled credential values and either an M3U download link or a configured URL template. The first successful scheduled refresh creates `live-tv-data` if it was deleted. Until then, a device with no cached catalog cannot load the remote catalog. A sanitized sample M3U is needed to confirm the source's group names and country labels before relying on the filter.
