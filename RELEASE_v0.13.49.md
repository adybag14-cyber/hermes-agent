## F-Droid And Emulator Debugging

This release strengthens Hermes' bundled F-Droid merge-request debugging skill
and makes local Android emulator visual validation easier from Windows.

- Updates the bundled F-Droid MR skill with the current Hermes review workflow:
  noninteractive Git, GitLab pipeline gates, Linux `fdroid rewritemeta`
  formatter handling, release APK verification, and reviewer response rules.
- Adds a PowerShell `wide-screenshot` action for ADB-visible emulators and
  phones so Hermes UI validation can capture laptop-width layouts without
  needing BlueStacks.
- Documents the wide screenshot path in the Android Tasker/Shizuku capability
  map.
