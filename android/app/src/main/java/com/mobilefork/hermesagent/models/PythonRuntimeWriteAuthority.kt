package com.mobilefork.hermesagent.models

/**
 * Serializes every Python credential/config mutation with local-runtime selection authority.
 *
 * Python startup and other potentially long work must happen before entering this authority.
 * The bounded file mutation owns the process-wide selection monitor. A newer selection either
 * waits until an admitted older write is complete, or makes a delayed older writer fail its
 * admission check before it can touch persistent Python state. Reusing that one monitor also
 * avoids introducing a second lock order around settings/runtime ownership.
 */
internal object PythonRuntimeWriteAuthority {
    fun <T> writeIfCurrent(
        selectionGeneration: Long,
        action: () -> T,
    ): T {
        return writeWithAdmissionCheck(
            admissionCheck = {
                LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
            },
            action = action,
        )
    }

    fun <T> writeWithAdmissionCheck(
        admissionCheck: () -> Unit,
        action: () -> T,
    ): T {
        return LocalModelRuntimeSelectionAuthority.withAdmissionCheck(admissionCheck, action)
    }
}
