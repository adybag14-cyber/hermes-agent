# Agent branding and compatibility

The Android application's product name is **Agent**. Launcher, shell, boot screen, floating control, permission explanations, notifications, widgets, settings, and current store-listing copy must use that name and the independent A mark. Do not reuse upstream logos, the earlier H mark, or earlier screenshots as current marketing material.

## Credits are not branding

The application is an independent community fork, not an official or endorsed Nous Research or Teknium product. Keep the existing MIT license, copyright notices, third-party licenses, contributor history, and accurate upstream attribution. Rebranding does not transfer authorship or remove license obligations.

## Deliberately preserved compatibility identifiers

The application ID `com.mobilefork.hermesagent` is retained so a correctly signed future release can upgrade existing installations without replacing their data. Kotlin package/class names, Android component identities, preference keys, existing URI authorities, persisted records, network headers such as `X-Hermes-Session-Id` and `X-Hermes-Tool-Activity`, repository URLs, and historical release evidence are not advertising names. Renaming them requires explicit migration and interoperability testing and is not accomplished by a global text replacement.

The upstream-derived CLI/desktop implementation and published historical artifacts keep their existing technical names. Current Android resources and new presentation must not use those names as the app brand. This change does not rename the hosted repository or publish a new store release.

## Review checks

Verify the installed application label and Android service strings in all six supported languages. Inspect the launcher, splash, app shell and floating button for the A mark. New screenshots must come from the changed application; historical evidence remains historical. Test upgrade compatibility with the correct production signing identity before publishing.
