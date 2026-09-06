package com.mobilefork.hermesagent.models

/** Public mirrors are bound to both immutable repository revisions and exact model bytes. */
object VerifiedLocalModelMirrors {
    data class Mirror(
        val repoId: String,
        val fileName: String,
        val sourceRevision: String,
        val sourceSha256: String,
        val mirrorRevision: String,
        val researchOnly: Boolean = false,
    ) {
        val downloadUrl: String
            get() = "https://modelscope.cn/api/v1/models/$repoId/repo" +
                "?Revision=$mirrorRevision&FilePath=$fileName"

        fun matches(artifact: VerifiedLocalModelArtifacts.Artifact): Boolean =
            artifact.repoId == repoId && artifact.fileName == fileName &&
                artifact.revision == sourceRevision && artifact.sha256 == sourceSha256
    }

    val mirrors = listOf(
        Mirror(
            "Tdamre/MiniCPM5-1B-litert-lm", "MiniCPM5-1B-web.litertlm",
            "06e61f79c625f864391fbb33049b5b46d1bfd7a6",
            "a6d6d61fdfa0e04458fea344791d15ca304b54a40573e1b44ebab30c54d7bf1d",
            "ae6fd30fe6a5ae31fa4e591f0d096052d7ccdced",
        ),
        Mirror(
            "Tdamre/VibeThinker-3B-litert-lm", "VibeThinker-3B.litertlm",
            "9378fddbdce35a6ff818e0f08aa05dce6f1032aa",
            "4cd4a856ab9fb890223d927efd4ed37268ecd1fa78559a9d27bf21daa6b8c22f",
            "2e4e63744705c623dca12e10370544969fee42f1",
            researchOnly = true,
        ),
    )

    fun forArtifact(artifact: VerifiedLocalModelArtifacts.Artifact): Mirror? =
        mirrors.firstOrNull { it.matches(artifact) }

    // Do not grant artifact identity to a moving revision, alternate host, URL
    // with credentials, changed query, or another file merely sharing a name.
    fun fromExactUrl(url: String): Mirror? = mirrors.firstOrNull { it.downloadUrl == url.trim() }
}
