package com.atrishub.atrisstream.server

import com.atrishub.atrisstream.core.sourceengine.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Lightweight HTTP server on port 8787 allowing users on the same LAN to paste
 * source or repository URLs from a phone/PC browser directly to the TV.
 */
class LanPasteServer(
    private val sourceManager: SourceManager,
    private val port: Int = DEFAULT_PORT
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val _lastStatusMessage = MutableStateFlow<String?>(null)
    val lastStatusMessage: StateFlow<String?> = _lastStatusMessage.asStateFlow()

    fun start() {
        if (_isRunning.value) return
        acceptJob = scope.launch {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                val ip = getLocalIpAddress() ?: "127.0.0.1"
                _serverUrl.value = "http://$ip:$port"
                _isRunning.value = true
                _lastStatusMessage.value = null

                while (isActive && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                _lastStatusMessage.value = "Server error: ${e.message}"
            } finally {
                _isRunning.value = false
                _serverUrl.value = null
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _isRunning.value = false
        _serverUrl.value = null
    }

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return@withContext
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@withContext
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore("?")

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength.coerceAtMost(65_536))
                    var totalRead = 0
                    while (totalRead < buffer.size) {
                        val read = reader.read(buffer, totalRead, buffer.size - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    String(buffer, 0, totalRead)
                } else {
                    ""
                }

                when {
                    method == "GET" && path == "/" -> {
                        writeHttpResponse(client, 200, "OK", "text/html; charset=utf-8", renderHtmlPage())
                    }
                    method == "POST" && path == "/add" -> {
                        val submittedUrl = extractUrlFromBody(body)
                        if (submittedUrl.isNullOrBlank()) {
                            writeHttpResponse(
                                client,
                                400,
                                "Bad Request",
                                "text/html; charset=utf-8",
                                renderHtmlPage(error = "Please enter a valid URL.")
                            )
                        } else {
                            val result = sourceManager.addSourceFromUrl(submittedUrl.trim())
                            result.fold(
                                onSuccess = { added ->
                                    val msg = "Added ${added.size} source(s) from $submittedUrl"
                                    _lastStatusMessage.value = msg
                                    writeHttpResponse(
                                        client,
                                        200,
                                        "OK",
                                        "text/html; charset=utf-8",
                                        renderHtmlPage(success = msg)
                                    )
                                },
                                onFailure = { err ->
                                    val msg = "Failed: ${err.message ?: "Unknown error"}"
                                    _lastStatusMessage.value = msg
                                    writeHttpResponse(
                                        client,
                                        200,
                                        "OK",
                                        "text/html; charset=utf-8",
                                        renderHtmlPage(error = msg)
                                    )
                                }
                            )
                        }
                    }
                    else -> {
                        writeHttpResponse(client, 404, "Not Found", "text/plain", "Not Found")
                    }
                }
            }
        }
    }

    private fun extractUrlFromBody(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val match = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
            return match?.groupValues?.getOrNull(1)
        }
        return trimmed.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else {
                    val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    key to value
                }
            }
            .firstOrNull { it.first == "url" }
            ?.second
    }

    private fun writeHttpResponse(
        socket: Socket,
        code: Int,
        reason: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $code $reason\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(body)
        writer.flush()
    }

    private fun renderHtmlPage(success: String? = null, error: String? = null): String {
        val banner = when {
            success != null -> "<div class='msg ok'>${escapeHtml(success)}</div>"
            error != null -> "<div class='msg err'>${escapeHtml(error)}</div>"
            else -> ""
        }
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>AtrisStream • Add Resource</title>
              <style>
                body { margin:0; font-family: system-ui, -apple-system, sans-serif; background:#0B0E14; color:#F8FAFC; display:flex; align-items:center; justify-content:center; min-height:100vh; padding:20px; }
                .card { width:100%; max-width:460px; background:#141923; border:1px solid #263145; border-radius:16px; padding:24px; box-shadow:0 20px 50px rgba(0,0,0,.5); }
                h1 { margin:0 0 8px; font-size:22px; color:#38BDF8; }
                p { margin:0 0 20px; color:#94A3B8; font-size:14px; line-height:1.5; }
                input[type=url] { width:100%; box-sizing:border-box; padding:14px; border-radius:10px; border:1px solid #334155; background:#0B0E14; color:#F8FAFC; font-size:15px; margin-bottom:14px; }
                button { width:100%; padding:14px; border-radius:10px; border:none; background:#38BDF8; color:#0B0E14; font-weight:700; font-size:15px; cursor:pointer; }
                .msg { padding:12px; border-radius:10px; margin-bottom:16px; font-size:14px; }
                .ok { background:rgba(34,197,94,.15); border:1px solid #22C55E; color:#86EFAC; }
                .err { background:rgba(239,68,68,.15); border:1px solid #EF4444; color:#FCA5A5; }
                .footer { margin-top:18px; text-align:center; font-size:12px; color:#64748B; }
                a { color:#818CF8; text-decoration:none; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>AtrisStream TV</h1>
                <p>Paste a JSON source manifest, repository URL, or QuickJS plugin URL to send it directly to your TV.</p>
                $banner
                <form method="POST" action="/add">
                  <input type="url" name="url" placeholder="https://example.com/source.json" required autofocus />
                  <button type="submit">Send to TV</button>
                </form>
                <div class="footer">Powered by <a href="https://atrishub.com" target="_blank">atrishub.com</a></div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun getLocalIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        const val DEFAULT_PORT = 8787
    }
}
