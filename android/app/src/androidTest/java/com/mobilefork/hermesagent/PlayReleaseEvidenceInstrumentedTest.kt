package com.mobilefork.hermesagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayReleaseEvidenceInstrumentedTest {
    @Test fun everyRegisteredModelHasAStableEvidenceFileName() {
        for (model in VerifiedLocalModelArtifacts.releaseMatrix) {
            val case = "model-${model.modelId}"
            assertEquals("$case.json", PlayReleaseEvidence.recordFileName(case))
        }
    }

    @Test fun evidenceNamesCannotEscapeTheirRunDirectory() {
        for (case in listOf("", ".", "..", "../outside", "model/../../outside", "model\\outside", "/absolute", "C:outside", "x".repeat(81))) {
            assertThrows(IllegalArgumentException::class.java) { PlayReleaseEvidence.recordFileName(case) }
        }
    }
}
