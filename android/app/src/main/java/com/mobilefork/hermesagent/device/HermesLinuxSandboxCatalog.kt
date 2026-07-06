package com.mobilefork.hermesagent.device

import org.json.JSONArray
import org.json.JSONObject

object HermesLinuxSandboxCatalog {
    const val PROOT_DISTRO_SOURCE_URL = "https://github.com/termux/proot-distro"
    const val TERMUX_PACKAGES_SOURCE_URL = "https://github.com/termux/termux-packages"

    fun recommendedSandboxIds(): JSONArray {
        return JSONArray()
            .put("debian-bookworm")
            .put("ubuntu-24-04")
            .put("alpine-3-21")
            .put("archlinux")
            .put("fedora-latest")
            .put("voidlinux")
            .put("opensuse-tumbleweed")
    }

    fun distroCatalog(): JSONArray {
        return JSONArray()
            .put(
                distro(
                    id = "debian-bookworm",
                    label = "Debian 12 Bookworm",
                    image = "debian:bookworm",
                    packageManager = "apt",
                    profile = "default",
                    reason = "Best default for Android agents: stable, broad package coverage, predictable apt behavior.",
                    name = "hermes-debian",
                ),
            )
            .put(
                distro(
                    id = "ubuntu-24-04",
                    label = "Ubuntu 24.04 LTS",
                    image = "ubuntu:24.04",
                    packageManager = "apt",
                    profile = "general",
                    reason = "Best when docs or upstream scripts assume Ubuntu LTS packages.",
                    name = "hermes-ubuntu",
                ),
            )
            .put(
                distro(
                    id = "alpine-3-21",
                    label = "Alpine Linux 3.21",
                    image = "alpine:3.21",
                    packageManager = "apk",
                    profile = "small",
                    reason = "Smallest practical sandbox for quick CLI tasks and low-storage phones.",
                    name = "hermes-alpine",
                ),
            )
            .put(
                distro(
                    id = "archlinux",
                    label = "Arch Linux",
                    image = "archlinux:latest",
                    packageManager = "pacman",
                    profile = "rolling",
                    reason = "Useful for recent compilers and rolling packages; advanced users should expect larger updates.",
                    name = "hermes-arch",
                ),
            )
            .put(
                distro(
                    id = "fedora-latest",
                    label = "Fedora",
                    image = "fedora:latest",
                    packageManager = "dnf",
                    profile = "modern",
                    reason = "Good for newer toolchains and RPM workflows without pinning Hermes to one Fedora release.",
                    name = "hermes-fedora",
                ),
            )
            .put(
                distro(
                    id = "voidlinux",
                    label = "Void Linux",
                    image = "voidlinux/voidlinux:latest",
                    packageManager = "xbps",
                    profile = "advanced",
                    reason = "Compact rolling option for advanced users who want xbps and musl/glibc variants.",
                    name = "hermes-void",
                ),
            )
            .put(
                distro(
                    id = "opensuse-tumbleweed",
                    label = "openSUSE Tumbleweed",
                    image = "opensuse/tumbleweed:latest",
                    packageManager = "zypper",
                    profile = "rolling",
                    reason = "Rolling SUSE-family option for users who need zypper and RPM workflows.",
                    name = "hermes-opensuse",
                ),
            )
    }

    fun desktopCatalog(): JSONArray {
        return JSONArray()
            .put(
                desktop(
                    id = "xfce4",
                    label = "Xfce4",
                    profile = "recommended",
                    reason = "Most reliable lightweight desktop target for proot/VNC style Android sessions.",
                ),
            )
            .put(
                desktop(
                    id = "cage",
                    label = "Cage kiosk",
                    profile = "single-app",
                    reason = "Small single-window Wayland shell for focused GUI tasks.",
                ),
            )
            .put(
                desktop(
                    id = "hyprland",
                    label = "Hyprland",
                    profile = "experimental",
                    reason = "Nested Wayland compositor target; expose as experimental because phone GPU/Wayland support varies.",
                ),
            )
    }

    fun mirrorProfiles(): JSONArray {
        return JSONArray()
            .put(
                mirrorProfile(
                    id = "default",
                    label = "Default upstream mirrors",
                    reason = "Use the distro's stock package mirrors.",
                ),
            )
            .put(
                mirrorProfile(
                    id = "china",
                    label = "China domestic mirrors",
                    reason = "Switch apt/apk repositories to common mainland-friendly mirrors (Aliyun + Tsinghua).",
                ),
            )
            .put(
                mirrorProfile(
                    id = "aliyun",
                    label = "Aliyun mirrors",
                    reason = "Use mirrors.aliyun.com for apt/apk when mainland routing is faster.",
                ),
            )
            .put(
                mirrorProfile(
                    id = "tsinghua",
                    label = "Tsinghua TUNA mirrors",
                    reason = "Use mirrors.tuna.tsinghua.edu.cn for apt/apk when mainland routing is faster.",
                ),
            )
    }

    fun agentSummary(): JSONObject {
        return JSONObject()
            .put("source", PROOT_DISTRO_SOURCE_URL)
            .put("termux_packages_source", TERMUX_PACKAGES_SOURCE_URL)
            .put("install_engine", "proot-distro-compatible OCI/rootfs sandbox")
            .put("recommended_default", "debian-bookworm")
            .put("recommended_small", "alpine-3-21")
            .put("recommended_ubuntu", "ubuntu-24-04")
            .put("recommended_opensuse", "opensuse-tumbleweed")
            .put("download_policy", "Download rootfs images only over user-approved network paths and run commands inside app-private storage.")
            .put("one_click_deploy", JSONObject()
                .put("action", "deploy")
                .put("distro_id", "debian-bookworm")
                .put("sandbox_name", "hermes-debian")
                .put("steps", JSONArray()
                    .put("download/install Debian Bookworm sandbox")
                    .put("start agent shell access")
                    .put("refresh packages inside the sandbox")))
            .put("mirror_profiles", mirrorProfiles())
            .put("recommended_ids", recommendedSandboxIds())
            .put("distros", distroCatalog())
            .put("desktops", desktopCatalog())
    }

    fun mirrorCommandFor(packageManager: String, mirrorProfile: String): String {
        val profile = mirrorProfile.trim().lowercase().ifBlank { "default" }
        return when (packageManager.trim().lowercase()) {
            "apt" -> when (profile) {
                "china", "aliyun" -> """
                    if [ -f /etc/apt/sources.list ]; then
                      sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com/debian-security|g' /etc/apt/sources.list;
                    fi;
                    find /etc/apt/sources.list.d -type f -name '*.list' -print0 2>/dev/null | xargs -0 -r sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com/debian-security|g';
                    find /etc/apt/sources.list.d -type f -name '*.sources' -print0 2>/dev/null | xargs -0 -r sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com/debian-security|g';
                    apt-get update
                """.trimIndent().replace("\n", " ")

                "tsinghua" -> """
                    if [ -f /etc/apt/sources.list ]; then
                      sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g; s|security.debian.org|mirrors.tuna.tsinghua.edu.cn/debian-security|g' /etc/apt/sources.list;
                    fi;
                    find /etc/apt/sources.list.d -type f -name '*.list' -print0 2>/dev/null | xargs -0 -r sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g; s|security.debian.org|mirrors.tuna.tsinghua.edu.cn/debian-security|g';
                    find /etc/apt/sources.list.d -type f -name '*.sources' -print0 2>/dev/null | xargs -0 -r sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g; s|security.debian.org|mirrors.tuna.tsinghua.edu.cn/debian-security|g';
                    apt-get update
                """.trimIndent().replace("\n", " ")

                else -> "apt-get update"
            }

            "apk" -> when (profile) {
                "china", "aliyun" -> """
                    if [ -f /etc/apk/repositories ]; then
                      sed -i 's|dl-cdn.alpinelinux.org|mirrors.aliyun.com|g' /etc/apk/repositories;
                    fi;
                    apk update
                """.trimIndent().replace("\n", " ")

                "tsinghua" -> """
                    if [ -f /etc/apk/repositories ]; then
                      sed -i 's|dl-cdn.alpinelinux.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apk/repositories;
                    fi;
                    apk update
                """.trimIndent().replace("\n", " ")

                else -> "apk update"
            }

            else -> "echo 'Mirror switching is only implemented for apt/apk sandboxes in this release.' >&2; exit 2"
        }
    }

    fun findDistro(value: String): JSONObject? {
        val needle = value.trim().lowercase()
        if (needle.isBlank()) {
            return null
        }
        val catalog = distroCatalog()
        for (index in 0 until catalog.length()) {
            val distro = catalog.optJSONObject(index) ?: continue
            val aliases = listOf(
                distro.optString("id"),
                distro.optString("name"),
                distro.optString("image"),
                distro.optString("label"),
            ).map { it.lowercase() }
            if (needle in aliases) {
                return distro
            }
        }
        return null
    }

    private fun distro(
        id: String,
        label: String,
        image: String,
        packageManager: String,
        profile: String,
        reason: String,
        name: String,
    ): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("label", label)
            .put("name", name)
            .put("image", image)
            .put("package_manager", packageManager)
            .put("profile", profile)
            .put("reason", reason)
            .put("source", PROOT_DISTRO_SOURCE_URL)
            .put("install_command", "proot-distro install --name $name $image")
            .put("run_command", "proot-distro login $name -- /bin/sh")
            .put("agent_command_hint", "After install, run Linux commands with proot-distro login $name -- <command>.")
    }

    private fun desktop(
        id: String,
        label: String,
        profile: String,
        reason: String,
    ): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("label", label)
            .put("profile", profile)
            .put("reason", reason)
    }

    private fun mirrorProfile(
        id: String,
        label: String,
        reason: String,
    ): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("label", label)
            .put("reason", reason)
    }
}
