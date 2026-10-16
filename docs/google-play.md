# Publishing on Google Play

What the repository already provides, and the one-time steps that happen in the Play Console.

## In the repository

| What | Where |
|---|---|
| Signed release bundle | `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`, or push a `v*` tag ([`release.yml`](../.github/workflows/release.yml)) |
| R8 mapping file (readable crash stack traces) | `app/build/outputs/mapping/release/mapping.txt`, uploaded with the bundle by the workflow |
| Store listing text, icon, feature graphic | [`fastlane/metadata/android/en-US/`](../fastlane/metadata/android/en-US) |
| Privacy policy and support | The public repository [Soloit-Technologies/doubleentry-app](https://github.com/Soloit-Technologies/doubleentry-app): `PRIVACY.md`, and issues as the support channel (*Settings → Support* links there) |

Release builds leave out cloud sync and the Supabase settings (see README → *Build types*), so the
Play app is offline-only and needs no login. Shipping paid cloud backup is described in
[Paid cloud backup](#paid-cloud-backup) below.

## Developer account

The app is published by the company, so the Play Console account is an **organization** account,
not a personal one.

1. **D-U-N-S number.** The legal name and address in the Dun & Bradstreet record must match what
   you enter in Play exactly; verification fails otherwise.
2. **Payments profile.** A Google payments profile of type *Business* with the company's legal name,
   address and VAT number. Subscription payouts go here.
3. **Sign up** at <https://play.google.com/console/signup>, choose *Organization*, and enter the
   D-U-N-S number, the payments profile, a public developer name, a website, and a contact email and
   phone number on the company's domain. Google verifies the email, the phone and the identity of
   the person signing up; the fee is a one-time $25.
4. **Trader status (EU).** Selling subscriptions makes the company a trader under the Digital
   Services Act: its address, phone and email are shown on the store listing.
5. **Users.** Invite other people and the release service account under *Users and permissions*
   instead of sharing the login.

## One-time setup

1. **Upload key.** Create it once and keep it (and its passwords) somewhere safe:
   ```bash
   keytool -genkeypair -v -keystore upload-key.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```
   Put it in `keystore.properties` for local builds and in the `SIGNING_*` secrets for the workflow.
2. **Create the app** in the Play Console: name *DoubleEntry*, app (not game), free.
   Play App Signing is on by default: Google keeps the app signing key, and you sign uploads with
   the upload key. A lost upload key can be reset through Play support.
3. **First upload by hand.** Upload `app-release.aab` to *Testing → Internal testing*. The package
   name `com.soloit.doubleentry` is fixed from then on. Later uploads can come from the workflow
   (secret `PLAY_SERVICE_ACCOUNT_JSON`: a Google Cloud service account invited in
   *Users and permissions* with release rights for this app).
4. **Production.** Organization accounts don't need the 12-tester, 14-day closed test that new
   personal accounts do; a release can be promoted from internal testing straight to production.

## App content answers

*Policy and programs → App content*. The answers below fit release builds without cloud backup.
[Paid cloud backup](#paid-cloud-backup) lists what changes when it ships.

| Section | Answer |
|---|---|
| Privacy policy | `https://github.com/Soloit-Technologies/doubleentry-app/blob/main/PRIVACY.md` |
| App access | All functionality is available without special access |
| Ads | No ads |
| Content rating | IARC questionnaire: category *Utility, productivity, communication or other*; answer *No* throughout. Expect *Everyone / PEGI 3* |
| Target audience | 18 and over (keeps the app out of the Families program) |
| News app | No |
| Health apps | No health features |
| Financial features | Answer for personal finance tracking only. The app offers no banking, payments, loans, trading or crypto, and it holds no money |
| Government app | No |
| Data safety | See below |

### Data safety

- **Does your app collect or share any of the required user data types?** No.
  Data processed only on the device doesn't count as collected, and the app sends nothing off it.
  User-initiated exports to a location the user picks don't count either.
- That answer skips the encryption and deletion questions. The listing then shows
  *No data collected* and *No data shared with third parties*.

## Store listing

- **App name:** `title.txt`. **Short and full description:** `short_description.txt`,
  `full_description.txt`.
- **App icon (512 × 512)** and **feature graphic (1024 × 500):** `images/`.
- **Phone screenshots:** at least 2, from a release build on a phone, e.g. Accounts, an account
  ledger, a new transaction, the income statement and the cost-trends chart. Put them in
  `images/phoneScreenshots/` if you want them versioned.
- **Category:** Finance. **Website:** `https://github.com/Soloit-Technologies/doubleentry-app`.
  **Contact email:** required by Play and shown publicly, even with GitHub issues as the main
  support channel.

## Every release

1. Raise `versionCode` (always) and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max) and
   paste it as the release notes.
3. Tag and push, e.g. `git tag v2.0.1 && git push origin v2.0.1`, then promote the release in the
   Play Console from internal testing onwards.

## Paid cloud backup

The app is free; cloud backup and sync is a subscription. Everything in the app works without it,
and an account whose subscription ended can still download (restore) what it backed up. How the
server enforces this is in [`supabase/README.md`](../supabase/README.md#accounts-and-paid-backup).

### How a purchase flows

1. The user signs in with Google (*Settings → Cloud backup & sync*), which creates the account the
   first time, then picks a plan.
2. The app starts the Google Play purchase tagged with the Supabase user id
   (`setObfuscatedAccountId`), so a purchase can't be claimed by another account.
3. The app sends the purchase token to the `play-verify` Edge Function, which checks it with
   Google and stores the expiry in `cloud_subscriptions`. Uploading starts.
4. Only then does the app acknowledge the purchase. Google refunds purchases left unacknowledged
   for three days, so nobody pays for a subscription the server never recorded. Unacknowledged
   purchases are retried every time the app starts or returns to the foreground.
5. Renewals, cancellations, payment holds, expiries and refunds reach the server through
   Real-time Developer Notifications (`play-notifications`), whether or not the app is opened.

### One-time setup

1. **Payments profile.** *Settings → Payments profile* must be set up before you can sell anything.
2. **Subscription.** *Monetize with Play → Products → Subscriptions → Create subscription*:
   - Product ID **`cloud_backup`** (fixed in `CloudBilling.PRODUCT_ID`), name *Cloud backup*.
   - Base plan **`yearly`**: auto-renewing, 1 year. Base plan **`monthly`**: auto-renewing, 1 month.
     The app shows the yearly plan first and names plans by their period; the ids are what the
     subscription card shows ("Yearly plan").
   - Prices: set them per country instead of converting automatically, so they look deliberate
     (e.g. 299 kr / 35 kr, €24.99 / €2.99, $24.99 / $2.99).
   - Offer on `yearly` (and optionally `monthly`): *Free trial*, 14 days, eligibility *New customer
     acquisition → Never had this subscription*. The app shows the trial only to users who can
     still get it.
   - Grace period (*Base plan → Edit*): 7 days, then account hold. Uploads keep working during
     the grace period and stop on hold; the app links to Google Play to fix the payment.
3. **Service account for the server.** The Play Developer API needs a Google Cloud service
   account invited in *Users and permissions* with *View financial data* and *Manage orders and
   subscriptions* for this app. The one used for uploading releases can get these permissions
   too. Put its JSON key into the Supabase secret `GOOGLE_PLAY_SERVICE_ACCOUNT`.
4. **Real-time Developer Notifications.**
   - In Google Cloud, create a Pub/Sub topic, e.g. `play-notifications`, and give
     `google-play-developer-notifications@system.gserviceaccount.com` the *Pub/Sub Publisher* role
     on it.
   - Add a **push** subscription to the topic with the endpoint
     `https://<project-ref>.supabase.co/functions/v1/play-notifications?secret=<PLAY_NOTIFICATIONS_SECRET>`.
   - *Monetize with Play → Monetization setup → Real-time developer notifications*: enter the
     topic name (`projects/<gcp-project>/topics/play-notifications`), choose *Subscriptions,
     voided purchases, and all one-time products*, and press *Send test notification*. The
     function answers it with 200 and stores nothing.
5. **Supabase.** Apply the migrations, set the Edge Function secrets, deploy the functions and
   enable the Google provider and the password-reset template (all in `supabase/README.md`).
6. **Sign in with Google.** Create the OAuth clients, including one for the Play app signing key's
   SHA-1 (README → *Sign in with Google*). Without that one, Google sign-in fails in the app
   installed from Play while it works in your own builds.
7. **Turn it on in the build.** Set the repository variables `CLOUD_SYNC_ENABLED=true`,
   `SUPABASE_URL`, `SUPABASE_ANON_KEY` and `GOOGLE_WEB_CLIENT_ID` (see `release.yml`). The build
   fails if backup is on without them.

### Testing purchases

- Billing only works in an app installed from Google Play with the package
  `com.soloit.doubleentry`. The debug build (`.dev`) shows "Subscriptions can't be loaded".
  Build a release with cloud backup on and roll it out to *Internal testing*.
- Add your Google account under *Settings → License testing*. Test purchases are free, renew
  every few minutes (a "yearly" plan renews after 30 minutes) and end after six renewals, which
  exercises renewal, cancellation and expiry notifications quickly.
- Check `cloud_subscriptions` in the Supabase table editor after each step; the function logs are
  under *Edge Functions → Logs*.

### What changes in App content

| Section | New answer |
|---|---|
| App access | Cloud backup needs an account. Reviewers can sign in with any Google account, but would then have to buy. Instead, create an email account for them in the Supabase dashboard (*Authentication → Add user*), give it complimentary access (SQL in `supabase/README.md`), and tell them to use *Sign in with email* |
| Data safety → collection | **Yes**, the app collects data. *Personal info → Email address* and *Name* (from the Google account; account management, required for cloud backup). *Financial info → Other financial info* (the ledger, for app functionality, optional: only with cloud backup). *Financial info → Purchase history* (subscription status, app functionality). None of it is shared with third parties or used for ads or analytics |
| Data safety → security | Encrypted in transit: yes (HTTPS). Users can request deletion: yes |
| Data deletion | In the app: *Cloud backup & sync → Delete account*. Play also requires a web link where people can ask for deletion without the app, e.g. a section of `PRIVACY.md` explaining how to request it (an issue or the contact email) |
| Privacy policy | Update `PRIVACY.md`: what is stored in the cloud (email, ledger, subscription status), where (Supabase, and the region of the project), how long (until the account is deleted), and that payments are handled by Google Play |
| Store listing | Mention the subscription in the full description: free app, optional paid cloud backup, prices in the listing come from Google Play |

