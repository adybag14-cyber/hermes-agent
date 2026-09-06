package com.mobilefork.hermesagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.models.HermesModelDownloadManager
import com.mobilefork.hermesagent.models.ModelDownloadDraft
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import com.mobilefork.hermesagent.models.VerifiedLocalModelMirrors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ModelScopeDownloadInstrumentedTest {
    @Test
    fun publicMirrorDownloadsThroughTheActualAndroidManagerWithoutAHubToken() {
        assertTrue(BuildConfig.HERMES_CHAQUOPY_LAB)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artifact = VerifiedLocalModelArtifacts.require("Tdamre/MiniCPM5-1B-litert-lm", "MiniCPM5-1B-web.litertlm")
        val mirror = requireNotNull(VerifiedLocalModelMirrors.forArtifact(artifact))
        val settings = AppSettingsStore(context)
        val original = settings.load()
        settings.save(original.copy(offlineAirplaneMode = false, dataSaverMode = false))
        val store = LocalModelDownloadStore(context)
        val started = System.nanoTime()
        try {
            val record = HermesModelDownloadManager.enqueueDownload(
                context, store,
                ModelDownloadDraft(mirror.downloadUrl, artifact.fileName, artifact.revision, "LiteRT-LM"),
                hfToken = "", dataSaverMode = false,
            )
            assertEquals(mirror.downloadUrl, record.sourceUrl)
            assertEquals(artifact.revision, record.revision)
            assertTrue("A real DownloadManager request must have been queued", record.downloadManagerId >= 0)
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            var latest = record
            while (latest.status != "completed" && System.nanoTime() < deadline) {
                assertFalse(latest.statusMessage, latest.status == "failed")
                Thread.sleep(1000)
                latest = HermesModelDownloadManager.refreshDownloads(context, store)
                    .single { it.id == record.id }
            }
            assertEquals(latest.statusMessage, "completed", latest.status)
            val verification = VerifiedLocalModelArtifacts.verify(File(latest.destinationPath), artifact)
            assertTrue(verification.detail, verification.valid)
            val report = JSONObject()
                .put("schema", "hermes-modelscope-android-download-v1")
                .put("status", "passed").put("release_certified", false)
                .put("source_url", latest.sourceUrl).put("hf_token_supplied", false)
                .put("modelscope_token_supplied", false).put("model_sha256", verification.actualSha256)
                .put("model_bytes", verification.actualBytes).put("download_manager_id", latest.downloadManagerId)
                .put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .put("mainland_china_network_measured", false)
            File(context.filesDir, "model-experiments").mkdirs()
            File(context.filesDir, "model-experiments/modelscope-android-download.json").writeText(report.toString(2))
        } finally {
            settings.save(original)
        }
    }
}
