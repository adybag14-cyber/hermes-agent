package com.mobilefork.hermesagent.backend

import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsPersistenceException
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceBackendRuntimeLaneReconciliationTest {
    @Test
    fun verifiedRequiredLaneIsPersistedBeforeItsSettingsBecomeLaunchable() {
        val current = AppSettings(
            provider = "custom",
            llamaCppRuntimeLane = "stable",
            llamaCppCacheTypeK = "turbo3",
            llamaCppCacheTypeV = "turbo3",
            llamaCppFlashAttention = "on",
            localModelToolMode = "small",
        )
        var persistedLane: String? = null

        val result = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = current,
            verifiedArtifact = requiredLaneArtifact(),
            persistRequiredLane = { requiredLane ->
                persistedLane = requiredLane
                current.copy(llamaCppRuntimeLane = requiredLane)
            },
        )

        assertTrue(result is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready)
        result as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready
        assertTrue(result.persistedRequiredLane)
        assertEquals("turboquant", persistedLane)
        assertEquals("turboquant", result.settings.llamaCppRuntimeLane)
        assertEquals("turbo3", result.settings.llamaCppCacheTypeK)
        assertEquals("turbo3", result.settings.llamaCppCacheTypeV)
        assertEquals("on", result.settings.llamaCppFlashAttention)
        assertEquals("small", result.settings.localModelToolMode)
    }

    @Test
    fun stableRequirementIsPersistedExactly() {
        val current = AppSettings(llamaCppRuntimeLane = "turboquant")
        var persistedLane: String? = null

        val result = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = current,
            verifiedArtifact = requiredLaneArtifact().copy(requiredLlamaCppRuntimeLane = " STABLE "),
            persistRequiredLane = { requiredLane ->
                persistedLane = requiredLane
                current.copy(llamaCppRuntimeLane = requiredLane)
            },
        )

        assertTrue(result is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready)
        result as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready
        assertTrue(result.persistedRequiredLane)
        assertEquals("stable", persistedLane)
        assertEquals("stable", result.settings.llamaCppRuntimeLane)
    }

    @Test
    fun experimentalRequirementUsesCanonicalTurboquantLane() {
        val current = AppSettings(llamaCppRuntimeLane = "stable")
        var persistedLane: String? = null

        val result = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = current,
            verifiedArtifact = requiredLaneArtifact().copy(requiredLlamaCppRuntimeLane = " experimental "),
            persistRequiredLane = { requiredLane ->
                persistedLane = requiredLane
                current.copy(llamaCppRuntimeLane = requiredLane)
            },
        )

        assertTrue(result is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready)
        result as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready
        assertTrue(result.persistedRequiredLane)
        assertEquals("turboquant", persistedLane)
        assertEquals("turboquant", result.settings.llamaCppRuntimeLane)
    }

    @Test
    fun onlyNullOrBlankLaneRequirementsPreserveWithoutPersistence() {
        val current = AppSettings(llamaCppRuntimeLane = "turboquant")
        var persistenceCalls = 0
        val persist: (String) -> AppSettings = {
            persistenceCalls += 1
            current.copy(llamaCppRuntimeLane = it)
        }
        val laneNeutralArtifact = VerifiedLocalModelArtifacts.releaseMatrix.first {
            it.runtime == "llama.cpp" && it.requiredLlamaCppRuntimeLane == null
        }

        val neutral = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = current,
            verifiedArtifact = laneNeutralArtifact,
            persistRequiredLane = persist,
        )
        val unknown = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = current,
            verifiedArtifact = null,
            persistRequiredLane = persist,
        )
        val blankLane = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = current,
            verifiedArtifact = requiredLaneArtifact().copy(requiredLlamaCppRuntimeLane = "  \t "),
            persistRequiredLane = persist,
        )

        assertTrue(neutral is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready)
        assertTrue(unknown is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready)
        assertTrue(blankLane is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready)
        neutral as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready
        unknown as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready
        blankLane as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready
        assertFalse(neutral.persistedRequiredLane)
        assertFalse(unknown.persistedRequiredLane)
        assertFalse(blankLane.persistedRequiredLane)
        assertSame(current, neutral.settings)
        assertSame(current, unknown.settings)
        assertSame(current, blankLane.settings)
        assertEquals(0, persistenceCalls)
    }

    @Test
    fun unknownNonblankRequirementFailsClosedWithoutPersistence() {
        val current = AppSettings(llamaCppRuntimeLane = "stable")
        var persistenceCalls = 0

        val result = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = current,
            verifiedArtifact = requiredLaneArtifact().copy(
                requiredLlamaCppRuntimeLane = " Future-Lane ",
            ),
            persistRequiredLane = {
                persistenceCalls += 1
                current.copy(llamaCppRuntimeLane = it)
            },
        )

        assertTrue(
            result is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.UnsupportedRequiredLane,
        )
        result as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.UnsupportedRequiredLane
        assertEquals("Future-Lane", result.requiredLane)
        assertEquals(0, persistenceCalls)
    }

    @Test
    fun alreadySelectedRequiredLaneDoesNotRewriteSettings() {
        val current = AppSettings(llamaCppRuntimeLane = "turboquant")
        var persistenceCalls = 0

        val result = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = current,
            verifiedArtifact = requiredLaneArtifact(),
            persistRequiredLane = {
                persistenceCalls += 1
                current
            },
        )

        assertTrue(result is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready)
        result as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready
        assertFalse(result.persistedRequiredLane)
        assertSame(current, result.settings)
        assertEquals(0, persistenceCalls)
    }

    @Test
    fun requiredLanePersistenceFailureReturnsNoLaunchSettings() {
        val failure = AppSettingsPersistenceException("simulated settings commit failure")

        val result = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = AppSettings(llamaCppRuntimeLane = "stable"),
            verifiedArtifact = requiredLaneArtifact(),
            persistRequiredLane = { throw failure },
        )

        assertTrue(result is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.PersistenceFailure)
        result as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.PersistenceFailure
        assertEquals("turboquant", result.requiredLane)
        assertSame(failure, result.cause)
    }

    @Test
    fun persistenceThatDoesNotReturnRequiredLaneFailsClosed() {
        val current = AppSettings(llamaCppRuntimeLane = "stable")

        val result = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = current,
            verifiedArtifact = requiredLaneArtifact(),
            persistRequiredLane = { current },
        )

        assertTrue(result is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.PersistenceFailure)
        result as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.PersistenceFailure
        assertEquals("turboquant", result.requiredLane)
        assertTrue(result.cause.message.orEmpty().contains("not present after settings persistence"))
    }

    private fun requiredLaneArtifact(): VerifiedLocalModelArtifacts.Artifact {
        return VerifiedLocalModelArtifacts.releaseMatrix.first {
            it.runtime == "llama.cpp" && it.requiredLlamaCppRuntimeLane != null
        }
    }
}
