package com.mobilefork.hermesagent.device

import android.os.Build
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Publish one prepared value only if request cancellation has not won its final commit boundary.
 * Callers must keep [publication] short: slow parsing, lookup, encoding, and staging belong before
 * this helper so Stop can acquire the request lock while that preparation is still in flight.
 */
internal fun <T : Any> AutomationPublicationGate?.publishValueIfActive(
    cancelledValue: () -> T,
    publication: () -> T,
): T {
    if (this == null) return publication()
    var value: T? = null
    val published = publishIfActive {
        value = publication()
    }
    return if (published) checkNotNull(value) else cancelledValue()
}

internal fun cancelledMutationJson(action: String, message: String): JSONObject {
    return JSONObject()
        .put("success", false)
        .put("cancelled", true)
        .put("exit_code", 130)
        .put("action", action)
        .put("error", message)
}

/** Publish a prepared file with one replace operation on the app data filesystem. */
internal fun replaceStagedFileAtCommit(staged: File, target: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val nioFailure = runCatching {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.exceptionOrNull()
        if (nioFailure == null) return
        runCatching { Os.rename(staged.absolutePath, target.absolutePath) }
            .getOrElse { androidFailure ->
                androidFailure.addSuppressed(nioFailure)
                throw androidFailure
            }
        return
    }
    Os.rename(staged.absolutePath, target.absolutePath)
}
