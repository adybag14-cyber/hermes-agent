# Agent

**An independent Android app for local AI models and practical device tools.**

Agent is maintained by MobileFork. It is not an official Nous Research product and is not endorsed by Nous Research or Teknium. Upstream authorship and open-source licenses are retained.

## Choose a model first

Open **Settings > Models**. Import an existing GGUF, LiteRT-LM (`.litertlm`), or Android `.task` file with the system file picker, or choose a model already downloaded to the device. Local import needs no account or internet connection. Use the model's **Use & Start** action to select its matching engine and run compatibility and memory checks.

Online downloads and remote providers are optional, separate choices. Response preferences, runtime controls, advanced GGUF settings, and local API sharing are grouped under expandable headings rather than placed before the model picker.

## Android application

The interface supports English, Chinese, Spanish, German, Portuguese, and French, with phone/tablet layouts, conversation history, local model management, and explicit permission and remote-processing controls. The Full edition also exposes Android workflow tools; the Play edition intentionally restricts the available tools.

Read the [Android guide](android/README.md) for installation, model constraints, settings, builds, and tests. Read [the Python runtime guide](android/PYTHON_RUNTIME.md) for verified source-built dependencies and [the compatibility policy](BRANDING.md) for upgrade identifiers and credits.

## Development and validation

The Android runtime uses an exact stable LiteRT-LM dependency, checked against Google Maven by `scripts/check_android_litertlm_version.py`. Build inputs and genuine Python dependencies are verified before Gradle consumes them; a successful compile is not a model/device certification.

This repository also retains the upstream-derived CLI and desktop sources. Their established module, command, protocol, and storage identifiers have not been globally renamed as part of the Android presentation change. See the [contributor instructions](AGENTS.md) and [MIT license](LICENSE).

For the retained Windows command-line tooling, use the [PowerShell installer](scripts/install.ps1). This is separate from installing the Android APK.

A candidate branch or debug build is not a new public store release. Release publication, signed upgrades, and device/model qualification use the repository's separate release gates.
