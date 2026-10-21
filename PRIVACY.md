# DoubleEntry privacy policy

*Effective 17 October 2026*

DoubleEntry is a personal bookkeeping app for Android published by Soloit Technologies AB, Sweden
("we"). This policy explains what happens to your information when you use the app. We are the
controller of the personal data described here.

## In short

Without cloud backup, everything you enter stays on your device: no account, no sign-up, and
nothing is sent to us. The app has no advertising, analytics or crash reporting.

If you turn on the optional cloud backup & sync, you sign in with your Google account and your
ledger is stored on our server in the EU so it can be backed up and synced between your devices.
We use it only to provide that service, and we never sell or share it for advertising.

## Information stored on your device

The app stores the bookkeeping data you enter on your device: accounts, transactions, recurring
transactions, assets, invoices, reconciliations and your settings. You can delete your ledger at
any time with *Settings → Data management → Delete all*, and everything the app stores by
uninstalling it or clearing its storage in Android's app settings.

## Cloud backup & sync

Cloud backup is optional and off until you sign in under *Settings → Cloud backup & sync*.
Downloading a backup is free; uploading needs a cloud backup subscription.

**What we store**

- **Your account:** your Google account's email address and the profile details Google shares at
  sign-in (name and profile picture), and when you signed up and last signed in.
- **Your ledger:** the bookkeeping data listed above and the settings that go with it, and for
  each row which member last changed it and when.
- **Shared ledgers:** if you share a ledger or join someone else's, the members and their roles.
  Invite codes are stored only as a hash and stop working after 7 days.
- **Your subscription:** whether your cloud backup subscription is active, its plan, when it ends
  and Google Play's purchase token for it. We never see your payment details.
- **Sync error reports:** when a sync fails unexpectedly, the app sends the kind of error, a
  technical message without any ledger content, the app version and how many changes were
  waiting. These are deleted after 90 days.

**Who can see it**

- You, on the devices where you're signed in.
- The other members of a ledger you share. They see that ledger's contents and your email
  address, and editors can change it. Your other ledgers stay private.
- We don't look at your ledger, except when you ask us to for support or when the law requires it.

**Why, and on what basis.** We process this data to provide the backup and sync you asked for
(performance of a contract, GDPR art. 6(1)(b)). We use the error reports to find and fix sync
problems (our legitimate interest, art. 6(1)(f)).

**Where.** Our server is run by [Supabase](https://supabase.com/privacy) and is hosted in the EU
(AWS, Ireland). Sign-in is handled by Google, and subscriptions and payments by Google Play, under
[Google's privacy policy](https://policies.google.com/privacy). Supabase and Google keep
short-lived technical logs, such as IP addresses, to run and secure their services. Data is
encrypted in transit (HTTPS).

**How long.** Until you delete your account; sync error reports at most 90 days. If your
subscription ends, what's already backed up stays so you can still restore it, until you delete
your account.

## Deleting your account

- **In the app:** *Settings → Cloud backup & sync → Delete account*.
- **Without the app:** email [privacy@soloit.se](mailto:privacy@soloit.se) from the address of
  the Google account you signed in with, asking us to delete your DoubleEntry account. We'll
  confirm within 30 days.

Deleting your account removes your account, the ledgers you own with everything in them, your
memberships of other people's ledgers, your subscription record and your error reports. Changes
you made in someone else's shared ledger stay in that ledger, no longer linked to you. Deleting
the account doesn't cancel a Google Play subscription; cancel it in Google Play first. The data on
your device stays until you delete it there.

## When data leaves your device in other ways

Data also leaves your device in these cases, and only when you choose to:

- **Exports.** When you export a JSON backup or a CSV file, the app writes it to the location you
  pick (for example your device storage or a cloud drive app). What happens to the file after that
  is governed by wherever you saved or shared it.
- **Imports.** When you import a backup or a bank statement, the app reads only the file you pick.
- **Support links.** *Settings → Support* opens GitHub in your browser. A bug report is
  prefilled with the app version, Android version and device model, which you can review or
  remove before submitting. Anything you post on GitHub is public and covered by
  [GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
- **Android device backup.** If Android backup is turned on for your device, Android may include
  the app's data in your device backup, for example in your Google account. This is managed by
  Android and your backup settings, not by us. Sign-in and sync settings are excluded.

## Permissions

- **Internet / network state.** Used only for cloud backup & sync, sign-in and subscriptions.
  Without cloud backup the app doesn't use the network; links open in your browser.
- **Notifications.** Used only for the optional daily invoice reminder, which you switch on
  yourself. Reminders are created on the device.
- **Biometrics / screen lock.** Used only for the optional app lock. Android checks your
  fingerprint, face or screen lock; the app never receives biometric data.

## Your rights

You can ask us for a copy of your data, to correct it, to delete it, to restrict or object to how
we use it, or to receive it in a portable format. Most of this you can do yourself: your ledger
can be exported as JSON or CSV in the app, and the account deleted as described above. For
anything else, email [privacy@soloit.se](mailto:privacy@soloit.se). If you think we handle your
data wrongly, you can complain to the Swedish Authority for Privacy Protection
([IMY](https://www.imy.se)) or the data protection authority where you live.

## Children

DoubleEntry is meant for adults managing their own finances and isn't directed at children.

## Changes to this policy

If we change this policy, we'll publish the new version at this address and update the date at
the top. A change in how we use data already stored in the cloud is published here before it takes
effect.

## Contact

Soloit Technologies AB, [privacy@soloit.se](mailto:privacy@soloit.se). Questions about the app
can also go to the [issue tracker](https://github.com/Soloit-Technologies/doubleentry/issues);
issues are public, so don't include personal or financial details there.
