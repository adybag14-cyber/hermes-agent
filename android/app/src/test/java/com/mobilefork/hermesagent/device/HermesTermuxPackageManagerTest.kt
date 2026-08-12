package com.mobilefork.hermesagent.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesTermuxPackageManagerTest {
    @Test
    fun parsePackagesIndex_readsDiscoveryFieldsWithoutGrantingAuthority() {
        val text = """
            Package: proot
            Version: 5.1.107.84
            Filename: pool/main/p/proot/proot_5.1.107.84_aarch64.deb
            SHA256: 59ace3b02894a9b87348eb5ccf246ed52ec64465021839422a151d7128acfe97
            Depends: libandroid-shmem, libtalloc

            Package: proot-distro
            Version: 5.4.0-1
            Filename: pool/main/p/proot-distro/proot-distro_5.4.0-1_all.deb
            SHA256: 99ffb654f4d16bd3b1222e0fb800144cc1ba2b6dfe4b4f8a8b9f4e29ec250efe
            Depends: proot

        """.trimIndent()

        val index = HermesTermuxPackageManager.parsePackagesIndex(text)

        assertEquals(2, index.size)
        assertEquals("5.1.107.84", index.getValue("proot").version)
        assertEquals(listOf("libandroid-shmem", "libtalloc"), index.getValue("proot").depends)
        assertTrue(index.containsKey("proot-distro"))
    }

    @Test
    fun parseDepends_skipsIgnoredTermuxPackages() {
        val deps = HermesTermuxPackageManager.parseDepends(
            "libtalloc, termux-exec | busybox, coreutils",
        )

        assertEquals(listOf("libtalloc", "busybox", "coreutils"), deps)
        assertFalse(deps.contains("termux-exec"))
    }

    @Test
    fun isPkgCommand_detectsHostPkgInvocations() {
        assertTrue(HermesTermuxPackageManager.isPkgCommand("pkg upgrade proot"))
        assertTrue(HermesTermuxPackageManager.isPkgCommand("hermes-pkg update"))
        assertFalse(HermesTermuxPackageManager.isPkgCommand("apt-get update"))
        assertFalse(HermesTermuxPackageManager.isPkgCommand("proot-distro list"))
    }

    @Test
    fun signedApkAuthority_acceptsCompleteContentAddressedDependencyClosure() {
        val packages = apkPackages()

        val result = HermesTermuxPackageManager.validateApkPackageAuthority(packages)

        assertTrue(result.valid)
        assertEquals(packages.length(), result.packageCount)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.tupleDigestSha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            result.tupleDigestSha256,
            HermesTermuxPackageManager.validateApkPackageAuthority(apkPackages()).tupleDigestSha256,
        )
    }

    @Test
    fun signedApkAuthority_rejectsUnresolvedDependencyAndUnsafeTuple() {
        val packages = apkPackages()
        packages.getJSONObject(0)
            .put("filename", "https://mirror.invalid/bash.deb")
            .put("sha256", "mirror-supplied")
        packages.getJSONObject(1).put("depends", JSONArray(listOf("missing-native-runtime")))

        val result = HermesTermuxPackageManager.validateApkPackageAuthority(packages)

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("unsafe filename") })
        assertTrue(result.errors.any { it.contains("invalid sha256") })
        assertTrue(result.errors.any { it.contains("unresolved dependency 'missing-native-runtime'") })
    }

    @Test
    fun everyHostMutationFailsClosedWithoutChangingActiveVersion() {
        listOf("update", "upgrade", "install", "remove").forEach { action ->
            val status = JSONObject()
                .put("ok", true)
                .put("packages", JSONArray().put(JSONObject().put("name", "bash").put("active_version", "1")))
            val before = status.toString()

            val result = HermesTermuxPackageManager.immutableHostMutationDecision(
                statusSnapshot = status,
                action = action,
                requested = if (action == "update") emptyList() else listOf("bash"),
                apkPackages = apkPackages(),
            )

            assertEquals(before, status.toString())
            assertFalse(result.getBoolean("ok"))
            assertEquals(1, result.getInt("exit_code"))
            assertEquals("signed_apk_content_addressed_baseline", result.getString("trust_policy"))
            assertEquals("signed_apk_asset_manifest", result.getString("authoritative_source"))
            assertEquals("untrusted_discovery_only", result.getString("live_mirror_authority"))
            assertFalse(result.getBoolean("mutation_permitted"))
            assertFalse(result.getBoolean("active_version_changed"))
            assertEquals(0, result.getInt("bytes_activated"))
            assertTrue(result.getBoolean("requires_signed_apk_update"))
            assertTrue(result.getJSONObject("apk_authority").getBoolean("valid"))
        }
    }

    @Test
    fun installRejectionClassifiesUnknownPackageWithoutGrantingMirrorAuthority() {
        val result = HermesTermuxPackageManager.immutableHostMutationDecision(
            statusSnapshot = JSONObject().put("packages", JSONArray()),
            action = "install",
            requested = listOf("mirror-only-package"),
            apkPackages = apkPackages(),
        )

        val assessment = result.getJSONArray("requested_assessment").getJSONObject(0)
        assertEquals("mirror-only-package", assessment.getString("name"))
        assertFalse(assessment.getBoolean("present_in_signed_apk_baseline"))
        assertFalse(result.getBoolean("mutation_permitted"))
        assertEquals(0, result.getInt("bytes_activated"))
    }

    @Test
    fun reconciliationRestoresEveryApkTupleFieldAndRemovesMirrorOnlyRows() {
        val apkPackages = apkPackages()
        val installed = JSONObject()
            .put(
                "bash",
                JSONObject()
                    .put("name", "bash")
                    .put("version", "999-mirror")
                    .put("active_version", "999-mirror")
                    .put("filename", "pool/mirror/bash.deb")
                    .put("sha256", "f".repeat(64))
                    .put("depends", JSONArray(listOf("mirror-dependency")))
                    .put("source", "ota")
                    .put("activation", "active_ota_script_data")
                    .put("files", JSONArray(listOf("bin/bash"))),
            )
            .put(
                "mirror-only-package",
                JSONObject()
                    .put("name", "mirror-only-package")
                    .put("version", "1")
                    .put("active_version", "1")
                    .put("source", "ota")
                    .put("files", JSONArray(listOf("bin/mirror-only"))),
            )
        val deferred = JSONObject().put(
            "proot",
            JSONObject().put("available_version", "mirror-version"),
        )

        val changed = HermesTermuxPackageManager.reconcileApkBaselineRows(
            apkPackages = apkPackages,
            installed = installed,
            deferred = deferred,
            updatedAtMs = 1234L,
        )

        assertTrue(changed)
        assertEquals(apkPackages.length(), installed.length())
        assertFalse(installed.has("mirror-only-package"))
        val bash = installed.getJSONObject("bash")
        val expectedBash = apkPackages.getJSONObject(0)
        assertEquals(expectedBash.getString("version"), bash.getString("version"))
        assertEquals(expectedBash.getString("version"), bash.getString("active_version"))
        assertEquals(expectedBash.getString("filename"), bash.getString("filename"))
        assertEquals(expectedBash.getString("sha256"), bash.getString("sha256"))
        assertEquals(expectedBash.getJSONArray("depends").toString(), bash.getJSONArray("depends").toString())
        assertEquals("apk_baseline", bash.getString("source"))
        assertEquals("active_apk_baseline", bash.getString("activation"))
        assertEquals(0, bash.getJSONArray("files").length())
        assertEquals(1234L, bash.getLong("updated_at_ms"))
        assertEquals(0, deferred.length())
    }

    @Test
    fun dependencyTupleMismatchIsRestoredEvenWhenVersionMatches() {
        val apkPackages = apkPackages()
        val expected = apkPackages.getJSONObject(1)
        val installed = JSONObject().put(
            "proot",
            JSONObject()
                .put("name", "proot")
                .put("version", expected.getString("version"))
                .put("active_version", expected.getString("version"))
                .put("filename", expected.getString("filename"))
                .put("sha256", expected.getString("sha256"))
                .put("depends", JSONArray(listOf("mirror-substitute")))
                .put("source", "apk_baseline")
                .put("activation", "active_apk_baseline")
                .put("files", JSONArray()),
        )

        assertTrue(
            HermesTermuxPackageManager.reconcileApkBaselineRows(
                apkPackages = apkPackages,
                installed = installed,
                deferred = JSONObject(),
                updatedAtMs = 222L,
            ),
        )
        assertEquals(
            expected.getJSONArray("depends").toString(),
            installed.getJSONObject("proot").getJSONArray("depends").toString(),
        )
    }

    @Test
    fun invalidApkAuthorityNeverMutatesInstalledState() {
        val invalidAuthority = apkPackages().apply {
            getJSONObject(0).put("sha256", "not-a-digest")
        }
        val installed = JSONObject().put(
            "bash",
            JSONObject().put("active_version", "preserve-me"),
        )
        val deferred = JSONObject().put("bash", JSONObject().put("available_version", "also-preserve"))
        val installedBefore = installed.toString()
        val deferredBefore = deferred.toString()

        val changed = HermesTermuxPackageManager.reconcileApkBaselineRows(
            apkPackages = invalidAuthority,
            installed = installed,
            deferred = deferred,
            updatedAtMs = 333L,
        )

        assertFalse(changed)
        assertEquals(installedBefore, installed.toString())
        assertEquals(deferredBefore, deferred.toString())
    }

    @Test
    fun baselineDriftTracksOnlyFilesNamedByNonAuthoritativeRows() {
        val apkPackages = apkPackages()
        val installed = JSONObject()
            .put(
                "bash",
                JSONObject()
                    .put("name", "bash")
                    .put("version", "mirror")
                    .put("files", JSONArray(listOf("bin/bash", "share/mirror-data"))),
            )
            .put(
                "extra",
                JSONObject()
                    .put("name", "extra")
                    .put("files", JSONArray(listOf("bin/extra"))),
            )

        val drift = HermesTermuxPackageManager.inspectBaselineDrift(apkPackages, installed)

        assertTrue(drift.hasDrift)
        assertEquals(listOf("bin/bash", "bin/extra", "share/mirror-data"), drift.trackedFiles)
    }

    private fun apkPackages(): JSONArray {
        return JSONArray()
            .put(
                JSONObject()
                    .put("name", "bash")
                    .put("version", "5.3.9-1")
                    .put("filename", "pool/main/b/bash/bash_5.3.9-1_aarch64.deb")
                    .put("sha256", "c2369162988d7a76ea9386446a6013be3cfb763bb6283704f6b47759b39de50b")
                    .put("depends", JSONArray()),
            )
            .put(
                JSONObject()
                    .put("name", "proot")
                    .put("version", "5.1.107.84")
                    .put("filename", "pool/main/p/proot/proot_5.1.107.84_aarch64.deb")
                    .put("sha256", "59ace3b02894a9b87348eb5ccf246ed52ec64465021839422a151d7128acfe97")
                    .put("depends", JSONArray(listOf("bash"))),
            )
    }
}
