# Releasing cloud sync

Automated tests cover the sync logic, the server's rules and upgrades (see
[`supabase/README.md`](../supabase/README.md#testing)). They can't cover real phones, real
networks or Google Play. This checklist covers those, plus the rollout and what to watch afterwards.
The first release with cloud sync gets all of it. Later releases that touch sync, the schema or
billing need the device test and the rollout.

## Before the release

- [ ] All workflows are green on the release commit: *Android*, *Sync contract* and *Supabase*.
- [ ] The Supabase project has backups you have restored at least once: daily backups (Pro plan)
      or point-in-time recovery (*Database → Backups*). Before `db push` applies new migrations,
      also take a dump yourself: `supabase db dump --data-only -f backup-$(date +%F).sql`.
- [ ] The switch that stops sync works on the real project: with a test device signed in, run
      `update public.service_control set sync_paused = true;` and check that the app shows
      *Cloud sync is paused for maintenance*. Make a change, set it back to `false`, and check
      that the change uploads. See [Stopping sync](../supabase/README.md#stopping-sync).
- [ ] The privacy policy and the Data safety answers include sync error reports (see
      [`google-play.md`](google-play.md#what-changes-in-app-content)).

## Device test

Use two phones (A and B) signed in to the same account, a second account, and a license tester
for purchases (see [Testing purchases](google-play.md#testing-purchases)). Install the build
from the internal testing track. After the test, `queries/health_check.sql` should show no
problems, and `queries/sync_errors.sql` should show nothing you didn't cause on purpose.

- [ ] **First sign-in.** On A, sign in with Google and subscribe. Add accounts and a few
      transactions. *Cloud sync* says *Everything is backed up*.
- [ ] **Second phone.** Sign in on B: everything appears. With both apps open, an edit on one
      phone shows up on the other within seconds (Realtime).
- [ ] **Offline.** Put A in airplane mode and edit three items. The banner says *Offline*.
      Reconnect: the edits upload and show up on B.
- [ ] **Conflict.** With both phones offline, edit the same transaction on each, B last.
      Reconnect A, then B. Both end with B's version, and A shows that one change was replaced.
- [ ] **Interrupted sync.** Import a large backup on A, and force-stop the app while it says
      *Syncing…*. Reopen it: sync finishes, and A, B and the cloud have the same number of
      transactions (no duplicates).
- [ ] **Replace the ledger.** With B open, run *Delete all* on A: B empties. Import a backup on A:
      B gets the imported ledger.
- [ ] **Wrong clock.** Set B's clock an hour ahead (automatic time off) and edit something there,
      then edit the same item on A. A's edit still wins.
- [ ] **Accounts.** Sign out and back in. Sign in to the second account and answer the question.
      Reset a device with unsynced changes and check that it warns first.
- [ ] **Shared ledger.** Invite the second account, edit from both sides, then remove the member:
      their phone says access was removed.
- [ ] **Subscription.** Cancel the test subscription in Google Play and wait for it to expire
      (minutes for license testers). Uploads stop with *Cloud backup isn't active*, and nothing
      on the phone is lost. Subscribe again: the waiting changes upload.
- [ ] **Outdated build.** Set `min_version_code` above this build's versionCode. The app asks for
      the update and opens Google Play. Set it back to `0`.
- [ ] **Delete account.** The cloud copy goes, and the data on the phone stays.

## Rollout

1. **Closed testing**, with a handful of real users on their own phones, for one to two weeks.
   Check `queries/sync_errors.sql` every few days.
2. **Production, staged:** 10 % → 50 % → 100 % (*Release → Production → Staged rollout*), at
   least two days per step. Before each step, check `queries/sync_errors.sql` (grouped by app
   version) and *Android vitals* for crashes.
3. **If something goes wrong:** halt the rollout in the Play Console. If the bug can damage
   synced data, also stop sync for that build right away by setting `min_version_code` to the
   fixed build's versionCode. Affected phones keep their changes until they update.

## After the release

- [ ] `queries/sync_errors.sql` daily for the first week, then weekly.
- [ ] `queries/health_check.sql` after the first week: no unbalanced transactions, no lines
      pointing at missing accounts.
