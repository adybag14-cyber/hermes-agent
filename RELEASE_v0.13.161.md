# Agent 0.13.161

This release repairs local model startup when the preferred loopback port is
occupied. Agent reserves an available port and keeps the selected authenticated
endpoint through readiness, chat, process reuse, and shutdown.

- Updates Google's LiteRT-LM Android SDK to 0.18.1, stable Termux llama.cpp to
  0.7.0, and the source-built TurboQuant engine to `6439568`.
- Updates dependencies affected by high and critical security advisories,
  including the Python runtime actually packaged in Android.
- Repairs a demonstrated multi-word context-file injection bypass, preserves
  advanced model arguments, and safely handles failed LiteRT initialization.
- Keeps optional HY Memory available through reviewed, commit-pinned HY Memory
  and ChromaDB security patches. Stored memory data is preserved.
- Preserves registered provider credentials when catalogs change and handles
  brief SQLite write contention without losing counter updates.

The source security review, including the four specific upstream-version
matches for already-patched fork code, is recorded in
`docs/design/android-v161-security-review.md`.

Full and Play packages remain separate. Physical phone testing is optional;
required emulator, signing, hosted, and F-Droid verification have independent
release receipts. A GitHub release is not a claim of central F-Droid index
publication or Google Play approval.
