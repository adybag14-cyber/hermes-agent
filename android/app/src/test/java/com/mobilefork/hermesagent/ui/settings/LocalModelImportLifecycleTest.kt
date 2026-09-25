package com.mobilefork.hermesagent.ui.settings

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class LocalModelImportLifecycleTest {
    @Test fun clearingTheViewModelInterruptsTheActiveImportAndRejectsDuplicateAdmission() {
        val application: Application = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(application)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val calls = AtomicInteger()
        val owner = ViewModelStore()
        val viewModel = LocalModelDownloadsViewModel(
            application = application,
            huggingFaceTokenLoader = { "" },
            downloadStore = store,
            localModelFileImporter = { _, _, _ ->
                calls.incrementAndGet()
                started.countDown()
                try {
                    check(release.await(10, TimeUnit.SECONDS))
                    error("A cancelled import must not complete")
                } finally {
                    stopped.countDown()
                }
            },
        )
        owner.put("import", viewModel)
        try {
            viewModel.importLocalModelFile(Uri.parse("content://agent.import/blocked.gguf"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Import did not start", started.await(5, TimeUnit.SECONDS))
            assertTrue(viewModel.uiState.value.isImporting)
            viewModel.importLocalModelFile(Uri.parse("content://agent.import/duplicate.gguf"))
            assertEquals(1, calls.get())
            owner.clear()
            assertTrue("Clearing the ViewModel must interrupt interruptible provider I/O", stopped.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(viewModel.uiState.value.isImporting)
            assertTrue(store.loadDownloads().isEmpty())
        } finally {
            release.countDown()
            owner.clear()
            assertTrue("Owned test importer did not terminate", stopped.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
