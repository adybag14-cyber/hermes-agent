package com.mobilefork.hermesagent.models

import android.net.Uri
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InterruptedIOException

/** Cancellation must not turn an incomplete or cancelled SAF copy into a stored model. */
@RunWith(RobolectricTestRunner::class)
class LocalModelImportCancellationTest {
    @Test fun interruptionBeforeReadingDoesNotPublishOrChangeTheOriginal() {
        val context = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(context)
        val source = File(context.cacheDir, "cancel-before.gguf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) {
                HermesModelDownloadManager.importLocalModelFile(context, store, Uri.fromFile(source))
            }
        } finally {
            Thread.interrupted()
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
        assertTrue(store.loadDownloads().isEmpty())
        assertTrue(HermesModelDownloadManager.modelsDirectory(context).listFiles().orEmpty().isEmpty())
    }

    @Test fun interruptionDuringTheFinalProviderReadClosesAndRemovesTheStagingCopy() {
        val context = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(context)
        val uri = Uri.parse("content://agent.cancel/final-read.gguf")
        var closed = false
        val source = object : ByteArrayInputStream(byteArrayOf(7, 8, 9)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count < 0) Thread.currentThread().interrupt()
                return count
            }
            override fun close() { closed = true; super.close() }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, source)
        try {
            assertThrows(InterruptedIOException::class.java) {
                HermesModelDownloadManager.importLocalModelFile(context, store, uri)
            }
        } finally {
            Thread.interrupted()
        }
        assertTrue("Source must close even when cancellation is observed at EOF", closed)
        assertTrue(store.loadDownloads().isEmpty())
        assertTrue(HermesModelDownloadManager.modelsDirectory(context).listFiles().orEmpty().isEmpty())
        assertTrue(HermesModelDownloadManager.importExistingModelFiles(context, emptyList()).isEmpty())
    }

    @Test fun unsupportedWebBundleIsRejectedBeforeTheProviderIsRead() {
        val context = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(context)
        val uri = Uri.parse("content://agent.cancel/model-web.task")
        val source = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                fail("Web-only .task bundles must not be copied")
                return -1
            }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, source)
        assertThrows(IllegalArgumentException::class.java) {
            HermesModelDownloadManager.importLocalModelFile(context, store, uri)
        }
        assertTrue(store.loadDownloads().isEmpty())
        assertTrue(HermesModelDownloadManager.modelsDirectory(context).listFiles().orEmpty().isEmpty())
    }
}
