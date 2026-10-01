# Supabase backend

Everything the app needs on the server is in this folder, so you can change the backend from
the repository instead of the dashboard.

```
supabase/
  config.toml      Supabase CLI configuration (local development)
  migrations/      Schema: tables, row-level security, triggers, realtime (applied in order)
  queries/         Read-only checks and maintenance SQL to run in the SQL editor
```

| Migration | What it does |
|---|---|
| `20250101000000_ledger_core.sql` | `accounts`, `transactions`, `transaction_entries`, `assets`: tables, indexes, `updated_at` trigger, RLS, realtime |
| `20250601000000_invoices.sql` | `invoices` (unchanged from the MAUI repository) |
| `20250601000001_user_settings.sql` | `user_settings` (unchanged from the MAUI repository) |
| `20261001000000_rls_performance_and_indexes.sql` | Faster RLS policies (`(select auth.uid())`), fixed `search_path` on the trigger function, foreign-key indexes |

Every migration is idempotent (`create … if not exists`, `drop policy if exists` + `create`) and
leaves existing data alone. That matters because the four core tables were originally created
by hand in the dashboard.

## The app's requests

All HTTP calls are in `app/src/main/java/com/soloit/doubleentry/sync/`:

- **`SupabaseApi.kt`.** Sign-in and token refresh (`/auth/v1/token`), upsert
  (`POST /rest/v1/<table>?on_conflict=id`) and incremental pull
  (`GET /rest/v1/<table>?updated_at=gte.<watermark>&order=updated_at.asc,id.asc`).
- **`RealtimeClient.kt`.** Subscribes to `postgres_changes` on all six tables.
- **`SyncRows.kt`.** The JSON shape of each table. Keep it in step with the migrations.

Sync rules the schema relies on:

- **`updated_at`** is always set by the server trigger. Clients pull with it and resolve conflicts
  per row, last write wins.
- **`deleted_at`** is a tombstone. Rows are never hard-deleted by the app, so no delete policy exists.
- **`user_id`** must equal `auth.uid()` for every read and write (RLS).
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
dashboard is safe. If your hand-made policies have other names, the old ones stay alongside
the new ones. Both kinds restrict rows to their owner, so remove the old ones in the dashboard
(*Authentication → Policies*) when convenient.

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
Supabase stack and can be re-run. To also deploy, add these in the repository settings
(*Settings → Secrets and variables → Actions*):

| Kind | Name | Value |
|---|---|---|
| Variable | `SUPABASE_PROJECT_ID` | the project ref |
| Secret | `SUPABASE_ACCESS_TOKEN` | a personal access token from https://supabase.com/dashboard/account/tokens |
| Secret | `SUPABASE_DB_PASSWORD` | the project's database password |

Pull requests then show a dry run of pending migrations, and pushes to `main`/`master` apply them.

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
user with *Authentication → Add user* in the local Studio (http://127.0.0.1:54323).

## Users

This is a single-user app per project: sign-ups are meant to stay disabled and you create your
user in the dashboard. Some ids are derived only from content (default-plan accounts from the
account number, settings rows from the key). Two different users in one project would therefore
collide on those rows; fold the user id into the derivation first if you ever need that.

## Queries

- **`queries/health_check.sql`.** Row counts, unbalanced transactions, orphaned lines, duplicate account numbers.
- **`queries/trial_balance.sql`.** Balance of every account as of a date.
- **`queries/purge_tombstones.sql`.** Removes old soft-deleted rows. Read the warning in the file first.
