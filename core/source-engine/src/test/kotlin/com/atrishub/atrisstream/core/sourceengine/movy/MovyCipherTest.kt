package com.atrishub.atrisstream.core.sourceengine.movy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MovyCipherTest {

    @Test
    fun `rotl32 rotates unsigned 32-bit integers accurately`() {
        assertEquals(0x12345678u, MovyCipher.rotl32(0x12345678u, 0))
        assertEquals(0x23456781u, MovyCipher.rotl32(0x12345678u, 4))
        assertEquals(0x00000001u, MovyCipher.rotl32(0x80000000u, 1))
        assertEquals(0x23456781u, MovyCipher.rotl32(0x12345678u, 36))
    }

    @Test
    fun `mix32 produces deterministic avalanched output`() {
        val a = MovyCipher.mix32(0u)
        val b = MovyCipher.mix32(1u)
        val aRepeat = MovyCipher.mix32(0u)
        assertEquals(0u, a)
        assertEquals(a, aRepeat)
        assertTrue(b != 0u)
        assertTrue(b != 1u)
        assertTrue(MovyCipher.mix32(0x00010000u) != 0x00010000u)
    }

    @Test
    fun `keystream is deterministic for same seed and tmdbId`() {
        val ks1 = MovyCipher.generateKeystream("test-seed-abc", 550, 64)
        val ks2 = MovyCipher.generateKeystream("test-seed-abc", 550, 64)
        val ksDifferentSeed = MovyCipher.generateKeystream("test-seed-xyz", 550, 64)
        val ksDifferentId = MovyCipher.generateKeystream("test-seed-abc", 42009, 64)

        assertEquals(64, ks1.size)
        assertTrue(ks1.contentEquals(ks2))
        assertTrue(!ks1.contentEquals(ksDifferentSeed))
        assertTrue(!ks1.contentEquals(ksDifferentId))
    }

    @Test
    fun `encode and decode round-trip with mvm1 magic succeeds`() {
        val originalJson = """{"sources":[{"url":"https://cdn.example.com/1080p.m3u8","quality":"1080p"}],"subtitles":[]}"""
        val seed = "live-seed-1234567890"
        val tmdbId = 550

        val encoded = MovyCipher.encode(originalJson, seed, tmdbId)
        val decoded = MovyCipher.decode(encoded, seed, tmdbId)

        assertEquals(originalJson, decoded)
    }

    @Test
    fun `decode rejects wrong seed or invalid magic`() {
        val originalJson = """{"sources":[]}"""
        val encoded = MovyCipher.encode(originalJson, "correct-seed", 42009)

        assertThrows(IllegalArgumentException::class.java) {
            MovyCipher.decode(encoded, "wrong-seed", 42009)
        }
    }

    @Test
    fun `decode rejects short payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            MovyCipher.decode("ab", "seed", 1)
        }
    }
}
