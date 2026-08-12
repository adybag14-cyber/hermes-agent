package com.mobilefork.hermesagent.device

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Extract Termux .deb packages into a Hermes prefix directory.
 * Mirrors scripts/prepare_android_linux_assets.py path stripping + shebang rewrite.
 */
object HermesTermuxDebExtractor {
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private val ELF_MAGIC = byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
    private val SHEBANG_PREFIX_RE =
        Regex("^#!" + Regex.escape(TERMUX_PREFIX) + "/bin/([^\\n\\r/]+)")

    data class ExtractResult(
        val files: List<String>,
        val links: List<Pair<String, String>>,
    )

    data class PackageInspection(
        val files: List<String>,
        val nativeCodeFiles: List<String>,
    )

    fun sha256Hex(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(payload: ByteArray, expected: String) {
        val actual = sha256Hex(payload)
        if (!actual.equals(expected.trim(), ignoreCase = true)) {
            throw IllegalStateException("SHA256 mismatch: expected $expected, got $actual")
        }
    }

    fun extractDebToPrefix(debBytes: ByteArray, prefixDir: File): ExtractResult {
        val (dataBytes, memberName) = loadDataTarBytesFromDeb(debBytes)
        return extractDataTarToPrefix(dataBytes, memberName, prefixDir)
    }

    /** Inspect a .deb without writing any package payload into the live prefix. */
    fun inspectDeb(debBytes: ByteArray): PackageInspection {
        val (dataBytes, memberName) = loadDataTarBytesFromDeb(debBytes)
        val files = mutableListOf<String>()
        val nativeCodeFiles = mutableListOf<String>()
        openDataTar(dataBytes, memberName).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relative = archiveTermuxRelative(entry.name)
                if (relative != null && relative.isNotEmpty() && entry.isFile) {
                    files.add(relative)
                    if (streamStartsWithElfMagic(tar)) {
                        nativeCodeFiles.add(relative)
                    }
                }
                entry = tar.nextEntry
            }
        }
        return PackageInspection(
            files = files.distinct().sorted(),
            nativeCodeFiles = nativeCodeFiles.distinct().sorted(),
        )
    }

    fun isElfFile(file: File): Boolean {
        if (!file.isFile || file.length() < ELF_MAGIC.size) return false
        return runCatching {
            file.inputStream().use(::streamStartsWithElfMagic)
        }.getOrDefault(false)
    }

    fun loadDataTarBytesFromDeb(payload: ByteArray): Pair<ByteArray, String> {
        val stream = ByteArrayInputStream(payload)
        val magic = ByteArray(8)
        if (stream.read(magic) != 8 || String(magic, StandardCharsets.US_ASCII) != "!<arch>\n") {
            throw IllegalArgumentException("Not a valid ar archive (.deb)")
        }
        while (true) {
            val header = ByteArray(60)
            val headerRead = stream.read(header)
            if (headerRead < 0) break
            if (headerRead != 60) {
                throw IllegalArgumentException("Truncated ar archive header")
            }
            val name = String(header, 0, 16, StandardCharsets.US_ASCII).trim().trimEnd('/')
            val sizeField = String(header, 48, 10, StandardCharsets.US_ASCII).trim()
            val size = sizeField.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid ar member size: $sizeField")
            val filePayload = stream.readNBytes(size)
            if (size % 2 == 1) {
                stream.read()
            }
            if (name.startsWith("data.tar")) {
                return filePayload to name
            }
        }
        throw IllegalArgumentException("data.tar member not found in deb archive")
    }

    fun extractDataTarToPrefix(
        dataBytes: ByteArray,
        memberName: String,
        prefixDir: File,
    ): ExtractResult {
        prefixDir.mkdirs()
        val files = mutableListOf<String>()
        val links = mutableListOf<Pair<String, String>>()
        openDataTar(dataBytes, memberName).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relative = archiveTermuxRelative(entry.name)
                if (relative == null) {
                    entry = tar.nextEntry
                    continue
                }
                if (relative.isEmpty()) {
                    prefixDir.mkdirs()
                    entry = tar.nextEntry
                    continue
                }
                val destination = stagingDestination(prefixDir, relative)
                when {
                    entry.isDirectory -> destination.mkdirs()
                    entry.isSymbolicLink -> {
                        val target = normalizeArchiveLinkTarget(relative, entry.linkName)
                        if (target != null) {
                            links.add(relative to target)
                        }
                    }
                    entry.isLink -> {
                        val target = normalizeArchiveHardlinkTarget(relative, entry.linkName)
                        if (target != null) {
                            links.add(relative to target)
                        }
                    }
                    entry.isFile -> {
                        destination.parentFile?.mkdirs()
                        val raw = tar.readBytes()
                        val payload = normalizeTextShebang(raw)
                        destination.writeBytes(payload)
                        if (
                            relative.startsWith("bin/") ||
                            relative.startsWith("libexec/") ||
                            entry.mode and 0b001001001 != 0
                        ) {
                            destination.setExecutable(true, false)
                        }
                        files.add(relative)
                    }
                }
                entry = tar.nextEntry
            }
        }
        for ((linkPath, targetPath) in links) {
            val linkFile = File(prefixDir, linkPath)
            val targetFile = File(prefixDir, targetPath)
            if (!targetFile.exists()) continue
            linkFile.parentFile?.mkdirs()
            if (linkFile.exists()) {
                linkFile.delete()
            }
            runCatching {
                android.system.Os.symlink(targetFile.absolutePath, linkFile.absolutePath)
            }.onFailure {
                linkFile.writeBytes(targetFile.readBytes())
                linkFile.setExecutable(targetFile.canExecute(), false)
            }
            files.add(linkPath)
        }
        return ExtractResult(files = files.distinct().sorted(), links = links)
    }

    private fun openDataTar(dataBytes: ByteArray, memberName: String): TarArchiveInputStream {
        val raw: InputStream = ByteArrayInputStream(dataBytes)
        val decompressed: InputStream = when {
            memberName.endsWith(".xz") -> XZCompressorInputStream(raw)
            memberName.endsWith(".gz") -> GzipCompressorInputStream(raw)
            memberName.endsWith(".bz2") -> BZip2CompressorInputStream(raw)
            else -> raw
        }
        return TarArchiveInputStream(decompressed)
    }

    private fun streamStartsWithElfMagic(input: InputStream): Boolean {
        val header = ByteArray(ELF_MAGIC.size)
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read < 0) break
            if (read == 0) continue
            offset += read
        }
        return offset == header.size && header.contentEquals(ELF_MAGIC)
    }

    private fun normalizeTextShebang(content: ByteArray): ByteArray {
        val text = runCatching { String(content, StandardCharsets.UTF_8) }.getOrNull() ?: return content
        val lines = text.split(Regex("(?<=\\n)"))
        if (lines.isEmpty()) return content
        val first = lines[0]
        val match = SHEBANG_PREFIX_RE.find(first) ?: return content
        val interpreter = match.groupValues[1]
        val suffix = first.removePrefix(match.value)
        val rewritten = "#!/usr/bin/env $interpreter$suffix"
        return (listOf(rewritten) + lines.drop(1)).joinToString("").toByteArray(StandardCharsets.UTF_8)
    }

    private fun archiveTermuxRelative(path: String): String? {
        val normalized = path.replace('\\', '/').trimStart('/').let {
            java.nio.file.Paths.get(it).normalize().toString().replace('\\', '/')
        }
        val stripped = stripTermuxPrefix(normalized) ?: return null
        if (stripped == "." || stripped.isEmpty()) return ""
        if (stripped.startsWith("../")) return null
        return stripped
    }

    private fun stripTermuxPrefix(path: String): String? {
        val normalized = path.trimStart('/')
        val prefix = TERMUX_PREFIX.trimStart('/') + "/"
        if (!normalized.startsWith(prefix)) {
            // Some debs use ./data/data/com.termux/files/usr/...
            val idx = normalized.indexOf(prefix)
            if (idx < 0) return null
            return normalized.substring(idx + prefix.length)
        }
        return normalized.removePrefix(prefix)
    }

    private fun stagingDestination(prefixDir: File, relative: String): File {
        val parts = relative.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.any { it == ".." }) {
            throw IllegalArgumentException("Unsafe archive member path: $relative")
        }
        return parts.fold(prefixDir) { acc, part -> File(acc, part) }
    }

    private fun normalizeArchiveLinkTarget(sourceRelative: String, target: String): String? {
        stripTermuxPrefix(target.replace('\\', '/').trimStart('.').trimStart('/'))?.let {
            if (it.isNotBlank() && !it.startsWith("../")) return it
        }
        val normalized = target.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        val parent = sourceRelative.substringBeforeLast('/', missingDelimiterValue = "")
        val joined = if (parent.isBlank()) normalized else "$parent/$normalized"
        val resolved = java.nio.file.Paths.get(joined).normalize().toString().replace('\\', '/')
        if (resolved == "." || resolved.startsWith("../")) return null
        return resolved
    }

    private fun normalizeArchiveHardlinkTarget(sourceRelative: String, target: String): String? {
        stripTermuxPrefix(target.replace('\\', '/'))?.let {
            if (it.isNotBlank() && !it.startsWith("../")) return it
        }
        val normalized = target.replace('\\', '/').trimStart('.').trimStart('/')
        if (normalized.isNotBlank() && !normalized.startsWith("../")) return normalized
        return normalizeArchiveLinkTarget(sourceRelative, target)
    }
}
