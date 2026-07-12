package com.mobilefork.hermesagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.models.HermesModelDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LegacyModelAutoImportInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun refreshDiscoversProvisionedLegacyModelAndRepairsPreferredSelection() {
        val store = LocalModelDownloadStore(context)
        val originalRecords = store.loadDownloads()
        val originalPreferred = store.preferredDownloadId()
        val legacyDirectory = File(context.filesDir, "hermes-home/downloads/models").apply { mkdirs() }
        val modelFile = File(legacyDirectory, "000-gemma-4-auto-import-smoke.litertlm")
        try {
            modelFile.writeBytes("LITERTLM-auto-import-smoke".toByteArray(Charsets.UTF_8))
            store.saveDownloads(emptyList())
            store.setPreferredDownloadId("")

            val refreshed = HermesModelDownloadManager.refreshDownloads(context, store)
            val imported = refreshed.firstOrNull { it.destinationPath == modelFile.absolutePath }

            assertNotNull(refreshed.toString(), imported)
            assertEquals("completed", imported?.status)
            assertEquals(modelFile.length(), imported?.downloadedBytes)
            assertEquals(imported?.id, store.preferredDownloadId())
            assertTrue(imported?.statusMessage.orEmpty(), imported?.statusMessage.orEmpty().contains("Imported existing model"))
        } finally {
            modelFile.delete()
            store.saveDownloads(originalRecords)
            store.setPreferredDownloadId(originalPreferred)
        }
    }
}
