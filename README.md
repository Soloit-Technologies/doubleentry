# DoubleEntry for Android

Personal double-entry bookkeeping for Android, written in Kotlin with Jetpack Compose and
Material 3. A native rewrite of the .NET MAUI app: same features, same data model, same
Supabase backend.

- **Accounts.** Net worth at a glance, accounts grouped by type, monthly ledger per account with running balance.
  Reconcile an account against a bank statement (optionally posting the difference as an adjustment);
  later changes to the reconciled period show up as drift.
- **Bank statement import.** Import an account's CSV/TSV export from the bank: delimiter, encoding, columns, dates and
  number formats are detected (and can be corrected), rows already in the ledger are skipped, and transactions in the
  period that the file doesn't contain are flagged. New rows are booked like the latest transaction with the same text,
  can be split across several accounts, and a row that pays an open invoice is booked as its payment.
- **Transactions.** Balanced multi-line entries with an account picker, auto-balancing, edit, duplicate and delete
  (with undo). Typing a title suggests earlier transactions and copies their lines. Search covers all months.
- **Recurring transactions.** Weekly, monthly, quarterly or yearly postings (rent, salary, subscriptions) that post
  themselves when the app opens.
- **Reports.** Balance sheet, income statement (EBITDA/EBDA/EBT, spendable net income), cash flow and a cost-trends chart.
  Month, year-to-date or year, with comparison against the previous period or the same period last year.
- **Assets.** Automatic monthly depreciation write-offs, with a repair tool for missing ones.
- **Invoices.** Booking, due-date tracking, payment in full or in instalments, and an optional daily reminder notification.
- **Cloud sync.** Offline-first local database, last-edit-wins sync with Supabase, realtime updates between devices.
- **Backups.** JSON export/import, compatible with backups made by the MAUI app. CSV export of transactions and balances.
- Light/dark theme, optional Material You (wallpaper) colors, optional app lock (screen lock or biometrics),
  a net-worth home-screen widget and launcher shortcuts.

## Build and run

1. Open this folder in Android Studio (*File → Open*). Use the bundled JDK (Android Studio's JBR, Java 21).
2. Let Gradle sync, then run the **app** configuration on a device or emulator (Android 8.0 / API 26+).

From a terminal: `./gradlew assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`,
`./gradlew testDebugUnitTest` runs the unit tests.

Build types:

| Build | Application id | Name on device | Notes |
|---|---|---|---|
| debug | `com.soloit.doubleentry.dev` | DoubleEntry Dev | Installs next to the release app. Cloud sync on. Allows plain HTTP to a local Supabase stack. |
| release | `com.soloit.doubleentry` | DoubleEntry | The Google Play build: minified with R8, cloud sync and the Supabase settings left out. |

To sign release builds, create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=/path/to/upload-key.jks
storePassword=…
keyAlias=…
keyPassword=…
```

CI signs with the `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD` environment variables instead. Publishing on Google Play (upload key, store
listing, privacy policy, Data safety answers) is described in [`docs/google-play.md`](docs/google-play.md).

## Configure Supabase

Add your project settings to `local.properties` (git-ignored; Android Studio created it with `sdk.dir`):

```properties
supabase.url=https://<project-ref>.supabase.co
supabase.anonKey=<anon / publishable key from Project Settings → API>
```

Without them the app builds and works offline; the cloud sync settings are simply hidden.
Cloud sync is on in debug builds and off in release builds; `cloudSync.enabled=true|false` (or
`CLOUD_SYNC_ENABLED`) overrides that for both. A build without cloud sync doesn't embed the
Supabase settings. CI reads the same values from the `SUPABASE_URL` / `SUPABASE_ANON_KEY`
environment variables.

Everything on the server (tables, row-level security, triggers, realtime) lives in
[`supabase/`](supabase/README.md) and can be deployed from the repository.

## Moving from the MAUI app

The local database of the MAUI app is not migrated on the device. Bring your data over in one of two ways:

- **Cloud sync (recommended).** Install the new app and tap *Sign in to restore from the cloud* on
  the welcome screen. Both apps can run against the same Supabase project during the transition.
  Deterministic ids (default chart of accounts, depreciation postings, settings rows) are
  byte-for-byte identical to the .NET implementation, so the two apps converge instead of
  duplicating rows.
- **JSON backup.** In the MAUI app use *Export to JSON*, then *Settings → Data management →
  Import from JSON* here. Schema versions 2–7 are supported.

## Behavior changes compared with the MAUI app

Everything else follows the original logic.

- **Android-only data** (recurring rules, reconciliations, invoice instalments) is stored as synced
  `user_settings` rows (`Recurring.<id>`, `Reconciliation.<accountId>`, `Invoice.Payments.<invoiceId>`), so it
  needs no backend change and the MAUI app simply ignores it. Recurring postings use ids derived from
  (rule, date), like depreciation, so two devices posting the same occurrence converge on one transaction.
  Backups carry this data in the `android` extension.
- **Instalments.** An invoice row is only marked paid by the payment that settles the rest, so the MAUI app sees
  a partly paid invoice as unpaid.
- **Concurrent edits.** A transaction syncs as one row with its lines, so when two devices edited the same
  transaction before syncing, the later edit is kept whole (never a mix, and never both line sets). The later
  edit wins by when it was made, not by which device came online first, and the server enforces it.

- **Default plan seeding.** Seeded accounts get the oldest possible timestamp, so accounts already
  in the cloud (renamed or deleted elsewhere) win over freshly seeded defaults when you sign in.
- **Tiny assets.** An asset whose monthly write-off rounds to 0.00 is written off at least 0.01 per
  month. The .NET schedule looped forever in that case.
- **Deleting an asset** also removes its legacy depreciation postings (made before deterministic ids).
- **Deleting an account** with transactions is refused by the data layer itself, not only by the UI.
- **Cash flow** only counts asset accounts that match a cash group's prefixes.
- **Transaction lines.** A line with an amount but no account is an error instead of being silently dropped.
- **Balance sheet** hides zero-balance accounts unless *Show empty accounts* is selected.
- **Backups.** Exports include the deferred-asset prefixes, which the MAUI export omitted.
- **Sync** runs when the app comes to the foreground, refreshes an expired session once
  before failing, and the realtime connection reconnects with backoff. A banner above the tab bar
  shows when changes aren't reaching the cloud (offline, paused, failing, session expired) and
  when unsynced edits were replaced by newer ones from another device. Sync stamps are corrected
  for device clock skew, measured from the server's `Date` header. Signing in never silently
  merges two ledgers: it asks when this device and the account each hold one, when changes were
  made while signed out, or when the device holds another account's ledger. *Delete all* and
  *Import* also delete cloud rows this device never pulled, and *Reset this device* uploads first
  and warns about anything it couldn't upload.

## Project structure

```
app/src/main/java/com/soloit/doubleentry/
  domain/          Pure Kotlin: models, money, deterministic ids, depreciation, report calculators
  data/            Room database and repositories (ledger, assets, invoices, settings, backup)
  sync/            Supabase auth + PostgREST client, push/pull engine, realtime, session storage
  system/          Invoice reminders (WorkManager), app lock, home-screen widget, launcher shortcuts
  ui/              Compose screens per feature, shared components and theme
app/src/test/      Unit tests (incl. compatibility tests against values produced by the .NET app) and
                   Robolectric tests of the repositories and of sync between two devices (fake PostgREST)
app/schemas/       Exported Room schema (keep under version control for migrations)
supabase/          Migrations, maintenance queries and CLI config for the backend
```

Amounts are stored as `Long` cents everywhere; dates as ISO `yyyy-MM-dd`. All deletes are soft
(tombstones) so they replicate.

## Continuous integration

- `.github/workflows/android.yml` runs the unit tests and builds a debug APK on every push and pull request.
- `.github/workflows/release.yml` builds the signed Play bundle when a `v*` tag is pushed and can upload it
  to the internal testing track. Setup is in [`docs/google-play.md`](docs/google-play.md).
- `.github/workflows/supabase.yml` validates the migrations on a throwaway Supabase stack, previews
  them on pull requests and applies them to your project on push. Setup is in
  [`supabase/README.md`](supabase/README.md#deploy-from-github).
