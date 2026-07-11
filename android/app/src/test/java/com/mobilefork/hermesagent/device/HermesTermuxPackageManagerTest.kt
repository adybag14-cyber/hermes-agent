package com.mobilefork.hermesagent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesTermuxPackageManagerTest {
    @Test
    fun parsePackagesIndex_readsCoreFields() {
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
            Depends: proot, python, python-pip

        """.trimIndent()
        val index = HermesTermuxPackageManager.parsePackagesIndex(text)
        assertEquals(2, index.size)
        val proot = index.getValue("proot")
        assertEquals("5.1.107.84", proot.version)
        assertEquals(listOf("libandroid-shmem", "libtalloc"), proot.depends)
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
    fun resolveDependencyClosure_ordersRootsAndDeps() {
        val index = mapOf(
            "proot" to HermesTermuxPackageManager.PackageRecord(
                name = "proot",
                version = "1",
                filename = "p.deb",
                sha256 = "a",
                depends = listOf("libtalloc"),
            ),
            "libtalloc" to HermesTermuxPackageManager.PackageRecord(
                name = "libtalloc",
                version = "1",
                filename = "l.deb",
                sha256 = "b",
                depends = emptyList(),
            ),
            "proot-distro" to HermesTermuxPackageManager.PackageRecord(
                name = "proot-distro",
                version = "1",
                filename = "d.deb",
                sha256 = "c",
                depends = listOf("proot"),
            ),
        )
        val ordered = HermesTermuxPackageManager.resolveDependencyClosure(index, listOf("proot-distro"))
        assertEquals(listOf("proot-distro", "proot", "libtalloc"), ordered.map { it.name })
    }

    @Test
    fun isPkgCommand_detectsHostPkgInvocations() {
        assertTrue(HermesTermuxPackageManager.isPkgCommand("pkg upgrade proot"))
        assertTrue(HermesTermuxPackageManager.isPkgCommand("hermes-pkg update"))
        assertFalse(HermesTermuxPackageManager.isPkgCommand("apt-get update"))
        assertFalse(HermesTermuxPackageManager.isPkgCommand("proot-distro list"))
    }

    @Test
    fun sha256_matchesKnownVector() {
        val payload = "abc".toByteArray()
        val digest = HermesTermuxDebExtractor.sha256Hex(payload)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest,
        )
    }
}
