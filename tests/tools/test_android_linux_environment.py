from tools.environments.android_linux import AndroidLinuxEnvironment


def test_android_linux_command_env_uses_shared_secret_filter(tmp_path, monkeypatch):
    prefix = tmp_path / "prefix"
    monkeypatch.setenv("HERMES_ANDROID_LINUX_PREFIX", str(prefix))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_BASH", str(prefix / "bin" / "bash"))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_BIN", str(prefix / "bin"))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(prefix / "home"))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_TMP", str(prefix / "tmp"))
    monkeypatch.setenv("OPENAI_API_KEY", "must-not-reach-shell")
    monkeypatch.setattr(AndroidLinuxEnvironment, "init_session", lambda self: None)
    environment = AndroidLinuxEnvironment(env={"ANDROID_TEST_VALUE": "kept"})
    child_env = environment._build_run_env()
    assert "OPENAI_API_KEY" not in child_env
    assert child_env["ANDROID_TEST_VALUE"] == "kept"
    assert child_env["PREFIX"] == str(prefix)
    assert child_env["PATH"].startswith("/system/bin:/system/xbin:/vendor/bin:/odm/bin")


def test_android_linux_snapshot_excludes_first_party_terminal_secrets(monkeypatch):
    environment = AndroidLinuxEnvironment.__new__(AndroidLinuxEnvironment)
    environment.env = {"BUZZ_PRIVATE_KEY": "profile-private-value"}
    assert "BUZZ_PRIVATE_KEY" in environment._additional_profile_scoped_passthrough_names()
    assert environment._profile_scoped_passthrough is True


def test_android_linux_environment_builds_system_first_runtime_env(tmp_path, monkeypatch):
    prefix = tmp_path / "prefix"
    bin_dir = prefix / "bin"
    lib_dir = prefix / "lib"
    home_dir = prefix / "home"
    tmp_dir = prefix / "tmp"
    for directory in [bin_dir, lib_dir, home_dir, tmp_dir]:
        directory.mkdir(parents=True, exist_ok=True)

    monkeypatch.setenv("HERMES_ANDROID_LINUX_PREFIX", str(prefix))
    monkeypatch.setenv("HERMES_ANDROID_SHELL", "/system/bin/sh")
    monkeypatch.setenv("HERMES_ANDROID_LINUX_BIN", str(bin_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_LIB", str(lib_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(home_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_TMP", str(tmp_dir))
    monkeypatch.setenv("PATH", "/test-host/bin")
    for name in ("TERMUX_PREFIX", "LD_LIBRARY_PATH", "TERMINFO", "GIT_EXEC_PATH", "HERMES_ANDROID_ALLOW_PREFIX_BIN"):
        monkeypatch.delenv(name, raising=False)

    env = AndroidLinuxEnvironment(cwd=str(home_dir), timeout=30)
    run_env = env._build_run_env()

    assert env.process_shell_path == "/system/bin/sh"
    assert run_env["PREFIX"] == str(prefix)
    assert run_env["HOME"] == str(home_dir)
    assert run_env["TMPDIR"] == str(tmp_dir)
    assert run_env["PATH"].startswith("/system/bin:/system/xbin:/vendor/bin:/odm/bin:")
    assert run_env["PATH"].endswith("/test-host/bin")
    assert str(bin_dir) not in run_env["PATH"]
    assert run_env["HERMES_ANDROID_SHELL"] == "/system/bin/sh"
    assert run_env["HERMES_ANDROID_EXECUTION_MODE"] == "android_system_shell"
    assert run_env["LD_LIBRARY_PATH"] == str(lib_dir)
    for name in ("TERMUX_PREFIX", "TERMINFO", "GIT_EXEC_PATH"):
        assert name not in run_env
    monkeypatch.setenv("HERMES_ANDROID_ALLOW_PREFIX_BIN", "1")
    assert env._build_run_env()["PATH"].endswith(":" + str(bin_dir))

    env.cleanup()


def test_android_linux_environment_preserves_selected_native_runtime(tmp_path, monkeypatch):
    native_dir = tmp_path / "native-libs"
    prefix_lib = tmp_path / "prefix-libs"
    monkeypatch.setenv("HERMES_ANDROID_EXECUTION_MODE", "embedded_termux")
    monkeypatch.setenv("HERMES_ANDROID_SHELL", str(native_dir / "libhermes_android_bash.so"))
    monkeypatch.setenv("HERMES_ANDROID_NATIVE_LIB", str(native_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_LIB", str(prefix_lib))
    monkeypatch.setenv("LD_LIBRARY_PATH", "/vendor/lib64")
    monkeypatch.setenv("OPENAI_API_KEY", "must-not-reach-native-shell")
    monkeypatch.setattr(AndroidLinuxEnvironment, "init_session", lambda self: None)

    environment = AndroidLinuxEnvironment(cwd=str(tmp_path))
    child_env = environment._build_run_env()

    assert child_env["HERMES_ANDROID_EXECUTION_MODE"] == "embedded_termux"
    assert child_env["HERMES_ANDROID_SHELL"] == str(native_dir / "libhermes_android_bash.so")
    assert environment.process_shell_path == "/system/bin/sh"
    assert child_env["LD_LIBRARY_PATH"].startswith(str(native_dir) + ":")
    assert child_env["LD_LIBRARY_PATH"].endswith(f"{prefix_lib}:/vendor/lib64")
    assert "OPENAI_API_KEY" not in child_env


def test_android_linux_environment_derives_native_library_dir_from_shell_path(tmp_path, monkeypatch):
    native_dir = tmp_path / "apk-lib"
    shell_path = native_dir / "libhermes_android_bash.so"
    monkeypatch.setenv("HERMES_ANDROID_SHELL", "/system/bin/sh")
    monkeypatch.setenv("HERMES_ANDROID_LINUX_NATIVE_BASH", str(shell_path))
    monkeypatch.delenv("HERMES_ANDROID_NATIVE_SHELL", raising=False)
    monkeypatch.delenv("HERMES_ANDROID_NATIVE_LIB", raising=False)
    monkeypatch.setattr(AndroidLinuxEnvironment, "init_session", lambda self: None)

    environment = AndroidLinuxEnvironment(cwd=str(tmp_path))
    child_env = environment._build_run_env()

    assert environment.process_shell_path == "/system/bin/sh"
    assert child_env["HERMES_ANDROID_SHELL"] == "/system/bin/sh"
    assert child_env["HERMES_ANDROID_NATIVE_SHELL"] == str(shell_path)
    assert child_env["LD_LIBRARY_PATH"].startswith(str(native_dir))
