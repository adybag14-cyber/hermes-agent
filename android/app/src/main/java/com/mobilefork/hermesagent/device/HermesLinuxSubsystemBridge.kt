package com.mobilefork.hermesagent.device

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val OWNED_SHELL_PROCESS_POLL_INTERVAL_MS = 10L

internal data class OwnedShellProcessWaitResult(
    val finishedWithinTimeout: Boolean,
    val waitFailure: Throwable?,
    val cleanupFailure: Throwable?,
    val interrupted: Boolean,
) {
    val processUnwindVerified: Boolean
        get() = finishedWithinTimeout || cleanupFailure == null
}

internal fun awaitOwnedShellProcess(
    current: NativeShellProcessStopHandle,
    waitTimeoutMs: Long,
    gracefulTimeoutMs: Long = 1_000L,
    forcedTimeoutMs: Long = 1_000L,
): OwnedShellProcessWaitResult {
    var interrupted = false
    var waitFailure: Throwable? = null
    val finishedWithinTimeout = try {
        waitForOwnedShellProcessExit(current, waitTimeoutMs)
    } catch (error: Throwable) {
        if (error is InterruptedException) {
            interrupted = true
        }
        waitFailure = error
        false
    }
    if (finishedWithinTimeout) {
        return OwnedShellProcessWaitResult(
            finishedWithinTimeout = true,
            waitFailure = null,
            cleanupFailure = null,
            interrupted = interrupted,
        )
    }

    val termination = NativeAndroidShellTool.terminateOwnedProcess(
        current = current,
        gracefulTimeoutMs = gracefulTimeoutMs,
        forcedTimeoutMs = forcedTimeoutMs,
    )
    interrupted = interrupted || termination.interrupted
    return OwnedShellProcessWaitResult(
        finishedWithinTimeout = false,
        waitFailure = waitFailure,
        cleanupFailure = termination.failure,
        interrupted = interrupted,
    )
}

private fun waitForOwnedShellProcessExit(
    current: NativeShellProcessStopHandle,
    timeoutMs: Long,
): Boolean {
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
    while (isOwnedShellProcessAlive(current)) {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return false
        Thread.sleep(ownedShellProcessPollSleepMillis(remainingNanos))
    }
    return true
}

private fun isOwnedShellProcessAlive(current: NativeShellProcessStopHandle): Boolean = try {
    current.exitValue()
    false
} catch (_: IllegalThreadStateException) {
    true
}

private fun ownedShellProcessPollSleepMillis(remainingNanos: Long): Long {
    val roundedUpMs = (remainingNanos + 999_999L) / 1_000_000L
    return roundedUpMs.coerceIn(1L, OWNED_SHELL_PROCESS_POLL_INTERVAL_MS)
}

internal fun ownedShellProcessStopHandle(process: Process): NativeShellProcessStopHandle {
    return object : NativeShellProcessStopHandle {
        override val supportsForceDestroy: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

        override fun exitValue(): Int = process.exitValue()

        override fun destroy() = process.destroy()

        override fun forceDestroy() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                destroyOwnedShellProcessForciblyApi26(process)
            } else {
                error("forced process termination requires Android 8.0 (API 26)")
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun destroyOwnedShellProcessForciblyApi26(process: Process) {
    process.destroyForcibly()
}

object HermesLinuxSubsystemBridge {
    private const val ASSET_ROOT = "hermes-linux"
    private const val STATE_FILE_NAME = "linux-subsystem-state.json"
    private const val EXECUTION_MODE = "embedded_termux"
    private const val SYSTEM_SHELL_MODE = "android_system_shell"
    private const val SYSTEM_SHELL_PATH = "/system/bin/sh"
    private const val RUNTIME_LAYOUT_VERSION = 8
    private const val EXPERIMENTAL_LLAMA_SERVER_LIBRARY = "libhermes_android_llama_server_experimental.so"
    private const val NATIVE_EXEC_ROOT_NAME = "native-exec"
    private const val NATIVE_COMMAND_ENV_NAME = "native-command-functions.sh"
    private const val PYTHON_BINARY_NAME = "python"
    private const val DIRECT_PROOT_ENV = "HERMES_ANDROID_PROOT_EXECUTABLE"
    private const val DIRECT_EXECUTION_MODE = "apk_native_library_direct"
    private const val NATIVE_EXECUTION_POLICY_VERSION = 1
    private const val PROOT_DIRECT_EXEC_EXPRESSION =
        "os.environ.get(\"HERMES_ANDROID_PROOT_EXECUTABLE\") or shutil.which(\"proot\") or \"proot\""
    private const val TAG = "HermesLinuxSubsystem"
    private const val MANAGED_PREFIX_REFRESH_MODE = "managed_overlay_preserve_mutable_state"
    private val ELF_MAGIC = byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
    private val DIRECT_FUNCTION_NAME = Regex("""[A-Za-z_][A-Za-z0-9_-]*""")
    private val DIRECT_NATIVE_EXECUTABLES = listOf(
        "bin/proot",
        "bin/python",
        "bin/qemu-aarch64",
        "bin/qemu-x86_64",
        "bin/curl",
        "bin/git",
    )
    private val SHELL_BUILTIN_NAMES = setOf(
        "bash",
        "sh",
        "command",
        "exec",
        "exit",
        "export",
        "printf",
        "pwd",
        "read",
        "source",
        "test",
        "true",
        "false",
    )
    private val NATIVE_EXECUTABLE_NAMES = mapOf(
        "bin/bash" to "libhermes_android_bash.so",
        "bin/llama-server" to "libhermes_android_llama_server.so",
        "bin/llama-server-bionic" to "libhermes_android_llama_server_bionic_spawn.so",
    )

    private data class ShellLaunchProbe(
        val ready: Boolean,
        val detail: String = "",
    )

    internal data class SignedHostPrefixRestoreResult(
        val success: Boolean,
        val restoredFileCount: Int = 0,
        val removedUntrustedFileCount: Int = 0,
        val error: String = "",
    )

    private data class InstalledRuntimeCache(
        val androidAbi: String,
        val assetFingerprint: String,
        val nativeLibraryDir: String,
        val layoutVersion: Int,
        val state: JSONObject,
    )

    @Volatile
    private var installedRuntimeCache: InstalledRuntimeCache? = null

    fun ensureInstalled(context: Context): JSONObject {
        val androidAbi = selectAndroidAbi()
        val currentAppVersionCode = appVersionCode(context)
        val currentAssetFingerprint = assetManifestSha256(context, androidAbi)
        val currentNativeLibraryDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        installedRuntimeCache?.let { cache ->
            if (
                cache.androidAbi == androidAbi &&
                cache.assetFingerprint == currentAssetFingerprint &&
                cache.nativeLibraryDir == currentNativeLibraryDir &&
                cache.layoutVersion == RUNTIME_LAYOUT_VERSION &&
                File(cache.state.optString("shell_path", cache.state.optString("bash_path"))).let {
                    it.path.startsWith("/system/") || (it.isFile && it.canExecute())
                }
            ) {
                return cache.state
            }
        }
        readState(context)?.let { state ->
            var stateChanged = false
            if (state.optString("android_abi") != androidAbi) {
                invalidateRuntimeState(
                    context,
                    "Stored ABI ${state.optString("android_abi")} is incompatible with current ABI $androidAbi.",
                )
                return@let
            }
            val storedShellPath = state.optString("shell_path", state.optString("bash_path"))
            val storedBashFile = File(state.optString("bash_path", storedShellPath))
            val prefixDirPath = state.optString("prefix_path").ifBlank {
                storedBashFile.parentFile?.parentFile?.absolutePath.orEmpty()
            }
            val expectedPrefixDir = File(context.filesDir, "hermes-home/linux/$androidAbi/prefix")
            val prefixDir = prefixDirPath
                .takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { candidate ->
                    runCatching { candidate.canonicalFile == expectedPrefixDir.canonicalFile }.getOrDefault(false)
                }
            if (prefixDir == null || !prefixDir.isDirectory) {
                invalidateRuntimeState(
                    context,
                    "Stored prefix path is missing or outside the app-managed $androidAbi runtime.",
                )
                return@let
            }
            if (state.optString("native_library_dir") != currentNativeLibraryDir) {
                refreshNativeRuntimePaths(context, androidAbi, state) ?: run {
                    invalidateRuntimeState(
                        context,
                        "APK native-library paths could not be refreshed for the existing $androidAbi prefix.",
                    )
                    return@let
                }
                stateChanged = true
            }
            if (refreshExperimentalLlamaServerPath(context, state)) {
                stateChanged = true
            }
            if (state.optInt("runtime_layout_version", 0) != RUNTIME_LAYOUT_VERSION) {
                state.put("runtime_layout_version", RUNTIME_LAYOUT_VERSION)
                stateChanged = true
            }
            if (state.optLong("app_version_code", -1L) != currentAppVersionCode) {
                state.put("app_version_code", currentAppVersionCode)
                stateChanged = true
            }
            val manifest = runCatching {
                JSONObject(readAssetText(context.assets, "$ASSET_ROOT/$androidAbi/manifest.json"))
            }.getOrNull()
            if (manifest != null) {
                if (state.optString("asset_manifest_sha256") != currentAssetFingerprint) {
                    runCatching {
                        refreshManagedPrefixAssets(
                            context = context,
                            androidAbi = androidAbi,
                            prefixDir = prefixDir,
                            manifest = manifest,
                            state = state,
                            currentAssetFingerprint = currentAssetFingerprint,
                            currentAppVersionCode = currentAppVersionCode,
                        )
                    }.onSuccess {
                        state.remove("asset_refresh_error")
                        stateChanged = true
                    }.onFailure { exc ->
                        // Never erase home, package state, or proot rootfs content merely
                        // because a new APK manifest could not be staged. Keep the previous
                        // fingerprint so a later ensureInstalled call retries the refresh.
                        state.put(
                            "asset_refresh_error",
                            "Managed APK asset refresh failed: ${exc.message ?: exc::class.java.simpleName}".take(1200),
                        )
                        stateChanged = true
                    }
                }
                val apkPackages = manifest.optJSONArray("packages") ?: JSONArray()
                if (state.optJSONArray("apk_packages")?.toString() != apkPackages.toString()) {
                    state.put("apk_packages", apkPackages)
                    stateChanged = true
                }
                if (refreshNativeExecutionRouting(context, state, prefixDir, manifest)) {
                    stateChanged = true
                }
            }
            if (refreshPythonRuntimePaths(prefixDir, state)) {
                stateChanged = true
            }
            val shellPath = state.optString("shell_path", state.optString("bash_path"))
            // A restored or OTA-updated prefix can leave an executable bit intact while
            // required shared libraries are missing. Probe once before caching the state
            // for this process so upgrades fall back or reinstall instead of selecting a
            // dynamically broken Termux shell.
            val shellReady = shellPath.startsWith("/system/") ||
                (File(shellPath).isFile && File(shellPath).canExecute())
            if (shellReady) {
                File(prefixDir, "home").mkdirs()
                File(prefixDir, "tmp").mkdirs()
                val homeDir = File(state.optString("home_path").ifBlank { prefixDir.absolutePath })
                val launchProbe = launchShellProbe(shellPath, homeDir, buildRunEnvironment(state))
                if (launchProbe.ready) {
                    val refreshedState = attachSandboxCatalog(state)
                    if (stateChanged) {
                        stateFile(context).writeText(refreshedState.toString(), Charsets.UTF_8)
                    }
                    installedRuntimeCache = InstalledRuntimeCache(
                        androidAbi = androidAbi,
                        assetFingerprint = state.optString("asset_manifest_sha256"),
                        nativeLibraryDir = currentNativeLibraryDir,
                        layoutVersion = RUNTIME_LAYOUT_VERSION,
                        state = refreshedState,
                    )
                    return refreshedState
                }
            }
            File(prefixDir, "home").mkdirs()
            File(prefixDir, "tmp").mkdirs()
            markExecutableTree(File(prefixDir, "bin"))
            markExecutableTree(File(prefixDir, "libexec"))
            val homeDir = File(state.optString("home_path").ifBlank { prefixDir.absolutePath })
            val retryProbe = launchShellProbe(shellPath, homeDir, buildRunEnvironment(state))
            if (retryProbe.ready) {
                val refreshedState = attachSandboxCatalog(state)
                runCatching { HermesTermuxPackageManager.seedStatusFromApkIfNeeded(context, refreshedState) }
                if (stateChanged) {
                    stateFile(context).writeText(refreshedState.toString(), Charsets.UTF_8)
                }
                installedRuntimeCache = InstalledRuntimeCache(
                    androidAbi = androidAbi,
                    assetFingerprint = state.optString("asset_manifest_sha256"),
                    nativeLibraryDir = currentNativeLibraryDir,
                    layoutVersion = RUNTIME_LAYOUT_VERSION,
                    state = refreshedState,
                )
                return refreshedState
            }
            invalidateRuntimeState(
                context,
                "Stored embedded shell failed launch verification: ${retryProbe.detail.take(600)}",
            )
        }

        val installRoot = File(context.filesDir, "hermes-home/linux/$androidAbi")
        val prefixDir = File(installRoot, "prefix")
        val state = runCatching {
            val manifest = JSONObject(readAssetText(context.assets, "$ASSET_ROOT/$androidAbi/manifest.json"))
            val embeddedState = JSONObject()
            refreshManagedPrefixAssets(
                context = context,
                androidAbi = androidAbi,
                prefixDir = prefixDir,
                manifest = manifest,
                state = embeddedState,
                currentAssetFingerprint = currentAssetFingerprint,
                currentAppVersionCode = currentAppVersionCode,
            )
            File(prefixDir, "home").mkdirs()
            File(prefixDir, "tmp").mkdirs()
            markExecutableTree(File(prefixDir, "bin"))
            markExecutableTree(File(prefixDir, "libexec"))

            val nativeExecRoot = recreateNativeExecutableShims(context, installRoot, prefixDir, manifest)
            val nativeBinDir = File(nativeExecRoot, "bin")
            val nativeLibexecDir = File(nativeExecRoot, "libexec")
            val prefixBashPath = File(prefixDir, "bin/bash").absolutePath
            val nativeBashPath = nativeExecutablePath(context, "libhermes_android_bash.so")
            val bashPath = nativeBashPath
                .takeIf { it.isNotBlank() && File(it).canExecute() }
                ?: prefixBashPath
            val llamaServerPath = nativeExecutablePath(context, "libhermes_android_llama_server.so")
            val bionicLlamaServerPath = nativeExecutablePath(context, "libhermes_android_llama_server_bionic_spawn.so")
            val experimentalLlamaServerPath = experimentalLlamaServerPath(context)
            val binPath = listOf(nativeBinDir, File(prefixDir, "bin"))
                .filter { it.isDirectory }
                .joinToString(":") { it.absolutePath }
            embeddedState.apply {
                put("enabled", true)
                put("runtime_layout_version", RUNTIME_LAYOUT_VERSION)
                put("app_version_code", currentAppVersionCode)
                put("asset_manifest_sha256", currentAssetFingerprint)
                put("execution_mode", EXECUTION_MODE)
                put("android_abi", androidAbi)
                put("termux_arch", manifest.optString("termux_arch"))
                put("uses_termux", true)
                put("prefix_path", prefixDir.absolutePath)
                put("shell_path", bashPath)
                put("bash_path", bashPath)
                put("prefix_bash_path", prefixBashPath)
                put("native_library_dir", context.applicationInfo.nativeLibraryDir.orEmpty())
                put("app_package_name", context.packageName)
                put("native_bash_path", nativeBashPath)
                put("native_llama_server_path", llamaServerPath)
                put("bionic_llama_server_path", bionicLlamaServerPath)
                put("experimental_llama_server_path", experimentalLlamaServerPath)
                put("native_bin_path", nativeBinDir.absolutePath)
                put("native_libexec_path", nativeLibexecDir.absolutePath)
                put("python_path", File(nativeBinDir, PYTHON_BINARY_NAME).absolutePath)
                put("python_lib_path", resolvePythonLibPath(prefixDir).absolutePath)
                put("bin_path", binPath.ifBlank { File(prefixDir, "bin").absolutePath })
                put("lib_path", File(prefixDir, "lib").absolutePath)
                put("home_path", File(prefixDir, "home").absolutePath)
                put("tmp_path", File(prefixDir, "tmp").absolutePath)
                put("root_packages", manifest.optJSONArray("root_packages"))
                put("packages", manifest.optJSONArray("packages"))
                put("apk_packages", manifest.optJSONArray("packages"))
            }
            refreshNativeExecutionRouting(context, embeddedState, prefixDir, manifest)
            val launchProbe = launchShellProbe(bashPath, File(prefixDir, "home"), buildRunEnvironment(embeddedState))
            if (launchProbe.ready) {
                val ready = attachSandboxCatalog(embeddedState)
                runCatching { HermesTermuxPackageManager.seedStatusFromApkIfNeeded(context, ready) }
                ready
            } else {
                systemShellState(
                    context = context,
                    androidAbi = androidAbi,
                    appVersionCode = currentAppVersionCode,
                    assetManifestSha256 = currentAssetFingerprint,
                    fallbackReason = launchProbe.detail,
                )
            }
        }.getOrElse { exc ->
            systemShellState(
                context = context,
                androidAbi = androidAbi,
                appVersionCode = currentAppVersionCode,
                assetManifestSha256 = currentAssetFingerprint,
                fallbackReason = "Embedded Linux assets unavailable: ${exc.message ?: exc::class.java.simpleName}",
            )
        }
        stateFile(context).apply {
            parentFile?.mkdirs()
            writeText(state.toString(), Charsets.UTF_8)
        }
        installedRuntimeCache = InstalledRuntimeCache(
            androidAbi = androidAbi,
            assetFingerprint = currentAssetFingerprint,
            nativeLibraryDir = currentNativeLibraryDir,
            layoutVersion = RUNTIME_LAYOUT_VERSION,
            state = state,
        )
        return state
    }

    fun readState(context: Context): JSONObject? {
        val stateFile = stateFile(context)
        val snapshot = readStateSnapshot(context)
        if (snapshot != null) {
            return snapshot
        }
        // Manual/background setup retains the historical cleanup behavior. Chat-owned package
        // status uses readStateSnapshot directly and therefore never mutates while observing.
        if (stateFile.isFile) {
            stateFile.delete()
        }
        return null
    }

    internal fun readStateSnapshot(context: Context): JSONObject? {
        val stateFile = stateFile(context)
        if (!stateFile.isFile) {
            return null
        }
        val rawState = stateFile.readText(Charsets.UTF_8).trim()
        if (rawState.isBlank()) {
            return null
        }
        return runCatching { JSONObject(rawState) }.getOrNull()
    }

    /**
     * Explicit user-requested reset. This is intentionally destructive and is
     * not used for ordinary APK upgrades or manifest drift.
     */
    fun reset(context: Context) {
        installedRuntimeCache = null
        File(context.filesDir, "hermes-home/linux").deleteRecursively()
        File(context.filesDir, "hermes-home/native-shell").deleteRecursively()
    }

    /**
     * Restore the host prefix from files protected by the APK signature.
     *
     * This is a one-time migration for prefixes which older Hermes releases may
     * have overlaid from live Termux mirrors. Guest distro roots and home data are
     * left in place; only manifest-owned host files/links and explicitly tracked
     * legacy package files are touched.
     */
    internal fun restoreSignedApkHostPrefix(
        context: Context,
        state: JSONObject,
        legacyTrackedFiles: Collection<String>,
    ): SignedHostPrefixRestoreResult {
        val app = context.applicationContext
        val androidAbi = state.optString("android_abi")
        if (androidAbi.isBlank()) {
            return SignedHostPrefixRestoreResult(false, error = "android ABI is missing")
        }
        val expectedPrefix = File(app.filesDir, "hermes-home/linux/$androidAbi/prefix")
        val prefix = File(state.optString("prefix_path"))
        val expectedCanonical = runCatching { expectedPrefix.canonicalFile }.getOrNull()
        val prefixCanonical = runCatching { prefix.canonicalFile }.getOrNull()
        if (expectedCanonical == null || prefixCanonical == null || expectedCanonical != prefixCanonical) {
            return SignedHostPrefixRestoreResult(false, error = "prefix is outside the APK-managed runtime")
        }
        val temporaryFiles = mutableListOf<File>()
        return try {
            val assetBase = "$ASSET_ROOT/$androidAbi"
            val manifest = JSONObject(readAssetText(app.assets, "$assetBase/manifest.json"))
            val files = manifest.optJSONArray("files")
                ?: throw IllegalStateException("signed APK manifest has no files array")
            if (files.length() == 0) {
                throw IllegalStateException("signed APK manifest file authority is empty")
            }
            val signedFiles = linkedSetOf<String>()
            for (index in 0 until files.length()) {
                val raw = files.optString(index)
                val relative = normalizeAssetRelativePath(raw)
                if (relative.isBlank() || relative != raw) {
                    throw IllegalStateException("unsafe signed APK file path at index $index")
                }
                if (!signedFiles.add(relative)) {
                    throw IllegalStateException("duplicate signed APK file path: $relative")
                }
            }
            val signedLinks = linkedSetOf<String>()
            val links = manifest.optJSONArray("links") ?: JSONArray()
            for (index in 0 until links.length()) {
                val item = links.optJSONObject(index)
                    ?: throw IllegalStateException("invalid signed APK link at index $index")
                val rawPath = item.optString("path")
                val rawTarget = item.optString("target")
                val linkPath = normalizeAssetRelativePath(rawPath)
                val targetPath = normalizeAssetRelativePath(rawTarget)
                if (
                    linkPath.isBlank() || targetPath.isBlank() ||
                    linkPath != rawPath || targetPath != rawTarget
                ) {
                    throw IllegalStateException("unsafe signed APK link at index $index")
                }
                if (!signedLinks.add(linkPath)) {
                    throw IllegalStateException("duplicate signed APK link path: $linkPath")
                }
            }
            val conflictingPaths = signedFiles.intersect(signedLinks)
            if (conflictingPaths.isNotEmpty()) {
                throw IllegalStateException("signed APK file/link conflict: ${conflictingPaths.first()}")
            }
            var removedUntrusted = 0
            legacyTrackedFiles.distinct().forEach { raw ->
                val relative = normalizeAssetRelativePath(raw)
                if (relative.isBlank() || relative != raw || relative in signedFiles || relative in signedLinks) {
                    return@forEach
                }
                val destination = safeRestoreDestination(prefixCanonical, relative)
                if (destination.exists() || isSymbolicLinkCompat(destination)) {
                    if (destination.isDirectory && !isSymbolicLinkCompat(destination)) {
                        throw IllegalStateException("refusing to recursively remove legacy directory: $relative")
                    } else {
                        destination.delete()
                    }
                    removedUntrusted += 1
                }
            }
            signedFiles.forEachIndexed { index, relative ->
                val destination = safeRestoreDestination(prefixCanonical, relative)
                destination.parentFile?.mkdirs()
                val temporary = File(destination.parentFile, ".${destination.name}.apk-restore-$index.tmp")
                temporaryFiles.add(temporary)
                if (temporary.exists() || isSymbolicLinkCompat(temporary)) {
                    if (isSymbolicLinkCompat(temporary) || temporary.isFile) {
                        temporary.delete()
                    } else {
                        throw IllegalStateException("restore temporary path is not a file: $relative")
                    }
                }
                app.assets.open("$assetBase/prefix/$relative").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (destination.exists() || isSymbolicLinkCompat(destination)) {
                    if (destination.isDirectory && !isSymbolicLinkCompat(destination)) {
                        throw IllegalStateException("signed APK file path is unexpectedly a directory: $relative")
                    } else {
                        destination.delete()
                    }
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
            }
            signedLinks.forEach { relative ->
                val destination = safeRestoreDestination(prefixCanonical, relative)
                if (destination.exists() || isSymbolicLinkCompat(destination)) {
                    if (destination.isDirectory && !isSymbolicLinkCompat(destination)) {
                        throw IllegalStateException("signed APK link path is unexpectedly a directory: $relative")
                    } else {
                        destination.delete()
                    }
                }
            }
            repeat(4) { recreateLinks(prefixCanonical, manifest) }
            markExecutableTree(File(prefixCanonical, "bin"))
            markExecutableTree(File(prefixCanonical, "libexec"))
            refreshNativeExecutionRouting(app, state, prefixCanonical, manifest)
            refreshPythonRuntimePaths(prefixCanonical, state)
            stateFile(app).apply {
                parentFile?.mkdirs()
                writeText(state.toString(), Charsets.UTF_8)
            }
            SignedHostPrefixRestoreResult(
                success = true,
                restoredFileCount = signedFiles.size,
                removedUntrustedFileCount = removedUntrusted,
            )
        } catch (exc: Exception) {
            SignedHostPrefixRestoreResult(
                success = false,
                error = exc.message ?: exc.javaClass.simpleName,
            )
        } finally {
            temporaryFiles.forEach { temporary ->
                if (temporary.exists() || isSymbolicLinkCompat(temporary)) {
                    if (isSymbolicLinkCompat(temporary) || temporary.isFile) {
                        temporary.delete()
                    }
                }
            }
        }
    }

    private fun safeRestoreDestination(prefixDir: File, relativePath: String): File {
        val normalized = normalizeAssetRelativePath(relativePath)
        require(normalized.isNotBlank() && normalized == relativePath) {
            "unsafe host-prefix path: $relativePath"
        }
        val parts = normalized.split('/')
        var parent = prefixDir
        parts.dropLast(1).forEach { part ->
            val candidate = File(parent, part)
            if (isSymbolicLinkCompat(candidate)) {
                require(candidate.delete()) { "failed to remove symlinked prefix directory: $normalized" }
            }
            if (candidate.exists() && !candidate.isDirectory) {
                require(candidate.delete()) { "failed to replace prefix parent: $normalized" }
            }
            if (!candidate.exists()) {
                require(candidate.mkdir()) { "failed to create prefix parent: $normalized" }
            }
            parent = candidate
        }
        return File(parent, parts.last())
    }

    private fun invalidateRuntimeState(context: Context, reason: String) {
        installedRuntimeCache = null
        val filesDir = context.filesDir
        val linuxRoot = File(filesDir, "hermes-home/linux")
        val stateFile = stateFile(context)
        if (stateFile.exists() && !stateFile.delete()) {
            Log.w(TAG, "Could not delete incompatible runtime state file: ${stateFile.absolutePath}")
        }
        File(filesDir, "hermes-home/native-shell").deleteRecursively()
        linuxRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .forEach { installRoot ->
                // Prefix assets and native-exec shims are replaceable APK-managed
                // runtime material. Preserve only the mutable proot/user subtrees
                // until a compatible ABI can deliberately migrate or export them.
                File(installRoot, "native-exec").deleteRecursively()
                File(installRoot, NATIVE_COMMAND_ENV_NAME).delete()
            }
        Log.w(TAG, "Invalidated incompatible Hermes runtime state without deleting user sandboxes: $reason")
    }

    private fun refreshManagedPrefixAssets(
        context: Context,
        androidAbi: String,
        prefixDir: File,
        manifest: JSONObject,
        state: JSONObject,
        currentAssetFingerprint: String,
        currentAppVersionCode: Long,
    ) {
        val preservedCount = refreshManagedPrefixFiles(prefixDir) {
            copyAssetFiles(
                context.assets,
                "$ASSET_ROOT/$androidAbi/prefix",
                prefixDir,
                manifest,
            )
        }
        recreateLinks(prefixDir, manifest)
        markExecutableTree(File(prefixDir, "bin"))
        markExecutableTree(File(prefixDir, "libexec"))
        state
            .put("runtime_layout_version", RUNTIME_LAYOUT_VERSION)
            .put("app_version_code", currentAppVersionCode)
            .put("asset_manifest_sha256", currentAssetFingerprint)
            .put("asset_refresh_mode", MANAGED_PREFIX_REFRESH_MODE)
            .put("asset_refresh_preserved_mutable_entries", preservedCount)
            .put("prefix_path", prefixDir.absolutePath)
            .put("home_path", File(prefixDir, "home").absolutePath)
            .put("tmp_path", File(prefixDir, "tmp").absolutePath)
            .put("root_packages", manifest.optJSONArray("root_packages") ?: JSONArray())
            .put("apk_packages", manifest.optJSONArray("packages") ?: JSONArray())
        // Native shims are rebuilt by refreshNativeExecutionRouting immediately
        // after this overlay. Mutable home, package database, and rootfs trees
        // are never removed here.
    }

    internal fun refreshManagedPrefixFixture(
        prefixDir: File,
        managedFiles: Map<String, ByteArray>,
        oldManifestSha256: String,
        newManifestSha256: String,
        oldLayoutVersion: Int,
        newLayoutVersion: Int = RUNTIME_LAYOUT_VERSION,
    ): JSONObject {
        val preservedCount = refreshManagedPrefixFiles(prefixDir) {
            managedFiles.forEach { (relativePath, bytes) ->
                val normalized = normalizeAssetRelativePath(relativePath)
                require(normalized.isNotBlank()) { "Managed fixture path is invalid: $relativePath" }
                File(prefixDir, normalized).apply {
                    parentFile?.mkdirs()
                    writeBytes(bytes)
                }
            }
        }
        return JSONObject()
            .put("previous_asset_manifest_sha256", oldManifestSha256)
            .put("asset_manifest_sha256", newManifestSha256)
            .put("previous_runtime_layout_version", oldLayoutVersion)
            .put("runtime_layout_version", newLayoutVersion)
            .put("asset_refresh_mode", MANAGED_PREFIX_REFRESH_MODE)
            .put("asset_refresh_preserved_mutable_entries", preservedCount)
    }

    private fun refreshManagedPrefixFiles(prefixDir: File, writer: () -> Unit): Int {
        val preservedBefore = mutableRuntimeSentinels(prefixDir)
        writer()
        val preservedAfter = mutableRuntimeSentinels(prefixDir)
        check(preservedBefore == preservedAfter) {
            "Managed prefix refresh changed a mutable user or sandbox sentinel."
        }
        return preservedAfter.size
    }

    private fun mutableRuntimeSentinels(prefixDir: File): Map<String, Pair<Long, Long>> {
        val installRoot = prefixDir.parentFile
        val mutableRoots = listOfNotNull(
            "prefix/home" to File(prefixDir, "home"),
            installRoot?.let { "install-root/var/lib/hermes-pkg" to File(it, "var/lib/hermes-pkg") },
            "prefix/var/lib/proot-distro/cache" to File(prefixDir, "var/lib/proot-distro/cache"),
            "prefix/var/lib/proot-distro/containers" to File(prefixDir, "var/lib/proot-distro/containers"),
        )
        return buildMap {
            mutableRoots.forEach { (label, root) ->
                if (!root.exists()) return@forEach
                root.walkTopDown().forEach { entry ->
                    val suffix = entry.relativeTo(root).invariantSeparatorsPath
                    val sentinelPath = label + suffix.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
                    put(sentinelPath, entry.length() to entry.lastModified())
                }
            }
        }
    }

    private fun refreshNativeExecutionRouting(
        context: Context,
        state: JSONObject,
        prefixDir: File,
        manifest: JSONObject,
    ): Boolean {
        val installRoot = prefixDir.parentFile ?: return false
        val linkMap = manifestLinkMap(manifest)
        var nativeExecRoot = File(installRoot, NATIVE_EXEC_ROOT_NAME)
        val directPaths = DIRECT_NATIVE_EXECUTABLES.associateWith { relativePath ->
            directNativeExecutablePath(context, relativePath, linkMap)
        }
        val legacyPolicy = state.optInt("native_execution_policy_version", 0) < NATIVE_EXECUTION_POLICY_VERSION
        val criticalShimMismatch = directPaths.any { (relativePath, directPath) ->
            directPath.isNotBlank() && !shimResolvesTo(File(nativeExecRoot, relativePath), File(directPath))
        }
        var changed = false
        if (legacyPolicy || criticalShimMismatch) {
            // This removes any legacy OTA symlink which targets writable app data and
            // recreates only links to immutable, package-manager-extracted APK libraries.
            nativeExecRoot = recreateNativeExecutableShims(context, installRoot, prefixDir, manifest)
            changed = true
        }

        val nativeBinDir = File(nativeExecRoot, "bin")
        val nativeLibexecDir = File(nativeExecRoot, "libexec")
        val directPython = directPaths.getValue("bin/python")
        val directProot = directPaths.getValue("bin/proot")
        val commandEnvironment = writeNativeCommandEnvironment(context, installRoot, manifest, linkMap)
        val prootPatchReady = isProotDistroDirectExecutionReady(prefixDir)

        fun putIfChanged(key: String, value: Any) {
            if (state.opt(key) != value) {
                state.put(key, value)
                changed = true
            }
        }

        putIfChanged("native_execution_policy_version", NATIVE_EXECUTION_POLICY_VERSION)
        putIfChanged("native_execution_route", DIRECT_EXECUTION_MODE)
        putIfChanged("native_bin_path", nativeBinDir.absolutePath)
        putIfChanged("native_libexec_path", nativeLibexecDir.absolutePath)
        putIfChanged(
            "bin_path",
            listOf(nativeBinDir, File(prefixDir, "bin"))
                .filter { it.isDirectory }
                .joinToString(":") { it.absolutePath },
        )
        putIfChanged("native_python_path", directPython)
        putIfChanged("native_proot_path", directProot)
        putIfChanged("native_qemu_aarch64_path", directPaths.getValue("bin/qemu-aarch64"))
        putIfChanged("native_qemu_x86_64_path", directPaths.getValue("bin/qemu-x86_64"))
        putIfChanged("python_path", directPython.ifBlank { File(nativeBinDir, PYTHON_BINARY_NAME).absolutePath })
        putIfChanged("native_command_env_path", commandEnvironment.first.absolutePath)
        putIfChanged("native_direct_command_count", commandEnvironment.second)
        putIfChanged("proot_direct_exec_patch_ready", prootPatchReady)
        putIfChanged(
            "android_exec_policy",
            "Downloaded ELF remains data-only; executable dispatch uses APK native libraries.",
        )
        return changed
    }

    private fun directNativeExecutablePath(
        context: Context,
        relativePath: String,
        linkMap: Map<String, String>,
    ): String {
        val targetRelativePath = resolveNativeExecutableTarget(relativePath, linkMap)
        val direct = File(nativeExecutablePath(context, nativeExecutableName(targetRelativePath)))
        return direct.absolutePath.takeIf { direct.isFile && direct.canExecute() }.orEmpty()
    }

    private fun shimResolvesTo(shim: File, directTarget: File): Boolean {
        if (!shim.exists() || !directTarget.isFile) return false
        return runCatching { shim.canonicalFile == directTarget.canonicalFile }.getOrDefault(false)
    }

    private fun writeNativeCommandEnvironment(
        context: Context,
        installRoot: File,
        manifest: JSONObject,
        linkMap: Map<String, String>,
    ): Pair<File, Int> {
        val nativeExecRoot = File(installRoot, NATIVE_EXEC_ROOT_NAME)
        val candidates = linkedSetOf<String>()
        val files = manifest.optJSONArray("files") ?: JSONArray()
        for (index in 0 until files.length()) {
            normalizeAssetRelativePath(files.optString(index))
                .takeIf { it.startsWith("bin/") }
                ?.let(candidates::add)
        }
        linkMap.keys.filterTo(candidates) { it.startsWith("bin/") }

        val functions = linkedMapOf<String, String>()
        candidates.sorted().forEach { relativePath ->
            val commandName = relativePath.substringAfterLast('/')
            if (!DIRECT_FUNCTION_NAME.matches(commandName) || commandName in SHELL_BUILTIN_NAMES) return@forEach
            val directTargetPath = directNativeExecutablePath(context, relativePath, linkMap)
            val directTarget = File(directTargetPath)
            val trustedShim = File(nativeExecRoot, relativePath)
            if (
                directTargetPath.isBlank() ||
                !isElfFile(directTarget) ||
                !shimResolvesTo(trustedShim, directTarget)
            ) {
                return@forEach
            }
            // Invoke through the trusted symlink so multicall tools such as
            // coreutils retain the requested applet name in argv[0]. The
            // symlink resolves to immutable APK-native code, never a writable
            // prefix ELF.
            functions.putIfAbsent(commandName, trustedShim.absolutePath)
        }
        val content = buildString {
            append("# Generated by Hermes. Source this file; do not execute it.\n")
            append("# Android writable app data is not an executable-code location.\n")
            functions.forEach { (commandName, target) ->
                append("function ")
                append(commandName)
                append(" { command ")
                append(shellQuote(target))
                append(" \"\$@\"; }\n")
            }
        }
        val environmentFile = File(installRoot, NATIVE_COMMAND_ENV_NAME)
        environmentFile.parentFile?.mkdirs()
        if (!environmentFile.isFile || environmentFile.readText(Charsets.UTF_8) != content) {
            environmentFile.writeText(content, Charsets.UTF_8)
        }
        environmentFile.setReadable(true, true)
        environmentFile.setExecutable(false, false)
        return environmentFile to functions.size
    }

    private fun isElfFile(file: File): Boolean {
        if (!file.isFile || file.length() < ELF_MAGIC.size) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(ELF_MAGIC.size)
                input.read(header) == header.size && header.contentEquals(ELF_MAGIC)
            }
        }.getOrDefault(false)
    }

    private fun isProotDistroDirectExecutionReady(prefixDir: File): Boolean {
        val pythonLibDir = resolvePythonLibPath(prefixDir)
        val relativeModules = listOf(
            "site-packages/proot_distro/commands/login/__init__.py",
            "site-packages/proot_distro/helpers/build_engine/run_step.py",
        )
        return relativeModules.all { relativeModule ->
            val module = File(pythonLibDir, relativeModule)
            module.isFile && runCatching {
                module.readText(Charsets.UTF_8).countOccurrences(PROOT_DIRECT_EXEC_EXPRESSION) == 1
            }.getOrDefault(false)
        }
    }

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var offset = 0
        while (true) {
            val match = indexOf(needle, offset)
            if (match < 0) return count
            count += 1
            offset = match + needle.length
        }
    }

    /** Merge OTA package DB versions into subsystem state for diagnostics/UI. */
    fun refreshPackageStateAfterOta(context: Context, state: JSONObject, pkgStatusDb: JSONObject) {
        val otaPackages = pkgStatusDb.optJSONObject("packages") ?: JSONObject()
        val deferredPackages = pkgStatusDb.optJSONObject("deferred_packages") ?: JSONObject()
        val existing = state.optJSONArray("apk_packages")
            ?: state.optJSONArray("packages")
            ?: JSONArray()
        val byName = linkedMapOf<String, JSONObject>()
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isNotBlank()) byName[name] = item
        }
        val names = otaPackages.keys()
        while (names.hasNext()) {
            val name = names.next()
            val row = otaPackages.optJSONObject(name) ?: continue
            val prev = byName[name] ?: JSONObject().put("name", name)
            prev.put("version", row.optString("version"))
            prev.put("filename", row.optString("filename"))
            prev.put("sha256", row.optString("sha256"))
            prev.put("source", row.optString("source"))
            prev.put("active_version", row.optString("active_version", row.optString("version")))
            prev.put("activation", row.optString("activation", "active_apk_baseline"))
            if (row.has("depends")) {
                prev.put("depends", row.optJSONArray("depends"))
            }
            byName[name] = prev
        }
        val merged = JSONArray()
        byName.values.forEach { merged.put(it) }
        val deferred = JSONArray()
        var deferredNativeCount = 0
        deferredPackages.keys().asSequence().toList().sorted().forEach { name ->
            val row = deferredPackages.optJSONObject(name) ?: return@forEach
            val nativeFiles = row.optJSONArray("native_code_files") ?: JSONArray()
            if (nativeFiles.length() > 0) deferredNativeCount += 1
            deferred.put(JSONObject(row.toString()).put("name", name))
        }
        state.put("packages", merged)
        state.put("host_pkg_installed_count", otaPackages.length())
        state.put("host_pkg_active_count", otaPackages.length())
        state.put("host_pkg_deferred_count", deferredPackages.length())
        state.put("host_pkg_deferred_native_count", deferredNativeCount)
        state.put("host_pkg_deferred_packages", deferred)
        state.put("ota_native_execution_policy", "signed_apk_required")
        state.put("host_pkg_enabled", true)
        stateFile(context).apply {
            parentFile?.mkdirs()
            writeText(state.toString(), Charsets.UTF_8)
        }
    }

    private fun refreshNativeRuntimePaths(context: Context, androidAbi: String, state: JSONObject): JSONObject? {
        val prefixDir = File(state.optString("prefix_path")).takeIf { it.isDirectory } ?: return null
        val installRoot = prefixDir.parentFile ?: return null
        val manifest = runCatching {
            JSONObject(readAssetText(context.assets, "$ASSET_ROOT/$androidAbi/manifest.json"))
        }.getOrNull() ?: return null
        val nativeExecRoot = recreateNativeExecutableShims(context, installRoot, prefixDir, manifest)
        val nativeBinDir = File(nativeExecRoot, "bin")
        val nativeLibexecDir = File(nativeExecRoot, "libexec")
        val prefixBashPath = File(prefixDir, "bin/bash").absolutePath
        val nativeBashPath = nativeExecutablePath(context, "libhermes_android_bash.so")
        val bashPath = nativeBashPath
            .takeIf { it.isNotBlank() && File(it).canExecute() }
            ?: prefixBashPath
        val llamaServerPath = nativeExecutablePath(context, "libhermes_android_llama_server.so")
        val bionicLlamaServerPath = nativeExecutablePath(context, "libhermes_android_llama_server_bionic_spawn.so")
        val experimentalLlamaServerPath = experimentalLlamaServerPath(context)
        val binPath = listOf(nativeBinDir, File(prefixDir, "bin"))
            .filter { it.isDirectory }
            .joinToString(":") { it.absolutePath }

        val refreshed = state
            .put("shell_path", bashPath)
            .put("bash_path", bashPath)
            .put("prefix_bash_path", prefixBashPath)
            .put("native_library_dir", context.applicationInfo.nativeLibraryDir.orEmpty())
            .put("app_package_name", context.packageName)
            .put("native_bash_path", nativeBashPath)
            .put("native_llama_server_path", llamaServerPath)
            .put("bionic_llama_server_path", bionicLlamaServerPath)
            .put("experimental_llama_server_path", experimentalLlamaServerPath)
            .put("native_bin_path", nativeBinDir.absolutePath)
            .put("native_libexec_path", nativeLibexecDir.absolutePath)
            .put("python_path", File(nativeBinDir, PYTHON_BINARY_NAME).absolutePath)
            .put("python_lib_path", resolvePythonLibPath(prefixDir).absolutePath)
            .put("bin_path", binPath.ifBlank { File(prefixDir, "bin").absolutePath })
            .put("lib_path", File(prefixDir, "lib").absolutePath)
            .put("home_path", File(prefixDir, "home").absolutePath)
            .put("tmp_path", File(prefixDir, "tmp").absolutePath)
            .put("root_packages", manifest.optJSONArray("root_packages"))
            .put("packages", manifest.optJSONArray("packages"))
            .put("apk_packages", manifest.optJSONArray("packages"))
        refreshNativeExecutionRouting(context, refreshed, prefixDir, manifest)
        return refreshed
    }

    private fun refreshPythonRuntimePaths(prefixDir: File, state: JSONObject): Boolean {
        val installRoot = prefixDir.parentFile ?: return false
        val directPython = state.optString("native_python_path")
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile && it.canExecute() }
        val pythonPath = directPython ?: File(File(installRoot, NATIVE_EXEC_ROOT_NAME), "bin/$PYTHON_BINARY_NAME")
        if (!pythonPath.isFile || !pythonPath.canExecute()) return false
        val pythonLibPath = resolvePythonLibPath(prefixDir)
        var changed = false
        if (state.optString("python_path") != pythonPath.absolutePath) {
            state.put("python_path", pythonPath.absolutePath)
            changed = true
        }
        if (state.optString("python_lib_path") != pythonLibPath.absolutePath) {
            state.put("python_lib_path", pythonLibPath.absolutePath)
            changed = true
        }
        return changed
    }

    fun buildRunEnvironment(state: JSONObject): Map<String, String> {
        val prefixPath = state.optString("prefix_path")
        val binPath = state.optString("bin_path")
        val libPath = state.optString("lib_path")
        val nativeLibexecPath = state.optString("native_libexec_path")
        val nativeLibraryDir = state.optString("native_library_dir")
        val appPackageName = state.optString("app_package_name").ifBlank { "com.nousresearch.hermesagent" }
        val nativeExecutableDir = state.optString("shell_path")
            .takeUnless { it.startsWith("/system/") }
            ?.let { File(it).parent.orEmpty() }
            .orEmpty()
        val homePath = state.optString("home_path").ifBlank { prefixPath }
        val tmpPath = state.optString("tmp_path").ifBlank { homePath.ifBlank { prefixPath } }
        val pythonLibPath = state.optString("python_lib_path").ifBlank {
            resolvePythonLibPath(File(prefixPath)).absolutePath
        }
        val prootLoaderPath = shellPathUnder(prefixPath, "libexec/proot/loader")
        val prootLoader32Path = shellPathUnder(prefixPath, "libexec/proot/loader32")
        return mapOf(
            "PREFIX" to prefixPath,
            "TERMUX_PREFIX" to prefixPath,
            "TERMUX_APP__PACKAGE_NAME" to appPackageName,
            "TERMUX_APP__APP_VERSION_NAME" to "Hermes",
            "TERMUX_VERSION" to "Hermes",
            "TERMUX__PREFIX" to prefixPath,
            "TERMUX__HOME" to homePath,
            "PATH" to listOf(binPath, "/system/bin", "/system/xbin", System.getenv("PATH").orEmpty())
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(":"),
            "LD_LIBRARY_PATH" to listOf(libPath, nativeExecutableDir, nativeLibraryDir, System.getenv("LD_LIBRARY_PATH").orEmpty())
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(":"),
            "HOME" to homePath,
            "TMPDIR" to tmpPath,
            "PROOT_TMP_DIR" to tmpPath,
            "PROOT_LOADER" to prootLoaderPath,
            "PROOT_LOADER_32" to prootLoader32Path,
            "PROOT_NO_SECCOMP" to "1",
            DIRECT_PROOT_ENV to state.optString("native_proot_path"),
            "HERMES_ANDROID_NATIVE_COMMAND_ENV" to state.optString("native_command_env_path"),
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "HERMES_ANDROID_EXECUTION_MODE" to state.optString("execution_mode"),
            "HERMES_ANDROID_SHELL" to SYSTEM_SHELL_PATH,
            "HERMES_ANDROID_NATIVE_SHELL" to state.optString("shell_path"),
            "HERMES_ANDROID_LINUX_BASH" to state.optString("shell_path").ifBlank { SYSTEM_SHELL_PATH },
            "HERMES_ANDROID_LINUX_NATIVE_BASH" to state.optString("shell_path"),
            "HERMES_ANDROID_LINUX_PYTHON" to state.optString("python_path").ifBlank { PYTHON_BINARY_NAME },
            "PYTHONHOME" to prefixPath,
            "PYTHONPATH" to listOf(
                pythonLibPath,
                File(pythonLibPath, "site-packages").absolutePath,
            ).joinToString(":"),
            "SSL_CERT_FILE" to File(prefixPath, "etc/tls/cert.pem").absolutePath,
            "REQUESTS_CA_BUNDLE" to File(prefixPath, "etc/tls/cert.pem").absolutePath,
            "CURL_CA_BUNDLE" to File(prefixPath, "etc/tls/cert.pem").absolutePath,
            "GIT_EXEC_PATH" to nativeLibexecPath
                .takeIf { it.isNotBlank() }
                ?.let { File(it, "git-core").absolutePath }
                .orEmpty(),
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
        )
    }

    private fun stateFile(context: Context): File {
        return File(context.filesDir, "hermes-home/linux/$STATE_FILE_NAME")
    }

    private fun systemShellState(
        context: Context,
        androidAbi: String,
        appVersionCode: Long,
        assetManifestSha256: String,
        fallbackReason: String,
    ): JSONObject {
        val manifest = runCatching {
            JSONObject(readAssetText(context.assets, "$ASSET_ROOT/$androidAbi/manifest.json"))
        }.getOrNull()
        val nativeRoot = File(context.filesDir, "hermes-home/native-shell")
        val homeDir = File(nativeRoot, "home").apply { mkdirs() }
        val tmpDir = File(nativeRoot, "tmp").apply { mkdirs() }
        return JSONObject().apply {
            put("enabled", true)
            put("runtime_layout_version", RUNTIME_LAYOUT_VERSION)
            put("app_version_code", appVersionCode)
            put("asset_manifest_sha256", assetManifestSha256)
            put("execution_mode", SYSTEM_SHELL_MODE)
            put("android_abi", androidAbi)
            put("termux_arch", manifest?.optString("termux_arch").orEmpty().ifBlank { androidAbi })
            put("uses_termux", false)
            put("prefix_path", nativeRoot.absolutePath)
            put("shell_path", SYSTEM_SHELL_PATH)
            put("bash_path", SYSTEM_SHELL_PATH)
            put("native_library_dir", context.applicationInfo.nativeLibraryDir.orEmpty())
            put("app_package_name", context.packageName)
            put("native_bash_path", nativeExecutablePath(context, "libhermes_android_bash.so"))
            put("native_llama_server_path", nativeExecutablePath(context, "libhermes_android_llama_server.so"))
            put("bionic_llama_server_path", nativeExecutablePath(context, "libhermes_android_llama_server_bionic_spawn.so"))
            put("experimental_llama_server_path", experimentalLlamaServerPath(context))
            put("python_path", "")
            put("bin_path", "/system/bin")
            put("lib_path", "")
            put("home_path", homeDir.absolutePath)
            put("tmp_path", tmpDir.absolutePath)
            put("root_packages", manifest?.optJSONArray("root_packages") ?: JSONArray())
            put("packages", manifest?.optJSONArray("packages") ?: JSONArray())
            put("apk_packages", manifest?.optJSONArray("packages") ?: JSONArray())
            attachSandboxCatalog(this)
            put("fallback_reason", fallbackReason.take(1200))
        }
    }

    private fun attachSandboxCatalog(state: JSONObject): JSONObject {
        return state
            .put("downloadable_linux_sandboxes", HermesLinuxSandboxCatalog.distroCatalog())
            .put("recommended_linux_sandboxes", HermesLinuxSandboxCatalog.recommendedSandboxIds())
            .put("desktop_environment_catalog", HermesLinuxSandboxCatalog.desktopCatalog())
            .put("linux_sandbox_agent_summary", HermesLinuxSandboxCatalog.agentSummary())
    }

    fun commandWithEmbeddedToolAliases(state: JSONObject, command: String): String {
        if (!state.optBoolean("uses_termux", false)) {
            return command
        }
        val prefixPath = state.optString("prefix_path")
        if (prefixPath.isBlank()) {
            return command
        }
        val homePath = state.optString("home_path").ifBlank { File(prefixPath, "home").absolutePath }
        val tmpPath = state.optString("tmp_path").ifBlank { File(prefixPath, "tmp").absolutePath }
        val appPackageName = state.optString("app_package_name").ifBlank { "com.nousresearch.hermesagent" }
        val pythonPath = state.optString("python_path").ifBlank {
            File(prefixPath, "bin/$PYTHON_BINARY_NAME").absolutePath
        }
        val directProotPath = state.optString("native_proot_path")
        val nativeCommandEnvironment = state.optString("native_command_env_path")
        val prootLoaderPath = shellPathUnder(prefixPath, "libexec/proot/loader")
        val prootLoader32Path = shellPathUnder(prefixPath, "libexec/proot/loader32")
        val prootDistroScript = File(prefixPath, "bin/proot-distro").absolutePath
        val runtimeLibraryPath = listOf(
            state.optString("lib_path"),
            state.optString("native_library_dir"),
            state.optString("shell_path")
                .takeUnless { it.startsWith("/system/") }
                ?.let { File(it).parent.orEmpty() }
                .orEmpty(),
        )
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(":")
        val prelude = listOf(
            "export TERMUX_APP__PACKAGE_NAME=${shellQuote(appPackageName)}",
            "export TERMUX_APP__APP_VERSION_NAME=Hermes",
            "export TERMUX_VERSION=Hermes",
            "export TERMUX__PREFIX=${shellQuote(prefixPath)}",
            "export TERMUX__HOME=${shellQuote(homePath)}",
            "export TMPDIR=${shellQuote(tmpPath)}",
            "export PROOT_TMP_DIR=${shellQuote(tmpPath)}",
            "export PROOT_LOADER=${shellQuote(prootLoaderPath)}",
            "export PROOT_LOADER_32=${shellQuote(prootLoader32Path)}",
            "export PROOT_NO_SECCOMP=${shellQuote("1")}",
            "export $DIRECT_PROOT_ENV=${shellQuote(directProotPath)}",
            "export LD_LIBRARY_PATH=${shellQuote(runtimeLibraryPath)}",
            nativeCommandEnvironment
                .takeIf { it.isNotBlank() && File(it).isFile }
                ?.let { ". ${shellQuote(it)}" }
                ?: ":",
            "proot-distro() { case \"\${1:-}\" in login|sh|run) local _pd_cmd=\"\$1\"; shift; command ${shellQuote(pythonPath)} ${shellQuote(prootDistroScript)} \"\$_pd_cmd\" -e \"LD_LIBRARY_PATH=\$LD_LIBRARY_PATH\" -e \"PROOT_TMP_DIR=\$PROOT_TMP_DIR\" -e \"PROOT_LOADER=\$PROOT_LOADER\" -e \"PROOT_LOADER_32=\$PROOT_LOADER_32\" -e \"PROOT_NO_SECCOMP=\$PROOT_NO_SECCOMP\" \"\$@\" ;; *) command ${shellQuote(pythonPath)} ${shellQuote(prootDistroScript)} \"\$@\" ;; esac; }",
            "pd() { proot-distro \"\$@\"; }",
            // Host suite is an immutable signed-APK baseline. Guest sandboxes use apt/apk via linux_sandbox_tool.
            "pkg() { echo \"Hermes host packages are supplied only by a signed APK; use linux_host_pkg_tool for status/list/search.\" >&2; echo \"For guest Debian/Alpine updates use linux_sandbox_tool action=update.\" >&2; return 64; }",
            "hermes-pkg() { pkg \"\$@\"; }",
            "apt() { echo \"Host packages require a signed Hermes APK. For guest distro packages use linux_sandbox_tool action=update.\" >&2; return 64; }",
            "apt-get() { apt \"\$@\"; }",
        ).joinToString("; ")
        return "$prelude; $command"
    }

    internal fun shellQuote(value: String): String {
        if (value.isEmpty()) {
            return "''"
        }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun resolvePythonLibPath(prefixDir: File): File {
        val pythonLibs = File(prefixDir, "lib").listFiles()
            .orEmpty()
            .filter { candidate ->
                candidate.isDirectory && PYTHON_LIB_VERSION.matches(candidate.name)
            }
        return pythonLibs.maxWithOrNull(
            compareBy<File> { pythonLibVersion(it.name).first }
                .thenBy { pythonLibVersion(it.name).second },
        ) ?: File(prefixDir, "lib")
    }

    private fun pythonLibVersion(name: String): Pair<Int, Int> {
        val match = PYTHON_LIB_VERSION.matchEntire(name) ?: return 0 to 0
        return (match.groupValues[1].toIntOrNull() ?: 0) to
            (match.groupValues[2].toIntOrNull() ?: 0)
    }

    private val PYTHON_LIB_VERSION = Regex("""python(\d+)\.(\d+)""")

    private fun shellPathUnder(basePath: String, relativePath: String): String {
        return basePath.trimEnd('/') + "/" + relativePath.trimStart('/')
    }

    private fun launchShellProbe(
        shellPath: String,
        workingDirectory: File,
        environment: Map<String, String>,
    ): ShellLaunchProbe {
        if (shellPath.isBlank()) {
            return ShellLaunchProbe(false, "shell path is blank")
        }
        if (!shellPath.startsWith("/system/") && !File(shellPath).canExecute()) {
            return ShellLaunchProbe(false, "shell is not executable: $shellPath")
        }
        return runCatching {
            workingDirectory.mkdirs()
            val process = ProcessBuilder(shellPath, "-c", "exit 0")
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .apply { environment().putAll(environment) }
                .start()
            val waitResult = awaitOwnedShellProcess(
                current = ownedShellProcessStopHandle(process),
                waitTimeoutMs = TimeUnit.SECONDS.toMillis(5L),
            )
            if (!waitResult.finishedWithinTimeout) {
                val waitDetail = when (val failure = waitResult.waitFailure) {
                    is InterruptedException -> "shell launch interrupted: $shellPath"
                    null -> "shell launch timed out: $shellPath"
                    else -> "shell launch wait failed for $shellPath: " +
                        (failure.message ?: failure.javaClass.simpleName)
                }
                val cleanupDetail = waitResult.cleanupFailure?.let { failure ->
                    "; process cleanup failed: ${failure.message ?: failure.javaClass.simpleName}"
                }.orEmpty()
                NativeAndroidShellTool.restoreInterruptAfterOwnedCleanup(waitResult.interrupted)
                return@runCatching ShellLaunchProbe(false, waitDetail + cleanupDetail)
            }
            NativeAndroidShellTool.restoreInterruptAfterOwnedCleanup(waitResult.interrupted)
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                generateSequence { reader.readLine() }
                    .take(40)
                    .joinToString("\n")
                    .take(1200)
            }
            if (process.exitValue() == 0) {
                ShellLaunchProbe(true)
            } else {
                ShellLaunchProbe(false, "shell exited ${process.exitValue()}: $output")
            }
        }.getOrElse { error ->
            ShellLaunchProbe(false, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun selectAndroidAbi(): String {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        return supportedAbis.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: supportedAbis.firstOrNull()
            ?: "arm64-v8a"
    }

    @Suppress("DEPRECATION")
    private fun appVersionCode(context: Context): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    private fun assetManifestSha256(context: Context, androidAbi: String): String {
        return runCatching {
            val payload = readAssetText(context.assets, "$ASSET_ROOT/$androidAbi/manifest.json")
            MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    fun experimentalLlamaServerPath(context: Context): String {
        val path = nativeExecutablePath(context, EXPERIMENTAL_LLAMA_SERVER_LIBRARY)
        return path.takeIf { it.isNotBlank() && File(it).isFile && File(it).canExecute() }.orEmpty()
    }

    private fun refreshExperimentalLlamaServerPath(context: Context, state: JSONObject): Boolean {
        val path = experimentalLlamaServerPath(context)
        if (state.optString("experimental_llama_server_path") == path) {
            return false
        }
        state.put("experimental_llama_server_path", path)
        return true
    }

    private fun nativeExecutablePath(context: Context, name: String): String {
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        if (nativeLibraryDir.isBlank()) {
            return ""
        }
        return File(nativeLibraryDir, name).absolutePath
    }

    private fun copyAssetFiles(assets: AssetManager, assetPath: String, destination: File, manifest: JSONObject) {
        val files = manifest.optJSONArray("files")
        if (files == null || files.length() == 0) {
            copyAssetTree(assets, assetPath, destination)
            return
        }
        val managedPaths = buildList {
            for (index in 0 until files.length()) {
                val relativePath = normalizeAssetRelativePath(files.optString(index))
                if (relativePath.isBlank()) continue
                require(!isMutablePrefixPath(relativePath)) {
                    "APK asset manifest cannot manage mutable user or sandbox path: $relativePath"
                }
                add(relativePath)
            }
        }
        destination.mkdirs()
        for (relativePath in managedPaths) {
            val outputFile = File(destination, relativePath)
            outputFile.parentFile?.mkdirs()
            if (isSymbolicLinkCompat(outputFile)) {
                require(outputFile.delete()) { "Could not remove stale APK-managed link: $relativePath" }
            }
            assets.open("$assetPath/$relativePath").use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun copyAssetTree(assets: AssetManager, assetPath: String, destination: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        destination.mkdirs()
        for (child in children) {
            copyAssetTree(assets, "$assetPath/$child", File(destination, child))
        }
    }

    private fun markExecutableTree(root: File) {
        if (!root.exists()) {
            return
        }
        root.walkTopDown().forEach { file ->
            if (file.isFile) {
                file.setExecutable(true, false)
            }
        }
    }

    private fun recreateNativeExecutableShims(
        context: Context,
        installRoot: File,
        prefixDir: File,
        manifest: JSONObject,
    ): File {
        val nativeExecRoot = File(installRoot, NATIVE_EXEC_ROOT_NAME)
        if (nativeExecRoot.exists()) {
            nativeExecRoot.deleteRecursively()
        }
        nativeExecRoot.mkdirs()
        val linkMap = manifestLinkMap(manifest)
        listOf(File(prefixDir, "bin"), File(prefixDir, "libexec")).forEach { root ->
            if (!root.exists()) {
                return@forEach
            }
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relativePath = file.relativeTo(prefixDir).invariantSeparatorsPath
                    createNativeExecutableShim(context, nativeExecRoot, relativePath, linkMap)
                }
        }
        linkMap.keys.forEach { relativePath ->
            createNativeExecutableShim(context, nativeExecRoot, relativePath, linkMap)
        }
        return nativeExecRoot
    }

    private fun createNativeExecutableShim(
        context: Context,
        nativeExecRoot: File,
        relativePath: String,
        linkMap: Map<String, String>,
    ) {
        val normalizedPath = normalizeAssetRelativePath(relativePath)
        if (!isNativeExecutableShimPath(normalizedPath)) {
            return
        }
        val targetRelativePath = resolveNativeExecutableTarget(normalizedPath, linkMap)
        val targetFile = File(nativeExecutablePath(context, nativeExecutableName(targetRelativePath)))
        if (!targetFile.isFile || !targetFile.canExecute()) {
            return
        }
        val shim = File(nativeExecRoot, normalizedPath)
        shim.parentFile?.mkdirs()
        if (shim.exists()) {
            shim.delete()
        }
        runCatching {
            Os.symlink(targetFile.absolutePath, shim.absolutePath)
        }
    }

    private fun manifestLinkMap(manifest: JSONObject): Map<String, String> {
        val links = manifest.optJSONArray("links") ?: return emptyMap()
        return buildMap {
            for (index in 0 until links.length()) {
                val item = links.optJSONObject(index) ?: continue
                val linkPath = normalizeAssetRelativePath(item.optString("path"))
                val targetPath = normalizeAssetRelativePath(item.optString("target"))
                if (linkPath.isBlank() || targetPath.isBlank()) {
                    continue
                }
                put(linkPath, targetPath)
            }
        }
    }

    private fun resolveNativeExecutableTarget(
        relativePath: String,
        linkMap: Map<String, String>,
    ): String {
        var current = relativePath
        repeat(12) {
            current = linkMap[current] ?: return current
        }
        return current
    }

    private fun isNativeExecutableShimPath(relativePath: String): Boolean {
        return relativePath.startsWith("bin/") || relativePath.startsWith("libexec/")
    }

    private fun nativeExecutableName(relativePath: String): String {
        NATIVE_EXECUTABLE_NAMES[relativePath]?.let { return it }
        val safeName = relativePath
            .replace('\\', '/')
            .replace(Regex("[^0-9A-Za-z_]+"), "_")
            .trim('_')
            .ifBlank { "command" }
        return "libhermes_exec_$safeName.so"
    }

    private fun recreateLinks(prefixDir: File, manifest: JSONObject) {
        val links = manifest.optJSONArray("links") ?: return
        for (index in 0 until links.length()) {
            val item = links.optJSONObject(index) ?: continue
            val linkPath = normalizeAssetRelativePath(item.optString("path"))
            val targetPath = normalizeAssetRelativePath(item.optString("target"))
            if (linkPath.isBlank() || targetPath.isBlank()) {
                continue
            }
            require(!isMutablePrefixPath(linkPath) && !isMutablePrefixPath(targetPath)) {
                "APK asset manifest cannot link mutable user or sandbox paths."
            }
            val linkFile = File(prefixDir, linkPath)
            val targetFile = File(prefixDir, targetPath)
            if (!targetFile.exists()) {
                continue
            }
            linkFile.parentFile?.mkdirs()
            if (isSymbolicLinkCompat(linkFile) || linkFile.exists()) {
                require(linkFile.delete()) { "Could not refresh APK-managed link: $linkPath" }
            }
            runCatching {
                Os.symlink(targetFile.absolutePath, linkFile.absolutePath)
            }.onFailure {
                linkFile.writeBytes(targetFile.readBytes())
                linkFile.setExecutable(targetFile.canExecute(), false)
            }
        }
    }

    /** java.nio.file symlink APIs require API 26; Android's lstat is available on API 21. */
    private fun isSymbolicLinkCompat(file: File): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.isSymbolicLink(file.toPath())
        } else {
            runCatching { OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)
        }
    }

    private fun normalizeAssetRelativePath(value: String): String {
        val parts = value
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() }
        if (parts.any { it == "." || it == ".." }) {
            return ""
        }
        return parts.joinToString("/")
    }

    private fun isMutablePrefixPath(relativePath: String): Boolean {
        return relativePath == "home" ||
            relativePath.startsWith("home/") ||
            relativePath == "var/lib/proot-distro/cache" ||
            relativePath.startsWith("var/lib/proot-distro/cache/") ||
            relativePath == "var/lib/proot-distro/containers" ||
            relativePath.startsWith("var/lib/proot-distro/containers/")
    }

    private fun readAssetText(assets: AssetManager, assetPath: String): String {
        return assets.open(assetPath).bufferedReader().use { it.readText() }
    }
}
