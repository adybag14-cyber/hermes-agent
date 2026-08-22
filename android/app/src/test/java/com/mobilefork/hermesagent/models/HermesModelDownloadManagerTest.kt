package com.mobilefork.hermesagent.models

import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.LocalModelDownloadPersistenceException
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class HermesModelDownloadManagerTest {
    @Test
    fun ggufArtifactSelection_prefersBonsaiQ10OverF16() {
        assertTrue(isCompatibleRepoFile("Bonsai-27B-Q1_0.gguf", "GGUF"))
        assertTrue(
            compatibleFileRank("Bonsai-27B-Q1_0.gguf", "GGUF") <
                compatibleFileRank("Bonsai-27B-F16.gguf", "GGUF"),
        )
    }

    @Test
    fun liteRtLmArtifactSelection_prefersMtpLiteRtLmBundleOverWebTask() {
        assertTrue(isCompatibleRepoFile("gemma-4-E2B-it.litertlm", "LiteRT-LM"))
        assertFalse(isCompatibleRepoFile("gemma-4-E2B-it-web.task", "LiteRT-LM"))
        assertEquals(0, compatibleFileRank("gemma-4-E2B-it.litertlm", "LiteRT-LM"))
        assertEquals(Int.MAX_VALUE, compatibleFileRank("gemma-4-E2B-it-web.task", "LiteRT-LM"))
    }

    @Test
    fun liteRtLmArtifactSelection_keepsGenericGemma4MtpBundleAheadOfSoCSpecificVariants() {
        assertTrue(isCompatibleRepoFile("gemma-4-E2B-it_qualcomm_sm8750.litertlm", "LiteRT-LM"))
        assertTrue(
            compatibleFileRank("gemma-4-E2B-it.litertlm", "LiteRT-LM") <
                compatibleFileRank("gemma-4-E2B-it_qualcomm_sm8750.litertlm", "LiteRT-LM")
        )
    }

    @Test
    fun liteRtLmArtifactSelection_prefersMatchingMediatekVariantWhenGenericBundleIsAbsent() {
        val generic = compatibleFileRank(
            "gemma-4-E2B-it.litertlm",
            "LiteRT-LM",
            socFamily = "mediatek",
            gpuFamily = "mali_immortalis",
        )
        val mediatek = compatibleFileRank(
            "gemma-4-E2B-it_mediatek_mt6989.litertlm",
            "LiteRT-LM",
            socFamily = "mediatek",
            gpuFamily = "mali_immortalis",
        )
        val mali = compatibleFileRank(
            "gemma-4-E2B-it_mali_immortalis.litertlm",
            "LiteRT-LM",
            socFamily = "mediatek",
            gpuFamily = "mali_immortalis",
        )
        val qualcomm = compatibleFileRank(
            "gemma-4-E2B-it_qualcomm_sm8750_adreno.litertlm",
            "LiteRT-LM",
            socFamily = "mediatek",
            gpuFamily = "mali_immortalis",
        )

        assertTrue(generic < mediatek)
        assertTrue(mediatek < qualcomm)
        assertTrue(mali < qualcomm)
    }

    @Test
    fun liteRtLmArtifactSelection_prefersMatchingQualcommVariantOnSnapdragonDevices() {
        val qualcomm = compatibleFileRank(
            "gemma-4-E2B-it_qualcomm_sm8750_adreno.litertlm",
            "LiteRT-LM",
            socFamily = "qualcomm_snapdragon",
            gpuFamily = "adreno",
        )
        val mediatek = compatibleFileRank(
            "gemma-4-E2B-it_mediatek_mt6989_mali.litertlm",
            "LiteRT-LM",
            socFamily = "qualcomm_snapdragon",
            gpuFamily = "adreno",
        )

        assertTrue(qualcomm < mediatek)
    }

    @Test
    fun liteRtLmAliasesPinEdgeGalleryMtpRevisionsForGemma4() {
        assertEquals(
            "litert-community/gemma-4-E2B-it-litert-lm",
            liteRtAlias("google/gemma-4-e2b-it"),
        )
        assertEquals(
            "litert-community/gemma-4-E4B-it-litert-lm",
            liteRtAlias("google/gemma-4-e4b-it"),
        )
        assertEquals(
            "7fa1d78473894f7e736a21d920c3aa80f950c0db",
            liteRtAliasRevision("litert-community/gemma-4-E2B-it-litert-lm"),
        )
        assertEquals(
            "9695417f248178c63a9f318c6e0c56cb917cb837",
            liteRtAliasRevision("litert-community/gemma-4-E4B-it-litert-lm"),
        )
    }

    @Test
    fun knownReleaseMatrixArtifactRewritesMainToExactCommit() {
        val artifact = VerifiedLocalModelArtifacts.releaseMatrix.first()
        assertEquals(
            artifact.revision,
            callPrivate(
                "pinnedArtifactRevision",
                artifact.repoId,
                artifact.fileName,
                "main",
                "main",
            ),
        )
    }

    @Test
    fun explicitUserRevisionIsNeverRewrittenByReleaseMatrix() {
        assertEquals(
            "1111111111111111111111111111111111111111",
            callPrivate(
                "pinnedArtifactRevision",
                "Tdamre/MiniCPM5-1B-litert-lm",
                "MiniCPM5-1B-web.litertlm",
                "1111111111111111111111111111111111111111",
                "1111111111111111111111111111111111111111",
            ),
        )
    }

    @Test
    fun activePreferredRemovalStopsFirstAndDeletesEveryRecordForThePhysicalModelExactlyOnce() {
        val application = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(application)
        val originalRecords = store.loadDownloads()
        val originalPreferredId = store.preferredDownloadId()
        val originalPendingId = store.pendingAutoStartRecordId()
        val modelFile = File(application.cacheDir, "remove-model-${System.nanoTime()}.gguf").apply {
            writeText("model")
        }
        val selected = removalRecord(
            id = "remove-selected",
            destinationPath = modelFile.absolutePath,
            downloadManagerId = 91L,
        )
        val duplicate = removalRecord(
            id = "remove-duplicate",
            destinationPath = File(modelFile.parentFile, ".${File.separator}${modelFile.name}").path,
            downloadManagerId = 91L,
        )
        val unrelated = removalRecord(
            id = "keep-unrelated",
            destinationPath = File(application.cacheDir, "unrelated-${System.nanoTime()}.gguf").absolutePath,
            downloadManagerId = 92L,
        )
        var stopCalls = 0
        var deleteCalls = 0
        val canceledDownloadIds = mutableListOf<Long>()

        try {
            store.saveDownloads(listOf(selected, duplicate, unrelated))
            store.setPreferredDownloadId(selected.id)
            store.setPendingAutoStartRecordId(duplicate.id)

            val result = HermesModelDownloadManager.removeDownload(
                store = store,
                recordId = selected.id,
                currentLocalStatus = {
                    LocalBackendStatus(
                        backendKind = BackendKind.LLAMA_CPP,
                        started = true,
                        sourceModelPath = modelFile.absolutePath,
                    )
                },
                stopAllLocalBackends = {
                    stopCalls += 1
                    LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                },
                removeSystemDownload = { canceledDownloadIds += it },
                deleteModelFile = {
                    deleteCalls += 1
                    it.delete()
                },
            )

            assertTrue(result.removed)
            assertEquals(setOf(selected.id, duplicate.id), result.removedRecordIds)
            assertEquals(1, stopCalls)
            assertEquals(listOf(91L), canceledDownloadIds)
            assertEquals(1, deleteCalls)
            assertFalse(modelFile.exists())
            assertEquals(listOf(unrelated.id), store.loadDownloads().map { it.id })
            assertEquals("", store.preferredDownloadId())
            assertEquals("", store.pendingAutoStartRecordId())

            val repeated = HermesModelDownloadManager.removeDownload(
                store = store,
                recordId = selected.id,
                currentLocalStatus = { error("must not inspect a removed record") },
                stopAllLocalBackends = { error("must not stop twice") },
                removeSystemDownload = { error("must not cancel twice") },
                deleteModelFile = { error("must not delete twice") },
            )
            assertFalse(repeated.removed)
            assertEquals(1, stopCalls)
            assertEquals(1, deleteCalls)
        } finally {
            modelFile.delete()
            store.saveDownloads(originalRecords)
            store.setPreferredDownloadId(originalPreferredId)
            store.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun activePreferredRemovalFailsClosedWhenOwnedRuntimeCannotStop() {
        val application = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(application)
        val originalRecords = store.loadDownloads()
        val originalPreferredId = store.preferredDownloadId()
        val originalPendingId = store.pendingAutoStartRecordId()
        val modelFile = File(application.cacheDir, "blocked-remove-${System.nanoTime()}.gguf").apply {
            writeText("model")
        }
        val selected = removalRecord(
            id = "blocked-remove",
            destinationPath = modelFile.absolutePath,
            downloadManagerId = 101L,
        )
        var deleteCalls = 0
        var cancelCalls = 0

        try {
            store.saveDownloads(listOf(selected))
            store.setPreferredDownloadId(selected.id)
            store.setPendingAutoStartRecordId(selected.id)

            val result = HermesModelDownloadManager.removeDownload(
                store = store,
                recordId = selected.id,
                currentLocalStatus = {
                    LocalBackendStatus(
                        backendKind = BackendKind.LLAMA_CPP,
                        started = true,
                        sourceModelPath = modelFile.absolutePath,
                    )
                },
                stopAllLocalBackends = {
                    LocalBackendStatus(
                        backendKind = BackendKind.LLAMA_CPP,
                        started = false,
                        statusMessage = "Force stop and reopen Hermes before retrying.",
                        requiresAppRestart = true,
                    )
                },
                removeSystemDownload = { cancelCalls += 1 },
                deleteModelFile = {
                    deleteCalls += 1
                    it.delete()
                },
            )

            assertFalse(result.removed)
            assertTrue(result.requiresAppRestart)
            assertEquals(0, cancelCalls)
            assertEquals(0, deleteCalls)
            assertTrue(modelFile.exists())
            assertEquals(listOf(selected.id), store.loadDownloads().map { it.id })
            assertEquals(selected.id, store.preferredDownloadId())
            assertEquals(selected.id, store.pendingAutoStartRecordId())
        } finally {
            modelFile.delete()
            store.saveDownloads(originalRecords)
            store.setPreferredDownloadId(originalPreferredId)
            store.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun serializedRemovalBlocksBackendStartupAdmissionUntilFileAndStoreMutationFinish() {
        val application = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(application)
        val originalRecords = store.loadDownloads()
        val originalPreferredId = store.preferredDownloadId()
        val originalPendingId = store.pendingAutoStartRecordId()
        val modelFile = File(application.cacheDir, "serialized-remove-${System.nanoTime()}.gguf").apply {
            writeText("model")
        }
        val selected = removalRecord(
            id = "serialized-remove",
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
        )
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val startupEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            store.saveDownloads(listOf(selected))
            store.setPreferredDownloadId(selected.id)
            store.setPendingAutoStartRecordId("")

            val removal = executor.submit<ModelRemovalResult> {
                HermesModelDownloadManager.removeDownloadWithOwnership(
                    store = store,
                    recordId = selected.id,
                    localMutationAuthority = { mutation ->
                        HermesRuntimeManager.withSerializedLocalBackendMutation(mutation)
                    },
                    removeSystemDownload = { error("No DownloadManager id should be canceled") },
                    deleteModelFile = { file ->
                        mutationEntered.countDown()
                        check(releaseMutation.await(3, TimeUnit.SECONDS)) {
                            "Timed out waiting to finish the serialized removal mutation"
                        }
                        file.delete()
                    },
                )
            }
            assertTrue("Removal did not enter its file mutation", mutationEntered.await(2, TimeUnit.SECONDS))

            val startupAdmission = executor.submit {
                // ensureConfigured uses this exact ownership monitor before inspecting a model.
                OnDeviceBackendManager.withLocalBackendOwnership {
                    startupEntered.countDown()
                }
            }
            assertFalse(
                "Backend startup entered while model deletion still owned the mutation lock",
                startupEntered.await(250, TimeUnit.MILLISECONDS),
            )

            releaseMutation.countDown()
            val result = removal.get(3, TimeUnit.SECONDS)
            startupAdmission.get(3, TimeUnit.SECONDS)

            assertTrue(result.statusMessage, result.removed)
            assertTrue("Backend startup never resumed after removal released ownership", startupEntered.await(1, TimeUnit.SECONDS))
            assertFalse(modelFile.exists())
            assertTrue(store.loadDownloads().none { it.id == selected.id })
        } finally {
            releaseMutation.countDown()
            executor.shutdownNow()
            modelFile.delete()
            store.saveDownloads(originalRecords)
            store.setPreferredDownloadId(originalPreferredId)
            store.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun refreshPausedAfterSnapshotCannotResurrectACompletedRemoval() {
        val application = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(application)
        val originalRecords = store.loadDownloads()
        val originalPreferredId = store.preferredDownloadId()
        val originalPendingId = store.pendingAutoStartRecordId()
        val modelFile = File(application.cacheDir, "refresh-remove-race-${System.nanoTime()}.gguf").apply {
            writeText("model")
        }
        val selected = removalRecord(
            id = "refresh-remove-race",
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
        )
        val firstSnapshotObserved = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()

        try {
            store.saveDownloads(listOf(selected))
            assertTrue(store.setPreferredDownloadId(selected.id))
            assertTrue(store.setPendingAutoStartRecordId(selected.id))

            val refresh = executor.submit<List<LocalModelDownloadRecord>> {
                HermesModelDownloadManager.refreshDownloads(
                    store = store,
                    refreshSnapshot = { records ->
                        records.map { record ->
                            record.copy(
                                statusMessage = "Refresh result computed from the stale snapshot",
                                updatedAtEpochMs = record.updatedAtEpochMs + 1L,
                            )
                        }
                    },
                    afterSnapshot = {
                        if (pauseOnce.compareAndSet(true, false)) {
                            firstSnapshotObserved.countDown()
                            check(releaseRefresh.await(3, TimeUnit.SECONDS)) {
                                "Timed out waiting for removal to finish"
                            }
                        }
                    },
                )
            }
            assertTrue(
                "Refresh did not pause after taking its initial snapshot",
                firstSnapshotObserved.await(2, TimeUnit.SECONDS),
            )

            val removal = HermesModelDownloadManager.removeDownload(
                store = store,
                recordId = selected.id,
                currentLocalStatus = {
                    LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                },
                stopAllLocalBackends = {
                    LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                },
                removeSystemDownload = { error("No DownloadManager id should be canceled") },
                deleteModelFile = File::delete,
            )

            assertTrue(removal.statusMessage, removal.removed)
            assertFalse(modelFile.exists())
            assertTrue(store.loadDownloads().isEmpty())
            assertEquals("", store.preferredDownloadId())
            assertEquals("", store.pendingAutoStartRecordId())
            assertFalse("A stale preferred action must be rejected", store.setPreferredDownloadId(selected.id))
            assertFalse("A stale pending action must be rejected", store.setPendingAutoStartRecordId(selected.id))
            assertFalse(
                "A stale restart must not recreate a removed record",
                store.replaceDownloadIfPresent(selected.copy(status = "queued")),
            )

            releaseRefresh.countDown()
            assertTrue(
                "Refresh returned the record removed after its original snapshot",
                refresh.get(3, TimeUnit.SECONDS).isEmpty(),
            )
            assertTrue("Stale refresh resurrected the removed record", store.loadDownloads().isEmpty())
            assertEquals("", store.preferredDownloadId())
            assertEquals("", store.pendingAutoStartRecordId())
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
            modelFile.delete()
            store.saveDownloads(originalRecords)
            store.setPreferredDownloadId(originalPreferredId)
            store.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun removalOperationClaimPreventsRestartFromEnqueuingAfterItsSnapshot() {
        val application = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(application)
        val originalRecords = store.loadDownloads()
        val originalPreferredId = store.preferredDownloadId()
        val originalPendingId = store.pendingAutoStartRecordId()
        val modelFile = File(application.cacheDir, "remove-restart-claim-${System.nanoTime()}.gguf").apply {
            writeText("model")
        }
        val selected = removalRecord(
            id = "remove-restart-claim",
            destinationPath = modelFile.absolutePath,
            downloadManagerId = 301L,
        )
        val removalReachedCancellation = CountDownLatch(1)
        val releaseRemoval = CountDownLatch(1)
        val replacementEnqueued = CountDownLatch(1)
        val canceledIds = CopyOnWriteArrayList<Long>()
        val executor = Executors.newFixedThreadPool(2)

        try {
            store.saveDownloads(listOf(selected))
            val removal = executor.submit<ModelRemovalResult> {
                HermesModelDownloadManager.removeDownload(
                    store = store,
                    recordId = selected.id,
                    currentLocalStatus = {
                        LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                    },
                    stopAllLocalBackends = {
                        LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                    },
                    removeSystemDownload = { downloadId ->
                        canceledIds += downloadId
                        removalReachedCancellation.countDown()
                        check(releaseRemoval.await(3, TimeUnit.SECONDS)) {
                            "Timed out waiting to release removal"
                        }
                    },
                    deleteModelFile = File::delete,
                )
            }
            assertTrue(
                "Removal did not pause after snapshotting the old DownloadManager id",
                removalReachedCancellation.await(2, TimeUnit.SECONDS),
            )

            val restart = executor.submit<LocalModelDownloadRecord?> {
                HermesModelDownloadManager.restartDownloadWithOperation(
                    store = store,
                    recordId = selected.id,
                    removeSystemDownload = { canceledIds += it },
                    deleteModelFile = File::delete,
                    enqueueReplacement = { existing ->
                        replacementEnqueued.countDown()
                        existing.copy(downloadManagerId = 302L)
                    },
                )
            }
            assertFalse(
                "Restart enqueued a replacement while removal owned the external-I/O claim",
                replacementEnqueued.await(250, TimeUnit.MILLISECONDS),
            )

            releaseRemoval.countDown()
            val removalResult = removal.get(3, TimeUnit.SECONDS)
            val restartResult = restart.get(3, TimeUnit.SECONDS)

            assertTrue(removalResult.statusMessage, removalResult.removed)
            assertEquals(null, restartResult)
            assertEquals(listOf(301L), canceledIds.toList())
            assertEquals(1L, replacementEnqueued.count)
            assertFalse(modelFile.exists())
            assertTrue(store.loadDownloads().none { it.id == selected.id })
        } finally {
            releaseRemoval.countDown()
            executor.shutdownNow()
            modelFile.delete()
            store.saveDownloads(originalRecords)
            store.setPreferredDownloadId(originalPreferredId)
            store.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun replacementCompletedBeforeRemovalIsCapturedCanceledAndLeavesNoOrphan() {
        val application = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(application)
        val originalRecords = store.loadDownloads()
        val originalPreferredId = store.preferredDownloadId()
        val originalPendingId = store.pendingAutoStartRecordId()
        val modelFile = File(application.cacheDir, "restart-remove-claim-${System.nanoTime()}.gguf").apply {
            writeText("old model")
        }
        val selected = removalRecord(
            id = "restart-remove-claim",
            destinationPath = modelFile.absolutePath,
            downloadManagerId = 401L,
        )
        val replacementCreated = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val removalEnteredClaim = CountDownLatch(1)
        val canceledIds = CopyOnWriteArrayList<Long>()
        val replacementIds = CopyOnWriteArrayList<Long>()
        val executor = Executors.newFixedThreadPool(2)

        try {
            store.saveDownloads(listOf(selected))
            val restart = executor.submit<LocalModelDownloadRecord?> {
                HermesModelDownloadManager.restartDownloadWithOperation(
                    store = store,
                    recordId = selected.id,
                    removeSystemDownload = { canceledIds += it },
                    deleteModelFile = File::delete,
                    enqueueReplacement = { existing ->
                        replacementIds += 402L
                        modelFile.writeText("replacement partial")
                        replacementCreated.countDown()
                        check(releaseReplacement.await(3, TimeUnit.SECONDS)) {
                            "Timed out waiting to commit replacement"
                        }
                        existing.copy(downloadManagerId = 402L)
                    },
                )
            }
            assertTrue("Restart did not create its replacement", replacementCreated.await(2, TimeUnit.SECONDS))

            val removal = executor.submit<ModelRemovalResult> {
                HermesModelDownloadManager.removeDownload(
                    store = store,
                    recordId = selected.id,
                    currentLocalStatus = {
                        removalEnteredClaim.countDown()
                        LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                    },
                    stopAllLocalBackends = {
                        LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                    },
                    removeSystemDownload = { canceledIds += it },
                    deleteModelFile = File::delete,
                )
            }
            assertFalse(
                "Removal entered while restart still owned the external-I/O claim",
                removalEnteredClaim.await(250, TimeUnit.MILLISECONDS),
            )

            releaseReplacement.countDown()
            val restartResult = restart.get(3, TimeUnit.SECONDS)
            val removalResult = removal.get(3, TimeUnit.SECONDS)

            assertEquals(402L, restartResult?.downloadManagerId)
            assertTrue(removalResult.statusMessage, removalResult.removed)
            assertEquals(listOf(401L, 402L), canceledIds.toList())
            assertTrue("A replacement DownloadManager job was orphaned", replacementIds.all { it in canceledIds })
            assertFalse(modelFile.exists())
            assertTrue(store.loadDownloads().none { it.id == selected.id })
        } finally {
            releaseReplacement.countDown()
            executor.shutdownNow()
            modelFile.delete()
            store.saveDownloads(originalRecords)
            store.setPreferredDownloadId(originalPreferredId)
            store.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun failedStoreCommitThrowsAndRefreshDoesNotRetryOrReportSuccess() {
        val application = RuntimeEnvironment.getApplication()
        val durableStore = LocalModelDownloadStore(application)
        val originalRecords = durableStore.loadDownloads()
        val originalPreferredId = durableStore.preferredDownloadId()
        val originalPendingId = durableStore.pendingAutoStartRecordId()
        val selected = removalRecord(
            id = "failed-store-commit",
            destinationPath = File(application.cacheDir, "missing-${System.nanoTime()}.gguf").absolutePath,
            downloadManagerId = -1L,
        )

        try {
            durableStore.saveDownloads(listOf(selected))
            val failingStore = LocalModelDownloadStore(application) { editor ->
                editor.commit()
                false
            }

            val pointerFailure = assertThrows(LocalModelDownloadPersistenceException::class.java) {
                failingStore.setPreferredDownloadId(selected.id)
            }
            assertTrue(pointerFailure.message.orEmpty().contains("could not persist"))

            var refreshAttempts = 0
            assertThrows(LocalModelDownloadPersistenceException::class.java) {
                HermesModelDownloadManager.refreshDownloads(
                    store = failingStore,
                    refreshSnapshot = { records ->
                        refreshAttempts += 1
                        records
                    },
                )
            }
            assertEquals("Persistence failure must not spin the refresh CAS loop", 1, refreshAttempts)

            assertThrows(LocalModelDownloadPersistenceException::class.java) {
                HermesModelDownloadManager.removeDownload(
                    store = failingStore,
                    recordId = selected.id,
                    currentLocalStatus = {
                        LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                    },
                    stopAllLocalBackends = {
                        LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                    },
                    removeSystemDownload = { error("No DownloadManager id should be canceled") },
                    deleteModelFile = { error("Missing file should not be deleted") },
                )
            }
            assertEquals(listOf(selected.id), durableStore.loadDownloads().map { it.id })
            assertEquals("", durableStore.preferredDownloadId())
        } finally {
            durableStore.saveDownloads(originalRecords)
            durableStore.setPreferredDownloadId(originalPreferredId)
            durableStore.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun newerSameRecordSelectionWinningBeforeTombstonePreventsRemovalAndDeletion() {
        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences(
            "remove_vs_newer_same_record_selection",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val store = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val modelFile = File(application.cacheDir, "remove-vs-use-${System.nanoTime()}.gguf").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val selected = removalRecord(
            id = "remove-vs-newer-use",
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
        )
        store.saveDownloads(listOf(selected))
        val removalGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val runtimeOwnershipEntered = CountDownLatch(1)
        val allowTombstoneAttempt = CountDownLatch(1)
        val deleteCalls = AtomicInteger(0)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val removal = executor.submit<ModelRemovalResult> {
                HermesModelDownloadManager.removeDownloadWithOwnership(
                    store = store,
                    recordId = selected.id,
                    selectionGeneration = removalGeneration,
                    localMutationAuthority = { mutation ->
                        runtimeOwnershipEntered.countDown()
                        check(allowTombstoneAttempt.await(5, TimeUnit.SECONDS))
                        mutation(
                            LocalBackendStatus(backendKind = BackendKind.NONE, started = false),
                        ) {
                            LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                        }
                    },
                    removeSystemDownload = { error("No system download should be canceled") },
                    deleteModelFile = { file ->
                        deleteCalls.incrementAndGet()
                        file.delete()
                    },
                )
            }
            assertTrue(runtimeOwnershipEntered.await(5, TimeUnit.SECONDS))

            val newerSelectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
            assertTrue(
                LocalModelRuntimeSelectionAuthority.withCurrent(newerSelectionGeneration) {
                    store.setPreferredDownloadId(selected.id)
                },
            )
            allowTombstoneAttempt.countDown()

            val failure = assertThrows(ExecutionException::class.java) { removal.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is RuntimeSelectionSupersededException)
            assertEquals(0, deleteCalls.get())
            assertTrue(modelFile.exists())
            assertEquals(selected.id, store.preferredDownloadId())
            assertEquals(selected.id, store.findDownload(selected.id)?.id)
        } finally {
            allowTombstoneAttempt.countDown()
            executor.shutdownNow()
            modelFile.delete()
        }
    }

    private fun isCompatibleRepoFile(path: String, runtimeFlavor: String): Boolean {
        return callPrivate("isCompatibleRepoFile", path, runtimeFlavor) as Boolean
    }

    private fun compatibleFileRank(path: String, runtimeFlavor: String): Int {
        return callPrivate("compatibleFileRank", path, runtimeFlavor) as Int
    }

    private fun compatibleFileRank(
        path: String,
        runtimeFlavor: String,
        socFamily: String,
        gpuFamily: String,
    ): Int {
        return callPrivate("compatibleFileRank", path, runtimeFlavor, socFamily, gpuFamily) as Int
    }

    private fun liteRtAlias(repoId: String): String? {
        return callPrivate("liteRtAlias", repoId) as String?
    }

    private fun liteRtAliasRevision(repoId: String): String? {
        val field = HermesModelDownloadManager::class.java.getDeclaredField("LITERT_ALIAS_REVISIONS")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val revisions = field.get(HermesModelDownloadManager) as Map<String, String>
        return revisions[repoId.lowercase()]
    }

    private fun callPrivate(name: String, vararg args: Any): Any? {
        val argTypes = args.map { it::class.java }.toTypedArray()
        val method = HermesModelDownloadManager::class.java.getDeclaredMethod(name, *argTypes)
        method.isAccessible = true
        return method.invoke(HermesModelDownloadManager, *args)
    }

    private fun removalRecord(
        id: String,
        destinationPath: String,
        downloadManagerId: Long,
    ): LocalModelDownloadRecord = LocalModelDownloadRecord(
        id = id,
        title = "$id.gguf",
        sourceUrl = "https://example.invalid/$id.gguf",
        repoOrUrl = "example/$id",
        filePath = "$id.gguf",
        revision = "main",
        runtimeFlavor = "GGUF",
        destinationFileName = "$id.gguf",
        destinationPath = destinationPath,
        downloadManagerId = downloadManagerId,
        totalBytes = 5L,
        downloadedBytes = 5L,
        status = "completed",
    )
}
