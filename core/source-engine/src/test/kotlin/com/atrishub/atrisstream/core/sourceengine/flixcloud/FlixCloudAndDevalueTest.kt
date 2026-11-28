package com.atrishub.atrisstream.core.sourceengine.flixcloud

import com.atrishub.atrisstream.core.model.SubtitleFormat
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FlixCloudAndDevalueTest {

    @Test
    fun `SvelteKitDevalueDecoder resolves nested references and arrays`() {
        val raw = """
            {
              "type": "data",
              "nodes": [
                {
                  "type": "data",
                  "data": [
                    {"obfuscation_seed": 1, "subtitles": 2},
                    "seed-123",
                    [3],
                    {"url": 4, "lang": 5, "label": 6},
                    "https://cdn.example.com/en.vtt",
                    "en",
                    "English"
                  ]
                }
              ]
            }
        """.trimIndent()

        val merged = SvelteKitDevalueDecoder.decodeMergedNodes(raw)
        assertEquals("seed-123", merged["obfuscation_seed"]?.toString()?.trim('"'))
        val subs = merged["subtitles"] as JsonArray
        assertEquals(1, subs.size)
        val first = subs[0] as JsonObject
        assertEquals("English", (first["label"] as JsonPrimitive).content)
    }

    @Test
    fun `TinyWasmVm executes _s and _r exports`() {
        // Hand-assembled WASM module:
        // global 0: mut i32 = 0
        // func 0 (_s(x: i32)): global.set 0 (x ^ 0x5A)
        // func 1 (_r() -> i32): return (global.get 0) + 7
        val wasm = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
            // Section 1: Type (2 types: (i32)->(), ()->i32)
            0x01, 0x09, 0x02,
            0x60, 0x01, 0x7F, 0x00,
            0x60, 0x00, 0x01, 0x7F,
            // Section 3: Function (2 funcs: type 0, type 1)
            0x03, 0x03, 0x02, 0x00, 0x01,
            // Section 6: Global (1 mutable i32 = 0)
            0x06, 0x06, 0x01, 0x7F, 0x01, 0x41, 0x00, 0x0B,
            // Section 7: Export ("_s" -> 0, "_r" -> 1)
            0x07, 0x0B, 0x02,
            0x02, 0x5F, 0x73, 0x00, 0x00,
            0x02, 0x5F, 0x72, 0x00, 0x01,
            // Section 10: Code
            0x0A, 0x14, 0x02,
            // body 0: local.get 0, i32.const 0x5A (unsigned/signed LEB: 0xDA 0x00), i32.xor, global.set 0, end
            0x0A, 0x00, 0x20, 0x00, 0x41, 0xDA.toByte(), 0x00, 0x73, 0x24, 0x00, 0x0B,
            // body 1: global.get 0, i32.const 7, i32.add, end
            0x07, 0x00, 0x23, 0x00, 0x41, 0x07, 0x6A, 0x0B
        )

        val vm = TinyWasmVm(wasm)
        assertTrue(vm.hasExport("_s"))
        assertTrue(vm.hasExport("_r"))
        vm.call("_s", 100)
        // (100 ^ 0x5A) + 7 = (100 ^ 90) + 7 = 62 + 7 = 69
        assertEquals(69, vm.call("_r"))
    }

    @Test
    fun `FlixCloudExtractor derives fields and decrypts m3u8 payload with raw-byte PBKDF2 and WASM memory`() {
        // WASM module:
        // _s(seedInt): global.set 0 (seedInt)
        // _r(p1, p2, p3, out, len): copies len bytes from p1 into out, XORing each byte with (p2[i] ^ p3[i] ^ (global0 & 0xFF))
        // For test simplicity: _r(p1, p2, p3, out, len) writes (p1[0] ^ p2[0] ^ p3[0] ^ (global0 & 0xFF)) into out[0]
        // Actually we can build a tiny WASM that writes (i32.load(p1) ^ i32.load(p2) ^ i32.load(p3) ^ global0) to out!
        val wasm = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
            // Section 1: Type: 0=(i32)->(), 1=(i32,i32,i32,i32,i32)->()
            0x01, 0x0D, 0x02,
            0x60, 0x01, 0x7F, 0x00,
            0x60, 0x05, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x00,
            // Section 3: Function
            0x03, 0x03, 0x02, 0x00, 0x01,
            // Section 5: Memory (1 page)
            0x05, 0x03, 0x01, 0x00, 0x01,
            // Section 6: Global (1 mut i32)
            0x06, 0x06, 0x01, 0x7F, 0x01, 0x41, 0x00, 0x0B,
            // Section 7: Export ("_s"->0, "_r"->1)
            0x07, 0x0B, 0x02,
            0x02, 0x5F, 0x73, 0x00, 0x00,
            0x02, 0x5F, 0x72, 0x00, 0x01,
            // Section 10: Code
            0x0A, 0x22, 0x02,
            // body 0: local.get 0, global.set 0, end (6 bytes)
            0x06, 0x00, 0x20, 0x00, 0x24, 0x00, 0x0B,
            // body 1: local.get 3 (out), local.get 0, i32.load, local.get 1, i32.load, i32.xor, local.get 2, i32.load, i32.xor, global.get 0, i32.xor, i32.store, end (25 bytes)
            0x19, 0x00,
            0x20, 0x03,
            0x20, 0x00, 0x28, 0x02, 0x00,
            0x20, 0x01, 0x28, 0x02, 0x00, 0x73,
            0x20, 0x02, 0x28, 0x02, 0x00, 0x73,
            0x23, 0x00, 0x73,
            0x36, 0x02, 0x00,
            0x0B
        )
        val wPayloadB64 = Base64.getEncoder().encodeToString(wasm)
        val seed = "deadbeef0123456789abcdef01234567"
        val extractor = FlixCloudExtractor(AtrisNetworkClient())

        // Verify derived field naming
        val fields = extractor.deriveFields(seed)
        assertTrue(fields.keyField.startsWith("kf_"))
        assertTrue(fields.ivField.startsWith("ivf_"))
        assertTrue(fields.containerName.startsWith("cd_"))
        assertTrue(fields.arrayName.startsWith("ad_"))
        assertTrue(fields.objectName.startsWith("od_"))
        assertTrue(fields.tokenField.contains("_"))
        assertTrue(fields.keyFrag2Field.contains("_"))

        // Include raw bytes >= 0x80 to verify raw-byte PBKDF2
        val frag1 = byteArrayOf(0x91.toByte(), 0xA2.toByte(), 0xB3.toByte(), 0xC4.toByte())
        val frag2 = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val frag3 = byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte())
        val seedInt = seed.substring(0, 8).toLong(16).toInt()

        val expectedO = byteArrayOf(
            (frag1[0].toInt() xor frag2[0].toInt() xor frag3[0].toInt() xor (seedInt and 0xFF)).toByte(),
            (frag1[1].toInt() xor frag2[1].toInt() xor frag3[1].toInt() xor ((seedInt ushr 8) and 0xFF)).toByte(),
            (frag1[2].toInt() xor frag2[2].toInt() xor frag3[2].toInt() xor ((seedInt ushr 16) and 0xFF)).toByte(),
            (frag1[3].toInt() xor frag2[3].toInt() xor frag3[3].toInt() xor ((seedInt ushr 24) and 0xFF)).toByte()
        )

        val seedBytes = seed.toByteArray(StandardCharsets.UTF_8)
        val hBytes = FlixCloudExtractor.pbkdf2HmacSha256(
            passwordBytes = expectedO,
            salt = seedBytes,
            iterations = 1000,
            keyLengthBytes = 32
        )
        val qBytes = ByteArray(32) { q ->
            (hBytes[q].toInt() xor seedBytes[q % seedBytes.size].toInt()).toByte()
        }
        val pe = FlixCloudExtractor.sha256Bytes(qBytes)
        val iv = ByteArray(16) { it.toByte() }
        val expectedUrl = "https://cdn.flixcloud.cc/stream/master.m3u8"
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pe, "AES"), IvParameterSpec(iv))
        val mCipherBytes = cipher.doFinal(expectedUrl.toByteArray(StandardCharsets.UTF_8))

        val decrypted = extractor.decryptM3u8Payload(
            wPayloadBase64 = wPayloadB64,
            seed = seed,
            frag1 = frag1,
            frag2 = frag2,
            frag3 = frag3,
            iv = iv,
            mCipherBytes = mCipherBytes
        )
        assertEquals(expectedUrl, decrypted)

        val subs = extractor.parseSubtitles(
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "url" to JsonPrimitive("https://cdn.example.com/sub.ass"),
                            "lang" to JsonPrimitive("en"),
                            "label" to JsonPrimitive("English")
                        )
                    )
                )
            )
        )
        assertEquals(1, subs.size)
        assertEquals(SubtitleFormat.ASS, subs[0].format)
    }
}
