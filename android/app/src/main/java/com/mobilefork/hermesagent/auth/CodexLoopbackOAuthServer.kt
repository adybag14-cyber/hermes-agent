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

data class CodexLoopbackStart(
    val started: Boolean,
    val redirectUri: String = "",
    val actualPort: Int = 0,
    val errorName: String = "",
    val handle: CodexLoopbackOAuthServer.Handle? = null,
)

/**
 * Local OAuth callback server matching openai/codex CLI:
 * bind 127.0.0.1:1455 (fallback 1457), path /auth/callback.
 */
object CodexLoopbackOAuthServer {
    private const val SERVER_TIMEOUT_MS = 15 * 60 * 1000

    @Volatile
    private var currentHandle: Handle? = null

    fun start(
        context: Context,
        pending: PendingAuthRequest,
        preferredPort: Int = CodexOAuthClient.DEFAULT_PORT,
    ): CodexLoopbackStart {
        stopCurrent()
        val ports = listOf(preferredPort, CodexOAuthClient.FALLBACK_PORT).distinct()
        var lastError = ""
        for (port in ports) {
            try {
                val socket = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).apply {
                    soTimeout = SERVER_TIMEOUT_MS
                }
                val actualPort = socket.localPort
                val redirectUri = "http://localhost:$actualPort${CodexOAuthClient.CALLBACK_PATH}"
                val handle = Handle(socket, redirectUri, actualPort)
                currentHandle = handle
                handle.worker = thread(name = "HermesCodexLoopbackOAuth", isDaemon = true) {
                    runServer(handle, pending, context.applicationContext)
                }
                return CodexLoopbackStart(
                    started = true,
                    redirectUri = redirectUri,
                    actualPort = actualPort,
                    handle = handle,
                )
            } catch (error: Exception) {
                lastError = error.javaClass.simpleName
            }
        }
        return CodexLoopbackStart(
            started = false,
            redirectUri = "http://localhost:${CodexOAuthClient.DEFAULT_PORT}${CodexOAuthClient.CALLBACK_PATH}",
            errorName = lastError.ifBlank { "bind_failed" },
        )
    }

    fun stopCurrent() {
        currentHandle?.stop()
        currentHandle = null
    }

    private fun runServer(handle: Handle, pending: PendingAuthRequest, context: Context) {
        try {
            handle.serverSocket.accept().use { client ->
                serveClient(client, handle, pending, context)
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
            Uri.parse("http://localhost:${handle.actualPort}${target.ifBlank { "/" }}")
        }
        if (callbackUri.path == "/cancel") {
            writeResponse(client, "200 OK", html("Cancelled", "Login cancelled."))
            return
        }
        if (callbackUri.path != CodexOAuthClient.CALLBACK_PATH) {
            writeResponse(client, "404 Not Found", html("Not found", "Unknown Codex callback path."))
            return
        }
        val state = callbackUri.getQueryParameter("state").orEmpty()
        if (state.isBlank() || state != pending.state) {
            writeResponse(client, "400 Bad Request", html("State mismatch", "Return to Hermes and try again."))
            return
        }
        val error = callbackUri.getQueryParameter("error_description")
            .orEmpty()
            .ifBlank { callbackUri.getQueryParameter("error").orEmpty() }
        if (error.isNotBlank()) {
            writeResponse(client, "400 Bad Request", html("OAuth denied", error))
            return
        }
        val code = callbackUri.getQueryParameter("code").orEmpty().trim()
        if (code.isBlank()) {
            writeResponse(client, "400 Bad Request", html("Missing code", "No authorization code returned."))
            return
        }
        // rebuild redirect_uri with the port actually used for this bind
        val redirectUri = "http://localhost:${handle.actualPort}${CodexOAuthClient.CALLBACK_PATH}"
        val session = runCatching {
            CodexOAuthClient.exchangeAuthorizationCode(
                code = code,
                codeVerifier = pending.codeVerifier,
                redirectUri = redirectUri,
                methodId = pending.methodId,
            )
        }.getOrElse { err ->
            writeResponse(
                client,
                "500 Internal Server Error",
                html("Exchange failed", err.message ?: err.javaClass.simpleName),
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
        val title = if (session.signedIn) "Codex sign-in complete" else "Codex sign-in failed"
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
        val t = title.replace("<", "&lt;")
        val m = message.replace("<", "&lt;")
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$t</title>
            <style>body{font-family:sans-serif;padding:24px;background:#111;color:#eee}h1{font-size:1.2rem}</style>
            </head><body><h1>$t</h1><p>$m</p>
            <p>You can close this tab and return to Hermes.</p></body></html>
        """.trimIndent()
    }

    class Handle(
        val serverSocket: ServerSocket,
        val redirectUri: String,
        val actualPort: Int,
    ) {
        @Volatile
        var worker: Thread? = null

        fun stop() {
            runCatching { serverSocket.close() }
            worker?.interrupt()
        }
    }
}
