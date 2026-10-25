# Confined ZIP extraction

This private dependency preserves the `extract-zip` 2.0.1 API and BSD-2-Clause
license. The upstream npm tarball has SHA-256
`59b4e0ae67fa617bb64ca0686effb02253d820ce57f913c95116dc7cb373dab4`.

CVE-2026-19693 and CVE-2026-56876 have no patched upstream npm release.
This fork checks link targets, rejects traversal through directory symlinks,
and writes regular files through exclusive temporary files and atomic rename.
It retains confined relative symlinks used by packaged applications.

Run `npm test` here on Linux or another host with symlink privileges. The tests
create real ZIP archives and check both attempted escapes and normal extraction.
