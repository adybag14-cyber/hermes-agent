package com.mobilefork.hermesagent.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LocalModelDownloadRecord(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sourceUrl: String,
    val repoOrUrl: String,
    val filePath: String,
    val revision: String,
    val runtimeFlavor: String,
    val destinationFileName: String,
    val destinationPath: String,
    val downloadManagerId: Long,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: String = "queued",
    val statusMessage: String = "Queued",
    val ramWarning: String = "",
    val supportsResume: Boolean = true,
    val allowMetered: Boolean = true,
    val allowRoaming: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

internal data class LocalModelDownloadStoreSnapshot(
    val downloads: List<LocalModelDownloadRecord>,
    val preferredDownloadId: String,
    val pendingAutoStartRecordId: String,
    val pendingAutoStartToken: Long,
    val revision: Long,
)

internal data class PendingAutoStartIntent(
    val recordId: String,
    val token: Long,
)

class LocalModelDownloadPersistenceException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class LocalModelDownloadStore internal constructor(
    private val preferences: SharedPreferences,
    private val commitEditor: (SharedPreferences.Editor) -> Boolean,
) {
    constructor(context: Context) : this(
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        commitEditor = SharedPreferences.Editor::commit,
    )

    internal constructor(
        context: Context,
        commitEditor: (SharedPreferences.Editor) -> Boolean,
    ) : this(
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        commitEditor = commitEditor,
    )

    fun loadDownloads(): List<LocalModelDownloadRecord> {
        return snapshot().downloads
    }

    fun saveDownloads(downloads: List<LocalModelDownloadRecord>) {
        synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            saveSnapshotLocked(current.copy(downloads = downloads))
        }
    }

    fun upsertDownload(
        download: LocalModelDownloadRecord,
        makePreferred: Boolean = false,
    ) {
        synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            val downloads = current.downloads.toMutableList()
            val index = downloads.indexOfFirst { it.id == download.id }
            if (index >= 0) {
                downloads[index] = download
            } else {
                downloads += download
            }
            saveSnapshotLocked(
                current.copy(
                    downloads = downloads.sortedByDescending { it.updatedAtEpochMs },
                    preferredDownloadId = if (makePreferred) download.id else current.preferredDownloadId,
                ),
            )
        }
    }

    fun replaceDownloadIfPresent(download: LocalModelDownloadRecord): Boolean {
        return synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            val index = current.downloads.indexOfFirst { it.id == download.id }
            if (index < 0) {
                return@synchronized false
            }
            val downloads = current.downloads.toMutableList().apply { set(index, download) }
            saveSnapshotLocked(
                current.copy(downloads = downloads.sortedByDescending { it.updatedAtEpochMs }),
            )
            true
        }
    }

    fun removeDownload(recordId: String) {
        removeDownloadsMatching { it.id == recordId }
    }

    fun findDownload(recordId: String): LocalModelDownloadRecord? {
        return snapshot().downloads.firstOrNull { it.id == recordId }
    }

    /**
     * Pointer writes share the record transaction and reject stale UI actions after removal.
     */
    fun setPreferredDownloadId(recordId: String): Boolean {
        return setRecordPointer(recordId) { state, value ->
            state.copy(preferredDownloadId = value)
        }
    }

    fun preferredDownloadId(): String {
        return snapshot().preferredDownloadId
    }

    fun clearPreferredDownloadId(expectedRecordId: String): Boolean {
        if (expectedRecordId.isBlank()) return false
        return synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            if (current.preferredDownloadId != expectedRecordId) {
                return@synchronized false
            }
            saveSnapshotLocked(current.copy(preferredDownloadId = ""))
            true
        }
    }

    fun setPendingAutoStartRecordId(recordId: String): Boolean {
        // commit() in saveSnapshotLocked is intentional: a multi-gigabyte DownloadManager job
        // may outlive this process, so recreation must observe the handoff intent.
        return synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            if (recordId.isNotBlank() && current.downloads.none { it.id == recordId }) {
                return@synchronized false
            }
            val nextToken = if (recordId.isBlank()) {
                current.pendingAutoStartToken
            } else {
                Math.addExact(current.pendingAutoStartToken, 1L)
            }
            saveSnapshotLocked(
                current.copy(
                    pendingAutoStartRecordId = recordId,
                    pendingAutoStartToken = nextToken,
                ),
            )
            true
        }
    }

    fun pendingAutoStartRecordId(): String {
        return snapshot().pendingAutoStartRecordId
    }

    /**
     * Capture the durable identity of one Download & Start intent. The token advances even when
     * a newer intent targets the same record, preventing an asynchronous A -> B -> A completion
     * from mistaking the replacement for the intent it originally observed.
     */
    internal fun pendingAutoStartIntent(): PendingAutoStartIntent? {
        val current = snapshot()
        return current.pendingAutoStartRecordId.takeIf(String::isNotBlank)?.let { recordId ->
            PendingAutoStartIntent(recordId = recordId, token = current.pendingAutoStartToken)
        }
    }

    fun clearPendingAutoStartRecordId(expectedRecordId: String): Boolean {
        if (expectedRecordId.isBlank()) {
            return false
        }
        return synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            if (current.pendingAutoStartRecordId != expectedRecordId) {
                return@synchronized false
            }
            saveSnapshotLocked(current.copy(pendingAutoStartRecordId = ""))
            true
        }
    }

    internal fun clearPendingAutoStartIntent(expected: PendingAutoStartIntent): Boolean {
        if (expected.recordId.isBlank()) return false
        return synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            if (
                current.pendingAutoStartRecordId != expected.recordId ||
                current.pendingAutoStartToken != expected.token
            ) {
                return@synchronized false
            }
            saveSnapshotLocked(current.copy(pendingAutoStartRecordId = ""))
            true
        }
    }

    internal fun snapshot(): LocalModelDownloadStoreSnapshot {
        return synchronized(STORE_LOCK) { loadSnapshotLocked() }
    }

    /**
     * Optimistic refresh commit. Any record, preferred, or pending mutation advances the same
     * revision, so a refresh computed from an older snapshot must retry instead of overwriting it.
     */
    internal fun compareAndSetSnapshot(
        expectedRevision: Long,
        downloads: List<LocalModelDownloadRecord>,
        preferredDownloadId: String,
        pendingAutoStartRecordId: String,
    ): LocalModelDownloadStoreSnapshot? {
        return synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            if (current.revision != expectedRevision) {
                return@synchronized null
            }
            saveSnapshotLocked(
                current.copy(
                    downloads = downloads,
                    preferredDownloadId = preferredDownloadId,
                    pendingAutoStartRecordId = pendingAutoStartRecordId,
                ),
            )
        }
    }

    /**
     * Removal is one record-and-pointer transaction. The predicate runs under only STORE_LOCK;
     * callers that also own runtime state must acquire runtime ownership before entering here.
     */
    internal fun removeDownloadsMatching(
        predicate: (LocalModelDownloadRecord) -> Boolean,
    ): Set<String> {
        return synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            val removedIds = current.downloads
                .asSequence()
                .filter(predicate)
                .mapTo(linkedSetOf()) { it.id }
            if (removedIds.isEmpty()) {
                return@synchronized emptySet()
            }
            saveSnapshotLocked(
                current.copy(
                    downloads = current.downloads.filterNot { it.id in removedIds },
                    preferredDownloadId = current.preferredDownloadId.takeUnless { it in removedIds }.orEmpty(),
                    pendingAutoStartRecordId = current.pendingAutoStartRecordId.takeUnless { it in removedIds }.orEmpty(),
                ),
            )
            removedIds
        }
    }

    private fun setRecordPointer(
        recordId: String,
        update: (
            LocalModelDownloadStoreSnapshot,
            String,
        ) -> LocalModelDownloadStoreSnapshot,
    ): Boolean {
        return synchronized(STORE_LOCK) {
            val current = loadSnapshotLocked()
            if (recordId.isNotBlank() && current.downloads.none { it.id == recordId }) {
                return@synchronized false
            }
            saveSnapshotLocked(update(current, recordId))
            true
        }
    }

    private fun loadSnapshotLocked(): LocalModelDownloadStoreSnapshot {
        val raw = preferences.getString(KEY_DOWNLOADS_JSON, null).orEmpty()
        val downloads = if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(item.toRecord())
                    }
                }
            }.getOrDefault(emptyList())
        }
        val recordIds = downloads.mapTo(hashSetOf()) { it.id }
        return LocalModelDownloadStoreSnapshot(
            downloads = downloads,
            preferredDownloadId = preferences.getString(KEY_PREFERRED_DOWNLOAD_ID, "")
                .orEmpty()
                .takeIf { it.isBlank() || it in recordIds }
                .orEmpty(),
            pendingAutoStartRecordId = preferences.getString(KEY_PENDING_AUTO_START_RECORD_ID, "")
                .orEmpty()
                .takeIf { it.isBlank() || it in recordIds }
                .orEmpty(),
            pendingAutoStartToken = preferences.getLong(KEY_PENDING_AUTO_START_TOKEN, 0L),
            revision = preferences.getLong(KEY_STORE_REVISION, 0L),
        )
    }

    private fun saveSnapshotLocked(
        snapshot: LocalModelDownloadStoreSnapshot,
    ): LocalModelDownloadStoreSnapshot {
        val rollback = loadSnapshotLocked()
        val recordIds = snapshot.downloads.mapTo(hashSetOf()) { it.id }
        val persisted = snapshot.copy(
            preferredDownloadId = snapshot.preferredDownloadId
                .takeIf { it.isBlank() || it in recordIds }
                .orEmpty(),
            pendingAutoStartRecordId = snapshot.pendingAutoStartRecordId
                .takeIf { it.isBlank() || it in recordIds }
                .orEmpty(),
            revision = snapshot.revision + 1L,
        )
        try {
            if (!commitEditor(editorForSnapshot(persisted))) {
                throw LocalModelDownloadPersistenceException(
                    "Hermes could not persist the local model download state. Check available storage and try again.",
                )
            }
        } catch (error: Throwable) {
            // commit() mutates SharedPreferences process memory before reporting its disk result.
            // Restore the last durable record/pointer tuple so a false/throw cannot leak a
            // rejected preferred or pending pointer through a subsequent read in this process.
            runCatching { editorForSnapshot(rollback).apply() }
            if (error is LocalModelDownloadPersistenceException) throw error
            throw LocalModelDownloadPersistenceException(
                "Hermes could not persist the local model download state. Check available storage and try again.",
                error,
            )
        }
        return persisted
    }

    private fun editorForSnapshot(
        snapshot: LocalModelDownloadStoreSnapshot,
    ): SharedPreferences.Editor {
        val array = JSONArray()
        snapshot.downloads.forEach { array.put(it.toJson()) }
        return preferences.edit()
            .putString(KEY_DOWNLOADS_JSON, array.toString())
            .putString(KEY_PREFERRED_DOWNLOAD_ID, snapshot.preferredDownloadId)
            .putString(KEY_PENDING_AUTO_START_RECORD_ID, snapshot.pendingAutoStartRecordId)
            .putLong(KEY_PENDING_AUTO_START_TOKEN, snapshot.pendingAutoStartToken)
            .putLong(KEY_STORE_REVISION, snapshot.revision)
    }

    private fun LocalModelDownloadRecord.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("sourceUrl", sourceUrl)
            put("repoOrUrl", repoOrUrl)
            put("filePath", filePath)
            put("revision", revision)
            put("runtimeFlavor", runtimeFlavor)
            put("destinationFileName", destinationFileName)
            put("destinationPath", destinationPath)
            put("downloadManagerId", downloadManagerId)
            put("totalBytes", totalBytes)
            put("downloadedBytes", downloadedBytes)
            put("status", status)
            put("statusMessage", statusMessage)
            put("ramWarning", ramWarning)
            put("supportsResume", supportsResume)
            put("allowMetered", allowMetered)
            put("allowRoaming", allowRoaming)
            put("updatedAtEpochMs", updatedAtEpochMs)
        }
    }

    private fun JSONObject.toRecord(): LocalModelDownloadRecord {
        return LocalModelDownloadRecord(
            id = optString("id", UUID.randomUUID().toString()),
            title = optString("title", "Downloaded model"),
            sourceUrl = optString("sourceUrl", ""),
            repoOrUrl = optString("repoOrUrl", ""),
            filePath = optString("filePath", ""),
            revision = optString("revision", "main"),
            runtimeFlavor = optString("runtimeFlavor", "GGUF"),
            destinationFileName = optString("destinationFileName", "model.bin"),
            destinationPath = optString("destinationPath", ""),
            downloadManagerId = optLong("downloadManagerId", -1L),
            totalBytes = optLong("totalBytes", 0L),
            downloadedBytes = optLong("downloadedBytes", 0L),
            status = optString("status", "queued"),
            statusMessage = optString("statusMessage", "Queued"),
            ramWarning = optString("ramWarning", ""),
            supportsResume = optBoolean("supportsResume", true),
            allowMetered = optBoolean("allowMetered", true),
            allowRoaming = optBoolean("allowRoaming", false),
            updatedAtEpochMs = optLong("updatedAtEpochMs", System.currentTimeMillis()),
        )
    }

    companion object {
        private val STORE_LOCK = Any()
        private const val PREFS_NAME = "hermes_android_local_model_downloads"
        private const val KEY_DOWNLOADS_JSON = "downloads_json"
        private const val KEY_PREFERRED_DOWNLOAD_ID = "preferred_download_id"
        private const val KEY_PENDING_AUTO_START_RECORD_ID = "pending_auto_start_record_id"
        private const val KEY_PENDING_AUTO_START_TOKEN = "pending_auto_start_token"
        private const val KEY_STORE_REVISION = "store_revision"
    }
}
