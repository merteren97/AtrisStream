package com.atrishub.atrisstream.core.sourceengine.movy

import java.nio.charset.StandardCharsets

/**
 * Decoder (and test encoder) for Movy.sx `enc=2` stream API payloads.
 */
object MovyCipher {

    private val MAGIC = byteArrayOf(109, 118, 109, 49) // "mvm1"

    fun rotl32(x: UInt, k: Int): UInt {
        val shift = k and 31
        return if (shift == 0) x else (x shl shift) or (x shr (32 - shift))
    }

    fun mix32(x: UInt): UInt {
        var v = x
        v = v xor (v shr 16)
        v *= 0x85ebca6bu
        v = v xor (v shr 13)
        v *= 0xc2b2ae35u
        v = v xor (v shr 16)
        return v
    }

    fun generateKeystream(seed: String, tmdbId: Int, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)

        var h = 0x811c9dc5u
        for (ch in seed) {
            h = (h xor ch.code.toUInt()) * 0x1000193u
        }

        var s = mix32(mix32(h) xor mix32(tmdbId.toUInt() xor 0x9e3779b9u))
        val sparseState = mutableMapOf<Int, UInt>()
        for (e in 0..7) {
            val a = (s % 61u).toInt()
            s = rotl32(s + 0x9e3779b9u, 7 + (e and 7))
            sparseState[a] = s xor mix32(s)
            s = mix32(s + a.toUInt())
        }

        var acc = mix32(0xa5a5a5a5u xor s)
        val out = ByteArray(length)
        var offset = 0
        var step = 0

        while (offset < length) {
            val i = (acc % 61u).toInt()
            val r = if (sparseState.containsKey(i)) 0xFFFFFFFFu else 0u
            val d = sparseState[i] ?: 0u
            val n = d xor (0x9e3779b9u * (step + 1).toUInt())
            var b = (acc xor n) or (acc and n and r)
            b = rotl32(b + acc, i and 31) xor rotl32(acc, (i * 7) and 31)
            acc = mix32(b + 0x9e3779b9u)
            sparseState[i] = acc

            val word = acc
            for (byteIdx in 0 until 4) {
                if (offset >= length) break
                out[offset++] = ((word shr (byteIdx * 8)) and 0xFFu).toByte()
            }
            step++
        }

        return out
    }

    fun decode(encodedBase64Url: String, seed: String, tmdbId: Int): String {
        val raw = base64UrlDecode(encodedBase64Url.trim())
        if (raw.size < MAGIC.size) {
            throw IllegalArgumentException("Encrypted payload too short (${raw.size} bytes)")
        }

        val keystream = generateKeystream(seed, tmdbId, raw.size)
        val plain = ByteArray(raw.size)
        for (idx in raw.indices) {
            plain[idx] = (raw[idx].toInt() xor keystream[idx].toInt()).toByte()
        }

        for (m in MAGIC.indices) {
            if (plain[m] != MAGIC[m]) {
                throw IllegalArgumentException("Invalid magic header in decoded Movy payload")
            }
        }

        return String(plain, MAGIC.size, plain.size - MAGIC.size, StandardCharsets.UTF_8)
    }

    fun encode(json: String, seed: String, tmdbId: Int): String {
        val payloadBytes = json.toByteArray(StandardCharsets.UTF_8)
        val plain = ByteArray(MAGIC.size + payloadBytes.size)
        MAGIC.copyInto(plain, 0)
        payloadBytes.copyInto(plain, MAGIC.size)

        val keystream = generateKeystream(seed, tmdbId, plain.size)
        val encrypted = ByteArray(plain.size)
        for (idx in plain.indices) {
            encrypted[idx] = (plain[idx].toInt() xor keystream[idx].toInt()).toByte()
        }
        return base64UrlEncode(encrypted)
    }

    internal fun base64UrlDecode(input: String): ByteArray {
        val clean = input.replace("-", "+").replace("_", "/").filterNot { it.isWhitespace() || it == '=' }
        if (clean.isEmpty()) return ByteArray(0)

        val out = ByteArray((clean.length * 3) / 4)
        var outIndex = 0
        var buffer = 0
        var bitsCollected = 0

        for (ch in clean) {
            val value = when (ch) {
                in 'A'..'Z' -> ch.code - 'A'.code
                in 'a'..'z' -> ch.code - 'a'.code + 26
                in '0'..'9' -> ch.code - '0'.code + 52
                '+' -> 62
                '/' -> 63
                else -> throw IllegalArgumentException("Invalid base64url character: '$ch'")
            }
            buffer = (buffer shl 6) or value
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                out[outIndex++] = ((buffer shr bitsCollected) and 0xFF).toByte()
            }
        }
        return if (outIndex == out.size) out else out.copyOf(outIndex)
    }

    internal fun base64UrlEncode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i < data.size) {
            val b0 = data[i++].toInt() and 0xFF
            val hasB1 = i < data.size
            val b1 = if (hasB1) data[i++].toInt() and 0xFF else 0
            val hasB2 = i < data.size
            val b2 = if (hasB2) data[i++].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(alphabet[(triple ushr 18) and 0x3F])
            sb.append(alphabet[(triple ushr 12) and 0x3F])
            if (hasB1) sb.append(alphabet[(triple ushr 6) and 0x3F])
            if (hasB2) sb.append(alphabet[triple and 0x3F])
        }
        return sb.toString()
    }
}
