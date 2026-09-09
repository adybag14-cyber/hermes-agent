"""Read-only resource and executable facts for the embedded Android terminal."""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path
from typing import Mapping

ANDROID_SYSTEM_PATH = "/system/bin:/system/xbin:/vendor/bin:/odm/bin"


def android_command_path(existing_path: str, prefix_bin: str, allow_prefix: bool) -> str:
    paths = [ANDROID_SYSTEM_PATH]
    if existing_path:
        paths.append(existing_path)
    if prefix_bin and allow_prefix and prefix_bin not in paths:
        paths.append(prefix_bin)
    return ":".join(paths)


def parse_proc_memory_kib(text: str) -> dict[str, int]:
    fields = {}
    for line in text.splitlines():
        key, separator, raw = line.partition(":")
        values = raw.split()
        if separator and len(values) == 2 and values[1] == "kB" and values[0].isdigit():
            fields[key] = int(values[0]) * 1024
    return fields


def read_runtime_capabilities(hermes_home: Path, *, environment: Mapping[str, str] | None = None) -> dict:
    environment = os.environ if environment is None else environment
    command_path = android_command_path(environment.get("PATH", ""), environment.get("HERMES_ANDROID_LINUX_BIN", ""),
                                        environment.get("HERMES_ANDROID_ALLOW_PREFIX_BIN") == "1")
    shell = environment.get("HERMES_ANDROID_SHELL") or environment.get("HERMES_ANDROID_LINUX_BASH") or "/system/bin/sh"
    storage = {"path": str(hermes_home), "scope": "app_private_hermes_home", "measurement_available": False}
    try:
        usage = shutil.disk_usage(hermes_home)
    except OSError:
        pass
    else:
        storage.update(measurement_available=True, total_bytes=usage.total, available_bytes=usage.free,
                       writable=os.access(hermes_home, os.W_OK))
    memory = {"measurement_available": False, "scope": "whole_device_not_hermes_heap", "source": "/proc/meminfo"}
    try:
        with open("/proc/meminfo", encoding="ascii") as stream:
            fields = parse_proc_memory_kib(stream.read(65536))
    except OSError:
        fields = {}
    if "MemTotal" in fields and "MemAvailable" in fields:
        memory.update(measurement_available=True, total_bytes=fields["MemTotal"], available_bytes=fields["MemAvailable"])
    skills = hermes_home / "skills"
    return {
        "storage": storage,
        "memory": memory,
        "embedded_python": {"running": True, "version": ".".join(map(str, sys.version_info[:3]))},
        "shell": {"path": shell, "mode": environment.get("HERMES_ANDROID_EXECUTION_MODE", "android_system_shell"),
                  "python_executable": shutil.which("python", path=command_path),
                  "python3_executable": shutil.which("python3", path=command_path)},
        "skills": {"installed_directory": str(skills), "installed_directory_exists": skills.is_dir(),
                   "bundled_directory": environment.get("HERMES_BUNDLED_SKILLS", "")},
        "workspace_directory": str(hermes_home / "workspace"),
        "interpretation": (
            "Storage is measured on the app's writable-data filesystem, not Android's read-only system image. "
            "Embedded Python is separate from a shell-visible python/python3 executable. "
            "Read skills at the reported installed directory, not relative to an unrelated shell directory. "
            "Kernel RAM availability is not this app's heap usage; the chat saved-facts count is not RAM."
        ),
    }
