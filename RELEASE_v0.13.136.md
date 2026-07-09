# Hermes Agent Fork v0.13.136

Device sandbox reliability and local-model polish after thorough emulator validation of v0.13.135.

## Fixes

- Deploy Alpine / One-click deploy no longer force China package mirrors (China remains opt-in).
- Device sandbox status no longer shows AI shell enabled when none are installed.
- Richer sandbox action status messages (exit code, distro, install stderr snippet).
- Gemma 3 1B LiteRT inference defaults when that model is preferred.
- scripts/provision-local-model.ps1 for run-as or external model provisioning.

## Validation (emulator HermesX86Api35)

- v0.13.135 UI smoke: Chat, drawer, Device sandbox card.
- Gemma 4 E2B LiteRT-LM instrumented tests: 2/2 passed.
- AI shell status honesty verified (disabled at 0 sandboxes).
- Unit tests for device package after fixes.

## Release

- Version: 0.13.136 / versionCode 143690
- Package: com.mobilefork.hermesagent
