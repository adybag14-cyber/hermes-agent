package com.mobilefork.hermesagent.device

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Termux-style host package manager for the Hermes embedded prefix.
 *
 * Updates proot / proot-distro / other Termux main packages in-app without an APK rebuild.
 * Guest distro packages still use linux_sandbox_tool action=update (apt/apk).
 */
object HermesTermuxPackageManager {
    private const val STATUS_FILE = "status.json"
    private const val INDEX_CACHE = "Packages.cache"
    private const val INDEX_META = "Packages.cache.meta"
    private const val SOURCE_APK = "apk_baseline"
    private const val SOURCE_OTA = "ota"

    private val IGNORED_DEPENDENCIES = setOf(
        "termux-am",
        "termux-am-socket",
        "termux-auth",
        "termux-core",
        "termux-exec",
        "termux-keyring",
        "termux-licenses",
        "termux-tools",
    )

    /** Same curated suite as hermes_android/linux_assets.py ROOT_PACKAGES. */
    val ROOT_PACKAGES = listOf(
        "bash",
        "busybox",
        "bzip2",
        "coreutils",
        "curl",
        "diffutils",
        "findutils",
        "gawk",
        "git",
        "grep",
        "gzip",
        "less",
        "llama-cpp",
        "proot",
        "proot-distro",
        "procps",
        "qemu-user-aarch64",
        "qemu-user-x86-64",
        "sed",
        "tar",
        "util-linux",
        "xz-utils",
    )

    private val CRITICAL_PACKAGES = setOf("proot", "proot-distro", "libtalloc", "bash")

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class PackageRecord(
        val name: String,
        val version: String,
        val filename: String,
        val sha256: String,
        val depends: List<String> = emptyList(),
    )

    fun performAction(
        context: Context,
        action: String,
        packages: List<String> = emptyList(),
        mirrorProfile: String = "",
        query: String = "",
    ): JSONObject {
        val app = context.applicationContext
        if (mirrorProfile.isNotBlank()) {
            HermesTermuxMirrorConfig.setMirrorProfile(app, mirrorProfile)
        }
        val state = HermesLinuxSubsystemBridge.ensureInstalled(app)
        if (!state.optBoolean("uses_termux", false)) {
            return errorResult(
                action = action,
                message = "Embedded Termux prefix is unavailable (system shell fallback).",
            )
        }
        seedStatusFromApkIfNeeded(app, state)
        return when (action.trim().lowercase()) {
            "", "status", "show" -> status(app, state, packages)
            "update", "refresh", "update_index" -> updateIndex(app, state)
            "upgrade", "full-upgrade", "dist-upgrade" -> upgrade(app, state, packages)
            "install", "add" -> install(app, state, packages)
            "remove", "uninstall", "purge" -> remove(app, state, packages)
            "list", "list-installed" -> listInstalled(app, state)
            "search", "find" -> search(app, state, query.ifBlank { packages.firstOrNull().orEmpty() })
            "set_mirror", "mirror" -> {
                val profile = mirrorProfile.ifBlank { packages.firstOrNull().orEmpty() }
                if (profile.isBlank()) {
                    return status(app, state)
                        .put("action", "set_mirror")
                        .put("mirror_profile", HermesTermuxMirrorConfig.mirrorProfile(app))
                }
                HermesTermuxMirrorConfig.setMirrorProfile(app, profile)
                status(app, state)
                    .put("action", "set_mirror")
                    .put("mirror_profile", HermesTermuxMirrorConfig.mirrorProfile(app))
                    .put("exit_code", 0)
            }
            "cli" -> runCli(app, state, packages)
            else -> errorResult(
                action = action,
                message = "Unknown action '$action'. Use status, update, upgrade, install, remove, list, search, set_mirror.",
            )
        }
    }

    /**
     * Parse a shell-style `pkg …` / `hermes-pkg …` command into an action.
     */
    fun performCliCommand(context: Context, commandLine: String): JSONObject {
        val tokens = tokenize(commandLine)
            .dropWhile { it in setOf("pkg", "hermes-pkg", "command") }
        if (tokens.isEmpty()) {
            return performAction(context, "status")
        }
        val sub = tokens[0].lowercase()
        val rest = tokens.drop(1).filter { !it.startsWith("-") }
        return when (sub) {
            "update", "upgrade", "install", "remove", "uninstall", "purge",
            "list", "search", "status", "show",
            -> performAction(
                context = context,
                action = when (sub) {
                    "uninstall", "purge" -> "remove"
                    "show" -> "status"
                    else -> sub
                },
                packages = rest,
                query = rest.joinToString(" "),
            )
            else -> performAction(context, "install", packages = tokens)
        }
    }

    fun isPkgCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
        return first == "pkg" || first == "hermes-pkg"
    }

    private fun runCli(context: Context, state: JSONObject, tokens: List<String>): JSONObject {
        if (tokens.isEmpty()) return status(context, state)
        return performCliCommand(context, tokens.joinToString(" "))
    }

    private fun status(context: Context, state: JSONObject, filter: List<String> = emptyList()): JSONObject {
        val db = loadStatus(context, state)
        val installed = db.optJSONObject("packages") ?: JSONObject()
        val names = if (filter.isEmpty()) {
            installed.keys().asSequence().toList().sorted()
        } else {
            filter
        }
        val packages = JSONArray()
        for (name in names) {
            val row = installed.optJSONObject(name) ?: continue
            packages.put(
                JSONObject()
                    .put("name", name)
                    .put("version", row.optString("version"))
                    .put("source", row.optString("source", SOURCE_APK))
                    .put("filename", row.optString("filename"))
                    .put("file_count", row.optJSONArray("files")?.length() ?: 0),
            )
        }
        val meta = loadIndexMeta(context, state)
        return JSONObject()
            .put("ok", true)
            .put("exit_code", 0)
            .put("action", "status")
            .put("android_abi", state.optString("android_abi"))
            .put("termux_arch", state.optString("termux_arch"))
            .put("prefix_path", state.optString("prefix_path"))
            .put("mirror_profile", HermesTermuxMirrorConfig.mirrorProfile(context))
            .put("mirrors", JSONArray(HermesTermuxMirrorConfig.orderedBaseUrls(context)))
            .put("index_updated_at", meta.optLong("updated_at_ms", 0L))
            .put("index_mirror", meta.optString("mirror"))
            .put("index_package_count", meta.optInt("package_count", 0))
            .put("installed_count", installed.length())
            .put("packages", packages)
            .put("proot_version", installed.optJSONObject("proot")?.optString("version").orEmpty())
            .put("proot_distro_version", installed.optJSONObject("proot-distro")?.optString("version").orEmpty())
            .put(
                "hint",
                "Host suite uses Termux-style pkg (linux_host_pkg_tool). " +
                    "Guest distro packages use linux_sandbox_tool action=update (apt/apk).",
            )
    }

    private fun listInstalled(context: Context, state: JSONObject): JSONObject {
        return status(context, state).put("action", "list")
    }

    private fun search(context: Context, state: JSONObject, query: String): JSONObject {
        if (query.isBlank()) {
            return errorResult("search", "search requires a query")
        }
        val index = ensureIndex(context, state)
        val q = query.lowercase()
        val matches = JSONArray()
        for ((name, record) in index) {
            if (name.contains(q) || record.version.contains(q)) {
                matches.put(
                    JSONObject()
                        .put("name", name)
                        .put("version", record.version)
                        .put("filename", record.filename),
                )
            }
            if (matches.length() >= 50) break
        }
        return JSONObject()
            .put("ok", true)
            .put("exit_code", 0)
            .put("action", "search")
            .put("query", query)
            .put("matches", matches)
            .put("match_count", matches.length())
    }

    private fun updateIndex(context: Context, state: JSONObject): JSONObject {
        val (index, mirror) = fetchIndex(context, state)
        saveIndexCache(context, state, index, mirror)
        return JSONObject()
            .put("ok", true)
            .put("exit_code", 0)
            .put("action", "update")
            .put("mirror", mirror)
            .put("package_count", index.size)
            .put("mirror_profile", HermesTermuxMirrorConfig.mirrorProfile(context))
            .put("message", "Package index refreshed (${index.size} packages from $mirror)")
    }

    private fun upgrade(
        context: Context,
        state: JSONObject,
        requested: List<String>,
    ): JSONObject {
        val index = ensureIndex(context, state)
        val db = loadStatus(context, state)
        val installed = db.optJSONObject("packages") ?: JSONObject()
        val targets = if (requested.isNotEmpty()) {
            requested
        } else {
            (ROOT_PACKAGES + installed.keys().asSequence().toList()).distinct()
        }
        val toUpgrade = mutableListOf<PackageRecord>()
        val skipped = JSONArray()
        for (name in targets) {
            val available = index[name] ?: continue
            val current = installed.optJSONObject(name)?.optString("version").orEmpty()
            if (current.isBlank() || current != available.version) {
                toUpgrade.add(available)
            } else {
                skipped.put(JSONObject().put("name", name).put("version", current).put("reason", "up_to_date"))
            }
        }
        if (toUpgrade.isEmpty()) {
            return status(context, state)
                .put("action", "upgrade")
                .put("message", "All selected packages are up to date")
                .put("upgraded", JSONArray())
                .put("skipped", skipped)
        }
        // Prefer upgrading proot + proot-distro together when either is selected.
        val names = toUpgrade.map { it.name }.toMutableSet()
        if ("proot" in names || "proot-distro" in names) {
            index["proot"]?.let { names.add(it.name) }
            index["proot-distro"]?.let { names.add(it.name) }
            index["libtalloc"]?.let { names.add(it.name) }
        }
        val ordered = resolveDependencyClosure(index, names)
        return installRecords(context, state, ordered, action = "upgrade", skipped = skipped)
    }

    private fun install(
        context: Context,
        state: JSONObject,
        requested: List<String>,
    ): JSONObject {
        if (requested.isEmpty()) {
            return errorResult("install", "install requires one or more package names")
        }
        val index = ensureIndex(context, state)
        val missing = requested.filter { it !in index }
        if (missing.isNotEmpty()) {
            return errorResult("install", "Unknown package(s): ${missing.joinToString(", ")}")
        }
        val ordered = resolveDependencyClosure(index, requested)
        return installRecords(context, state, ordered, action = "install")
    }

    private fun remove(
        context: Context,
        state: JSONObject,
        requested: List<String>,
    ): JSONObject {
        if (requested.isEmpty()) {
            return errorResult("remove", "remove requires one or more package names")
        }
        val prefix = File(state.optString("prefix_path"))
        val db = loadStatus(context, state)
        val installed = db.optJSONObject("packages") ?: JSONObject()
        val removed = JSONArray()
        for (name in requested) {
            if (name in CRITICAL_PACKAGES && name in ROOT_PACKAGES) {
                return errorResult(
                    "remove",
                    "Refusing to remove critical suite package '$name'. Use upgrade instead.",
                )
            }
            val row = installed.optJSONObject(name)
            if (row == null) {
                removed.put(JSONObject().put("name", name).put("removed", false).put("reason", "not_installed"))
                continue
            }
            val files = row.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val rel = files.optString(i)
                    if (rel.isNotBlank()) {
                        File(prefix, rel).delete()
                    }
                }
            }
            installed.remove(name)
            removed.put(JSONObject().put("name", name).put("removed", true))
        }
        db.put("packages", installed)
        saveStatus(context, state, db)
        HermesLinuxSubsystemBridge.refreshPackageStateAfterOta(context, state, db)
        return JSONObject()
            .put("ok", true)
            .put("exit_code", 0)
            .put("action", "remove")
            .put("removed", removed)
    }

    private fun installRecords(
        context: Context,
        state: JSONObject,
        records: List<PackageRecord>,
        action: String,
        skipped: JSONArray = JSONArray(),
    ): JSONObject {
        val prefix = File(state.optString("prefix_path"))
        if (!prefix.isDirectory) {
            return errorResult(action, "Prefix directory missing: ${prefix.absolutePath}")
        }
        val db = loadStatus(context, state)
        val installed = db.optJSONObject("packages") ?: JSONObject()
        val upgraded = JSONArray()
        val errors = JSONArray()
        var lastMirror = ""
        var totalBytes = 0L

        for (record in records) {
            val previous = installed.optJSONObject(record.name)
            val previousVersion = previous?.optString("version").orEmpty()
            if (previousVersion == record.version && previous?.optString("source") == SOURCE_OTA) {
                skipped.put(
                    JSONObject()
                        .put("name", record.name)
                        .put("version", record.version)
                        .put("reason", "already_ota_current"),
                )
                continue
            }
            val backupDir = if (record.name in CRITICAL_PACKAGES && previous != null) {
                createBackup(context, state, record.name, previous, prefix)
            } else {
                null
            }
            try {
                val (debBytes, mirror) = downloadDeb(context, record)
                lastMirror = mirror
                totalBytes += debBytes.size
                HermesTermuxDebExtractor.verifySha256(debBytes, record.sha256)
                val extracted = HermesTermuxDebExtractor.extractDebToPrefix(debBytes, prefix)
                HermesLinuxSubsystemBridge.markPrefixExecutables(prefix)
                HermesLinuxSubsystemBridge.repointNativeExecForOtaFiles(context, state, extracted.files)
                val row = JSONObject()
                    .put("name", record.name)
                    .put("version", record.version)
                    .put("filename", record.filename)
                    .put("sha256", record.sha256)
                    .put("depends", JSONArray(record.depends))
                    .put("source", SOURCE_OTA)
                    .put("files", JSONArray(extracted.files))
                    .put("updated_at_ms", System.currentTimeMillis())
                installed.put(record.name, row)
                upgraded.put(
                    JSONObject()
                        .put("name", record.name)
                        .put("from_version", previousVersion.ifBlank { null })
                        .put("to_version", record.version)
                        .put("mirror", mirror)
                        .put("bytes", debBytes.size)
                        .put("file_count", extracted.files.size),
                )
                backupDir?.deleteRecursively()
            } catch (exc: Exception) {
                backupDir?.let { restoreBackup(it, prefix) }
                errors.put(
                    JSONObject()
                        .put("name", record.name)
                        .put("error", exc.message ?: exc.javaClass.simpleName),
                )
                // Fail fast on critical package errors
                if (record.name in CRITICAL_PACKAGES) {
                    break
                }
            }
        }

        db.put("packages", installed)
        saveStatus(context, state, db)
        HermesLinuxSubsystemBridge.refreshPackageStateAfterOta(context, state, db)

        val exitCode = if (errors.length() > 0 && upgraded.length() == 0) 1 else 0
        return JSONObject()
            .put("ok", exitCode == 0)
            .put("exit_code", exitCode)
            .put("action", action)
            .put("mirror", lastMirror)
            .put("bytes_downloaded", totalBytes)
            .put("upgraded", upgraded)
            .put("skipped", skipped)
            .put("errors", errors)
            .put("proot_version", installed.optJSONObject("proot")?.optString("version").orEmpty())
            .put("proot_distro_version", installed.optJSONObject("proot-distro")?.optString("version").orEmpty())
            .put(
                "message",
                if (exitCode == 0) {
                    "Installed/upgraded ${upgraded.length()} package(s)"
                } else {
                    "Package operation finished with errors (${errors.length()})"
                },
            )
    }

    private fun ensureIndex(context: Context, state: JSONObject): Map<String, PackageRecord> {
        val cached = loadIndexCache(context, state)
        if (cached.isNotEmpty()) {
            val meta = loadIndexMeta(context, state)
            val age = System.currentTimeMillis() - meta.optLong("updated_at_ms", 0L)
            if (age in 1 until TimeUnit.HOURS.toMillis(12)) {
                return cached
            }
        }
        val (index, mirror) = fetchIndex(context, state)
        saveIndexCache(context, state, index, mirror)
        return index
    }

    private fun fetchIndex(context: Context, state: JSONObject): Pair<Map<String, PackageRecord>, String> {
        val arch = state.optString("termux_arch").ifBlank {
            when (state.optString("android_abi")) {
                "arm64-v8a" -> "aarch64"
                "x86_64" -> "x86_64"
                else -> "aarch64"
            }
        }
        val relative = HermesTermuxMirrorConfig.packagesIndexPath(arch)
        val errors = mutableListOf<String>()
        for (base in HermesTermuxMirrorConfig.orderedBaseUrls(context)) {
            val url = HermesTermuxMirrorConfig.url(base, relative)
            try {
                val body = httpGetBytes(url)
                val text = body.toString(Charsets.UTF_8)
                val index = parsePackagesIndex(text)
                if (index.isEmpty()) {
                    errors.add("$url: empty index")
                    continue
                }
                return index to base
            } catch (exc: Exception) {
                errors.add("$url: ${exc.message}")
            }
        }
        throw IllegalStateException("Failed to fetch Packages index: ${errors.joinToString(" | ")}")
    }

    private fun downloadDeb(context: Context, record: PackageRecord): Pair<ByteArray, String> {
        val errors = mutableListOf<String>()
        for (base in HermesTermuxMirrorConfig.orderedBaseUrls(context)) {
            val url = HermesTermuxMirrorConfig.url(base, record.filename)
            try {
                val body = httpGetBytes(url)
                if (body.isEmpty()) {
                    errors.add("$url: empty body")
                    continue
                }
                return body to base
            } catch (exc: Exception) {
                errors.add("$url: ${exc.message}")
            }
        }
        throw IllegalStateException(
            "Failed to download ${record.name}: ${errors.joinToString(" | ")}",
        )
    }

    private fun httpGetBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HermesTermuxMirrorConfig.USER_AGENT)
            .header("Accept", "*/*")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    internal fun parsePackagesIndex(text: String): Map<String, PackageRecord> {
        val records = linkedMapOf<String, PackageRecord>()
        var current = linkedMapOf<String, String>()
        fun flush() {
            val name = current["Package"] ?: return
            val version = current["Version"] ?: return
            val filename = current["Filename"] ?: return
            val sha256 = current["SHA256"] ?: return
            records[name] = PackageRecord(
                name = name,
                version = version,
                filename = filename,
                sha256 = sha256,
                depends = parseDepends(current["Depends"]),
            )
            current = linkedMapOf()
        }
        for (line in text.lineSequence()) {
            if (line.isBlank()) {
                flush()
                continue
            }
            if (line.startsWith(" ") || line.startsWith("\t")) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            current[line.substring(0, idx)] = line.substring(idx + 1).trim()
        }
        flush()
        return records
    }

    internal fun parseDepends(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val resolved = mutableListOf<String>()
        for (chunk in raw.split(',')) {
            val options = chunk.split('|').map {
                it.replace(Regex("\\s*\\(.*?\\)"), "").trim()
            }.filter { it.isNotBlank() }
            if (options.isEmpty()) continue
            val selected = options.firstOrNull { it !in IGNORED_DEPENDENCIES } ?: options.first()
            if (selected in IGNORED_DEPENDENCIES) continue
            if (selected !in resolved) resolved.add(selected)
        }
        return resolved
    }

    internal fun resolveDependencyClosure(
        records: Map<String, PackageRecord>,
        roots: Collection<String>,
    ): List<PackageRecord> {
        val pending = ArrayDeque(roots)
        val seen = linkedSetOf<String>()
        val ordered = mutableListOf<PackageRecord>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (name in seen || name in IGNORED_DEPENDENCIES) continue
            val record = records[name]
                ?: throw IllegalArgumentException("Package '$name' not found in index")
            seen.add(name)
            ordered.add(record)
            for (dep in record.depends) {
                if (dep !in seen && dep !in IGNORED_DEPENDENCIES) {
                    pending.add(dep)
                }
            }
        }
        return ordered
    }

    private fun pkgDir(context: Context, state: JSONObject): File {
        val abi = state.optString("android_abi").ifBlank { "arm64-v8a" }
        val dir = File(context.filesDir, "hermes-home/linux/$abi/var/lib/hermes-pkg")
        dir.mkdirs()
        return dir
    }

    private fun loadStatus(context: Context, state: JSONObject): JSONObject {
        val file = File(pkgDir(context, state), STATUS_FILE)
        if (!file.isFile) {
            return JSONObject().put("packages", JSONObject())
        }
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }
            .getOrElse { JSONObject().put("packages", JSONObject()) }
    }

    private fun saveStatus(context: Context, state: JSONObject, db: JSONObject) {
        File(pkgDir(context, state), STATUS_FILE).writeText(db.toString(), Charsets.UTF_8)
    }

    private fun loadIndexCache(context: Context, state: JSONObject): Map<String, PackageRecord> {
        val file = File(pkgDir(context, state), INDEX_CACHE)
        if (!file.isFile) return emptyMap()
        return runCatching { parsePackagesIndex(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyMap())
    }

    private fun loadIndexMeta(context: Context, state: JSONObject): JSONObject {
        val file = File(pkgDir(context, state), INDEX_META)
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse { JSONObject() }
    }

    private fun saveIndexCache(
        context: Context,
        state: JSONObject,
        index: Map<String, PackageRecord>,
        mirror: String,
    ) {
        val dir = pkgDir(context, state)
        // Rebuild a minimal Packages-like cache for parsePackagesIndex
        val body = buildString {
            for (record in index.values.sortedBy { it.name }) {
                append("Package: ${record.name}\n")
                append("Version: ${record.version}\n")
                append("Filename: ${record.filename}\n")
                append("SHA256: ${record.sha256}\n")
                if (record.depends.isNotEmpty()) {
                    append("Depends: ${record.depends.joinToString(", ")}\n")
                }
                append('\n')
            }
        }
        File(dir, INDEX_CACHE).writeText(body, Charsets.UTF_8)
        File(dir, INDEX_META).writeText(
            JSONObject()
                .put("updated_at_ms", System.currentTimeMillis())
                .put("mirror", mirror)
                .put("package_count", index.size)
                .toString(),
            Charsets.UTF_8,
        )
    }

    fun seedStatusFromApkIfNeeded(context: Context, state: JSONObject) {
        val db = loadStatus(context, state)
        val packages = db.optJSONObject("packages") ?: JSONObject()
        if (packages.length() > 0) return
        val apkPackages = state.optJSONArray("packages") ?: JSONArray()
        for (i in 0 until apkPackages.length()) {
            val item = apkPackages.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isBlank()) continue
            packages.put(
                name,
                JSONObject()
                    .put("name", name)
                    .put("version", item.optString("version"))
                    .put("filename", item.optString("filename"))
                    .put("sha256", item.optString("sha256"))
                    .put("depends", item.optJSONArray("depends") ?: JSONArray())
                    .put("source", SOURCE_APK)
                    .put("files", JSONArray())
                    .put("updated_at_ms", System.currentTimeMillis()),
            )
        }
        db.put("packages", packages)
        saveStatus(context, state, db)
    }

    private fun createBackup(
        context: Context,
        state: JSONObject,
        name: String,
        previous: JSONObject,
        prefix: File,
    ): File {
        val txn = File(pkgDir(context, state), "backup/${System.currentTimeMillis()}-$name")
        txn.mkdirs()
        val files = previous.optJSONArray("files") ?: return txn
        for (i in 0 until files.length()) {
            val rel = files.optString(i)
            if (rel.isBlank()) continue
            val src = File(prefix, rel)
            if (!src.isFile) continue
            val dest = File(txn, rel)
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        File(txn, "_meta.json").writeText(previous.toString(), Charsets.UTF_8)
        return txn
    }

    private fun restoreBackup(backupDir: File, prefix: File) {
        if (!backupDir.isDirectory) return
        backupDir.walkTopDown()
            .filter { it.isFile && it.name != "_meta.json" }
            .forEach { file ->
                val rel = file.relativeTo(backupDir).invariantSeparatorsPath
                val dest = File(prefix, rel)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
                if (rel.startsWith("bin/") || rel.startsWith("libexec/")) {
                    dest.setExecutable(true, false)
                }
            }
    }

    private fun errorResult(action: String, message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("exit_code", 1)
            .put("action", action)
            .put("error", message)
            .put("message", message)
    }

    private fun tokenize(commandLine: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (ch in commandLine.trim()) {
            when {
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch.isWhitespace() && !inSingle && !inDouble -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }
}
