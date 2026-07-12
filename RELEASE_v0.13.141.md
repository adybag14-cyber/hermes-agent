# Hermes Agent Fork v0.13.141

Android reliability and local-agent release focused on a real Gemma 4 E2B to Alpine workflow.

## Local model and memory safety

- Restores automatic discovery of models provisioned under legacy internal and release-visible external app directories.
- Adds atomic PowerShell model provisioning for debug and non-debuggable release APKs with exact byte and SHA-256 verification and no multi-gigabyte device staging copy.
- Uses RAM-aware LiteRT-LM context budgets and compact route-specific tool prompts to avoid emulator/device OOM while retaining native tool calling.
- Keeps Gemma 4 MTP checks architecture-correct on universal x86_64/arm64 builds.

## Alpine sandbox execution

- Installs the opposite-architecture Alpine guest and runs it through PRoot plus the packaged QEMU emulator, avoiding Android same-architecture W^X/seccomp failures.
- Reports installed sandbox architecture and Android execution support honestly; legacy unsupported containers receive an actionable remediation error.
- Routes active-guest commands to `mcp_run_in_proot` and lifecycle actions to `linux_sandbox_tool` without competing host-terminal schemas.
- Adds live proof that a real Gemma model request invokes a tool and creates/reads a marker inside Alpine 3.21 via `proot_distro_qemu`.

## Startup, UI, and Windows reliability

- Guarantees Linux extraction/repair occurs before every direct Python startup entry point.
- Shows stopped Python as `idle` instead of the misleading `booting` label and keeps readiness polling passive.
- Keeps language controls reachable and verifies English-to-Spanish navigation across Settings, Accounts, Device, and Provider Portal.
- Defaults locked Termux asset downloads to IPv4 on Windows, while retaining an explicit opt-out.
- Fixes Windows gateway environment isolation, UTF-8 notification reads, dynamic plugin enum attributes on Python 3.13, and portable runtime-footer paths.

## Version

- 0.13.141 / versionCode 144190
- Package: com.mobilefork.hermesagent
