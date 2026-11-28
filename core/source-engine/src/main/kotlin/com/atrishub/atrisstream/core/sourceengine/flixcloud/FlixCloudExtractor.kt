package com.atrishub.atrisstream.core.sourceengine.flixcloud

import com.atrishub.atrisstream.core.model.StreamFormat
import com.atrishub.atrisstream.core.model.StreamResult
import com.atrishub.atrisstream.core.model.StreamSource
import com.atrishub.atrisstream.core.model.SubtitleFormat
import com.atrishub.atrisstream.core.model.SubtitleTrack
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared stream & subtitle extractor for FlixCloud (`https://flixcloud.cc/e/{id}?v={v}`).
 * Used by both [ReAnimeProvider] and [AnimeXProvider].
 */
class FlixCloudExtractor(
    private val networkClient: AtrisNetworkClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    internal data class DerivedFields(
        val keyField: String,
        val ivField: String,
        val containerName: String,
        val arrayName: String,
        val objectName: String,
        val tokenField: String,
        val keyFrag2Field: String
    )

    suspend fun extract(
        embedUrl: String,
        serverName: String = "FlixCloud",
        referer: String = BASE_URL
    ): StreamResult {
        val dataUrl = buildDataJsonUrl(embedUrl)
        val headers = mapOf(
            "Referer" to embedUrl,
            "Origin" to BASE_URL
        )
        val rawDataJson = networkClient.getText(dataUrl, headers)
        val pageData = SvelteKitDevalueDecoder.decodeMergedNodes(rawDataJson)

        val seed = pageData.str("obfuscation_seed")
            ?: throw IllegalStateException("FlixCloud response missing obfuscation_seed")
        val fields = deriveFields(seed)

        val cryptoObj = pageData["obfuscated_crypto_data"]?.let { el ->
            when (el) {
                is JsonObject -> el
                else -> runCatching {
                    json.parseToJsonElement(el.jsonPrimitive.content).jsonObject
                }.getOrNull()
            }
        } ?: throw IllegalStateException("FlixCloud response missing obfuscated_crypto_data")

        val nestedObj = cryptoObj[fields.containerName]?.jsonObject
            ?.get(fields.arrayName)?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get(fields.objectName)?.jsonObject
            ?: throw IllegalStateException("FlixCloud response missing nested crypto object")

        val frag1 = decodeBase64(
            nestedObj.str(fields.keyField)
                ?: throw IllegalStateException("FlixCloud missing ${fields.keyField}")
        )
        val iv = decodeBase64(
            nestedObj.str(fields.ivField)
                ?: throw IllegalStateException("FlixCloud missing ${fields.ivField}")
        )
        val frag2 = decodeBase64(
            pageData.str(fields.keyFrag2Field)
                ?: throw IllegalStateException("FlixCloud missing ${fields.keyFrag2Field}")
        )
        val m = pageData.str(fields.tokenField)
            ?: throw IllegalStateException("FlixCloud missing ${fields.tokenField}")

        val m3u8ApiUrl = "$BASE_URL/api/m3u8/$m"
        val apiResponseText = networkClient.getText(m3u8ApiUrl, headers)
        val apiJson = json.parseToJsonElement(apiResponseText).jsonObject

        val wPayload = pageData.str("w_payload")
            ?: apiJson.str("w_payload")
            ?: throw IllegalStateException("FlixCloud response missing w_payload")

        val mField = sha256Hex("${m}vid").substring(0, 10)
        val kField = sha256Hex("${m}key").substring(0, 10)
        val mCipherBytes = decodeBase64(
            apiJson.str(mField)
                ?: throw IllegalStateException("FlixCloud API missing $mField")
        )
        val frag3 = decodeBase64(
            apiJson.str(kField)
                ?: throw IllegalStateException("FlixCloud API missing $kField")
        )

        val resolvedUrl = decryptM3u8Payload(
            wPayloadBase64 = wPayload,
            seed = seed,
            frag1 = frag1,
            frag2 = frag2,
            frag3 = frag3,
            iv = iv,
            mCipherBytes = mCipherBytes
        )

        val subtitles = parseSubtitles(pageData["subtitles"] as? JsonArray ?: apiJson["subtitles"] as? JsonArray)

        val stream = StreamSource(
            url = resolvedUrl,
            quality = "Auto",
            format = StreamFormat.HLS,
            serverName = serverName,
            headers = mapOf(
                "Referer" to "$BASE_URL/",
                "Origin" to BASE_URL
            )
        )
        return StreamResult(streams = listOf(stream), subtitles = subtitles)
    }

    internal fun deriveFields(seed: String): DerivedFields {
        var e = seed
        repeat(3) { r -> e = sha256Hex("$e$r") }
        var a = e
        repeat(3) { r -> a = sha256Hex("$a$r") }
        return DerivedFields(
            keyField = "kf_${e.substring(8, 16)}",
            ivField = "ivf_${e.substring(16, 24)}",
            containerName = "cd_${e.substring(24, 32)}",
            arrayName = "ad_${e.substring(32, 40)}",
            objectName = "od_${e.substring(40, 48)}",
            tokenField = "${e.substring(48, 64)}_${e.substring(56, 64)}",
            keyFrag2Field = "${a.substring(0, 16)}_${a.substring(16, 24)}"
        )
    }

    internal fun decryptM3u8Payload(
        wPayloadBase64: String,
        seed: String,
        frag1: ByteArray,
        frag2: ByteArray,
        frag3: ByteArray,
        iv: ByteArray,
        mCipherBytes: ByteArray
    ): String {
        val wasmBytes = decodeBase64(wPayloadBase64)
        val vm = TinyWasmVm(wasmBytes)
        val len = frag1.size
        vm.writeMemory(1000, frag1)
        vm.writeMemory(1000 + len, frag2)
        vm.writeMemory(1000 + 2 * len, frag3)
        if (vm.hasExport("_s")) {
            val seedInt = seed.substring(0, 8).toLong(16).toInt()
            vm.call("_s", seedInt)
        }
        if (vm.hasExport("_r")) {
            vm.call("_r", 1000, 1000 + len, 1000 + 2 * len, 1000 + 3 * len, len)
        }
        val oBytes = vm.readMemory(1000 + 3 * len, len)

        val seedBytes = seed.toByteArray(StandardCharsets.UTF_8)
        val hBytes = pbkdf2HmacSha256(
            passwordBytes = oBytes,
            salt = seedBytes,
            iterations = 1000,
            keyLengthBytes = 32
        )
        val qBytes = ByteArray(32) { q ->
            (hBytes[q].toInt() xor seedBytes[q % seedBytes.size].toInt()).toByte()
        }
        val pe = sha256Bytes(qBytes)
        val plainText = aes256CbcDecrypt(mCipherBytes, pe, iv)
            .toString(StandardCharsets.UTF_8)
            .trim()

        if (plainText.startsWith("{")) {
            val obj = runCatching { json.parseToJsonElement(plainText).jsonObject }.getOrNull()
            obj?.str("url")?.let { return it }
            obj?.str("m3u8")?.let { return it }
            obj?.str("source")?.let { return it }
        }
        return plainText
    }

    internal fun parseSubtitles(arr: JsonArray?): List<SubtitleTrack> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val url = obj.str("url") ?: obj.str("file") ?: obj.str("src") ?: return@mapNotNull null
            val label = obj.str("label") ?: obj.str("lang") ?: obj.str("language") ?: "English"
            val language = obj.str("lang") ?: obj.str("language") ?: label.take(2).lowercase()
            val format = when {
                url.contains(".ass", ignoreCase = true) || obj.str("format").equals("ass", ignoreCase = true) -> SubtitleFormat.ASS
                url.contains(".srt", ignoreCase = true) || obj.str("format").equals("srt", ignoreCase = true) -> SubtitleFormat.SRT
                else -> SubtitleFormat.VTT
            }
            SubtitleTrack(
                url = url,
                language = language,
                label = label,
                format = format,
                headers = mapOf("Referer" to "$BASE_URL/"),
                isExternal = false
            )
        }
    }

    companion object {
        const val BASE_URL = "https://flixcloud.cc"

        fun buildDataJsonUrl(embedUrl: String): String {
            val uri = URI(embedUrl)
            val path = uri.path.trimEnd('/')
            val dataPath = if (path.endsWith("/__data.json")) path else "$path/__data.json"
            val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: "flixcloud.cc"
            return "$scheme://$host$dataPath$query"
        }

        fun sha256Bytes(input: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(input)

        fun sha256Hex(input: String): String =
            sha256Bytes(input.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * Raw-byte PBKDF2-HMAC-SHA256 using [Mac] so arbitrary bytes >= 0x80 are preserved intact.
         */
        fun pbkdf2HmacSha256(
            passwordBytes: ByteArray,
            salt: ByteArray,
            iterations: Int = 1000,
            keyLengthBytes: Int = 32
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            val hLen = mac.macLength
            val blockCount = (keyLengthBytes + hLen - 1) / hLen
            val output = ByteArray(blockCount * hLen)
            val blockInput = ByteArray(salt.size + 4)
            salt.copyInto(blockInput)

            for (blockIndex in 1..blockCount) {
                blockInput[salt.size] = ((blockIndex ushr 24) and 0xFF).toByte()
                blockInput[salt.size + 1] = ((blockIndex ushr 16) and 0xFF).toByte()
                blockInput[salt.size + 2] = ((blockIndex ushr 8) and 0xFF).toByte()
                blockInput[salt.size + 3] = (blockIndex and 0xFF).toByte()

                var u = mac.doFinal(blockInput)
                val t = u.copyOf()
                for (iter in 2..iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) {
                        t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                    }
                }
                t.copyInto(output, destinationOffset = (blockIndex - 1) * hLen)
            }
            return output.copyOf(keyLengthBytes)
        }

        fun aes256CbcDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(cipherBytes)
        }

        fun decodeBase64(input: String): ByteArray {
            val normalized = input.trim().replace('-', '+').replace('_', '/')
            val padded = when (normalized.length % 4) {
                2 -> "$normalized=="
                3 -> "$normalized="
                else -> normalized
            }
            return Base64.getDecoder().decode(padded)
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonElement)?.let { el ->
                runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
            }?.takeIf { it.isNotBlank() }
    }
}
