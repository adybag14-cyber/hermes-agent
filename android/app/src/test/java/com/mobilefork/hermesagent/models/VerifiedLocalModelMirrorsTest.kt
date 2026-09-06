package com.mobilefork.hermesagent.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedLocalModelMirrorsTest {
    @Test
    fun mirrorsRetainExactUpstreamArtifactIdentityWithoutAcceptingChangedContent() {
        VerifiedLocalModelMirrors.mirrors.forEach { mirror ->
            val artifact = VerifiedLocalModelArtifacts.require(mirror.repoId, mirror.fileName)
            assertTrue(mirror.matches(artifact))
            assertEquals(artifact, VerifiedLocalModelArtifacts.find(mirror.downloadUrl, mirror.fileName))
            assertNull(VerifiedLocalModelMirrors.forArtifact(artifact.copy(sha256 = "0".repeat(64))))
            assertNull(VerifiedLocalModelMirrors.forArtifact(artifact.copy(revision = "1".repeat(40))))
        }
    }

    @Test
    fun onlyExactImmutablePublicUrlsReceiveMirrorIdentity() {
        val mirror = VerifiedLocalModelMirrors.mirrors.first()
        assertEquals(mirror, VerifiedLocalModelMirrors.fromExactUrl(mirror.downloadUrl))
        assertNull(VerifiedLocalModelMirrors.fromExactUrl(mirror.downloadUrl.replace("modelscope.cn", "modelscope.cn.example.com")))
        assertNull(VerifiedLocalModelMirrors.fromExactUrl(mirror.downloadUrl.replace(mirror.mirrorRevision, "master")))
        assertNull(VerifiedLocalModelMirrors.fromExactUrl(mirror.downloadUrl + "&token=not-allowed"))
        assertNull(VerifiedLocalModelMirrors.fromExactUrl(mirror.downloadUrl.replace(mirror.fileName, "another.litertlm")))
    }
}
