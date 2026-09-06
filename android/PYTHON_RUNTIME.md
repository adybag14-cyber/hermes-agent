# Source-built Android Python runtime

The normal application now selects the genuine SDK set in
`requirements-android-chaquopy.txt`: OpenAI 2.54, Anthropic 0.125, FAL 1.0,
Pydantic 2.13 and their Android dependencies. Production packaging no longer
installs the raising Anthropic/FAL placeholders. The interpreter remains
Chaquopy's Python **3.13**; standalone Python 3.14 dependency tests are not a
claim that the complete Hermes app supports 3.14.

## Build inputs

`hermes_android/python_runtime.lock.json` binds the exact Chaquopy fork source
archive and every trusted PyPI/Chaquopy complementary wheel by SHA-256 and size.
`scripts/prepare_android_python_runtime.py` downloads that source, builds jiter
and pydantic-core for ARM64/x86_64 with Rust 1.88 and NDK 27.3, builds upstream's
pure-Python msgpack mode, and compiles the fork's Python bootstrap. No custom
prebuilt wheel or bootstrap download is accepted. The app's separate C++ lane
continues to use NDK 29; neither compiler silently substitutes for the other.

The Java/JNI runtime and Gradle plugin remain upstream Chaquopy 17.0.0. Only the
source-built Python bootstrap uses fork version 17.0.1, including the corrected
split-archive importer. All bootstrap code objects and license files are
validated against source. The fork CI independently compares the source-only
bootstrap with its upstream Gradle compiler output, then runs the app matrix.

## Preparation

Use Linux Python 3.13, `python3-venv`, `g++`, `rustup`, and the declared SDK/NDKs.
Windows release preparation uses the pinned F-Droid buildserver container from
`fdroid/LOCAL_TOOLCHAIN.md`, working on the container filesystem. Do not install
target Android wheels into a host interpreter or retag host wheels.

```sh
python3.13 scripts/prepare_android_python_runtime.py prepare \
  --output "${GRADLE_USER_HOME:-$HOME/.gradle}/hermes-python-runtime" \
  --work-dir /new/unique/hermes-python-build
```

The work directory must be new and remains intact after failure. An existing
output is reused only after verifying the entire closed inventory and current
committed lock/requirements hashes. A failed or changed output is not silently
overwritten. Generated sources, wheels, compiler caches and receipts remain
outside the checkout and are not Git blobs.

Gradle defaults to the output above. A local developer may select a verified
source-built bundle with `-PhermesPythonBundle=/absolute/bundle`. All requirements
install offline, and bootstrap resolution is exclusive and checksum-verified.
`-PhermesChaquopyLab` remains a separate, non-release package; it cannot produce
tagged releases or combine with the production bundle selector.

## Evidence boundaries

Preparation checks offline dependency closure, original/rebuilt wheel RECORDs,
ELF architecture, dependency closure and 16-KB load alignment for each ABI.
Rust paths and generated SBOM source references are normalized without changing
code, licenses or dependency relationships. Python bootstrap ZIP entries and
PYC headers are deterministic. The closed receipt explicitly says
`runtime_tested: false`.

Debug builds include the genuine SDK/Hermes-loop instrumentation probes. Release
builds exclude those probe modules. The actual app/device gates, signed upgrade,
and source-bound release evidence remain mandatory. After GitHub publication,
fresh updater detection and pinned-buildserver comparison with the public APK
are both required; a prepared bundle or successful Gradle build is not either
post-release gate.
