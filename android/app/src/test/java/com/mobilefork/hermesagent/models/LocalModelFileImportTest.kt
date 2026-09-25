package com.mobilefork.hermesagent.models

import android.net.Uri
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import com.mobilefork.hermesagent.data.AppSettings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord

@RunWith(RobolectricTestRunner::class)
class LocalModelFileImportTest {
    private lateinit var originalSettings: AppSettings
    @Before fun saveOriginalSettings() {
        originalSettings = AppSettingsStore(RuntimeEnvironment.getApplication()).load()
    }
    @After fun restoreOriginalSettings() {
        AppSettingsStore(RuntimeEnvironment.getApplication()).save(originalSettings)
    }

    @Test
    fun contentUriWithUnknownMimeImportsOfflineWithoutSelectingAnEngine() {
        val context = RuntimeEnvironment.getApplication()
        val settings = AppSettingsStore(context)
        settings.save(settings.load().copy(offlineAirplaneMode = true, onDeviceBackend = "none"))
        val store = LocalModelDownloadStore(context)
        val directory = HermesModelDownloadManager.modelsDirectory(context)
        val uri = Uri.parse("content://agent.import/documents/My%20Model.GGUF")
        val bytes = ByteArray(8193) { (it % 251).toByte() }
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertFalse("Incomplete copies must not have model suffixes",
                    directory.listFiles().orEmpty().any { it.extension.equals("gguf", true) })
                return super.read(buffer, offset, length)
            }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        val record = HermesModelDownloadManager.importLocalModelFile(context, store, uri)
        assertArrayEquals(bytes, File(record.destinationPath).readBytes())
        assertEquals("GGUF", record.runtimeFlavor)
        assertEquals("completed", record.status)
        assertEquals("none", settings.load().onDeviceBackend)
        assertEquals(record.id, store.loadDownloads().single().id)
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test
    fun providerFailureCannotLeaveADiscoverablePartialModel() {
        val context = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(context)
        val uri = Uri.parse("content://agent.import/interrupted.gguf")
        val stream = object : InputStream() {
            var copied = false
            override fun read(): Int = throw IOException("Storage disconnected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (copied) throw IOException("Storage disconnected")
                copied = true
                buffer.fill(7, offset, offset + minOf(length, 16))
                return minOf(length, 16)
            }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        assertThrows(IOException::class.java) {
            HermesModelDownloadManager.importLocalModelFile(context, store, uri)
        }
        assertTrue(store.loadDownloads().isEmpty())
        val directory = HermesModelDownloadManager.modelsDirectory(context)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(HermesModelDownloadManager.importExistingModelFiles(context, emptyList()).isEmpty())
    }

    @Test
    fun duplicateNamesNeverOverwriteEarlierModelsOrTheOriginal() {
        val context = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(context)
        val original = File(context.cacheDir, "my-model.gguf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val first = HermesModelDownloadManager.importLocalModelFile(context, store, Uri.fromFile(original))
        original.writeBytes(byteArrayOf(4, 5, 6, 7))
        val second = HermesModelDownloadManager.importLocalModelFile(context, store, Uri.fromFile(original))
        assertNotEquals(first.destinationPath, second.destinationPath)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(first.destinationPath).readBytes())
        assertArrayEquals(original.readBytes(), File(second.destinationPath).readBytes())
        assertEquals(2, store.loadDownloads().size)
    }

    @Test
    fun emptyOrUnsupportedFilesAreRejectedWithoutChangingUserFiles() {
        val context = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(context)
        for ((name, bytes) in listOf("empty.gguf" to byteArrayOf(), "not-a-model.txt" to byteArrayOf(1))) {
            val original = File(context.cacheDir, name).apply { writeBytes(bytes) }
            assertThrows(IllegalArgumentException::class.java) {
                HermesModelDownloadManager.importLocalModelFile(context, store, Uri.fromFile(original))
            }
            assertArrayEquals(bytes, original.readBytes())
        }
        assertTrue(store.loadDownloads().isEmpty())
        assertTrue(HermesModelDownloadManager.modelsDirectory(context).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun persistenceFailureRollsBackThePublishedCopy() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("failing-import", 0)
        val store = LocalModelDownloadStore(preferences = prefs, commitEditor = { false })
        val original = File(context.cacheDir, "rollback.litertlm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertThrows(com.mobilefork.hermesagent.data.LocalModelDownloadPersistenceException::class.java) {
            HermesModelDownloadManager.importLocalModelFile(context, store, Uri.fromFile(original))
        }
        assertTrue(original.isFile)
        assertTrue(HermesModelDownloadManager.modelsDirectory(context).listFiles().orEmpty().isEmpty())
    }
    @Test
    fun aSlowProviderDoesNotBlockOtherImportsAndConcurrentNamesRemainDistinct() {
        val context = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(context)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstUri = Uri.parse("content://slow.agent.provider/shared.gguf")
        val secondUri = Uri.parse("content://fast.agent.provider/shared.gguf")
        val slow = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
        shadowOf(context.contentResolver).registerInputStream(firstUri, slow)
        shadowOf(context.contentResolver).registerInputStream(secondUri, ByteArrayInputStream(byteArrayOf(4, 5)))
        val executor = Executors.newFixedThreadPool(2)
        val first = executor.submit<LocalModelDownloadRecord> {
            HermesModelDownloadManager.importLocalModelFile(context, store, firstUri)
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = executor.submit<LocalModelDownloadRecord> {
                HermesModelDownloadManager.importLocalModelFile(context, store, secondUri)
            }.get(5, TimeUnit.SECONDS)
            release.countDown()
            val completedFirst = first.get(5, TimeUnit.SECONDS)
            assertNotEquals(second.destinationPath, completedFirst.destinationPath)
            assertArrayEquals(byteArrayOf(4, 5), File(second.destinationPath).readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), File(completedFirst.destinationPath).readBytes())
            assertEquals(2, store.loadDownloads().size)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

}
