package com.mobilefork.hermesagent.device

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Termux-style host package manager for the Hermes embedded prefix.
 *
 * The host Termux suite is an immutable, content-addressed APK baseline.
 * In-app mirror metadata is intentionally read-only: accepting a Packages file
 * and its hashes from the same mirror is not a signature trust boundary.
 * Host package changes therefore require a newly signed Hermes APK.
 * Guest distro packages still use linux_sandbox_tool action=update (apt/apk).
 */
object HermesTermuxPackageManager {
    private const val STATUS_FILE = "status.json"
    private const val INDEX_CACHE = "Packages.cache"
    private const val INDEX_META = "Packages.cache.meta"
    private const val SOURCE_APK = "apk_baseline"
    private const val ACTIVATION_APK_BASELINE = "active_apk_baseline"
    private const val DEFERRED_PACKAGES_KEY = "deferred_packages"
    private const val TRUST_POLICY = "signed_apk_content_addressed_baseline"
    private const val APK_AUTHORITY = "signed_apk_asset_manifest"
    private const val MIRROR_AUTHORITY = "untrusted_discovery_only"
    private const val TRUST_POLICY_MIGRATION_VERSION = 1
    private val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]*")
    private val SHA256 = Regex("[0-9a-f]{64}")

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

    internal data class ApkAuthorityValidation(
        val valid: Boolean,
        val packageCount: Int,
        val tupleDigestSha256: String,
        val errors: List<String>,
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

    /**
     * Validate the package tuples supplied by the manifest embedded in the signed APK.
     *
     * This does not make a network mirror authoritative. The result is used only to
     * describe and restore the immutable APK baseline and to fail closed if that
     * baseline is malformed.
     */
    internal fun validateApkPackageAuthority(apkPackages: JSONArray): ApkAuthorityValidation {
        val errors = mutableListOf<String>()
        val records = linkedMapOf<String, PackageRecord>()
        for (index in 0 until apkPackages.length()) {
            val item = apkPackages.optJSONObject(index)
            if (item == null) {
                errors.add("package[$index]: expected object")
                continue
            }
            val name = item.optString("name").trim()
            val version = item.optString("version").trim()
            val filename = item.optString("filename").trim()
            val sha256 = item.optString("sha256").trim().lowercase()
            val dependsArray = item.optJSONArray("depends")
            val depends = mutableListOf<String>()
            if (!PACKAGE_NAME.matches(name)) errors.add("package[$index]: invalid name '$name'")
            if (version.isBlank()) errors.add("package[$index] $name: version is blank")
            if (!isSafeRepositoryFilename(filename)) {
                errors.add("package[$index] $name: unsafe filename '$filename'")
            }
            if (!SHA256.matches(sha256)) errors.add("package[$index] $name: invalid sha256")
            if (dependsArray == null) {
                errors.add("package[$index] $name: depends must be an array")
            } else {
                for (dependencyIndex in 0 until dependsArray.length()) {
                    val dependency = dependsArray.optString(dependencyIndex).trim()
                    if (!PACKAGE_NAME.matches(dependency)) {
                        errors.add("package[$index] $name: invalid dependency '$dependency'")
                    } else if (dependency == name) {
                        errors.add("package[$index] $name: self dependency")
                    } else if (dependency in depends) {
                        errors.add("package[$index] $name: duplicate dependency '$dependency'")
                    } else {
                        depends.add(dependency)
                    }
                }
            }
            if (name.isNotBlank() && records.containsKey(name)) {
                errors.add("package[$index]: duplicate package '$name'")
                continue
            }
            if (
                PACKAGE_NAME.matches(name) && version.isNotBlank() &&
                isSafeRepositoryFilename(filename) && SHA256.matches(sha256)
            ) {
                records[name] = PackageRecord(name, version, filename, sha256, depends)
            }
        }
        if (records.isEmpty()) errors.add("signed APK package authority is empty")
        records.values.forEach { record ->
            record.depends.forEach { dependency ->
                if (dependency !in records) {
                    errors.add("package ${record.name}: unresolved dependency '$dependency'")
                }
            }
        }
        val canonical = records.values
            .sortedBy { it.name }
            .joinToString("\n") { record ->
                listOf(
                    record.name,
                    record.version,
                    record.filename,
                    record.sha256,
                    record.depends.sorted().joinToString(","),
                ).joinToString("\u0000")
            }
        val digest = if (canonical.isBlank()) "" else sha256Hex(canonical.toByteArray(Charsets.UTF_8))
        return ApkAuthorityValidation(
            valid = errors.isEmpty(),
            packageCount = records.size,
            tupleDigestSha256 = digest,
            errors = errors.distinct().sorted(),
        )
    }

    /** Build the structured, non-mutating rejection used by every host mutation route. */
    internal fun immutableHostMutationDecision(
        statusSnapshot: JSONObject,
        action: String,
        requested: List<String>,
        apkPackages: JSONArray,
    ): JSONObject {
        val authority = validateApkPackageAuthority(apkPackages)
        val knownPackages = buildSet {
            for (index in 0 until apkPackages.length()) {
                apkPackages.optJSONObject(index)
                    ?.optString("name")
                    ?.takeIf { PACKAGE_NAME.matches(it) }
                    ?.let(::add)
            }
        }
        val requestedAssessment = JSONArray()
        requested.forEach { name ->
            requestedAssessment.put(
                JSONObject()
                    .put("name", name)
                    .put("present_in_signed_apk_baseline", name in knownPackages),
            )
        }
        return JSONObject(statusSnapshot.toString())
            .put("ok", false)
            .put("exit_code", 1)
            .put("action", action)
            .put("requested", JSONArray(requested))
            .put("requested_assessment", requestedAssessment)
            .put("trust_policy", TRUST_POLICY)
            .put("authoritative_source", APK_AUTHORITY)
            .put("live_mirror_authority", MIRROR_AUTHORITY)
            .put("mutation_permitted", false)
            .put("active_version_changed", false)
            .put("bytes_activated", 0)
            .put("requires_signed_apk_update", true)
            .put("apk_authority", authorityJson(authority))
            .put(
                "message",
                "Hermes does not activate host packages from live mirrors because a mirror-provided index and mirror-provided hashes are not an independent signature. Update the signed Hermes APK to change the embedded host suite. Guest Debian/Alpine packages remain updateable inside their own sandbox package manager.",
            )
    }

    private fun runCli(context: Context, state: JSONObject, tokens: List<String>): JSONObject {
        if (tokens.isEmpty()) return status(context, state)
        return performCliCommand(context, tokens.joinToString(" "))
    }

    private fun status(context: Context, state: JSONObject, filter: List<String> = emptyList()): JSONObject {
        val db = loadStatus(context, state)
        val installed = db.optJSONObject("packages") ?: JSONObject()
        val deferred = db.optJSONObject(DEFERRED_PACKAGES_KEY) ?: JSONObject()
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
                    .put("active_version", row.optString("active_version", row.optString("version")))
                    .put("source", row.optString("source", SOURCE_APK))
                    .put("activation", row.optString("activation", ACTIVATION_APK_BASELINE))
                    .put("filename", row.optString("filename"))
                    .put("file_count", row.optJSONArray("files")?.length() ?: 0),
            )
        }
        val deferredPackages = JSONArray()
        val deferredNames = deferred.keys().asSequence().toList().sorted()
        for (name in deferredNames) {
            val row = deferred.optJSONObject(name) ?: continue
            deferredPackages.put(
                JSONObject(row.toString())
                    .put("name", name),
            )
        }
        val meta = loadIndexMeta(context, state)
        val authority = validateApkPackageAuthority(apkPackagesFromState(state))
        return JSONObject()
            .put("ok", true)
            .put("exit_code", 0)
            .put("action", "status")
            .put("trust_policy", TRUST_POLICY)
            .put("authoritative_source", APK_AUTHORITY)
            .put("live_mirror_authority", MIRROR_AUTHORITY)
            .put("host_packages_mutable", false)
            .put("apk_authority", authorityJson(authority))
            .put("android_abi", state.optString("android_abi"))
            .put("termux_arch", state.optString("termux_arch"))
            .put("prefix_path", state.optString("prefix_path"))
            .put("mirror_profile", HermesTermuxMirrorConfig.mirrorProfile(context))
            .put("mirrors", JSONArray(HermesTermuxMirrorConfig.orderedBaseUrls(context)))
            .put("index_updated_at", meta.optLong("updated_at_ms", 0L))
            .put("index_mirror", meta.optString("mirror"))
            .put("index_package_count", meta.optInt("package_count", 0))
            .put("installed_count", installed.length())
            .put("active_count", installed.length())
            .put("deferred_count", deferred.length())
            .put("packages", packages)
            .put("deferred_packages", deferredPackages)
            .put("proot_version", installed.optJSONObject("proot")?.optString("version").orEmpty())
            .put("proot_distro_version", installed.optJSONObject("proot-distro")?.optString("version").orEmpty())
            .put(
                "hint",
                "Host suite uses Termux-style pkg (linux_host_pkg_tool). " +
                    "All host package changes require a signed Hermes APK; live mirror indexes are discovery-only and never an activation trust boundary. " +
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
            .put("authoritative", false)
            .put("metadata_authority", MIRROR_AUTHORITY)
            .put("activation_permitted", false)
            .put("matches", matches)
            .put("match_count", matches.length())
    }

    private fun updateIndex(context: Context, state: JSONObject): JSONObject {
        return immutableHostPackageResult(context, state, "update")
    }

    private fun upgrade(
        context: Context,
        state: JSONObject,
        requested: List<String>,
    ): JSONObject {
        return immutableHostPackageResult(context, state, "upgrade", requested)
    }

    private fun install(
        context: Context,
        state: JSONObject,
        requested: List<String>,
    ): JSONObject {
        if (requested.isEmpty()) {
            return errorResult("install", "install requires one or more package names")
        }
        return immutableHostPackageResult(context, state, "install", requested)
    }

    private fun immutableHostPackageResult(
        context: Context,
        state: JSONObject,
        action: String,
        requested: List<String> = emptyList(),
    ): JSONObject = immutableHostMutationDecision(
        statusSnapshot = status(context, state),
        action = action,
        requested = requested,
        apkPackages = apkPackagesFromState(state),
    )

    private fun remove(
        context: Context,
        state: JSONObject,
        requested: List<String>,
    ): JSONObject {
        if (requested.isEmpty()) {
            return errorResult("remove", "remove requires one or more package names")
        }
        return immutableHostPackageResult(context, state, "remove", requested)
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
        val deferred = db.optJSONObject(DEFERRED_PACKAGES_KEY) ?: JSONObject()
        val apkPackages = apkPackagesFromState(state)
        val authority = validateApkPackageAuthority(apkPackages)
        state.put("host_pkg_trust_policy", TRUST_POLICY)
        state.put("host_pkg_authoritative_source", APK_AUTHORITY)
        state.put("host_pkg_authority_valid", authority.valid)
        state.put("host_pkg_authority_digest_sha256", authority.tupleDigestSha256)
        state.put("host_pkg_authority_errors", JSONArray(authority.errors))
        if (!authority.valid) {
            return
        }
        val now = System.currentTimeMillis()
        val baselineDrift = inspectBaselineDrift(apkPackages, packages)
        val existingBaseline = packages.length() > 0
        val migrationPending = state.optInt("host_pkg_trust_policy_version", 0) < TRUST_POLICY_MIGRATION_VERSION
        if (existingBaseline && (migrationPending || baselineDrift.hasDrift)) {
            val restoration = HermesLinuxSubsystemBridge.restoreSignedApkHostPrefix(
                context = context,
                state = state,
                legacyTrackedFiles = baselineDrift.trackedFiles,
            )
            state.put("host_pkg_baseline_restore_ok", restoration.success)
            state.put("host_pkg_baseline_restored_file_count", restoration.restoredFileCount)
            state.put("host_pkg_removed_untrusted_file_count", restoration.removedUntrustedFileCount)
            state.put("host_pkg_baseline_restore_error", restoration.error)
            if (!restoration.success) {
                state.put("host_pkg_authority_valid", false)
                state.put(
                    "host_pkg_authority_errors",
                    JSONArray(authority.errors + "signed APK host-prefix restoration failed: ${restoration.error}"),
                )
                return
            }
        }
        val changed = reconcileApkBaselineRows(
            apkPackages = apkPackages,
            installed = packages,
            deferred = deferred,
            updatedAtMs = now,
        )
        state.put("host_pkg_trust_policy_version", TRUST_POLICY_MIGRATION_VERSION)
        if (changed || !db.has(DEFERRED_PACKAGES_KEY)) {
            db.put("packages", packages)
            db.put(DEFERRED_PACKAGES_KEY, deferred)
            saveStatus(context, state, db)
        }
        HermesLinuxSubsystemBridge.refreshPackageStateAfterOta(context, state, db)
    }

    internal fun reconcileApkBaselineRows(
        apkPackages: JSONArray,
        installed: JSONObject,
        deferred: JSONObject,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!validateApkPackageAuthority(apkPackages).valid) return false
        var changed = false
        val authoritativeNames = linkedSetOf<String>()
        for (index in 0 until apkPackages.length()) {
            val item = apkPackages.optJSONObject(index) ?: continue
            val name = item.optString("name")
            if (!PACKAGE_NAME.matches(name)) continue
            authoritativeNames.add(name)
            val active = installed.optJSONObject(name)
            if (active == null || !rowMatchesApkBaseline(active, item)) {
                installed.put(name, apkBaselineRow(item, updatedAtMs))
                changed = true
            }
        }
        installed.keys().asSequence().toList().forEach { name ->
            if (name !in authoritativeNames) {
                installed.remove(name)
                changed = true
            }
        }
        deferred.keys().asSequence().toList().forEach { name ->
            deferred.remove(name)
            changed = true
        }
        return changed
    }

    internal data class BaselineDrift(
        val hasDrift: Boolean,
        val trackedFiles: List<String>,
    )

    internal fun inspectBaselineDrift(
        apkPackages: JSONArray,
        installed: JSONObject,
    ): BaselineDrift {
        val apkByName = linkedMapOf<String, JSONObject>()
        for (index in 0 until apkPackages.length()) {
            val item = apkPackages.optJSONObject(index) ?: continue
            item.optString("name").takeIf { it.isNotBlank() }?.let { apkByName[it] = item }
        }
        var hasDrift = false
        val trackedFiles = linkedSetOf<String>()
        installed.keys().asSequence().toList().forEach { name ->
            val row = installed.optJSONObject(name)
            val baseline = apkByName[name]
            if (row == null || baseline == null || !rowMatchesApkBaseline(row, baseline)) {
                hasDrift = true
                val files = row?.optJSONArray("files") ?: JSONArray()
                for (index in 0 until files.length()) {
                    files.optString(index).takeIf { it.isNotBlank() }?.let(trackedFiles::add)
                }
            }
        }
        apkByName.keys.forEach { name ->
            if (!installed.has(name)) hasDrift = true
        }
        return BaselineDrift(hasDrift, trackedFiles.sorted())
    }

    private fun apkBaselineRow(item: JSONObject, updatedAtMs: Long): JSONObject {
        return JSONObject()
            .put("name", item.optString("name"))
            .put("version", item.optString("version"))
            .put("active_version", item.optString("version"))
            .put("filename", item.optString("filename"))
            .put("sha256", item.optString("sha256").lowercase())
            .put("depends", JSONArray((item.optJSONArray("depends") ?: JSONArray()).toString()))
            .put("source", SOURCE_APK)
            .put("activation", ACTIVATION_APK_BASELINE)
            .put("files", JSONArray())
            .put("updated_at_ms", updatedAtMs)
    }

    private fun rowMatchesApkBaseline(row: JSONObject, item: JSONObject): Boolean {
        val expectedDepends = item.optJSONArray("depends") ?: return false
        val actualDepends = row.optJSONArray("depends") ?: return false
        val files = row.optJSONArray("files") ?: return false
        return row.optString("name") == item.optString("name") &&
            row.optString("version") == item.optString("version") &&
            row.optString("active_version") == item.optString("version") &&
            row.optString("filename") == item.optString("filename") &&
            row.optString("sha256").lowercase() == item.optString("sha256").lowercase() &&
            jsonStringArray(actualDepends) == jsonStringArray(expectedDepends) &&
            row.optString("source") == SOURCE_APK &&
            row.optString("activation") == ACTIVATION_APK_BASELINE &&
            files.length() == 0
    }

    private fun jsonStringArray(array: JSONArray): List<String> {
        return buildList {
            for (index in 0 until array.length()) add(array.optString(index))
        }
    }

    private fun apkPackagesFromState(state: JSONObject): JSONArray {
        return state.optJSONArray("apk_packages")
            ?: state.optJSONArray("packages")
            ?: JSONArray()
    }

    private fun authorityJson(authority: ApkAuthorityValidation): JSONObject {
        return JSONObject()
            .put("valid", authority.valid)
            .put("package_count", authority.packageCount)
            .put("tuple_digest_sha256", authority.tupleDigestSha256)
            .put("errors", JSONArray(authority.errors))
    }

    private fun isSafeRepositoryFilename(filename: String): Boolean {
        if (filename.isBlank() || filename != filename.trim()) return false
        if (!filename.startsWith("pool/") || '\\' in filename || "://" in filename) return false
        val parts = filename.split('/')
        return parts.size >= 3 && parts.none { it.isBlank() || it == "." || it == ".." }
    }

    private fun sha256Hex(payload: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
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
