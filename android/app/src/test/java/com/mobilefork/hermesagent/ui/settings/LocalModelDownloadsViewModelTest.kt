package com.mobilefork.hermesagent.ui.settings

import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalModelDownloadsViewModelTest {
    @Test
    fun pendingAutoStartSurvivesRecreationAndClearsExactlyOnceAfterAcceptedHandoff() {
        val application = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(application)
        val originalPendingId = store.pendingAutoStartRecordId()
        val recordId = "download-awaiting-runtime-handoff"

        try {
            store.setPendingAutoStartRecordId(recordId)
            val first = LocalModelDownloadsViewModel(application) { "" }
            assertEquals(recordId, first.uiState.value.pendingAutoStartRecordId)

            assertFalse(first.completePendingAutoStartHandoff(recordId, accepted = false))
            assertEquals(recordId, store.pendingAutoStartRecordId())

            val recreated = LocalModelDownloadsViewModel(application) { "" }
            assertEquals(recordId, recreated.uiState.value.pendingAutoStartRecordId)
            assertTrue(recreated.completePendingAutoStartHandoff(recordId, accepted = true))
            assertEquals("", store.pendingAutoStartRecordId())
            assertEquals("", recreated.uiState.value.pendingAutoStartRecordId)

            assertFalse(recreated.completePendingAutoStartHandoff(recordId, accepted = true))
        } finally {
            store.setPendingAutoStartRecordId(originalPendingId)
        }
    }
}
