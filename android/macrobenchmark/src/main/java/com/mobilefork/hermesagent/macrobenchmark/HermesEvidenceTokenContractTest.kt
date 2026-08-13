package com.mobilefork.hermesagent.macrobenchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HermesEvidenceTokenContractTest {
    @Test
    fun frameMetricRequiresExactlyOneTargetProcessPlaceholder() {
        val placeholder = "__HERMES_TARGET_PROCESS_SQL_PREDICATE__"
        val valid = "SELECT * FROM process WHERE $placeholder"

        assertEquals(valid, requireSingleTargetProcessPlaceholder(valid))
        assertThrows(IllegalStateException::class.java) {
            requireSingleTargetProcessPlaceholder("SELECT * FROM process")
        }
        assertThrows(IllegalStateException::class.java) {
            requireSingleTargetProcessPlaceholder("$placeholder OR $placeholder")
        }
    }

    @Test
    fun canonicalIdentityProducesStable52BitToken() {
        val token = hermesEvidenceToken(
            sourceDigest = "a".repeat(64),
            targetApkSha256 = "b".repeat(64),
            benchmarkApkSha256 = "c".repeat(64),
            evidenceRunId = "v0.13.147-phone-0001",
            evidenceProfile = "phone-compact",
            avdName = "Medium_Phone_API_35",
            bootId = "12345678-1234-4abc-8def-1234567890ab",
        )

        // SHA-256 begins da25938030b3d... for the newline-delimited v2 identity.
        assertEquals(3_837_678_772_751_165L, token)
        assertEquals(token, token.toDouble().toLong())
    }
}
