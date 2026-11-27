package com.atrishub.atrisstream.server

import android.content.Context
import android.os.Build
import com.atrishub.atrisstream.core.database.AtrisDatabase
import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.core.model.WatchlistItem
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SyncPayload(
    val sources: List<InstalledSource> = emptyList(),
    val watchProgress: List<WatchProgress> = emptyList(),
    val watchlist: List<WatchlistItem> = emptyList()
)

@Serializable
data class LanInfoResponse(
    val app: String,
    val version: String,
    val deviceName: String,
    val protocol: Int
)

@Serializable
data class PairRequest(
    val pin: String,
    val deviceName: String = ""
)

@Serializable
data class PairResponse(
    val token: String,
    val deviceName: String
)

@Serializable
data class LanErrorResponse(
    val error: String
)

data class MergeOutcome<T>(
    val toUpsert: List<T>,
    val merged: List<T>
)

/**
 * Pure, deterministic merge helper for two-way LAN sync between AtrisStream TV and Desktop.
 */
object LanSyncMerger {

    private data class ProgressKey(
        val sourceId: String,
        val mediaId: String,
        val seasonNumber: Int?,
        val episodeNumber: Int?
    )

    private data class WatchlistKey(
        val sourceId: String,
        val mediaId: String
    )

    fun mergeWatchProgress(
        local: List<WatchProgress>,
        incoming: List<WatchProgress>
    ): MergeOutcome<WatchProgress> {
        val byKey = LinkedHashMap<ProgressKey, WatchProgress>()
        for (item in local) {
            byKey[ProgressKey(item.sourceId, item.mediaId, item.seasonNumber, item.episodeNumber)] = item
        }
        val toUpsert = mutableListOf<WatchProgress>()
        for (candidate in incoming) {
            val key = ProgressKey(
                candidate.sourceId,
                candidate.mediaId,
                candidate.seasonNumber,
                candidate.episodeNumber
            )
            val existing = byKey[key]
            val shouldTakeIncoming = when {
                existing == null -> true
                candidate.updatedAt > existing.updatedAt -> true
                candidate.updatedAt == existing.updatedAt && candidate.isCompleted && !existing.isCompleted -> true
                else -> false
            }
            if (shouldTakeIncoming) {
                byKey[key] = candidate
                toUpsert += candidate
            }
        }
        return MergeOutcome(
            toUpsert = toUpsert,
            merged = byKey.values.toList()
        )
    }

    fun mergeSources(
        local: List<InstalledSource>,
        incoming: List<InstalledSource>
    ): MergeOutcome<InstalledSource> {
        val byId = LinkedHashMap<String, InstalledSource>()
        for (source in local) {
            byId[source.id] = source
        }
        val toUpsert = mutableListOf<InstalledSource>()
        for (candidate in incoming) {
            if (candidate.isBuiltIn) continue
            val existing = byId[candidate.id]
            if (existing?.isBuiltIn == true) continue
            val shouldTakeIncoming = existing == null || candidate.addedAt > existing.addedAt
            if (shouldTakeIncoming) {
                byId[candidate.id] = candidate
                toUpsert += candidate
            }
        }
        return MergeOutcome(
            toUpsert = toUpsert,
            merged = byId.values.toList()
        )
    }

    fun mergeWatchlist(
        local: List<WatchlistItem>,
        incoming: List<WatchlistItem>
    ): MergeOutcome<WatchlistItem> {
        val byKey = LinkedHashMap<WatchlistKey, WatchlistItem>()
        for (item in local) {
            byKey[WatchlistKey(item.sourceId, item.mediaId)] = item
        }
        val toUpsert = mutableListOf<WatchlistItem>()
        for (candidate in incoming) {
            val key = WatchlistKey(candidate.sourceId, candidate.mediaId)
            val existing = byKey[key]
            val shouldTakeIncoming = existing == null || candidate.addedAt > existing.addedAt
            if (shouldTakeIncoming) {
                byKey[key] = candidate
                toUpsert += candidate
            }
        }
        return MergeOutcome(
            toUpsert = toUpsert,
            merged = byKey.values.toList()
        )
    }
}

/**
 * Lightweight HTTP server on port 8787 allowing users on the same LAN to:
 * 1. Paste source or repository URLs from a phone/PC browser (`GET /`, `POST /add`).
 * 2. Discover, pair via 6-digit PIN, and two-way sync with AtrisStream Desktop
 *    (`GET /api/v1/info`, `POST /api/v1/pair`, `POST /api/v1/sync`).
 */
class LanPasteServer(
    private val sourceManager: SourceManager,
    private val repository: AtrisRepository? = null,
    private val database: AtrisDatabase? = null,
    context: Context? = null,
    private val port: Int = DEFAULT_PORT
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val appContext = context?.applicationContext
    private val nsdAdvertiser = appContext?.let { LanNsdAdvertiser(it) }
    private val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val authorizedTokens: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        prefs?.getStringSet(KEY_AUTHORIZED_TOKENS, emptySet())?.let { addAll(it) }
    }
    private val secureRandom = SecureRandom()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val _lastStatusMessage = MutableStateFlow<String?>(null)
    val lastStatusMessage: StateFlow<String?> = _lastStatusMessage.asStateFlow()

    private val _pairingPin = MutableStateFlow<String?>(null)
    val pairingPin: StateFlow<String?> = _pairingPin.asStateFlow()

    fun start() {
        if (_isRunning.value) return
        _pairingPin.value = generatePin()
        acceptJob = scope.launch {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                val ip = getLocalIpAddress() ?: "127.0.0.1"
                _serverUrl.value = "http://$ip:$port"
                _isRunning.value = true
                _lastStatusMessage.value = null
                nsdAdvertiser?.register(port)

                while (isActive && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { client.soTimeout = 10_000 }
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                _lastStatusMessage.value = "Server error: ${e.message}"
            } finally {
                nsdAdvertiser?.unregister()
                _isRunning.value = false
                _serverUrl.value = null
                _pairingPin.value = null
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        nsdAdvertiser?.unregister()
        runCatching { serverSocket?.close() }
        serverSocket = null
        _isRunning.value = false
        _serverUrl.value = null
        _pairingPin.value = null
    }

    fun regeneratePin() {
        if (_isRunning.value) {
            _pairingPin.value = generatePin()
        }
    }

    private fun generatePin(): String =
        secureRandom.nextInt(1_000_000).toString().padStart(6, '0')

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            socket.use { client ->
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readAsciiLine(input) ?: return@withContext
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@withContext
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore("?")

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val name = line.substring(0, colonIdx).trim().lowercase()
                        val value = line.substring(colonIdx + 1).trim()
                        headers[name] = value
                    }
                }

                val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0)
                    .coerceIn(0, MAX_BODY_BYTES)
                val body = if (contentLength > 0) {
                    val buffer = ByteArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = input.read(buffer, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    String(buffer, 0, totalRead, StandardCharsets.UTF_8)
                } else {
                    ""
                }

                when {
                    method == "GET" && path == "/" -> {
                        writeHttpResponse(client, 200, "OK", "text/html; charset=utf-8", renderHtmlPage())
                    }
                    method == "POST" && path == "/add" -> {
                        handleAddUrl(client, body)
                    }
                    method == "GET" && path == "/api/v1/info" -> {
                        val info = LanInfoResponse(
                            app = "AtrisStream",
                            version = APP_VERSION,
                            deviceName = getDeviceName(),
                            protocol = SYNC_PROTOCOL_VERSION
                        )
                        writeJsonResponse(client, 200, "OK", json.encodeToString(info))
                    }
                    method == "POST" && path == "/api/v1/pair" -> {
                        handlePair(client, body)
                    }
                    method == "POST" && path == "/api/v1/sync" -> {
                        handleSync(client, headers, body)
                    }
                    else -> {
                        writeHttpResponse(client, 404, "Not Found", "text/plain", "Not Found")
                    }
                }
            }
        }
    }

    private suspend fun handleAddUrl(client: Socket, body: String) {
        val submittedUrl = extractUrlFromBody(body)
        if (submittedUrl.isNullOrBlank()) {
            writeHttpResponse(
                client,
                400,
                "Bad Request",
                "text/html; charset=utf-8",
                renderHtmlPage(error = "Please enter a valid URL.")
            )
            return
        }
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

    private fun handlePair(client: Socket, body: String) {
        val request = runCatching { json.decodeFromString<PairRequest>(body) }.getOrNull()
        val activePin = _pairingPin.value
        if (request == null || activePin.isNullOrBlank() || request.pin.trim() != activePin) {
            writeJsonResponse(
                client,
                403,
                "Forbidden",
                json.encodeToString(LanErrorResponse("Invalid PIN"))
            )
            return
        }

        val token = UUID.randomUUID().toString()
        authorizedTokens.add(token)
        prefs?.edit()?.putStringSet(KEY_AUTHORIZED_TOKENS, authorizedTokens.toSet())?.apply()

        val peerName = request.deviceName.trim().ifBlank { "Desktop" }
        _lastStatusMessage.value = "Paired with $peerName"

        val response = PairResponse(
            token = token,
            deviceName = getDeviceName()
        )
        writeJsonResponse(client, 200, "OK", json.encodeToString(response))
    }

    private suspend fun handleSync(
        client: Socket,
        headers: Map<String, String>,
        body: String
    ) {
        val token = headers["x-atris-token"]
        if (token.isNullOrBlank() || token !in authorizedTokens) {
            writeJsonResponse(
                client,
                401,
                "Unauthorized",
                json.encodeToString(LanErrorResponse("Unauthorized"))
            )
            return
        }

        val incoming = runCatching { json.decodeFromString<SyncPayload>(body) }.getOrNull()
        if (incoming == null) {
            writeJsonResponse(
                client,
                400,
                "Bad Request",
                json.encodeToString(LanErrorResponse("Invalid sync payload"))
            )
            return
        }

        val localSources = repository?.observeAllSources()?.first().orEmpty()
        val localWatchProgress = readAllWatchProgress()
        val localWatchlist = repository?.observeWatchlist()?.first().orEmpty()

        val progressMerge = LanSyncMerger.mergeWatchProgress(localWatchProgress, incoming.watchProgress)
        val sourceMerge = LanSyncMerger.mergeSources(localSources, incoming.sources)
        val watchlistMerge = LanSyncMerger.mergeWatchlist(localWatchlist, incoming.watchlist)

        if (repository != null) {
            for (progress in progressMerge.toUpsert) {
                repository.upsertWatchProgress(progress)
            }
            for (source in sourceMerge.toUpsert) {
                repository.upsertSource(source)
            }
            for (item in watchlistMerge.toUpsert) {
                repository.upsertWatchlistItem(item)
            }
        }

        val responsePayload = SyncPayload(
            sources = repository?.observeAllSources()?.first() ?: sourceMerge.merged,
            watchProgress = repository?.getAllWatchProgress() ?: progressMerge.merged,
            watchlist = repository?.observeWatchlist()?.first() ?: watchlistMerge.merged
        )

        _lastStatusMessage.value = "Synced (${progressMerge.toUpsert.size} progress, ${sourceMerge.toUpsert.size} sources, ${watchlistMerge.toUpsert.size} watchlist)"
        writeJsonResponse(client, 200, "OK", json.encodeToString(responsePayload))
    }

    private suspend fun readAllWatchProgress(): List<WatchProgress> =
        repository?.getAllWatchProgress().orEmpty()

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawAny) out.toString(StandardCharsets.UTF_8.name()) else null
            }
            sawAny = true
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, len, StandardCharsets.UTF_8)
            }
            out.write(b)
        }
    }

    private fun getDeviceName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"

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

    private fun writeJsonResponse(
        socket: Socket,
        code: Int,
        reason: String,
        jsonBody: String
    ) {
        writeHttpResponse(socket, code, reason, "application/json; charset=utf-8", jsonBody)
    }

    private fun writeHttpResponse(
        socket: Socket,
        code: Int,
        reason: String,
        contentType: String,
        body: String
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        val out = socket.getOutputStream()
        out.write(header)
        out.write(bodyBytes)
        out.flush()
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
        const val SYNC_PROTOCOL_VERSION = 1
        const val APP_VERSION = "0.1.0"
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val PREFS_NAME = "atris_lan_sync"
        private const val KEY_AUTHORIZED_TOKENS = "authorized_tokens"
    }
}
