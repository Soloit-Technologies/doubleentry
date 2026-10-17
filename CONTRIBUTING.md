# Contributing

Bug reports, fixes and improvements are welcome. For a larger change, open an issue first so we can
agree on the approach before you put time into it.

## Pull requests

1. Fork the repository and branch off `main`.
2. Build and test: `./gradlew testDebugUnitTest assembleDebug`. For changes to `supabase/`, see
   [`supabase/README.md`](supabase/README.md#testing) for running the local stack and its tests.
3. Open a pull request against `main`. The *Android* and *Sync contract* workflows must pass, and a
   maintainer reviews every change before it is merged.

Merging to `main` doesn't publish anything to Google Play; releases are cut separately from version
tags.

## License

DoubleEntry is licensed under the [Mozilla Public License 2.0](LICENSE). By submitting a pull
request, you agree that your contribution is licensed under the same terms.

The name *DoubleEntry* and its logo identify the official app; forks published elsewhere should use
their own name and application id.
