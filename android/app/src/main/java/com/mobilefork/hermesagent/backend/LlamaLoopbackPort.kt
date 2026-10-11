package com.mobilefork.hermesagent.backend

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Hold the selected address until the owned llama.cpp process is ready to launch. */
internal fun reserveLlamaLoopbackPort(preferredPort: Int): ServerSocket {
    require(preferredPort in 1..65535) { "Invalid preferred llama.cpp port: $preferredPort" }

    fun bind(port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    return try {
        bind(preferredPort)
    } catch (preferredFailure: BindException) {
        // Port zero asks the kernel for an available port without touching the other listener.
        try {
            bind(0)
        } catch (fallbackFailure: Exception) {
            fallbackFailure.addSuppressed(preferredFailure)
            throw fallbackFailure
        }
    }
}
