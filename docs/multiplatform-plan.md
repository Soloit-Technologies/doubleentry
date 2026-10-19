# Windows, iOS and macOS

The plan for bringing DoubleEntry to more platforms. Steps 1 and most of 2 are done; see
[Status](#status) for what the code does today and what's left.

## Status

- **Step 1 is done.** `shared` is a Kotlin Multiplatform module (Android and JVM targets) with the
  domain, the Room database, the repositories, sync and the wiring both apps use (`AppCore`).
  Device storage is behind `KeyValueStores` and `TokenStore`; transactions use Room's
  multiplatform API (`useWriterConnection`), and the migration runs on `SQLiteConnection`. The
  former Robolectric tests run as plain JVM tests on the bundled SQLite.
- **Step 2, done:** the JVM target and `desktopApp`. The screens' ViewModels moved to `shared`
  (accounts, ledger, statement import, transactions, reports, invoices, assets, data management),
  and so did the wording of the sign-in questions, sync notices and invoice reminders. The
  desktop app has the multi-pane layout, Fluent components (compose-fluent `v0.1.0`), the
  system's light/dark mode and accent color, a matching Windows 11 title bar, keyboard entry,
  drag-and-drop statement import, sign-in with Google in the system browser (PKCE, loopback
  redirect), DPAPI token storage, toast reminders, and an MSI built by CI.
- **Step 2, not done yet:**
  - Paid backup on the desktop: no web checkout. Signed-in users with a subscription from Google
    Play upload from the desktop too; others are told to subscribe in the Android app.
  - App lock (Windows Hello). Creating invoices, assets and recurring transactions, the cost
    trends report, reconciliation, ledger sharing and invites: the desktop shows and syncs this
    data but they're still done in the Android app.
  - Real Mica behind the content (the title bar takes the app's color instead), AppCDS for
    startup, and signing the installer.
- **Steps 3 and 4 haven't started.** They need a Mac to build and test. `shared` still uses
  `java.time`, `BigDecimal` and OkHttp, and two ViewModels keep the search text in Compose
  snapshot state, which a SwiftUI app would need as a `StateFlow` instead.

## Goals

- **Windows is a priority.** Many users want to do their bookkeeping on a laptop.
- **Each app looks native and is fast.**
- **The sync logic is written once.** The double-entry rules, reports, reconciliation,
  depreciation, statement import, recurring postings, invoices and last-edit-wins sync aren't
  rewritten per platform. If two copies of this logic drifted apart, balances would be wrong.

## Architecture

A shared Kotlin Multiplatform core with a separate UI for each platform:

```
shared/        Kotlin Multiplatform: domain, data (Room), sync, and screen ViewModels
androidApp/    Jetpack Compose (the current app)
desktopApp/    Compose Desktop (Windows; macOS if needed)
iosApp/        SwiftUI (iOS; macOS if needed), uses shared through SKIE
```

- **`shared`** holds what is now `domain/`, `data/` and `sync/`, plus each screen's state and
  actions as multiplatform AndroidX ViewModels. A UI only displays `StateFlow`s and calls
  methods, which keeps logic out of Swift.
- **Platform features** stay in each app, behind interfaces defined in `shared` (for example
  `SecureStore`, `EntitlementChecker`, `Notifier`, `AppLock`):

| Feature | Android | Windows (Compose Desktop) | iOS / macOS (SwiftUI) |
|---|---|---|---|
| Sign-in | Credential Manager, Google | Google OAuth with PKCE in the system browser, loopback redirect | Google Sign-In SDK and **Sign in with Apple** (required by App Store guideline 4.8 once Google login is offered) |
| Paid cloud backup | Play Billing, `play-verify` | Web checkout (Stripe or Paddle) granting the same Supabase entitlement | StoreKit 2, new `appstore-verify` edge function (App Store Server API, notifications v2) |
| App lock | BiometricPrompt | Windows Hello | LocalAuthentication (Face ID / Touch ID) |
| Invoice reminders | WorkManager | Toast notifications | UNUserNotificationCenter |
| Secret storage | Android Keystore | DPAPI / Credential Manager | Keychain |
| File import/export | SAF | FileKit, drag and drop | FileKit, share sheet |
| Widget | AppWidget | — | WidgetKit (Swift), reading the shared database |

Subscriptions are already verified on the server (`supabase/functions/play-verify`,
`_shared/subscriptions.ts`). The entitlement should belong to the user rather than to a store,
so a subscription bought on one platform unlocks the others. Apple allows this under guideline
3.1.3(b), provided the subscription is also sold as an in-app purchase on iOS.

## Windows: Compose Desktop

- **The core runs unchanged.** Compose Desktop runs on the JVM, so `java.time`, `BigDecimal`,
  OkHttp and `java.util.zip` work as they do on Android. Windows is the cheapest platform to add.
- **Desktop-first layout, not stretched phone screens:** multi-pane views (accounts, ledger,
  transaction detail), wide tables for ledgers and bank imports, keyboard entry and shortcuts,
  right-click menus, dragging statement files onto the window, resizable windows.
- **Windows 11 look:** a Fluent component library (for example compose-fluent-ui), the Mica
  background, a native-style title bar, Windows-style context menus and keyboard focus.
- **Speed:** rendering goes through Skia on the GPU and runs fast. Startup (about 1–2 s) is
  reduced with AppCDS.
- **Ruled out: WinUI 3 in C#.** It would reach the Kotlin core through a native Windows DLL with
  a hand-written C interface. Coroutines, Flows and realtime callbacks would need manual glue,
  and Room's support for that target is uncertain.

## iOS and macOS: SwiftUI

- The views are fully native. The shared core is compiled to native code (Kotlin/Native), and
  Room uses a bundled SQLite.
- SKIE turns `Flow` into `AsyncSequence`, `suspend` into `async`, and sealed classes into Swift
  enums.
- The core also compiles for macOS, so the SwiftUI app can target macOS. That's the native
  option for Mac laptops. The Compose Desktop app also runs on macOS and is the cheaper option.
  Which to use depends on how many laptop users are on a Mac.
- Making the core compile for Apple platforms requires replacing the JVM-only APIs:
  - `java.time` → `kotlinx-datetime`
  - `BigDecimal` → a multiplatform money type (a decimal library, or `Long` minor units)
  - OkHttp in `SupabaseApi` and `RealtimeClient` → Ktor client (with websockets)

## Steps

Each step leaves the Android app working and releasable.

1. **Extract `shared`, still Android-only.** Move `domain/`, `data/` and `sync/` into a module;
   no behaviour changes, and the existing unit tests guard the logic.
2. **Windows app.** Add the JVM target to `shared` and build `desktopApp` with the desktop
   layout, desktop sign-in and web checkout. Move screen state into shared ViewModels as each
   screen is built.
3. **Make the core work on Apple platforms.** Add the iOS and macOS targets and replace
   `java.time`, `BigDecimal` and OkHttp.
4. **SwiftUI app** for iOS (and macOS if needed), with Sign in with Apple, StoreKit 2 and
   `appstore-verify`.

## Distribution

- **Windows:** Compose Desktop's `packageMsi` builds an MSI. To avoid SmartScreen warnings, sign it
  with Azure Trusted Signing (around $10 a month) or publish through the Microsoft Store (free
  for individual developers). Hydraulic Conveyor is free for open-source projects and builds an
  MSIX with auto-updates.
- **iOS / macOS:** Apple Developer Program ($99 a year), and a Mac or GitHub Actions macOS runners
  for builds. Releases go through App Store Connect, with TestFlight for betas. The existing
  `fastlane/` setup can handle the uploads.
