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
Play app is offline-only and needs no login.

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
4. **Testing requirement.** Personal developer accounts created after November 2023 must run a
   closed test with at least 12 testers for 14 days before production access. Organization
   accounts are exempt.

## App content answers

*Policy and programs → App content*. The answers below fit release builds as they are today.
Revisit them if cloud sync is shipped.

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
