package com.mobilefork.hermesagent.models

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Content-addressed artifacts used by the Android release model matrix.
 *
 * A pinned publisher revision identifies the remote source, while exact byte length and SHA-256
 * identify the downloaded bytes. Release/device tests must verify both byte properties before
 * claiming a model passed.
 */
object VerifiedLocalModelArtifacts {
    data class Artifact(
        val modelId: String,
        val repoId: String,
        val revision: String,
        val fileName: String,
        val runtime: String,
        val expectedBytes: Long,
        val sha256: String,
        val validationEvidence: String,
        val remoteManifestMatches: Boolean,
    )

    data class Verification(
        val valid: Boolean,
        val actualBytes: Long,
        val actualSha256: String,
        val detail: String,
    )

    val releaseMatrix: List<Artifact> = listOf(
        Artifact(
            modelId = "minicpm5-1b-web-litert-lm",
            repoId = "Tdamre/MiniCPM5-1B-litert-lm",
            revision = "06e61f79c625f864391fbb33049b5b46d1bfd7a6",
            fileName = "MiniCPM5-1B-web.litertlm",
            runtime = "litert-lm",
            expectedBytes = 1_103_486_896L,
            sha256 = "a6d6d61fdfa0e04458fea344791d15ca304b54a40573e1b44ebab30c54d7bf1d",
            validationEvidence = "publisher conversion manifest; current release-device completion required",
            remoteManifestMatches = true,
        ),
        Artifact(
            modelId = "vibethinker-3b-litert-lm",
            repoId = "Tdamre/VibeThinker-3B-litert-lm",
            revision = "9378fddbdce35a6ff818e0f08aa05dce6f1032aa",
            fileName = "VibeThinker-3B.litertlm",
            runtime = "litert-lm",
            expectedBytes = 3_446_780_848L,
            sha256 = "4cd4a856ab9fb890223d927efd4ed37268ecd1fa78559a9d27bf21daa6b8c22f",
            validationEvidence = "content-addressed Android release fixture; generic artifact still requires release-device completion",
            remoteManifestMatches = true,
        ),
        Artifact(
            modelId = "qwen3.5-0.8b-q4-k-m",
            repoId = "unsloth/Qwen3.5-0.8B-GGUF",
            revision = "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0",
            fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
            runtime = "llama.cpp",
            expectedBytes = 532_517_120L,
            sha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
            validationEvidence = "content-addressed Android release fixture; current release-device completion required",
            remoteManifestMatches = false,
        ),
        Artifact(
            modelId = "minicpm5-1b-fable5-q4-k-m",
            repoId = "GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF",
            revision = "5a4ed2c3605634e7b043e8b98fa01e504b0dfbed",
            fileName = "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-Q4_K_M.gguf",
            runtime = "llama.cpp",
            expectedBytes = 688_066_496L,
            sha256 = "b1c3bf2995e96cb792a0031e4e1497a500e9244c68ba17c24a7e6edf1fc59019",
            validationEvidence = "content-addressed Android release fixture; current release-device completion required",
            remoteManifestMatches = false,
        ),
    );

    fun find(repoOrUrl: String, filePathOrName: String): Artifact? {
        val normalizedRepo = normalizeRepo(repoOrUrl)
        val fileName = filePathOrName.substringBefore('?').substringAfterLast('/')
        return releaseMatrix.firstOrNull { artifact ->
            artifact.repoId.equals(normalizedRepo, ignoreCase = true) &&
                artifact.fileName.equals(fileName, ignoreCase = true)
        }
    }

    fun findByFileName(filePathOrName: String): Artifact? {
        val fileName = filePathOrName.substringBefore('?').substringAfterLast('/')
        return releaseMatrix.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
    }

    fun require(repoOrUrl: String, filePathOrName: String): Artifact {
        return requireNotNull(find(repoOrUrl, filePathOrName)) {
            "No verified local-model artifact is registered for $repoOrUrl/$filePathOrName"
        }
    }

    fun verify(file: File, artifact: Artifact): Verification {
        if (!file.isFile) {
            return Verification(false, 0L, "", "${artifact.fileName} is not present at ${file.absolutePath}")
        }
        val actualBytes = file.length()
        if (actualBytes != artifact.expectedBytes) {
            return Verification(
                valid = false,
                actualBytes = actualBytes,
                actualSha256 = "",
                detail = "Byte-size mismatch for ${artifact.fileName}: expected ${artifact.expectedBytes}, found $actualBytes",
            )
        }
        val actualSha = sha256(file)
        val valid = actualSha.equals(artifact.sha256, ignoreCase = true)
        return Verification(
            valid = valid,
            actualBytes = actualBytes,
            actualSha256 = actualSha,
            detail = if (valid) {
                "Exact byte length and SHA-256 verified for ${artifact.repoId}/${artifact.fileName}"
            } else {
                "SHA-256 mismatch for ${artifact.fileName}: expected ${artifact.sha256}, found $actualSha"
            },
        )
    }

    /**
     * Re-hash immediately before every native launch. A path/size/mtime cache can be
     * bypassed by an in-place same-size replacement with a restored timestamp.
     */
    fun verifyCached(file: File, artifact: Artifact): Verification = verify(file, artifact)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    }

    private fun normalizeRepo(repoOrUrl: String): String {
        val trimmed = repoOrUrl.trim().removePrefix("hf://").trim('/')
        val marker = "huggingface.co/"
        val afterHost = trimmed.substringAfter(marker, trimmed).substringBefore("/blob/").substringBefore("/resolve/")
        return afterHost.split('/').take(2).joinToString("/")
    }

}
