package com.mobilefork.hermesagent.auth

import android.content.Context
import android.net.Uri
import com.mobilefork.hermesagent.data.AuthSession
import com.mobilefork.hermesagent.data.AuthSessionStore
import com.mobilefork.hermesagent.data.PendingAuthRequest
import com.mobilefork.hermesagent.device.DeviceStateWriter
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

data class XaiLoopbackStart(
    val started: Boolean,
    val redirectUri: String = "",
    val errorName: String = "",
    val handle: XaiLoopbackOAuthServer.Handle? = null,
)

/**
 * Local HTTP callback for xAI OAuth. Host must be 127.0.0.1 (not localhost).
 */
object XaiLoopbackOAuthServer {
    private const val SERVER_TIMEOUT_MS = 10 * 60 * 1000

    @Volatile
    private var currentHandle: Handle? = null

    fun start(
        context: Context,
        pending: PendingAuthRequest,
        tokenEndpoint: String,
        codeChallenge: String,
        port: Int = XaiOAuthClient.REDIRECT_PORT,
    ): XaiLoopbackStart {
        stopCurrent()
        return try {
            val socket = ServerSocket(port, 1, InetAddress.getByName(XaiOAuthClient.REDIRECT_HOST)).apply {
                soTimeout = SERVER_TIMEOUT_MS
            }
            val redirectUri = "http://${XaiOAuthClient.REDIRECT_HOST}:${socket.localPort}${XaiOAuthClient.REDIRECT_PATH}"
            val handle = Handle(socket, redirectUri)
            currentHandle = handle
            handle.worker = thread(name = "HermesXaiLoopbackOAuth", isDaemon = true) {
                runServer(
                    handle = handle,
                    pending = pending,
                    context = context.applicationContext,
                    tokenEndpoint = tokenEndpoint,
                    codeChallenge = codeChallenge,
                )
            }
            XaiLoopbackStart(started = true, redirectUri = redirectUri, handle = handle)
        } catch (error: Exception) {
            XaiLoopbackStart(
                started = false,
                redirectUri = "http://${XaiOAuthClient.REDIRECT_HOST}:$port${XaiOAuthClient.REDIRECT_PATH}",
                errorName = error.javaClass.simpleName,
            )
        }
    }

    fun stopCurrent() {
        currentHandle?.stop()
        currentHandle = null
    }

    private fun runServer(
        handle: Handle,
        pending: PendingAuthRequest,
        context: Context,
        tokenEndpoint: String,
        codeChallenge: String,
    ) {
        try {
            handle.serverSocket.accept().use { client ->
                serveClient(client, handle, pending, context, tokenEndpoint, codeChallenge)
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: SocketException) {
        } finally {
            handle.stop()
            if (currentHandle === handle) currentHandle = null
        }
    }

    private fun serveClient(
        client: Socket,
        handle: Handle,
        pending: PendingAuthRequest,
        context: Context,
        tokenEndpoint: String,
        codeChallenge: String,
    ) {
        val reader = BufferedReader(client.getInputStream().reader(Charsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        while (reader.readLine()?.isNotBlank() == true) {
            // drain headers
        }
        val target = requestLine.split(" ").getOrNull(1).orEmpty()
        val callbackUri = if (target.startsWith("http")) {
            Uri.parse(target)
        } else {
            Uri.parse("http://${XaiOAuthClient.REDIRECT_HOST}${target.ifBlank { "/" }}")
        }
        if (callbackUri.path != XaiOAuthClient.REDIRECT_PATH) {
            writeResponse(client, "404 Not Found", html("Not found", "Unknown xAI callback path."))
            return
        }
        val state = callbackUri.getQueryParameter("state").orEmpty()
        if (state.isBlank() || state != pending.state) {
            writeResponse(client, "400 Bad Request", html("State mismatch", "Return to Hermes and try xAI sign-in again."))
            return
        }
        val error = callbackUri.getQueryParameter("error_description")
            .orEmpty()
            .ifBlank { callbackUri.getQueryParameter("error").orEmpty() }
        if (error.isNotBlank()) {
            writeResponse(client, "400 Bad Request", html("xAI denied", error))
            return
        }
        val code = callbackUri.getQueryParameter("code").orEmpty().trim()
        if (code.isBlank()) {
            writeResponse(client, "400 Bad Request", html("Missing code", "No authorization code returned."))
            return
        }
        val session = runCatching {
            XaiOAuthClient.exchangeCodeForSession(
                code = code,
                pending = pending,
                redirectUri = handle.redirectUri,
                tokenEndpoint = tokenEndpoint,
                codeChallenge = codeChallenge,
            )
        }.getOrElse { error ->
            writeResponse(
                client,
                "500 Internal Server Error",
                html("Exchange failed", error.message ?: error.javaClass.simpleName),
            )
            return
        }
        val store = AuthSessionStore(context)
        store.clearPendingRequest()
        store.saveSession(session)
        if (session.signedIn) {
            AuthRuntimeApplier.apply(context, session)
        }
        DeviceStateWriter.write(context)
        val title = if (session.signedIn) "xAI sign-in complete" else "xAI sign-in failed"
        writeResponse(client, "200 OK", html(title, session.status))
    }

    private fun writeResponse(client: Socket, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8).use { writer ->
            writer.write("HTTP/1.1 $status\r\n")
            writer.write("Content-Type: text/html; charset=utf-8\r\n")
            writer.write("Cache-Control: no-store\r\n")
            writer.write("Connection: close\r\n")
            writer.write("Content-Length: ${bytes.size}\r\n\r\n")
            writer.write(body)
            writer.flush()
        }
    }

    private fun html(title: String, message: String): String {
        val safeTitle = title.replace("<", "&lt;")
        val safeMessage = message.replace("<", "&lt;")
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$safeTitle</title>
            <style>body{font-family:sans-serif;padding:24px;background:#111;color:#eee}
            h1{font-size:1.2rem}</style></head>
            <body><h1>$safeTitle</h1><p>$safeMessage</p>
            <p>You can close this tab and return to Hermes.</p></body></html>
        """.trimIndent()
    }

    class Handle(
        val serverSocket: ServerSocket,
        val redirectUri: String,
    ) {
        @Volatile
        var worker: Thread? = null

        fun stop() {
            runCatching { serverSocket.close() }
            worker?.interrupt()
        }
    }
}
