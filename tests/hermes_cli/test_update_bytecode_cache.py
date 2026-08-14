"""Filesystem behavior for update-time bytecode cache cleanup."""

from hermes_cli.main import _clear_bytecode_cache


def test_clear_bytecode_cache_skips_generated_and_retained_trees(tmp_path):
    source_cache = tmp_path / "hermes_cli" / "__pycache__"
    source_cache.mkdir(parents=True)
    (source_cache / "main.cpython-313.pyc").write_bytes(b"source")

    preserved_files = []
    for relative_dir in (
        ".artifacts/prior-run/__pycache__",
        "android/app/build/generated/__pycache__",
        ".gradle/caches/__pycache__",
    ):
        cache_dir = tmp_path / relative_dir
        cache_dir.mkdir(parents=True)
        cache_file = cache_dir / "fixture.cpython-313.pyc"
        cache_file.write_bytes(b"generated")
        preserved_files.append(cache_file)

    assert _clear_bytecode_cache(tmp_path) == 1
    assert not source_cache.exists()
    assert all(path.read_bytes() == b"generated" for path in preserved_files)
