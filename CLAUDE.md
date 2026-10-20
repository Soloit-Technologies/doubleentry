# Claude instructions

## No warnings or errors on main

`main` has no lint warnings or errors and no Kotlin compiler warnings, and it has to stay that way.
The build enforces it: lint runs with `warningsAsErrors` (app/build.gradle.kts), every Kotlin
compilation runs with `allWarningsAsErrors` (build.gradle.kts), and the *Android* workflow's `build`
job, which runs lint, is a required check on `main`.

- Before you call a change done, run `./gradlew lint :shared:jvmTest testDebugUnitTest :desktopApp:test assembleDebug`
  and make sure it passes with no warnings.
- Fix the cause of a warning. Suppress it only when the warning is wrong for that spot, as close to
  the spot as possible (`@Suppress("…")`, `tools:ignore="…"`), and add a comment saying why.
- Don't add a lint baseline, don't loosen `warningsAsErrors` or `allWarningsAsErrors`, and don't
  disable a lint check project-wide to get a change through.

## UI changes must work with large fonts

Users can raise the system font size (Android lets them go up to 200%), and every screen has to stay
usable when they do. Before you call a UI change done, check it at a large font scale as well as the
default one.

When you write Compose UI:

- Size text in `sp` and take styles from `MaterialTheme.typography`. Never convert text sizes to
  `dp`, and never cancel out the user's font scale with a custom `LocalDensity`.
- Don't give containers that hold text a fixed `height`. Use `heightIn(min = ...)` so they can grow.
  Fixed sizes are fine for icons and charts.
- When a row holds a label and a value, such as an account name and an amount, the label should take
  `Modifier.weight(1f)` and wrap or ellipsize. The amount must always be shown in full, so never
  truncate it.
- When you set `maxLines = 1`, also set `overflow = TextOverflow.Ellipsis`, and only do it where losing
  the end of the text is acceptable.
- Put screens whose content can outgrow the viewport in a scrolling container (`LazyColumn` or
  `verticalScroll`) so nothing is pushed off-screen.
- Rows of buttons or chips side by side should wrap (for example with `FlowRow`) or stack vertically
  instead of squeezing their labels.

To check a change on a device or emulator:

```sh
adb shell settings put system font_scale 2.0   # largest setting
adb shell settings put system font_scale 1.0   # restore the default
```

Open each screen you changed and look for clipped or overlapping text, labels squeezed to a few
characters, amounts that are cut off, and buttons pushed out of reach. If you can't run the app,
say so in your summary rather than claiming the change was verified.
