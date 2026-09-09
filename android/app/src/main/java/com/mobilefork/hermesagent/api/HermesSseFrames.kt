package com.mobilefork.hermesagent.api

import okio.BufferedSource
import java.io.EOFException

internal data class HermesSseFrame(val event: String, val data: String)

/** Keep named events and multiline data together; never buffer an unbounded provider frame. */
internal fun BufferedSource.hermesSseFrames(): Sequence<HermesSseFrame> = sequence {
    val maxFrameBytes = 256 * 1024L
    var event = ""
    var data = StringBuilder()
    var frameSize = 0L
    while (!exhausted()) {
        val line = try {
            readUtf8LineStrict(maxFrameBytes)
        } catch (error: EOFException) {
            if (buffer.size > maxFrameBytes) throw IllegalArgumentException("SSE frame exceeds the size limit", error)
            readUtf8()
        }
        frameSize += line.toByteArray(Charsets.UTF_8).size
        require(frameSize <= maxFrameBytes) { "SSE frame exceeds the size limit" }
        if (line.isEmpty()) {
            if (data.isNotEmpty()) yield(HermesSseFrame(event, data.toString().removeSuffix("\n")))
            event = ""
            data = StringBuilder()
            frameSize = 0
        } else if (line.startsWith("event:")) {
            event = line.removePrefix("event:").removePrefix(" ")
        } else if (line.startsWith("data:")) {
            data.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
        }
    }
    if (data.isNotEmpty()) yield(HermesSseFrame(event, data.toString().removeSuffix("\n")))
}
