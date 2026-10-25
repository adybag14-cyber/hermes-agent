# v0.13.161 security and dependency review

The 25 October 2026 GitHub baseline contained 8 critical and 97 high code-scanning
entries, including duplicate reports. Fresh OSV and npm queries also identified
advisories published since the repository's last scan. The update covers all
five lockfiles used by `.github/workflows/osv-scanner.yml` and the separate
Android Python wheel inventory.

## Dependency changes

The Python security refresh includes aiohttp 3.14.5, AnyIO 4.14.2, brotlicffi
1.2.1.0, certifi 2026.10.23, filelock 4.2.4, fsspec 2026.9.0, MCP/mcp-types
2.3.0, Pillow 13.0.0, PyJWT 2.15.1, Tornado 6.6, urllib3 2.8.0, and yarl
1.25.1. Android independently pins and hashes its actual supported wheel set;
host dependencies such as Pillow are not inferred to be in the APK.

The npm manifests retain their supported major lines where patched releases
exist. The generated locks update affected transport, XML, parser, cache,
image, worker-pool, development-server, and desktop tooling dependencies.
The unsupported upstream desktop/website products do not become Android
release gates merely because their retained lockfiles were repaired.

`extract-zip` has no published fix for CVE-2026-19693 or CVE-2026-56876.
`vendor/extract-zip` preserves the upstream license and API while validating
symlink targets, rejecting symlink directory traversal, and using exclusive
temporary files plus atomic rename for regular file writes. Real malicious
ZIP fixtures fail on the original implementation; confined links and normal
files still work with the repair. See the vendor README for provenance.

## HY Memory is preserved

The latest published ChromaDB 1.5.9 still carries CVE-2026-45829,
CVE-2026-45830, CVE-2026-45831, and CVE-2026-45833. Moving to the latest PyPI
version alone would not resolve them.

HY Memory 2.0.5 is patched at
`adybag14-cyber/Hy-Memory@df2cf75f2fb59af54016559fc08c4d57a5fe827f` and requires
`adybag14-cyber/chroma@00c54a0164bc0d433d92bf956bebf2a0883d77dd`. The Chroma
repair rejects remote-code enablement through serialized embedding settings,
checks tenant/database scope in RBAC and both storage implementations, and
propagates scope through v1 collection handlers. Its 65 regression cases use
real FastAPI routing, token authentication, and Segment/Rust storage. HY Memory's
19 focused store/SDK checks pass against that patched source. These are source
validation results, not Android release certification.

Agent's lazy installer accepts only those complete reviewed source requirements;
arbitrary URLs and moving Git references remain rejected. Installed source
dependencies must have matching PEP 610 repository and commit provenance. A
previously imported, older module requires a Python restart. The provider no
longer treats the mere presence of an old HY Memory package as sufficient.

## Source fixes and four version-only matches

The editable fork retains its historical Python distribution version
`hermes-agent==0.13.146`, independently of the Android version. OSV matches that
number against upstream PyPI releases even though the relevant source fixes
are already ancestors of this fork. `osv-scanner.toml` excepts only these four
advisory IDs and their aliases, with an expiry of 23 January 2027:

| Advisory | Source and behavioral evidence |
| --- | --- |
| CVE-2026-9353 / GHSA-238w-f66p-w349 | Upstream `7ebebfbb8d10937cbf2219b4cf5e121b71e67428`; `tests/tools/test_skills_guard.py` checks multi-word overrides and policy impersonation. |
| CVE-2026-53869 / GHSA-4pqm-j46f-795x | Upstream `d9ec90585cf7616b5972e44cf8d92bb569fc3feb`; `test_web_server_host_header.py` and `test_dashboard_auth_ws_auth.py` exercise WebSocket Host/Origin rejection. |
| CVE-2026-9366 / GHSA-pgp4-xr4j-h5cg | Upstream `0dee92df22bdc0cfbcad90ca954aa14916f018de`; context files are blocked when shared threat scanning finds injection. v161 additionally repairs a demonstrated multi-word system-prompt override bypass using the existing bounded filler expression. |
| CVE-2026-10221 / GHSA-xq8w-9jvx-gm3v | Upstream `2ca38e5df43411afe11aedfc34bfd71a2eecd4bb`; compression snapshot and zero-user-provenance regression suites preserve the actual user's instruction authority. |

These exceptions do not suppress third-party packages or future Hermes
advisories. Prompt scanning is a bounded detection layer, not a guarantee that
arbitrary model prompt injection is impossible. The source and behavior checks
must continue to pass; changing the fork's version number is not remediation.

## Runtime refresh and release boundary

The complete Linux suite also exposed two shared-runtime defects: a new
models.dev entry could replace a registered provider's credential/transport
identity, and a legitimate concurrent metrics writer could exceed the short
250-ms read budget. Registered profiles now retain precedence when no curated
overlay exists; counter updates use an immediate transaction with a bounded
five-second write budget. A real held-writer regression fails before the fix.
The API shutdown-drain fixture now delays only its owned run coroutine,
preserving the real HTTP client tasks used by newer aiohttp releases.

LiteRT-LM is pinned to Google's published Android 0.18.1 artifact. Stable
Termux llama.cpp is 0.7.0 on both ABIs, with its current dependency closure.
TurboQuant is pinned to `643956891dfdf51aecdbd05ca508dd21e21e8195`; source,
license, and compatibility-patch hashes were independently verified.

The deterministic Termux input archive has 141 packages and SHA-256
`27caa54545eaab0809034dfe7f69e3b41384e16640cbf9a20717a7df70b23288`.
Publication, installed-device evidence, hosted validation, signing, and both
post-publication F-Droid gates remain separate required release receipts.
No GitLab merge request is part of this release scope.
