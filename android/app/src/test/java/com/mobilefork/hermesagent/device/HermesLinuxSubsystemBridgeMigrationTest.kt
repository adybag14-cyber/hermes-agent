package com.mobilefork.hermesagent.device

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesLinuxSubsystemBridgeMigrationTest {
    @Test
    fun publishedV146ManifestDriftToV147PreservesMutableState() {
        val previousLayoutVersion = 6
        val installRoot = Files.createTempDirectory("hermes-v146-v147-migration-").toFile()
        val prefix = installRoot.resolve("prefix")
        val managedTool = prefix.resolve("bin/proot-distro")
        val homeSentinel = prefix.resolve("home/.hermes-user-sentinel")
        val packageState = installRoot.resolve("var/lib/hermes-pkg/status.json")
        val rootfsSentinel = prefix.resolve(
            "var/lib/proot-distro/containers/hermes-debian/rootfs/etc/hermes-release-sentinel",
        )
        val cacheSentinel = prefix.resolve("var/lib/proot-distro/cache/debian-bookworm.fixture")
        val oldManagedBytes = "v146 managed tool".toByteArray()
        val newManagedBytes = "v147 managed tool with direct APK-native execution".toByteArray()
        val homeBytes = "user-home-preserved".toByteArray()
        val packageBytes = "{\"user_package_state\":true}".toByteArray()
        val rootfsBytes = "installed-debian-rootfs-preserved".toByteArray()
        val cacheBytes = "download-cache-preserved".toByteArray()

        try {
            listOf(managedTool, homeSentinel, packageState, rootfsSentinel, cacheSentinel)
                .forEach { it.parentFile?.mkdirs() }
            managedTool.writeBytes(oldManagedBytes)
            homeSentinel.writeBytes(homeBytes)
            packageState.writeBytes(packageBytes)
            rootfsSentinel.writeBytes(rootfsBytes)
            cacheSentinel.writeBytes(cacheBytes)

            val result = HermesLinuxSubsystemBridge.refreshManagedPrefixFixture(
                prefixDir = prefix,
                managedFiles = mapOf("bin/proot-distro" to newManagedBytes),
                oldManifestSha256 = PUBLISHED_V146_X86_64_MANIFEST_SHA256,
                newManifestSha256 = V147_X86_64_MANIFEST_SHA256,
                oldLayoutVersion = previousLayoutVersion,
            )

            assertArrayEquals(newManagedBytes, managedTool.readBytes())
            assertArrayEquals(homeBytes, homeSentinel.readBytes())
            assertArrayEquals(packageBytes, packageState.readBytes())
            assertArrayEquals(rootfsBytes, rootfsSentinel.readBytes())
            assertArrayEquals(cacheBytes, cacheSentinel.readBytes())
            assertTrue(prefix.resolve("var/lib/proot-distro/containers/hermes-debian/rootfs").isDirectory)
            assertEquals(PUBLISHED_V146_X86_64_MANIFEST_SHA256, result.getString("previous_asset_manifest_sha256"))
            assertEquals(V147_X86_64_MANIFEST_SHA256, result.getString("asset_manifest_sha256"))
            val currentLayoutVersion = result.getInt("runtime_layout_version")
            assertEquals(previousLayoutVersion, result.getInt("previous_runtime_layout_version"))
            assertTrue(currentLayoutVersion > previousLayoutVersion)
            assertEquals("managed_overlay_preserve_mutable_state", result.getString("asset_refresh_mode"))
            assertTrue(result.getInt("asset_refresh_preserved_mutable_entries") >= 9)
        } finally {
            installRoot.deleteRecursively()
        }
    }

    private companion object {
        // Historical release fixtures, not assertions about future manifests.
        const val PUBLISHED_V146_X86_64_MANIFEST_SHA256 =
            "7511c4c9f330cb6a85ac700af37557f1abf7217bf366b16931d228288267e178"
        const val V147_X86_64_MANIFEST_SHA256 =
            "48080ae62154158883768ba7be473112ed19737cb3d6e1949851c708ac721d0e"
    }
}
