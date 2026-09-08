package com.mobilefork.hermesagent.play

import com.mobilefork.hermesagent.BuildConfig

/** Compile-time distribution identity, not a user preference or a remotely supplied flag. */
object DistributionPolicy {
    fun requireFullEdition(capability: String) {
        check(!BuildConfig.HERMES_PLAY_EDITION) { "$capability is not available in the Play edition" }
    }
}
