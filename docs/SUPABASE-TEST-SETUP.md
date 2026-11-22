# JA Supabase test build setup

The `v2.6.3` branch is a non-production test build. Its welcome page includes a hidden test-only unlock gesture: tap the JA logo seven times, then enter `192168` to use JA locally without an account. The unlock is remembered for that installed app version and is requested again after an app update. The code is present in this public repository and can be extracted from the APK; never merge or publish this bypass in a public device update.

With normal account access, this branch requires sign-in or account creation the first time JA opens after installation. New accounts remain pending until an administrator approves them in Supabase. It adds email/password authentication and account sync for watched/favorite Movies and TV, resume progress, JA Music likes/saved items/playlists/recent searches, and IPTV favorite channels and their order. Playback/display preferences, provider credentials, current playback state, and temporary caches remain on each device.

## 1. Create the sync table

In the Supabase project, open **SQL Editor**, create a query, paste `supabase/ja_user_state.sql`, and run it. It creates the account approval table and the sync table. Row-level security lets each account read its own approval status; only approved accounts can read or change their own sync row.

## 2. Approve the first JA account and new users

Create your administrator/owner account in JA and verify its email. In Supabase, open **Table Editor → ja_user_access**, find that account by `user_id` (match it to the user ID in **Authentication → Users**), and set `approved` to `true`. You can leave `approved_at` blank. The account can then sign in to JA.

For each future registration, verify the person is the intended user, then change their row's `approved` value to `true` in `ja_user_access`. Until then, JA stays at the sign-in screen and the sync table's row-level security blocks access. Approval is manual; this test build does not send an administrator notification or provide an in-app admin panel.

## 3. Configure email links in Supabase

Open **Authentication → URL Configuration** and add this exact value to **Redirect URLs**:

`ja://auth/callback`

The JA test build explicitly requests this callback for email confirmation and password recovery. The current Site URL can remain unchanged. When sending a recovery email from Supabase's dashboard, that dashboard action may use the Site URL; use **Forgot password?** in JA so the email is sent with JA's callback. Existing email links keep the redirect they were created with, so request a fresh email after installing the callback-enabled build.

## 4. Build variables in GitHub

In `jdaraver2011/ja_app`, open **Settings → Secrets and variables → Actions → Secrets** and add these repository secrets (the workflow reads from `secrets.*`):

| Secret | Value |
| --- | --- |
| `SUPABASE_URL` | The Project URL from Supabase **Settings → API** |
| `SUPABASE_PUBLISHABLE_KEY` | The `sb_publishable_…` key from Supabase **Settings → API Keys** |

These are client-side connection values. Do not add a Supabase secret or `service_role` key to the app or GitHub Actions.

## 5. Build the test APK

Open **Actions → Build JA APK → Run workflow**, select branch `v2.6.3`, leave **Run automated tests and lint** off for this one-build smoke run, and leave **Publish this version as a public device update** off. Download artifact `JA-2.6.3-supabase-test-debug` and install the APK directly.

Email verification remains enabled. A new user must verify the email address and receive admin approval before JA opens. Initial sync uploads this device's saved data if no cloud row exists. If cloud data already exists and this device also has saved data, JA asks whether to download or upload to prevent accidental replacement.
