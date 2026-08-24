package com.mobilefork.hermesagent.device

import android.content.Context

/**
 * Termux main-repo mirror list for read-only package discovery.
 * Host package activation is restricted to the signed APK baseline.
 * Mirrors match the build-time prepare_android_linux_assets fallthrough order.
 */
object HermesTermuxMirrorConfig {
    const val PREFS_NAME = "hermes_termux_pkg"
    const val KEY_MIRROR_PROFILE = "mirror_profile"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36 HermesAgent/pkg"

    private val DEFAULT_MIRRORS = listOf(
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://grimler.se/termux/termux-main",
        "https://packages.termux.dev/apt/termux-main",
        "https://termux.librehat.com/apt/termux-main",
        "https://mirror.rinarin.dev/termux/termux-main",
        "https://ftp.fau.de/termux/apt/termux-main",
    )

    private val CHINA_FIRST_MIRRORS = listOf(
        "https://mirror.iscas.ac.cn/termux/apt/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
        "https://mirrors.aliyun.com/termux/termux-packages-24",
    ) + DEFAULT_MIRRORS

    fun mirrorProfile(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MIRROR_PROFILE, "default")
            ?.trim()
            ?.lowercase()
            .orEmpty()
            .ifBlank { "default" }
    }

    fun setMirrorProfile(context: Context, profile: String) {
        val normalized = when (profile.trim().lowercase()) {
            "china", "cn", "domestic", "iscas", "tuna", "aliyun" -> "china"
            else -> "default"
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MIRROR_PROFILE, normalized)
            // This preference can be committed inside a chat request's atomic mutation gate.
            // Keep the disk write synchronous so it cannot be queued after Stop wins.
            .commit()
    }

    fun orderedBaseUrls(context: Context): List<String> {
        val profile = mirrorProfile(context)
        val bases = if (profile == "china") CHINA_FIRST_MIRRORS else DEFAULT_MIRRORS
        return bases.map { it.trimEnd('/') }.distinct()
    }

    fun packagesIndexPath(termuxArch: String): String =
        "dists/stable/main/binary-$termuxArch/Packages"

    fun url(baseUrl: String, relativePath: String): String =
        "${baseUrl.trimEnd('/')}/${relativePath.trimStart('/')}"
}
