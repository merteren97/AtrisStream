use crate::http::HttpClient;
use crate::provider::SourceError;
use aes::Aes256;
use atris_core::{StreamFormat, StreamResult, StreamSource, SubtitleFormat, SubtitleTrack};
use base64::{engine::general_purpose, Engine as _};
use cbc::cipher::{block_padding::Pkcs7, BlockDecryptMut, KeyIvInit};
use pbkdf2::pbkdf2_hmac;
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;

pub const FLIXCLOUD_BASE: &str = "https://flixcloud.cc";

/// Decodes a SvelteKit `__data.json` response (including multi-line NDJSON chunks)
/// by unflattening its `devalue` array nodes.
pub fn decode_sveltekit_data_nodes(raw_json: &str) -> Result<Vec<Value>, SourceError> {
    let trimmed = raw_json.trim();
    if trimmed.is_empty() {
        return Ok(Vec::new());
    }

    let mut out = Vec::new();
    let lines: Vec<&str> = if trimmed.contains('\n') && !trimmed.starts_with("{\n") && !trimmed.starts_with("{\r\n") {
        trimmed.lines().map(str::trim).filter(|l| !l.is_empty()).collect()
    } else {
        vec![trimmed]
    };

    for line in lines {
        let Ok(root) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        if let Some(nodes) = root.get("nodes").and_then(|v| v.as_array()) {
            for node in nodes {
                if let Some(arr) = node.get("data").and_then(|v| v.as_array()) {
                    if !arr.is_empty() {
                        out.push(unflatten_devalue(arr, 0, 0));
                    }
                }
            }
        } else if let Some(arr) = root.get("data").and_then(|v| v.as_array()) {
            if !arr.is_empty() {
                out.push(unflatten_devalue(arr, 0, 0));
            }
        } else {
            out.push(root);
        }
    }

    if out.is_empty() {
        let root: Value = serde_json::from_str(trimmed)?;
        out.push(root);
    }
    Ok(out)
}

/// Merges all unflattened object nodes from a SvelteKit `__data.json` or NDJSON response.
pub fn decode_sveltekit_merged(raw_json: &str) -> Result<Value, SourceError> {
    let nodes = decode_sveltekit_data_nodes(raw_json)?;
    let mut merged = Map::new();
    for node in nodes {
        if let Value::Object(obj) = node {
            for (k, v) in obj {
                merged.insert(k, v);
            }
        }
    }
    Ok(Value::Object(merged))
}

/// Decodes SvelteKit NDJSON stream chunks (such as `?x-sveltekit-invalidated=01`),
/// merging chunk nodes by index/id and top-level objects.
pub fn decode_sveltekit_ndjson(raw: &str) -> Result<Value, SourceError> {
    let mut merged = Map::new();
    let mut chunks: HashMap<i64, Value> = HashMap::new();

    for line in raw.lines().map(str::trim).filter(|l| !l.is_empty()) {
        let Ok(val) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        if let Some(nodes) = val.get("nodes").and_then(|v| v.as_array()) {
            for node in nodes {
                if let Some(arr) = node.get("data").and_then(|v| v.as_array()) {
                    if !arr.is_empty() {
                        if let Value::Object(obj) = unflatten_devalue(arr, 0, 0) {
                            for (k, v) in obj {
                                merged.insert(k, v);
                            }
                        }
                    }
                }
            }
        } else if let Some(arr) = val.get("data").and_then(|v| v.as_array()) {
            if !arr.is_empty() {
                let decoded = unflatten_devalue(arr, 0, 0);
                if let Some(id) = val.get("id").and_then(|v| v.as_i64()) {
                    chunks.insert(id, decoded.clone());
                }
                if let Value::Object(obj) = decoded {
                    for (k, v) in obj {
                        merged.insert(k, v);
                    }
                }
            }
        } else if let Value::Object(obj) = val {
            for (k, v) in obj {
                merged.insert(k, v);
            }
        }
    }

    if !chunks.is_empty() {
        let mut keys: Vec<i64> = chunks.keys().copied().collect();
        keys.sort_unstable();
        for k in keys {
            if let Some(Value::Object(obj)) = chunks.remove(&k) {
                for (fk, fv) in obj {
                    merged.insert(fk, fv);
                }
            }
        }
    }

    Ok(Value::Object(merged))
}

fn unflatten_devalue(pool: &[Value], index: i64, depth: usize) -> Value {
    if depth > 64 || index < 0 {
        return Value::Null;
    }
    let Some(val) = pool.get(index as usize) else {
        return Value::Null;
    };

    match val {
        Value::Array(arr) => {
            if let Some(Value::String(tag)) = arr.first() {
                match tag.as_str() {
                    "Date" | "BigInt" | "URL" | "RegExp" => {
                        return arr.get(1).cloned().unwrap_or(Value::Null);
                    }
                    "Set" => {
                        let items = arr[1..]
                            .iter()
                            .filter_map(|v| v.as_i64())
                            .map(|idx| unflatten_devalue(pool, idx, depth + 1))
                            .collect();
                        return Value::Array(items);
                    }
                    "Map" => {
                        let mut map = Map::new();
                        let mut i = 1;
                        while i + 1 < arr.len() {
                            if let (Some(k_idx), Some(v_idx)) = (arr[i].as_i64(), arr[i + 1].as_i64()) {
                                let k_val = unflatten_devalue(pool, k_idx, depth + 1);
                                let v_val = unflatten_devalue(pool, v_idx, depth + 1);
                                if let Some(ks) = k_val.as_str() {
                                    map.insert(ks.to_string(), v_val);
                                }
                            }
                            i += 2;
                        }
                        return Value::Object(map);
                    }
                    _ => {}
                }
            }
            let items = arr
                .iter()
                .map(|elem| match elem.as_i64() {
                    Some(idx) => unflatten_devalue(pool, idx, depth + 1),
                    None => elem.clone(),
                })
                .collect();
            Value::Array(items)
        }
        Value::Object(obj) => {
            let mut map = Map::new();
            for (k, v) in obj {
                let resolved = match v.as_i64() {
                    Some(idx) => unflatten_devalue(pool, idx, depth + 1),
                    None => v.clone(),
                };
                map.insert(k.clone(), resolved);
            }
            Value::Object(map)
        }
        other => other.clone(),
    }
}

/// Computes SHA-256 lowercase hex digest of `input`.
pub fn sha256_hex(input: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    let digest = hasher.finalize();
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

/// Stateful pure-Rust WebAssembly i32 stack VM sharing linear memory and globals across calls.
pub struct WasmInstance {
    types: Vec<(Vec<u8>, Vec<u8>)>,
    func_types: Vec<usize>,
    exports: HashMap<String, usize>,
    codes: Vec<(Vec<(u32, u8)>, Vec<u8>)>,
    pub memory: Vec<u8>,
    pub globals: Vec<i32>,
}

impl WasmInstance {
    pub fn from_bytes(wasm_bytes: &[u8]) -> Result<Self, SourceError> {
        if wasm_bytes.len() < 8 || &wasm_bytes[0..4] != b"\0asm" {
            return Err(SourceError::Cipher("Invalid WASM magic header".to_string()));
        }

        let mut pos = 8usize;
        let mut types: Vec<(Vec<u8>, Vec<u8>)> = Vec::new();
        let mut func_types: Vec<usize> = Vec::new();
        let mut exports: HashMap<String, usize> = HashMap::new();
        let mut codes: Vec<(Vec<(u32, u8)>, Vec<u8>)> = Vec::new();
        let mut memory = vec![0u8; 65536];
        let mut globals: Vec<i32> = Vec::new();

        while pos < wasm_bytes.len() {
            let sec_id = wasm_bytes[pos];
            pos += 1;
            let sec_len = read_u32_leb(wasm_bytes, &mut pos)? as usize;
            let sec_end = (pos + sec_len).min(wasm_bytes.len());
            let mut s = pos;

            match sec_id {
                1 => {
                    let count = read_u32_leb(wasm_bytes, &mut s)?;
                    for _ in 0..count {
                        let form = wasm_bytes.get(s).copied().unwrap_or(0);
                        s += 1;
                        if form != 0x60 {
                            return Err(SourceError::Cipher("Unsupported WASM type form".to_string()));
                        }
                        let np = read_u32_leb(wasm_bytes, &mut s)? as usize;
                        let params = wasm_bytes[s..s + np].to_vec();
                        s += np;
                        let nr = read_u32_leb(wasm_bytes, &mut s)? as usize;
                        let results = wasm_bytes[s..s + nr].to_vec();
                        s += nr;
                        types.push((params, results));
                    }
                }
                3 => {
                    let count = read_u32_leb(wasm_bytes, &mut s)?;
                    for _ in 0..count {
                        func_types.push(read_u32_leb(wasm_bytes, &mut s)? as usize);
                    }
                }
                5 => {
                    let count = read_u32_leb(wasm_bytes, &mut s)?;
                    if count > 0 {
                        let flags = wasm_bytes.get(s).copied().unwrap_or(0);
                        s += 1;
                        let min_pages = read_u32_leb(wasm_bytes, &mut s)? as usize;
                        if flags & 1 != 0 {
                            let _max_pages = read_u32_leb(wasm_bytes, &mut s)?;
                        }
                        let needed = min_pages.saturating_mul(65536).min(1024 * 1024);
                        if needed > memory.len() {
                            memory.resize(needed, 0);
                        }
                    }
                }
                6 => {
                    let count = read_u32_leb(wasm_bytes, &mut s)?;
                    for _ in 0..count {
                        let _vt = wasm_bytes.get(s).copied().unwrap_or(0x7f);
                        let _mut = wasm_bytes.get(s + 1).copied().unwrap_or(0);
                        s += 2;
                        let mut init_val = 0i32;
                        if wasm_bytes.get(s) == Some(&0x41) {
                            s += 1;
                            init_val = read_i32_leb(wasm_bytes, &mut s)?;
                        }
                        while s < sec_end && wasm_bytes[s] != 0x0b {
                            s += 1;
                        }
                        if s < sec_end {
                            s += 1;
                        }
                        globals.push(init_val);
                    }
                }
                7 => {
                    let count = read_u32_leb(wasm_bytes, &mut s)?;
                    for _ in 0..count {
                        let name_len = read_u32_leb(wasm_bytes, &mut s)? as usize;
                        let name = String::from_utf8_lossy(&wasm_bytes[s..s + name_len]).to_string();
                        s += name_len;
                        let kind = wasm_bytes.get(s).copied().unwrap_or(0);
                        s += 1;
                        let idx = read_u32_leb(wasm_bytes, &mut s)? as usize;
                        if kind == 0 {
                            exports.insert(name, idx);
                        }
                    }
                }
                10 => {
                    let count = read_u32_leb(wasm_bytes, &mut s)?;
                    for _ in 0..count {
                        let body_len = read_u32_leb(wasm_bytes, &mut s)? as usize;
                        let body_end = (s + body_len).min(sec_end);
                        let local_decl_count = read_u32_leb(wasm_bytes, &mut s)?;
                        let mut locals = Vec::new();
                        for _ in 0..local_decl_count {
                            let n = read_u32_leb(wasm_bytes, &mut s)?;
                            let vt = wasm_bytes.get(s).copied().unwrap_or(0x7f);
                            s += 1;
                            locals.push((n, vt));
                        }
                        let expr = wasm_bytes[s..body_end].to_vec();
                        codes.push((locals, expr));
                        s = body_end;
                    }
                }
                11 => {
                    let count = read_u32_leb(wasm_bytes, &mut s)?;
                    for _ in 0..count {
                        let mode = read_u32_leb(wasm_bytes, &mut s)?;
                        if mode == 0 {
                            let mut offset = 0usize;
                            if wasm_bytes.get(s) == Some(&0x41) {
                                s += 1;
                                offset = read_i32_leb(wasm_bytes, &mut s)? as usize;
                            }
                            while s < sec_end && wasm_bytes[s] != 0x0b {
                                s += 1;
                            }
                            if s < sec_end {
                                s += 1;
                            }
                            let data_len = read_u32_leb(wasm_bytes, &mut s)? as usize;
                            if offset + data_len > memory.len() && offset + data_len <= 1024 * 1024 {
                                memory.resize(offset + data_len, 0);
                            }
                            if offset + data_len <= memory.len() && s + data_len <= sec_end {
                                memory[offset..offset + data_len]
                                    .copy_from_slice(&wasm_bytes[s..s + data_len]);
                            }
                            s += data_len;
                        }
                    }
                }
                _ => {}
            }
            pos = sec_end;
        }

        Ok(Self {
            types,
            func_types,
            exports,
            codes,
            memory,
            globals,
        })
    }

    pub fn write_bytes(&mut self, offset: usize, data: &[u8]) {
        if offset + data.len() > self.memory.len() && offset + data.len() <= 1024 * 1024 {
            self.memory.resize(offset + data.len(), 0);
        }
        if offset + data.len() <= self.memory.len() {
            self.memory[offset..offset + data.len()].copy_from_slice(data);
        }
    }

    pub fn read_bytes(&self, offset: usize, len: usize) -> &[u8] {
        let end = (offset + len).min(self.memory.len());
        &self.memory[offset.min(end)..end]
    }

    pub fn call(&mut self, export_name: &str, args: &[i32]) -> Result<i32, SourceError> {
        let func_idx = *self.exports.get(export_name).ok_or_else(|| {
            SourceError::Cipher(format!("WASM export '{export_name}' not found"))
        })?;
        self.exec_func(func_idx, args, 0)
    }

    fn exec_func(&mut self, func_idx: usize, args: &[i32], depth: usize) -> Result<i32, SourceError> {
        if depth > 32 {
            return Err(SourceError::Cipher("WASM call depth exceeded".to_string()));
        }
        let (local_decls, code) = self
            .codes
            .get(func_idx)
            .cloned()
            .ok_or_else(|| SourceError::Cipher("WASM function body missing".to_string()))?;

        let mut locals = args.to_vec();
        for (n, _) in local_decls {
            for _ in 0..n {
                locals.push(0);
            }
        }

        let mut stack: Vec<i32> = Vec::new();
        let mut pc = 0usize;
        let mut steps = 0usize;
        let mut blocks: Vec<(bool, usize, usize)> = Vec::new();

        while pc < code.len() {
            steps += 1;
            if steps > 1_000_000 {
                return Err(SourceError::Cipher("WASM step limit exceeded".to_string()));
            }
            let op = code[pc];
            pc += 1;
            match op {
                0x00 => return Err(SourceError::Cipher("WASM unreachable".to_string())),
                0x01 => {}
                0x02 | 0x03 => {
                    let _bt = code.get(pc).copied().unwrap_or(0x40);
                    pc += 1;
                    let end_pc = find_matching_end(&code, pc);
                    blocks.push((op == 0x03, pc, end_pc));
                }
                0x04 => {
                    let _bt = code.get(pc).copied().unwrap_or(0x40);
                    pc += 1;
                    let cond = stack.pop().unwrap_or(0);
                    let (else_pc, end_pc) = find_if_targets(&code, pc);
                    if cond != 0 {
                        blocks.push((false, pc, end_pc));
                    } else if let Some(ep) = else_pc {
                        pc = ep;
                        blocks.push((false, ep, end_pc));
                    } else {
                        pc = end_pc;
                    }
                }
                0x05 => {
                    if let Some((_, _, end_pc)) = blocks.pop() {
                        pc = end_pc;
                    }
                }
                0x0b => {
                    if blocks.pop().is_none() {
                        break;
                    }
                }
                0x0c | 0x0d => {
                    let label = read_u32_leb(&code, &mut pc)? as usize;
                    let do_branch = if op == 0x0d {
                        stack.pop().unwrap_or(0) != 0
                    } else {
                        true
                    };
                    if do_branch {
                        let target_idx = blocks.len().saturating_sub(1 + label);
                        if let Some(&(is_loop, start_pc, end_pc)) = blocks.get(target_idx) {
                            if is_loop {
                                blocks.truncate(target_idx + 1);
                                pc = start_pc;
                            } else {
                                blocks.truncate(target_idx);
                                pc = end_pc;
                            }
                        } else {
                            break;
                        }
                    }
                }
                0x0f => break,
                0x10 => {
                    let target = read_u32_leb(&code, &mut pc)? as usize;
                    let type_idx = *self.func_types.get(target).unwrap_or(&0);
                    let n_params = self.types.get(type_idx).map(|t| t.0.len()).unwrap_or(0);
                    let has_result = self
                        .types
                        .get(type_idx)
                        .map(|t| !t.1.is_empty())
                        .unwrap_or(false);
                    let start = stack.len().saturating_sub(n_params);
                    let call_args: Vec<i32> = stack.drain(start..).collect();
                    let res = self.exec_func(target, &call_args, depth + 1)?;
                    if has_result {
                        stack.push(res);
                    }
                }
                0x1a => {
                    stack.pop();
                }
                0x1b => {
                    let c = stack.pop().unwrap_or(0);
                    let v2 = stack.pop().unwrap_or(0);
                    let v1 = stack.pop().unwrap_or(0);
                    stack.push(if c != 0 { v1 } else { v2 });
                }
                0x20 => {
                    let idx = read_u32_leb(&code, &mut pc)? as usize;
                    stack.push(*locals.get(idx).unwrap_or(&0));
                }
                0x21 => {
                    let idx = read_u32_leb(&code, &mut pc)? as usize;
                    let v = stack.pop().unwrap_or(0);
                    if idx < locals.len() {
                        locals[idx] = v;
                    }
                }
                0x22 => {
                    let idx = read_u32_leb(&code, &mut pc)? as usize;
                    let v = *stack.last().unwrap_or(&0);
                    if idx < locals.len() {
                        locals[idx] = v;
                    }
                }
                0x23 => {
                    let idx = read_u32_leb(&code, &mut pc)? as usize;
                    stack.push(*self.globals.get(idx).unwrap_or(&0));
                }
                0x24 => {
                    let idx = read_u32_leb(&code, &mut pc)? as usize;
                    let v = stack.pop().unwrap_or(0);
                    if idx < self.globals.len() {
                        self.globals[idx] = v;
                    }
                }
                0x28..=0x2d => {
                    let _align = read_u32_leb(&code, &mut pc)?;
                    let offset = read_u32_leb(&code, &mut pc)? as usize;
                    let addr = (stack.pop().unwrap_or(0) as u32 as usize).saturating_add(offset);
                    let val = match op {
                        0x28 if addr + 4 <= self.memory.len() => {
                            i32::from_le_bytes(self.memory[addr..addr + 4].try_into().unwrap())
                        }
                        0x2c if addr < self.memory.len() => (self.memory[addr] as i8) as i32,
                        0x2d if addr < self.memory.len() => self.memory[addr] as i32,
                        _ => 0,
                    };
                    stack.push(val);
                }
                0x36 | 0x3a => {
                    let _align = read_u32_leb(&code, &mut pc)?;
                    let offset = read_u32_leb(&code, &mut pc)? as usize;
                    let val = stack.pop().unwrap_or(0);
                    let addr = (stack.pop().unwrap_or(0) as u32 as usize).saturating_add(offset);
                    if op == 0x36 && addr + 4 <= self.memory.len() {
                        self.memory[addr..addr + 4].copy_from_slice(&val.to_le_bytes());
                    } else if op == 0x3a && addr < self.memory.len() {
                        self.memory[addr] = (val & 0xff) as u8;
                    }
                }
                0x41 => {
                    let v = read_i32_leb(&code, &mut pc)?;
                    stack.push(v);
                }
                0x45 => {
                    let a = stack.pop().unwrap_or(0);
                    stack.push(i32::from(a == 0));
                }
                0x46..=0x4f => {
                    let b = stack.pop().unwrap_or(0);
                    let a = stack.pop().unwrap_or(0);
                    let res = match op {
                        0x46 => a == b,
                        0x47 => a != b,
                        0x48 => a < b,
                        0x49 => (a as u32) < (b as u32),
                        0x4a => a > b,
                        0x4b => (a as u32) > (b as u32),
                        0x4c => a <= b,
                        0x4d => (a as u32) <= (b as u32),
                        0x4e => a >= b,
                        0x4f => (a as u32) >= (b as u32),
                        _ => false,
                    };
                    stack.push(i32::from(res));
                }
                0x67..=0x69 => {
                    let a = stack.pop().unwrap_or(0) as u32;
                    let res = match op {
                        0x67 => a.leading_zeros() as i32,
                        0x68 => a.trailing_zeros() as i32,
                        0x69 => a.count_ones() as i32,
                        _ => 0,
                    };
                    stack.push(res);
                }
                0x6a..=0x78 => {
                    let b = stack.pop().unwrap_or(0);
                    let a = stack.pop().unwrap_or(0);
                    let res = match op {
                        0x6a => a.wrapping_add(b),
                        0x6b => a.wrapping_sub(b),
                        0x6c => a.wrapping_mul(b),
                        0x6d => a.checked_div(b).unwrap_or(0),
                        0x6e => ((a as u32).checked_div(b as u32).unwrap_or(0)) as i32,
                        0x6f => a.checked_rem(b).unwrap_or(0),
                        0x70 => ((a as u32).checked_rem(b as u32).unwrap_or(0)) as i32,
                        0x71 => a & b,
                        0x72 => a | b,
                        0x73 => a ^ b,
                        0x74 => a.wrapping_shl(b as u32),
                        0x75 => a.wrapping_shr(b as u32),
                        0x76 => ((a as u32).wrapping_shr(b as u32)) as i32,
                        0x77 => (a as u32).rotate_left(b as u32) as i32,
                        0x78 => (a as u32).rotate_right(b as u32) as i32,
                        _ => 0,
                    };
                    stack.push(res);
                }
                other => {
                    return Err(SourceError::Cipher(format!(
                        "Unsupported WASM opcode 0x{other:02x}"
                    )));
                }
            }
        }

        Ok(stack.pop().unwrap_or(0))
    }
}

/// Convenience wrapper for single-call WASM execution.
pub fn run_wasm_i32(wasm_bytes: &[u8], export_name: &str, args: &[i32]) -> Result<i32, SourceError> {
    let mut vm = WasmInstance::from_bytes(wasm_bytes)?;
    vm.call(export_name, args)
}

fn find_matching_end(code: &[u8], start: usize) -> usize {
    let mut depth = 1usize;
    let mut p = start;
    while p < code.len() {
        match code[p] {
            0x02 | 0x03 | 0x04 => {
                depth += 1;
                p += 2;
            }
            0x0b => {
                depth -= 1;
                p += 1;
                if depth == 0 {
                    return p;
                }
            }
            0x0c | 0x0d | 0x10 | 0x20..=0x24 | 0x41 => {
                p += 1;
                let _ = read_u32_leb(code, &mut p);
            }
            0x28..=0x3a => {
                p += 1;
                let _ = read_u32_leb(code, &mut p);
                let _ = read_u32_leb(code, &mut p);
            }
            _ => p += 1,
        }
    }
    code.len()
}

fn find_if_targets(code: &[u8], start: usize) -> (Option<usize>, usize) {
    let mut depth = 1usize;
    let mut else_pc = None;
    let mut p = start;
    while p < code.len() {
        match code[p] {
            0x02 | 0x03 | 0x04 => {
                depth += 1;
                p += 2;
            }
            0x05 if depth == 1 && else_pc.is_none() => {
                p += 1;
                else_pc = Some(p);
            }
            0x0b => {
                depth -= 1;
                p += 1;
                if depth == 0 {
                    return (else_pc, p);
                }
            }
            0x0c | 0x0d | 0x10 | 0x20..=0x24 | 0x41 => {
                p += 1;
                let _ = read_u32_leb(code, &mut p);
            }
            0x28..=0x3a => {
                p += 1;
                let _ = read_u32_leb(code, &mut p);
                let _ = read_u32_leb(code, &mut p);
            }
            _ => p += 1,
        }
    }
    (else_pc, code.len())
}

fn read_u32_leb(data: &[u8], pos: &mut usize) -> Result<u32, SourceError> {
    let mut result = 0u32;
    let mut shift = 0u32;
    loop {
        let b = *data
            .get(*pos)
            .ok_or_else(|| SourceError::Cipher("Unexpected EOF in WASM LEB128".to_string()))?;
        *pos += 1;
        result |= ((b & 0x7f) as u32) << shift;
        if b & 0x80 == 0 {
            return Ok(result);
        }
        shift += 7;
        if shift >= 35 {
            return Err(SourceError::Cipher("WASM LEB128 overflow".to_string()));
        }
    }
}

fn read_i32_leb(data: &[u8], pos: &mut usize) -> Result<i32, SourceError> {
    let mut result = 0i32;
    let mut shift = 0u32;
    let mut byte;
    loop {
        byte = *data
            .get(*pos)
            .ok_or_else(|| SourceError::Cipher("Unexpected EOF in WASM SLEB128".to_string()))?;
        *pos += 1;
        result |= ((byte & 0x7f) as i32) << shift;
        shift += 7;
        if byte & 0x80 == 0 {
            break;
        }
        if shift >= 35 {
            return Err(SourceError::Cipher("WASM SLEB128 overflow".to_string()));
        }
    }
    if shift < 32 && (byte & 0x40) != 0 {
        result |= !0i32 << shift;
    }
    Ok(result)
}

/// FlixCloud key-derivation field names derived from `obfuscation_seed`.
pub struct FlixFieldNames {
    pub key_field: String,
    pub iv_field: String,
    pub cd: String,
    pub ad: String,
    pub od: String,
    pub token_field: String,
    pub key_frag2_field: String,
}

pub fn derive_flix_field_names(seed: &str) -> FlixFieldNames {
    let mut e = seed.to_string();
    for r in 0..3 {
        e = sha256_hex(&format!("{e}{r}"));
    }
    let mut a = e.clone();
    for r in 0..3 {
        a = sha256_hex(&format!("{a}{r}"));
    }
    FlixFieldNames {
        key_field: format!("kf_{}", &e[8..16]),
        iv_field: format!("ivf_{}", &e[16..24]),
        cd: format!("cd_{}", &e[24..32]),
        ad: format!("ad_{}", &e[32..40]),
        od: format!("od_{}", &e[40..48]),
        token_field: format!("{}_{}", &e[48..64], &e[56..64]),
        key_frag2_field: format!("{}_{}", &a[0..16], &a[16..24]),
    }
}

/// Decrypts FlixCloud `M` payload using WASM `_s`/`_r` + PBKDF2-HMAC-SHA256 + XOR(seed) + SHA-256 + AES-256-CBC.
pub fn decrypt_flixcloud_m3u8(
    seed: &str,
    wasm_bytes: &[u8],
    frag1: &[u8],
    frag2: &[u8],
    frag3: &[u8],
    iv_bytes: &[u8],
    encrypted_m: &[u8],
) -> Result<String, SourceError> {
    if iv_bytes.len() != 16 {
        return Err(SourceError::Cipher(format!(
            "Invalid AES-CBC IV length: {}",
            iv_bytes.len()
        )));
    }
    if seed.len() < 8 {
        return Err(SourceError::Cipher("FlixCloud seed too short".to_string()));
    }

    let len = frag1.len();
    let mut vm = WasmInstance::from_bytes(wasm_bytes)?;
    vm.write_bytes(1000, frag1);
    vm.write_bytes(1000 + len, frag2);
    vm.write_bytes(1000 + 2 * len, frag3);

    let seed_i32 = u32::from_str_radix(&seed[..8], 16)
        .map_err(|e| SourceError::Cipher(format!("Invalid hex seed prefix: {e}")))? as i32;
    vm.call("_s", &[seed_i32])?;
    vm.call(
        "_r",
        &[
            1000,
            (1000 + len) as i32,
            (1000 + 2 * len) as i32,
            (1000 + 3 * len) as i32,
            len as i32,
        ],
    )?;
    let o_bytes = vm.read_bytes(1000 + 3 * len, len).to_vec();

    let mut h = [0u8; 32];
    pbkdf2_hmac::<Sha256>(&o_bytes, seed.as_bytes(), 1000, &mut h);
    let seed_bytes = seed.as_bytes();
    let q: Vec<u8> = h
        .iter()
        .enumerate()
        .map(|(i, b)| b ^ seed_bytes[i % seed_bytes.len()])
        .collect();
    let pe: [u8; 32] = Sha256::digest(&q).into();

    let mut ct = encrypted_m.to_vec();
    let decryptor = cbc::Decryptor::<Aes256>::new_from_slices(&pe, iv_bytes)
        .map_err(|e| SourceError::Cipher(format!("AES-CBC init failed: {e}")))?;
    let plain = decryptor
        .decrypt_padded_mut::<Pkcs7>(&mut ct)
        .map_err(|e| SourceError::Cipher(format!("AES-CBC decrypt failed: {e}")))?;
    String::from_utf8(plain.to_vec())
        .map_err(|e| SourceError::Cipher(format!("UTF-8 decode failed: {e}")))
}

fn decode_b64_or_hex(input: &str) -> Result<Vec<u8>, SourceError> {
    let trimmed = input.trim();
    if let Ok(bytes) = general_purpose::STANDARD.decode(trimmed) {
        return Ok(bytes);
    }
    if let Ok(bytes) = general_purpose::URL_SAFE_NO_PAD.decode(trimmed) {
        return Ok(bytes);
    }
    if trimmed.len() % 2 == 0 && trimmed.chars().all(|c| c.is_ascii_hexdigit()) {
        let mut out = Vec::with_capacity(trimmed.len() / 2);
        for i in (0..trimmed.len()).step_by(2) {
            if let Ok(b) = u8::from_str_radix(&trimmed[i..i + 2], 16) {
                out.push(b);
            }
        }
        return Ok(out);
    }
    Err(SourceError::Cipher("Invalid base64/hex payload".to_string()))
}

fn parse_flixcloud_embed_id(embed_url_or_id: &str) -> (String, Option<String>) {
    let trimmed = embed_url_or_id.trim();
    let v_param = trimmed.split('?').nth(1).and_then(|q| {
        q.split('&')
            .find_map(|pair| pair.strip_prefix("v=").map(String::from))
    });
    let path_only = trimmed.split('?').next().unwrap_or(trimmed);
    let id = if let Some(after_e) = path_only.split("/e/").nth(1) {
        after_e.trim_matches('/').to_string()
    } else {
        path_only
            .rsplit('/')
            .next()
            .unwrap_or(path_only)
            .to_string()
    };
    (id, v_param)
}

pub async fn extract_streams(
    http: &HttpClient,
    embed_url_or_id: &str,
    server_name: &str,
) -> Result<StreamResult, SourceError> {
    let (id, v_param) = parse_flixcloud_embed_id(embed_url_or_id);
    if id.is_empty() {
        return Err(SourceError::InvalidArgument(
            "Empty FlixCloud embed ID".to_string(),
        ));
    }

    let mut data_url = format!("{FLIXCLOUD_BASE}/e/{id}/__data.json");
    if let Some(v) = v_param {
        data_url.push_str(&format!("?v={v}"));
    }

    let mut headers = HashMap::new();
    headers.insert("Referer".to_string(), format!("{FLIXCLOUD_BASE}/"));
    headers.insert("Origin".to_string(), FLIXCLOUD_BASE.to_string());

    let raw_data = http.get_text(&data_url, &headers).await?;
    let merged = decode_sveltekit_merged(&raw_data)?;
    let obj = merged
        .as_object()
        .ok_or_else(|| SourceError::Cipher("FlixCloud __data.json is not an object".to_string()))?;

    let seed = obj
        .get("obfuscation_seed")
        .and_then(|v| v.as_str())
        .ok_or_else(|| SourceError::Cipher("Missing obfuscation_seed".to_string()))?;
    let fields = derive_flix_field_names(seed);

    let crypto_val = obj
        .get("obfuscated_crypto_data")
        .ok_or_else(|| SourceError::Cipher("Missing obfuscated_crypto_data".to_string()))?;
    let crypto_obj: Map<String, Value> = match crypto_val {
        Value::Object(m) => m.clone(),
        Value::String(s) => {
            if let Ok(Value::Object(m)) = serde_json::from_str::<Value>(s) {
                m
            } else if let Ok(bytes) = decode_b64_or_hex(s) {
                serde_json::from_slice(&bytes)?
            } else {
                return Err(SourceError::Cipher(
                    "Invalid obfuscated_crypto_data format".to_string(),
                ));
            }
        }
        _ => {
            return Err(SourceError::Cipher(
                "Unsupported obfuscated_crypto_data type".to_string(),
            ))
        }
    };

    let nested = crypto_obj
        .get(&fields.cd)
        .and_then(|v| v.get(&fields.ad))
        .and_then(|v| v.as_array())
        .and_then(|arr| arr.first())
        .and_then(|v| v.get(&fields.od))
        .and_then(|v| v.as_object())
        .ok_or_else(|| SourceError::Cipher("Missing nested obfuscated_crypto_data path".to_string()))?;

    let frag1_str = nested
        .get(&fields.key_field)
        .and_then(|v| v.as_str())
        .ok_or_else(|| SourceError::Cipher("Missing FlixCloud frag1".to_string()))?;
    let iv_str = nested
        .get(&fields.iv_field)
        .and_then(|v| v.as_str())
        .ok_or_else(|| SourceError::Cipher("Missing FlixCloud iv".to_string()))?;

    let frag2_str = obj
        .get(&fields.key_frag2_field)
        .and_then(|v| v.as_str())
        .ok_or_else(|| SourceError::Cipher("Missing FlixCloud frag2".to_string()))?;
    let m_token = obj
        .get(&fields.token_field)
        .and_then(|v| v.as_str())
        .ok_or_else(|| SourceError::Cipher("Missing FlixCloud m token".to_string()))?;

    let m3u8_api_url = format!("{FLIXCLOUD_BASE}/api/m3u8/{m_token}");
    let api_raw = http.get_text(&m3u8_api_url, &headers).await?;
    let api_json: Value = serde_json::from_str(&api_raw)?;
    let w_obj = api_json
        .as_object()
        .ok_or_else(|| SourceError::Cipher("FlixCloud /api/m3u8 response not object".to_string()))?;

    let vid_key = sha256_hex(&format!("{m_token}vid"))[..10].to_string();
    let key_key = sha256_hex(&format!("{m_token}key"))[..10].to_string();

    let m_b64 = w_obj
        .get(&vid_key)
        .and_then(|v| v.as_str())
        .ok_or_else(|| SourceError::Cipher("Missing FlixCloud M payload".to_string()))?;
    let frag3_b64 = w_obj
        .get(&key_key)
        .and_then(|v| v.as_str())
        .ok_or_else(|| SourceError::Cipher("Missing FlixCloud frag3 payload".to_string()))?;

    let wasm_b64 = obj
        .get("w_payload")
        .and_then(|v| v.as_str())
        .ok_or_else(|| SourceError::Cipher("Missing FlixCloud w_payload".to_string()))?;
    let wasm_bytes = decode_b64_or_hex(wasm_b64)?;

    let frag1 = decode_b64_or_hex(frag1_str)?;
    let frag2 = decode_b64_or_hex(frag2_str)?;
    let frag3 = decode_b64_or_hex(frag3_b64)?;
    let iv_bytes = decode_b64_or_hex(iv_str)?;
    let encrypted_m = decode_b64_or_hex(m_b64)?;

    let stream_url = decrypt_flixcloud_m3u8(
        seed,
        &wasm_bytes,
        &frag1,
        &frag2,
        &frag3,
        &iv_bytes,
        &encrypted_m,
    )?;

    let mut subtitles = Vec::new();
    if let Some(subs_arr) = obj
        .get("subtitles")
        .or_else(|| w_obj.get("subtitles"))
        .and_then(|v| v.as_array())
    {
        for sub in subs_arr {
            let Some(sub_url) = sub
                .get("url")
                .or_else(|| sub.get("file"))
                .and_then(|v| v.as_str())
            else {
                continue;
            };
            let lang = sub
                .get("lang")
                .or_else(|| sub.get("language"))
                .and_then(|v| v.as_str())
                .unwrap_or("en")
                .to_string();
            let label = sub
                .get("label")
                .and_then(|v| v.as_str())
                .unwrap_or(&lang)
                .to_string();
            let lower = sub_url.to_ascii_lowercase();
            let format = if lower.contains(".srt") {
                SubtitleFormat::SRT
            } else if lower.contains(".ass") {
                SubtitleFormat::ASS
            } else {
                SubtitleFormat::VTT
            };
            subtitles.push(SubtitleTrack {
                url: sub_url.to_string(),
                language: lang,
                label,
                format,
                headers: headers.clone(),
                is_external: false,
            });
        }
    }

    Ok(StreamResult {
        streams: vec![StreamSource {
            url: stream_url,
            quality: "Auto".to_string(),
            format: StreamFormat::HLS,
            server_name: server_name.to_string(),
            audio_lang: None,
            headers,
        }],
        subtitles,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use cbc::cipher::{BlockEncryptMut, KeyIvInit};

    #[test]
    fn decodes_sveltekit_devalue_array() {
        let raw = r#"{
            "type": "data",
            "nodes": [
                null,
                {
                    "type": "data",
                    "data": [
                        {"title": 1, "episodes": 2, "published": 5},
                        "Solo Leveling",
                        [3, 4],
                        {"ep": 6, "playable": 7},
                        {"ep": 8, "playable": 9},
                        "2024-01-06",
                        1,
                        true,
                        2,
                        false
                    ]
                }
            ]
        }"#;
        let merged = decode_sveltekit_merged(raw).unwrap();
        assert_eq!(merged["title"], "Solo Leveling");
        assert_eq!(merged["episodes"][0]["ep"], 1);
        assert_eq!(merged["episodes"][0]["playable"], true);
        assert_eq!(merged["episodes"][1]["ep"], 2);
        assert_eq!(merged["episodes"][1]["playable"], false);
    }

    #[test]
    fn decodes_sveltekit_ndjson_chunks() {
        let ndjson = concat!(
            r#"{"type":"data","nodes":[{"type":"data","data":[{"title":1},"Frieren"]}]}"#,
            "\n",
            r#"{"type":"chunk","id":1,"data":[{"zen":1},{"data":2},[3],{"player_url":4},"https://flixcloud.cc/e/abc?v=1"]}"#
        );
        let decoded = decode_sveltekit_ndjson(ndjson).unwrap();
        assert_eq!(decoded["title"], "Frieren");
        assert_eq!(
            decoded["zen"]["data"][0]["player_url"],
            "https://flixcloud.cc/e/abc?v=1"
        );
    }

    #[test]
    fn wasm_vm_shares_memory_and_globals_across_s_and_r_calls() {
        // WASM binary with 1 i32 global (g0 = 0), 1 memory (1 page),
        // _s(seed): g0 = seed & 0xff;
        // _r(p1, p2, p3, out, len): writes mem[out] = mem[p1] ^ mem[p2] ^ mem[p3] ^ g0 for 1 byte (or loop)
        // Here we test a loop over `i` from 0 to len-1!
        // Locals: p1=0, p2=1, p3=2, out=3, len=4, i=5
        // _s(seed): local.get 0, i32.const 0xff, i32.and, global.set 0, end
        // _r(p1, p2, p3, out, len):
        //   loop:
        //     local.get 5, local.get 4, i32.lt_u, if
        //       local.get 3, local.get 5, i32.add (addr_out)
        //       local.get 0, local.get 5, i32.add, i32.load8_u
        //       local.get 1, local.get 5, i32.add, i32.load8_u, i32.xor
        //       local.get 2, local.get 5, i32.add, i32.load8_u, i32.xor
        //       global.get 0, i32.xor
        //       i32.store8
        //       local.get 5, i32.const 1, i32.add, local.set 5
        //       br 1
        //     end
        //   end, i32.const 0, end
        let body_s: Vec<u8> = vec![
            0x00, // 0 local decls
            0x20, 0x00, 0x41, 0xff, 0x7f, 0x00, // wait: i32.const 0xff is SLEB128 0xff 0x00!
        ];
        let _ = body_s;

        // Simpler & exact SLEB128 bytes:
        // _s(seed): local.get 0, i32.const 0x3f (0x3f), i32.and, global.set 0, end
        let fn_s: Vec<u8> = vec![0x00, 0x20, 0x00, 0x41, 0x3f, 0x71, 0x24, 0x00, 0x0b];
        // _r(p1, p2, p3, out, len) -> i32:
        // single-byte or 4-byte? Let's do a 1-byte XOR with loop for len bytes:
        // local decl: 1 group of 1 i32 (local 5 = i)
        let fn_r: Vec<u8> = vec![
            0x01, 0x01, 0x7f, // 1 local: i32
            0x03, 0x40, // loop
            0x20, 0x05, 0x20, 0x04, 0x49, // i < len (lt_u)
            0x04, 0x40, // if
            0x20, 0x03, 0x20, 0x05, 0x6a, // out + i
            0x20, 0x00, 0x20, 0x05, 0x6a, 0x2d, 0x00, 0x00, // mem[p1 + i]
            0x20, 0x01, 0x20, 0x05, 0x6a, 0x2d, 0x00, 0x00, 0x73, // ^ mem[p2 + i]
            0x20, 0x02, 0x20, 0x05, 0x6a, 0x2d, 0x00, 0x00, 0x73, // ^ mem[p3 + i]
            0x23, 0x00, 0x73, // ^ g0
            0x3a, 0x00, 0x00, // store8
            0x20, 0x05, 0x41, 0x01, 0x6a, 0x21, 0x05, // i += 1
            0x0c, 0x01, // br 1 (back to loop)
            0x0b, // end if
            0x0b, // end loop
            0x41, 0x00, // return 0
            0x0b, // end func
        ];

        let mut wasm: Vec<u8> = vec![0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00];
        // Type section: (i32)->(), (i32,i32,i32,i32,i32)->i32
        let type_sec = vec![
            0x02,
            0x60, 0x01, 0x7f, 0x00,
            0x60, 0x05, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x01, 0x7f,
        ];
        wasm.push(1);
        wasm.push(type_sec.len() as u8);
        wasm.extend_from_slice(&type_sec);

        // Function section: 2 funcs
        wasm.extend_from_slice(&[3, 3, 2, 0, 1]);
        // Memory section: 1 memory, min 1 page
        wasm.extend_from_slice(&[5, 3, 1, 0, 1]);
        // Global section: 1 mutable i32 = 0
        wasm.extend_from_slice(&[6, 6, 1, 0x7f, 0x01, 0x41, 0x00, 0x0b]);
        // Export section: "_s"->0, "_r"->1
        let exp_sec = vec![
            0x02,
            0x02, b'_', b's', 0x00, 0x00,
            0x02, b'_', b'r', 0x00, 0x01,
        ];
        wasm.push(7);
        wasm.push(exp_sec.len() as u8);
        wasm.extend_from_slice(&exp_sec);

        // Code section
        let mut code_sec = vec![0x02];
        code_sec.push(fn_s.len() as u8);
        code_sec.extend_from_slice(&fn_s);
        code_sec.push(fn_r.len() as u8);
        code_sec.extend_from_slice(&fn_r);
        wasm.push(10);
        wasm.push(code_sec.len() as u8);
        wasm.extend_from_slice(&code_sec);

        let seed = "12345678abcdef0123456789abcdef01";
        let frag1 = b"AAAA";
        let frag2 = b"BBBB";
        let frag3 = b"CCCC";
        let len = frag1.len();

        // Expected O: frag1[i] ^ frag2[i] ^ frag3[i] ^ (0x12345678 & 0x3f)
        let g0 = (0x12345678u32 & 0x3f) as u8;
        let expected_o: Vec<u8> = (0..len).map(|i| frag1[i] ^ frag2[i] ^ frag3[i] ^ g0).collect();

        let mut h = [0u8; 32];
        pbkdf2_hmac::<Sha256>(&expected_o, seed.as_bytes(), 1000, &mut h);
        let q: Vec<u8> = h
            .iter()
            .enumerate()
            .map(|(i, b)| b ^ seed.as_bytes()[i % seed.len()])
            .collect();
        let pe: [u8; 32] = Sha256::digest(&q).into();

        let iv = [0x07u8; 16];
        let plaintext = b"https://cdn.flixcloud.cc/live/master.m3u8";
        let mut buf = vec![0u8; plaintext.len() + 16];
        buf[..plaintext.len()].copy_from_slice(plaintext);
        let ct = cbc::Encryptor::<Aes256>::new_from_slices(&pe, &iv)
            .unwrap()
            .encrypt_padded_mut::<Pkcs7>(&mut buf, plaintext.len())
            .unwrap()
            .to_vec();

        let decrypted =
            decrypt_flixcloud_m3u8(seed, &wasm, frag1, frag2, frag3, &iv, &ct).unwrap();
        assert_eq!(decrypted, "https://cdn.flixcloud.cc/live/master.m3u8");
    }
}
