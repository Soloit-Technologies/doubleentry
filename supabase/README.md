# Supabase backend

Everything the app needs on the server is in this folder, so you can change the backend from
the repository instead of the dashboard.

```
supabase/
  config.toml      Supabase CLI configuration (local development)
  migrations/      Schema: tables, row-level security, triggers, realtime (applied in order)
  functions/       Edge Functions: Google Play purchases and account deletion
  templates/       Auth email (password-reset code)
  queries/         Read-only checks and maintenance SQL to run in the SQL editor
  tests/           Security tests (pgTAP), run with `supabase test db`
  fixtures/        Sample data and the upgrade check run by GitHub Actions
```

| Migration | What it does |
|---|---|
| `20250101000000_ledger_core.sql` | `accounts`, `transactions`, `transaction_entries`, `assets`: tables, indexes, `updated_at` trigger, RLS, realtime |
| `20250601000000_invoices.sql` | `invoices` (unchanged from the MAUI repository) |
| `20250601000001_user_settings.sql` | `user_settings` (unchanged from the MAUI repository) |
| `20261001000000_rls_performance_and_indexes.sql` | Faster RLS policies (`(select auth.uid())`), fixed `search_path` on the trigger function, foreign-key indexes |
| `20261006000000_per_user_row_ids.sql` | Primary keys `(user_id, id)` instead of `id`, and references that include `user_id`, so ids are unique per user |
| `20261006000001_align_hand_made_schema.sql` | Brings the dashboard-made tables in line: drops the catch-all policies and duplicate indexes, adds the value checks, defaults and settings cascade, keeps setting keys unique per user |
| `20261006000002_transaction_lines_and_edit_times.sql` | Moves transaction lines into `transactions.lines` (jsonb), adds `modified_at` to every table, and a trigger that refuses an update older than the stored row |
| `20261007000000_cloud_subscriptions.sql` | Paid backup: `cloud_subscriptions`, `has_cloud_access()` / `cloud_access()`, uploads only with access (reads stay open), complimentary access for every existing user |
| `20261016000000_shared_ledgers.sql` | Shared ledgers: `ledgers` and `ledger_members`, `ledger_id` on every synced table with keys `(ledger_id, id)`, row-level security by membership (writing needs the owner or editor role and the owner's cloud access), `user_id` as who last wrote a row, `own_ledger()`, a ledger for every existing user |
| `20261017000000_ledger_invites.sql` | Invites: `ledger_invites` (single-use codes, stored hashed), and the functions the app shares ledgers with: `create_invite`, `invite_details`, `accept_invite`, `list_ledgers`, `list_members`, `remove_member`, `rename_ledger`, `ledger_access` |
| `20261018000000_sync_control.sql` | The switch that stops sync from the server: `service_control` (`sync_paused`, `min_version_code`, `message`) and `sync_control()`, which the app reads before every sync (see [Stopping sync](#stopping-sync)) |
| `20261018000001_sync_error_reports.sql` | `sync_error_reports`: unexpected sync failures the app sends in, without ledger data; users can only add their own, 50 a day (see [Sync error reports](#sync-error-reports)) |
| `20261019000000_tighten_table_grants.sql` | Table privileges no wider than the policies: nothing for signed-out users, no delete or truncate for signed-in users, only select on `ledgers`, `ledger_members` and `cloud_subscriptions`, nothing on `ledger_invites`; the same defaults for tables added later |

Every migration is idempotent (`create … if not exists`, `drop policy if exists` + `create`) and
leaves existing data alone. That matters because the four core tables were originally created
by hand in the dashboard.

## The app's requests

All HTTP calls are in `app/src/main/java/com/soloit/doubleentry/sync/`:

- **`SupabaseApi.kt`.** Sign-in and token refresh (`/auth/v1/token`), the account's ledger
  (`POST /rest/v1/rpc/own_ledger`), upsert (`POST /rest/v1/<table>?on_conflict=ledger_id,id`), incremental pull
  (`GET /rest/v1/<table>?ledger_id=eq.<ledger>&updated_at=gte.<watermark>&order=updated_at.asc,id.asc`, continuing
  each page after the last row with `or=(updated_at.gt.<t>,and(updated_at.eq.<t>,id.gt.<id>))`), reading refused rows
  by id (`id=in.(…)`) and the bulk soft-delete behind *Delete all* and *Import*
  (`PATCH /rest/v1/<table>?ledger_id=eq.<ledger>&deleted_at=is.null&modified_at=lt.<time>`). Every read and bulk
  update names its ledger: row-level security allows all the user's ledgers.
- **`RealtimeClient.kt`.** Subscribes to `postgres_changes` on the five synced tables, filtered to the ledger
  (`ledger_id=eq.<ledger>`).
- **`SyncRows.kt`.** The JSON shape of each table. Keep it in step with the migrations.

Sync rules the schema relies on:

- **`modified_at`** is when the edit was made (the device's clock, corrected to the server's). It
  decides conflicts, last edit wins: a pull keeps the local row unless the server's edit is later,
  and the `keep_newer_edit` trigger refuses an update older than the stored row. A refused row is
  left out of the upsert response, and the client then reads the stored row instead.
- **`updated_at`** is always set by the server trigger when a row arrives. Clients only pull with it.
- **`transactions.lines`** holds a transaction's lines (`id`, `account_id`, `amount`, `entry_type`),
  so a transaction and its lines are always written, pulled and resolved together.
  `transaction_entries` is no longer used (see `queries/drop_transaction_entries.sql`).
- **`deleted_at`** is a tombstone. Rows are never hard-deleted by the app, so no delete policy exists.
- **Rows belong to ledgers.** `ledger_id` is part of every primary and foreign key: ids only need to be
  unique per ledger. Reading needs membership of the ledger (`ledger_members`); writing needs the
  owner or editor role.
- **Uploads need the owner's cloud access.** Insert and update policies require the ledger's owner to
  have a `cloud_subscriptions` row whose `expires_at` is null or in the future, so the owner's
  subscription covers every member. Without it PostgREST answers 403 (`42501`); the app then keeps
  changes on the device. Select policies don't check it, so a ledger whose subscription ended can
  still be restored on a new phone.
- **`user_id`** is who last wrote the row, stamped by a trigger from `auth.uid()` (clients don't send
  it). It becomes null when that user deletes their account; the row stays in the ledger.
- **PostgREST `max_rows`** must be at least 1000; the app pages 1000 rows at a time.

## First-time setup against your existing project

```bash
supabase login
supabase link --project-ref <project-ref>
supabase migration list            # what the project has recorded as applied
supabase db push --include-all     # applies anything not yet recorded
```

`--include-all` is needed because the core migration is dated before the two invoice/settings
migrations, which may already be recorded. Re-running SQL that was originally typed into the
dashboard is safe. Policies and indexes that were made by hand in the dashboard are removed by
`20261006000001_align_hand_made_schema.sql`; add any new hand-made ones to that list.

## Making a change

```bash
supabase migration new add_something      # creates supabase/migrations/<timestamp>_add_something.sql
# write the SQL, then:
supabase db push                          # or merge to main and let GitHub Actions apply it
```

If you add or rename a column, update the matching row class in `SyncRows.kt` and the Room
entity in `data/local/Entities.kt` (bump the Room database version with a migration).

## Deploy from GitHub

`.github/workflows/supabase.yml` always checks that every migration applies cleanly to a fresh
Supabase stack and can be re-run. Deploying runs in the `production` environment
(*Settings → Environments*), which only the protected `main` branch may use and which waits for
approval. Its secrets live in that environment, not in the repository, so no other workflow or
branch can read them:

| Kind | Where | Name | Value |
|---|---|---|---|
| Variable | repository | `SUPABASE_PROJECT_ID` | the project ref |
| Secret | `production` | `SUPABASE_ACCESS_TOKEN` | a personal access token from https://supabase.com/dashboard/account/tokens |
| Secret | `production` | `SUPABASE_DB_PASSWORD` | optional, the project's database password |

Pushes to `main` then apply pending migrations once approved. Pull requests don't get the
secrets; preview pending migrations locally with `supabase db push --dry-run --include-all`.

## Local development

```bash
supabase start          # local stack on http://127.0.0.1:54321
```

For the emulator, point the debug build at your machine in `local.properties`:

```properties
supabase.url=http://10.0.2.2:54321
supabase.anonKey=<anon key printed by supabase start>
```

Debug builds allow plain HTTP to `10.0.2.2`/`localhost` (see `app/src/debug/`). Create a test
user with *Authentication → Add user* in the local Studio (http://127.0.0.1:54323) and sign in with
email, or export `SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID=<web client id>` before `supabase start`
to test Sign in with Google locally.

## Accounts and paid backup

Accounts are created with **Sign in with Google**: the app gets a Google ID token from Android's
Credential Manager and exchanges it for a session (`POST /auth/v1/token?grant_type=id_token` with
`provider: google`, the token and the raw nonce whose SHA-256 the token carries). The first sign-in
creates the account. A Google sign-in with the same verified email as an existing account is linked
to that account, so older email accounts keep their ledger.

Email and password remain only for accounts made before that (and for reviewer accounts); new email
sign-ups are off. A forgotten password is reset with a 6-digit code typed into the app
(`/auth/v1/recover`, `/auth/v1/verify`, `PUT /auth/v1/user`), so the email must contain the code.

In the hosted project, set this once in the dashboard (`config.toml` only covers the local stack):

1. *Authentication → Sign In / Providers → Google*: enable, and put the OAuth **web** client id in
   *Client IDs* (comma-separated if there are several). The client secret is only needed for browser
   sign-in, which the app doesn't use. Leave *Skip nonce check* off.
2. *Authentication → Sign In / Providers*: *Allow new users to sign up* on;
   under *Email*, *Allow new users to sign up* off; email OTP length 6, minimum password length 8.
3. *Authentication → Emails → Templates*: paste `templates/recovery.html` into *Reset password*.
   The built-in email sender is enough for the few password resets of older accounts.

Creating the OAuth clients in Google Cloud is described in the main README
([Sign in with Google](../README.md#sign-in-with-google)).

Uploading needs a `cloud_subscriptions` row (see the sync rules above). Rows are written only with
the service role:

- **Google Play subscriptions** by the Edge Functions below.
- **Complimentary access** (no end date) by hand, e.g. for family or reviewers:
  ```sql
  insert into public.cloud_subscriptions (user_id, source, state)
  select id, 'complimentary', 'active' from auth.users where email = 'someone@example.com'
  on conflict (user_id) do update set source = 'complimentary', state = 'active', expires_at = null;
  ```
  The migration gave every account that existed at the time complimentary access.

### Edge Functions

| Function | Called by | What it does |
|---|---|---|
| `play-verify` | the app, after a purchase and when it finds a purchase the server may not know | Reads the purchase from the Google Play Developer API, checks it was bought for this account (`obfuscatedExternalAccountId` = user id) and stores it. The app acknowledges the purchase only after this succeeds |
| `play-notifications` | Google Play Real-time Developer Notifications (Pub/Sub push) | Re-reads the subscription on every renewal, cancellation, hold, expiry or refund and updates `expires_at` |
| `delete-account` | the app (*Cloud backup & sync → Delete account*) | Deletes the user; the ledgers they own go with it (`on delete cascade`), their edits in other ledgers stay |

Secrets (*Edge Functions → Secrets*, or `supabase secrets set NAME=value`):

| Secret | Value |
|---|---|
| `GOOGLE_PLAY_SERVICE_ACCOUNT` | The JSON key of a Google Cloud service account invited in Play Console → *Users and permissions* with *View financial data* and *Manage orders and subscriptions* for the app |
| `PLAY_NOTIFICATIONS_SECRET` | A long random string, also put in the Pub/Sub push URL: `https://<project-ref>.supabase.co/functions/v1/play-notifications?secret=<it>` |
| `PLAY_PACKAGE_NAME` | Optional; defaults to `com.soloit.doubleentry` |

Deploy with `supabase functions deploy` (the GitHub workflow does this on push to `main`).
`play-notifications` is deployed without JWT verification (`config.toml`), because Pub/Sub can't
send a Supabase token; it checks the secret instead. The Play Console side is in
[`docs/google-play.md`](../docs/google-play.md#paid-cloud-backup).

## Users and ledgers

Several users share one project, and a ledger can have several members:

- **`ledgers`**: one row per ledger. `owner_id` is the user whose subscription pays for uploads;
  the ledger is deleted with its owner.
- **`ledger_members`**: `(ledger_id, user_id, role)` with role `owner`, `editor` or `viewer`. The
  owner is always a member (a trigger adds them), and each ledger has exactly one.
- **`own_ledger()`** returns the signed-in user's own ledger, the oldest they own, and creates it the
  first time. It lives in the device's main database. The migration gave every existing user one,
  holding the rows they had.
- **Invites.** The owner calls `create_invite(ledger, role)` and sends the code it returns
  (`XXXX-XXXX-XXXX-XXXX`, Crockford base 32: case, dashes, `O`/`I`/`L` don't matter). It works once,
  for 7 days; only its SHA-256 is stored. The person invited sees what it's for with
  `invite_details(code)` and joins with `accept_invite(code)`. The app sends editor invites.
- **Members.** `list_ledgers()` and `list_members(ledger)` list them; `remove_member(ledger, user)`
  lets the owner remove anyone else and a member leave (the owner can't); `rename_ledger` is the
  owner's. `ledger_access(ledger)` tells the app its role and whether it may upload now, which is
  how a device notices it was removed. The app keeps a shared ledger in a database of its own,
  removed again when the user leaves it, is removed, or signs out.

Refusals raise `P0001` with a machine-readable message (`invite_not_found`, `invite_used`,
`invite_expired`, `not_owner`, `owner_cannot_leave`, `not_member`), which PostgREST returns as HTTP
400; the app turns them into sentences.

Some ids are derived only from content (default-plan accounts from the account number, settings
rows from the key, depreciation and recurring postings from the asset/rule and month), so every
ledger has rows with the same ids. That is why each table is keyed by `(ledger_id, id)` and every
reference includes `ledger_id`: one ledger can never collide with, or point at, another's rows.
It also lets the app copy a ledger to another account with its ids unchanged. For members it means
that two phones posting the same recurring transaction converge on one row.

Builds of the app from before `20261006000000_per_user_row_ids.sql` upsert with `on_conflict=id`,
which the server rejects after that migration; update the app on every device when deploying it.
The same goes for `20261006000002_transaction_lines_and_edit_times.sql` (older builds don't send
`modified_at` or `lines`) and `20261016000000_shared_ledgers.sql` (older builds upsert with
`on_conflict=user_id,id` and don't send `ledger_id`): their uploads fail.

## Stopping sync

If a release turns out to damage synced data, or the backend needs maintenance, stop sync from the
SQL editor instead of waiting for a fixed build to pass review. Every app reads the switch before
each sync and before signing in; changes wait on the devices and upload once sync runs again.

```sql
-- Every app: sync stops, and the app says it's paused for maintenance.
update public.service_control set sync_paused = true, message = null;

-- Only builds older than versionCode 15 (e.g. one with a sync bug): they ask the user to update.
update public.service_control set min_version_code = 15,
  message = 'This version has a problem with cloud sync. Update DoubleEntry to keep syncing; your changes are safe on this device.';

-- Back to normal.
update public.service_control set sync_paused = false, min_version_code = 0, message = null;
```

`message` replaces the app's own text for either case. When the switch can't be read (offline,
or a project without the migration), sync isn't stopped.

## Sync error reports

The app sends unexpected sync failures to `sync_error_reports`: the kind of error, its technical
message with row content removed, where in the code it happened, the app version and how many
changes were waiting. No ledger data, and nothing for expected states (offline, an ended session,
no subscription). Each failure is sent at most every 6 hours per device, and the server keeps at
most 50 a day per user and deletes reports after 90 days (a `pg_cron` job). Check
`queries/sync_errors.sql` after every release.

## Testing

GitHub Actions runs all of these on every pull request; this is how to run them locally.

| What | Where | Run with |
|---|---|---|
| Security rules: what a user can't do with their own token (read others' ledgers, grant themselves access, promote themselves, …), and guards for new tables and functions | `tests/security_test.sql` | `supabase test db` |
| The app's sync code against the real stack: conflicts, paging, invites, Realtime, account deletion. The same tests run against the app's in-memory fake, which keeps the fake honest | `app/src/test/.../sync/SupabaseContractTest.kt` | see below |
| Migrations re-run over existing data, and new migrations applied over the previous schema's data | `fixtures/sample_ledgers.sql`, `fixtures/upgrade_check.sql` | see `.github/workflows/supabase.yml` |
| Subscription rules of the Edge Functions | `functions/_shared/subscriptions_test.ts` | `deno test` in `functions/` |

Contract tests against a local stack:

```bash
supabase start
eval "$(supabase status -o env | grep -E '^(API_URL|ANON_KEY|SERVICE_ROLE_KEY)=')"
SUPABASE_TEST_URL=$API_URL SUPABASE_TEST_ANON_KEY=$ANON_KEY SUPABASE_TEST_SERVICE_KEY=$SERVICE_ROLE_KEY \
  ./gradlew testDebugUnitTest --tests '*SupabaseContractTest*'
```

Without the variables only the fake half runs. **When a migration changes the schema, update
`fixtures/sample_ledgers.sql` in the same pull request** so it fits the new schema (cover new
tables and columns): the next change's upgrade check starts from it.

## Queries

- **`queries/health_check.sql`.** Row counts, unbalanced transactions, orphaned lines, duplicate account numbers.
- **`queries/sync_errors.sql`.** Recent sync error reports by kind and app version.
- **`queries/trial_balance.sql`.** Balance of every account of a user's ledger as of a date.
- **`queries/purge_tombstones.sql`.** Removes old soft-deleted rows. Read the warning in the file first.
- **`queries/drop_transaction_entries.sql`.** Drops the old lines table once every device runs a current build.
