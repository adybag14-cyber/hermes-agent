# Hermes Agent Fork v0.13.115

This release ships the current Android capability expansion for signal-aware
agent workflows, localization polish, and non-Snapdragon device readiness.

## Android

- Adds Kai-style passive self-check diagnostics covering heartbeat status, tool
  sandbox readiness, card discoverability, local inference readiness, and
  workflow routing.
- Expands Wi-Fi, Bluetooth, AM/FM radio, RF coexistence, gyroscope, and
  accelerometer diagnostics into agent-readable evidence and expandable graph
  cards.
- Improves MediaTek and non-Adreno compatibility signals, including backend risk
  and LiteRT artifact recommendations.
- Improves Chinese and other localized UI coverage for custom endpoint settings,
  onboarding, portal, settings, and chat surfaces.
- Stabilizes the chat composer layout so typing into the text box does not jump
  across compact phones, tablets, and large screens.
- Publishes signed universal APK and AAB artifacts for the Hermes Agent Fork
  Android package `com.mobilefork.hermesagent`.
