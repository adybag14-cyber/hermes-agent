# Play edition and v0.13.157 review boundary

The Play edition is a separate distribution of the same application source. The
full GitHub/F-Droid APK retains Linux, Python-agent tools, accessibility-assisted
device actions and automation. Do not upload that full APK/AAB as the Play edition.
The package ID, release version and signing identity remain shared; the installed
label, BuildConfig flag and binary manifest distribution marker distinguish editions.

| Surface | Full GitHub/F-Droid | Play |
|---|---|---|
| Remote API chat | Existing agent transport, explicit processing consent | Foreground direct API chat, explicit target-bound consent, no model tools |
| Local GGUF | Existing Stable and TurboQuant engines | Packaged static e30664a engine for both conventional and TurboQuant cache profiles |
| LiteRT-LM | Existing packaged runtime | Packaged runtime, foreground only |
| Linux, executable/package installation, arbitrary shell | Retained | No packaged Linux payload; runtime admission denied |
| Accessibility, broad app inventory, privileged actions, watchers | Retained with applicable disclosure/permission checks | Components/permissions removed; tool dispatch denied |
| Private AI-content reporting | Voluntary preview and submission | Same voluntary preview and submission |
| Local/private-data deletion and remote-consent revocation | Available | Available |

Model weights are data downloads, not executable updates. Neither cache-profile
name implies that Play includes the full edition's Termux Stable executable.
Arbitrary extra llama.cpp arguments are rejected in Play. Provider setup/payment
links and unsupported account-auth flows are absent from Play; users may configure
an existing supported API credential. No billing, advertising or subscription is
introduced by this edition.

## Applicable policy audit

This is an engineering audit, not a Google approval or a legal-compliance opinion.
Policy sources were reviewed on 2026-09-08. Recheck Console requirements before
submission, particularly if features, target audience, providers or SDKs change.

- **AI-generated content:** system safety instructions, bounded input/output checks,
  no publication of unscreened streaming deltas, AI labelling and in-app reporting.
  The local lexical checks are a limited safeguard, not an exhaustive classifier.
  Test adversarial prompts and real model behavior, review reports using the private
  service runbook, and correct demonstrated gaps before Play submission. A working
  report button does not alone satisfy the generation-safety requirement.
  [Policy guidance](https://support.google.com/googleplay/android-developer/answer/14094294?hl=en),
  [AI-content policy](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en).
- **User data and third-party AI:** disclose the provider/endpoint and data sent,
  require an affirmative action, preserve a declined draft, re-prompt after revocation
  or a target change, and use HTTPS except explicit device loopback. A configured
  loopback proxy still requires consent; it is not a packaged on-device model.
  Runtime checks apply before network dispatch and before response publication.
  SDK/provider data practices remain part of the review, not something a generic
  consent checkbox exempts. [User Data](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
- **Private reports:** the developer receives only the previewed text, selected
  reason, optional notes, app version/edition and necessary receipt metadata.
  Reports are not public; deletion requires the private receipt. No conversation
  analytics or automatic transcript upload is added. Active records expire after
  30 days with hourly cleanup; infrastructure backups have separate retention.
  See [report-service/README.md](report-service/README.md) and the
  [published privacy policy](https://adybag14-cyber.github.io/hermes-agent/privacy-policy/).
- **Sensitive permissions and accessibility:** Play contains no accessibility
  service, QUERY_ALL_PACKAGES, usage access, location, Bluetooth scans, notification
  listener, overlays, boot receiver, foreground services or Shizuku provider.
  Camera/speech actions remain user initiated; speech disclosure precedes the
  Android microphone permission flow. The full edition has an independent
  accessibility disclosure, which never infers consent from Android's switch.
  [Permissions policy](https://support.google.com/googleplay/android-developer/answer/9888170?hl=en).
- **Device/network abuse and executable downloads:** Play omits full-edition
  Linux/native-package assets, disables the Python agent and blocks arbitrary tool
  and shell entry points. Packaged native engines are shipped in the application.
  [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en).
- **Platform compatibility:** inspect the actual APK, not just Gradle. Require
  targetSdk >= 36, narrow components/permissions, 16 KB ELF alignment across native
  libraries (including nested Chaquopy archives), matching APK/AAB library and asset
  payloads, and real Android 16/16 KB emulator inference. Signed release APKs also
  pass zipalign. [16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes).
- **Payments:** Play is consumption-only with no in-app payment route. Provider
  API-key acquisition links are hidden, including Open/Copy/Check controls.
  Reassess any future purchase or external-offer links by region/program.
  [Payments guidance](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en).
- **Account/data deletion:** this Play app does not create a developer-hosted user
  account. Device-private chats, settings, keys, receipts and private model files
  can be erased through Android's own app-data deletion operation. Public/shared
  files and independently held provider accounts are not deleted by that action.
  Report deletion remains a separate remote operation. Reassess account-deletion
  requirements if developer-hosted sign-up is later introduced.
- **Store/Console declarations:** app access instructions, content rating, audience,
  AI content, ads, Data safety, privacy URL and store descriptions must describe the
  actual Play edition. Do not describe data collection as "none": optional remote
  processing and private reports need accurate treatment. Do not advertise the full
  edition's automation or Linux features for Play. Health/finance/government/news,
  children/families, dating, gambling, VPN and special-permission declarations must
  be evaluated against actual features, not selected merely because an AI can
  discuss those subjects. Existing Console answers require a final review against
  the final uploaded Play artifact; technical tests cannot approve those answers.

## Build and acceptance

Full tasks retain their names: `assembleRelease`, `bundleRelease`, `testDebugUnitTest`.
Play tasks are `assemblePlayRelease`, `bundlePlayRelease`, and `testPlayDebugUnitTest`.
For Play instrumentation/unit tests, also pass `-PhermesTestBuildType=playDebug`.
The full tool-execution unit suite runs under the full flag; the Play-specific
distribution, privacy, transport and translation tests run under the Play flag.

Release filenames:

- Full: `hermes-agent-android-v0.13.157-universal.apk` and matching `.aab`.
- Play: `hermes-agent-android-play-v0.13.157-universal.apk` and matching `.aab`.

`verify_android_play_package.py` checks the binary package boundary and optional
APK/AAB payload equality. It does not claim runtime execution or Google approval.
`PlayPrivacyInstrumentedTest` exercises consent, voice decline, lifecycle, live
synthetic report submission/deletion and six real UI language switches.
`PlayModelMatrixInstrumentedTest` exercises every content-addressed release model
through the real Play chat path, safety refusal, Stop and background shutdown.

For release records, supply the existing source/APK/test-APK/run/device identity
arguments plus `record_play_release_evidence=true`. The producer independently
hashes both installed APKs and checks the embedded source digest. Retrieve the
resulting `files/hermes-play-evidence/<run-id>` directory into
`android/release-evidence/<tag>/play`. The release workflow requires
`verify_android_play_release_evidence.py` to pass before signing/publication; missing
cases, stale/mixed identity, ordinary full-debug evidence or a 4 KB device fail.
From v0.13.156 onward the Full release manifest also requires and validates this
same Play contract before hashing its files. The closed `play/` layout contains
the three behavior-case JSON records, one `model-<model-id>.json` for each
registered release model, and exactly six `privacy-<language>.png` screenshots.
Missing, extra, non-regular, or stale evidence fails both entry points. Earlier
release layouts remain unchanged.

Ordinary unbound debug test passes are development evidence only. Stable v0.13.157
retains its historical physical-phone waiver. The owner's standing instruction
of 2026-09-12 makes physical on-device validation optional extra checking for all
future releases; a disconnected phone or absent physical video is not a release
gate. This does not certify ARM64 device behavior or waive the required AVD,
hosted release or two post-release F-Droid gates. Physical checks/videos can be
performed when separately requested. Google review/approval and central F-Droid
publication are external states.
