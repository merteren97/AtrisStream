use crate::provider::SourceError;
use std::collections::HashMap;

const MAGIC: [u8; 4] = [109, 118, 109, 49]; // "mvm1"

/// Bit-exact port of Android's `MovyCipher.kt` for Movy.sx `enc=2` stream API payloads.
pub struct MovyCipher;

impl MovyCipher {
    #[inline]
    pub fn rotl32(x: u32, k: i32) -> u32 {
        let shift = (k & 31) as u32;
        if shift == 0 {
            x
        } else {
            (x << shift) | (x >> (32 - shift))
        }
    }

    #[inline]
    pub fn mix32(x: u32) -> u32 {
        let mut v = x;
        v ^= v >> 16;
        v = v.wrapping_mul(0x85ebca6b);
        v ^= v >> 13;
        v = v.wrapping_mul(0xc2b2ae35);
        v ^= v >> 16;
        v
    }

    pub fn generate_keystream(seed: &str, tmdb_id: i32, length: usize) -> Vec<u8> {
        if length == 0 {
            return Vec::new();
        }

        let mut h: u32 = 0x811c9dc5;
        for ch in seed.encode_utf16() {
            h = (h ^ (ch as u32)).wrapping_mul(0x1000193);
        }

        let mut s = Self::mix32(
            Self::mix32(h) ^ Self::mix32((tmdb_id as u32) ^ 0x9e3779b9),
        );
        let mut sparse_state: HashMap<i32, u32> = HashMap::new();
        for e in 0..=7 {
            let a = (s % 61) as i32;
            s = Self::rotl32(s.wrapping_add(0x9e3779b9), 7 + (e & 7));
            sparse_state.insert(a, s ^ Self::mix32(s));
            s = Self::mix32(s.wrapping_add(a as u32));
        }

        let mut acc = Self::mix32(0xa5a5a5a5 ^ s);
        let mut out = vec![0u8; length];
        let mut offset = 0usize;
        let mut step = 0u32;

        while offset < length {
            let i = (acc % 61) as i32;
            let r: u32 = if sparse_state.contains_key(&i) {
                0xFFFFFFFF
            } else {
                0
            };
            let d = sparse_state.get(&i).copied().unwrap_or(0);
            let n = d ^ 0x9e3779b9u32.wrapping_mul(step.wrapping_add(1));
            let mut b = (acc ^ n) | (acc & n & r);
            b = Self::rotl32(b.wrapping_add(acc), i & 31)
                ^ Self::rotl32(acc, (i.wrapping_mul(7)) & 31);
            acc = Self::mix32(b.wrapping_add(0x9e3779b9));
            sparse_state.insert(i, acc);

            let word = acc;
            for byte_idx in 0..4 {
                if offset >= length {
                    break;
                }
                out[offset] = ((word >> (byte_idx * 8)) & 0xFF) as u8;
                offset += 1;
            }
            step = step.wrapping_add(1);
        }

        out
    }

    pub fn decode(encoded_base64_url: &str, seed: &str, tmdb_id: i32) -> Result<String, SourceError> {
        let raw = base64_url_decode(encoded_base64_url.trim())?;
        if raw.len() < MAGIC.len() {
            return Err(SourceError::Cipher(format!(
                "Encrypted payload too short ({} bytes)",
                raw.len()
            )));
        }

        let keystream = Self::generate_keystream(seed, tmdb_id, raw.len());
        let mut plain = vec![0u8; raw.len()];
        for idx in 0..raw.len() {
            plain[idx] = raw[idx] ^ keystream[idx];
        }

        if plain[..MAGIC.len()] != MAGIC {
            return Err(SourceError::Cipher(
                "Invalid magic header in decoded Movy payload".to_string(),
            ));
        }

        String::from_utf8(plain[MAGIC.len()..].to_vec())
            .map_err(|e| SourceError::Cipher(format!("UTF-8 decode error: {e}")))
    }

    pub fn encode(json: &str, seed: &str, tmdb_id: i32) -> String {
        let payload = json.as_bytes();
        let mut plain = Vec::with_capacity(MAGIC.len() + payload.len());
        plain.extend_from_slice(&MAGIC);
        plain.extend_from_slice(payload);

        let keystream = Self::generate_keystream(seed, tmdb_id, plain.len());
        let encrypted: Vec<u8> = plain
            .iter()
            .zip(keystream.iter())
            .map(|(p, k)| p ^ k)
            .collect();
        base64_url_encode(&encrypted)
    }
}

fn base64_url_decode(input: &str) -> Result<Vec<u8>, SourceError> {
    let clean: String = input
        .chars()
        .map(|c| match c {
            '-' => '+',
            '_' => '/',
            other => other,
        })
        .filter(|c| !c.is_whitespace() && *c != '=')
        .collect();

    if clean.is_empty() {
        return Ok(Vec::new());
    }

    let mut out = Vec::with_capacity((clean.len() * 3) / 4);
    let mut buffer: u32 = 0;
    let mut bits_collected: u32 = 0;

    for ch in clean.chars() {
        let value: u32 = match ch {
            'A'..='Z' => (ch as u32) - ('A' as u32),
            'a'..='z' => (ch as u32) - ('a' as u32) + 26,
            '0'..='9' => (ch as u32) - ('0' as u32) + 52,
            '+' => 62,
            '/' => 63,
            _ => {
                return Err(SourceError::Cipher(format!(
                    "Invalid base64url character: '{ch}'"
                )))
            }
        };
        buffer = (buffer << 6) | value;
        bits_collected += 6;
        if bits_collected >= 8 {
            bits_collected -= 8;
            out.push(((buffer >> bits_collected) & 0xFF) as u8);
        }
    }

    Ok(out)
}

fn base64_url_encode(data: &[u8]) -> String {
    if data.is_empty() {
        return String::new();
    }
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity((data.len() * 4 + 2) / 3);
    let mut i = 0;
    while i < data.len() {
        let b0 = data[i] as u32;
        i += 1;
        let has_b1 = i < data.len();
        let b1 = if has_b1 {
            let v = data[i] as u32;
            i += 1;
            v
        } else {
            0
        };
        let has_b2 = i < data.len();
        let b2 = if has_b2 {
            let v = data[i] as u32;
            i += 1;
            v
        } else {
            0
        };

        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(ALPHABET[((triple >> 18) & 0x3F) as usize] as char);
        out.push(ALPHABET[((triple >> 12) & 0x3F) as usize] as char);
        if has_b1 {
            out.push(ALPHABET[((triple >> 6) & 0x3F) as usize] as char);
        }
        if has_b2 {
            out.push(ALPHABET[(triple & 0x3F) as usize] as char);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rotl32_rotates_accurately() {
        assert_eq!(MovyCipher::rotl32(0x12345678, 0), 0x12345678);
        assert_eq!(MovyCipher::rotl32(0x12345678, 4), 0x23456781);
        assert_eq!(MovyCipher::rotl32(0x80000000, 1), 0x00000001);
        assert_eq!(MovyCipher::rotl32(0x12345678, 36), 0x23456781);
    }

    #[test]
    fn mix32_produces_deterministic_avalanched_output() {
        let a = MovyCipher::mix32(0);
        let b = MovyCipher::mix32(1);
        let a_repeat = MovyCipher::mix32(0);
        assert_eq!(a, 0);
        assert_eq!(a, a_repeat);
        assert_ne!(b, 0);
        assert_ne!(b, 1);
        assert_ne!(MovyCipher::mix32(0x00010000), 0x00010000);
    }

    #[test]
    fn keystream_is_deterministic_for_same_seed_and_tmdb_id() {
        let ks1 = MovyCipher::generate_keystream("test-seed-abc", 550, 64);
        let ks2 = MovyCipher::generate_keystream("test-seed-abc", 550, 64);
        let ks_diff_seed = MovyCipher::generate_keystream("test-seed-xyz", 550, 64);
        let ks_diff_id = MovyCipher::generate_keystream("test-seed-abc", 42009, 64);

        assert_eq!(ks1.len(), 64);
        assert_eq!(ks1, ks2);
        assert_ne!(ks1, ks_diff_seed);
        assert_ne!(ks1, ks_diff_id);
    }

    #[test]
    fn encode_and_decode_round_trip_succeeds() {
        let original_json = r#"{"sources":[{"url":"https://cdn.example.com/1080p.m3u8","quality":"1080p"}],"subtitles":[]}"#;
        let seed = "live-seed-1234567890";
        let tmdb_id = 550;

        let encoded = MovyCipher::encode(original_json, seed, tmdb_id);
        let decoded = MovyCipher::decode(&encoded, seed, tmdb_id).unwrap();

        assert_eq!(decoded, original_json);
    }

    #[test]
    fn decode_rejects_wrong_seed() {
        let encoded = MovyCipher::encode(r#"{"sources":[]}"#, "correct-seed", 42009);
        assert!(MovyCipher::decode(&encoded, "wrong-seed", 42009).is_err());
    }

    #[test]
    fn decode_rejects_short_payload() {
        assert!(MovyCipher::decode("ab", "seed", 1).is_err());
    }
}
