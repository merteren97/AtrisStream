use atris_core::SubtitleFormat;
use axum::{
    body::Body,
    extract::{Path, Query, State},
    http::{header, HeaderMap, HeaderName, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use serde::Deserialize;
use std::{
    collections::{HashMap, VecDeque},
    net::SocketAddr,
    sync::{Arc, RwLock},
    time::Duration,
};
use tokio::net::TcpListener;
use url::Url;
use uuid::Uuid;

const MAX_REGISTRY_ENTRIES: usize = 64;

#[derive(Clone)]
struct RegisteredStream {
    url: String,
    headers: HashMap<String, String>,
}

#[derive(Clone)]
struct RegisteredSubtitle {
    url: String,
    headers: HashMap<String, String>,
    format: SubtitleFormat,
}

struct Registry<T> {
    items: HashMap<String, T>,
    order: VecDeque<String>,
}

impl<T> Default for Registry<T> {
    fn default() -> Self {
        Self {
            items: HashMap::new(),
            order: VecDeque::new(),
        }
    }
}

impl<T> Registry<T> {
    fn insert(&mut self, id: String, value: T) {
        if !self.items.contains_key(&id) {
            self.order.push_back(id.clone());
        }
        self.items.insert(id, value);
        while self.items.len() > MAX_REGISTRY_ENTRIES {
            if let Some(oldest) = self.order.pop_front() {
                self.items.remove(&oldest);
            } else {
                break;
            }
        }
    }

    fn get(&self, id: &str) -> Option<&T> {
        self.items.get(id)
    }
}

#[derive(Clone)]
struct ProxyState {
    auth_token: String,
    port: u16,
    client: reqwest::Client,
    streams: Arc<RwLock<Registry<RegisteredStream>>>,
    subtitles: Arc<RwLock<Registry<RegisteredSubtitle>>>,
}

#[derive(Clone)]
pub struct StreamProxyServer {
    state: ProxyState,
    _shutdown_tx: Arc<tokio::sync::watch::Sender<bool>>,
}

impl StreamProxyServer {
    pub async fn start() -> Result<Self, String> {
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .map_err(|e| format!("Failed to bind proxy listener: {e}"))?;
        let addr: SocketAddr = listener
            .local_addr()
            .map_err(|e| format!("Failed to read proxy local_addr: {e}"))?;

        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::limited(8))
            .build()
            .map_err(|e| format!("Failed to build reqwest client: {e}"))?;

        let state = ProxyState {
            auth_token: Uuid::new_v4().to_string(),
            port: addr.port(),
            client,
            streams: Arc::new(RwLock::new(Registry::default())),
            subtitles: Arc::new(RwLock::new(Registry::default())),
        };

        let app = Router::new()
            .route("/stream/{id}", get(handle_stream))
            .route("/proxy-asset", get(handle_proxy_asset))
            .route("/subtitle/{id}", get(handle_subtitle))
            .with_state(state.clone());

        let (shutdown_tx, mut shutdown_rx) = tokio::sync::watch::channel(false);

        tokio::spawn(async move {
            let server = axum::serve(listener, app).with_graceful_shutdown(async move {
                let _ = shutdown_rx.changed().await;
            });
            let _ = server.await;
        });

        Ok(Self {
            state,
            _shutdown_tx: Arc::new(shutdown_tx),
        })
    }

    pub fn port(&self) -> u16 {
        self.state.port
    }

    pub fn register_stream(&self, url: String, headers: HashMap<String, String>) -> String {
        let id = Uuid::new_v4().to_string();
        if let Ok(mut reg) = self.state.streams.write() {
            reg.insert(id.clone(), RegisteredStream { url, headers });
        }
        format!(
            "http://127.0.0.1:{}/stream/{}?t={}",
            self.state.port, id, self.state.auth_token
        )
    }

    pub fn register_subtitle(
        &self,
        url: String,
        headers: HashMap<String, String>,
        format: SubtitleFormat,
    ) -> String {
        let id = Uuid::new_v4().to_string();
        if let Ok(mut reg) = self.state.subtitles.write() {
            reg.insert(
                id.clone(),
                RegisteredSubtitle {
                    url,
                    headers,
                    format,
                },
            );
        }
        format!(
            "http://127.0.0.1:{}/subtitle/{}?t={}",
            self.state.port, id, self.state.auth_token
        )
    }
}

#[derive(Deserialize)]
struct TokenQuery {
    t: Option<String>,
}

#[derive(Deserialize)]
struct AssetQuery {
    u: Option<String>,
    #[serde(rename = "ref")]
    ref_id: Option<String>,
    t: Option<String>,
}

fn unauthorized_response() -> Response {
    (StatusCode::UNAUTHORIZED, "Unauthorized").into_response()
}

fn cors_headers(mut builder: axum::http::response::Builder) -> axum::http::response::Builder {
    builder = builder
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .header(header::ACCESS_CONTROL_ALLOW_METHODS, "GET, OPTIONS")
        .header(header::ACCESS_CONTROL_ALLOW_HEADERS, "Range, Content-Type");
    builder
}

fn build_upstream_headers(
    custom_headers: &HashMap<String, String>,
    incoming: &HeaderMap,
) -> reqwest::header::HeaderMap {
    let mut out = reqwest::header::HeaderMap::new();
    for (k, v) in custom_headers {
        if let (Ok(name), Ok(val)) = (
            reqwest::header::HeaderName::from_bytes(k.as_bytes()),
            reqwest::header::HeaderValue::from_str(v),
        ) {
            out.insert(name, val);
        }
    }
    if let Some(range) = incoming.get(header::RANGE) {
        if let Ok(val) = reqwest::header::HeaderValue::from_bytes(range.as_bytes()) {
            out.insert(reqwest::header::RANGE, val);
        }
    }
    if !out.contains_key(reqwest::header::USER_AGENT) {
        out.insert(
            reqwest::header::USER_AGENT,
            reqwest::header::HeaderValue::from_static(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            ),
        );
    }
    out
}

fn is_hls_response(url: &str, content_type: Option<&str>) -> bool {
    if let Some(ct) = content_type {
        let lower = ct.to_ascii_lowercase();
        if lower.contains("mpegurl") || lower.contains("m3u8") {
            return true;
        }
    }
    Url::parse(url)
        .map(|u| u.path().to_ascii_lowercase().ends_with(".m3u8"))
        .unwrap_or_else(|_| url.to_ascii_lowercase().contains(".m3u8"))
}

async fn handle_stream(
    State(state): State<ProxyState>,
    Path(id): Path<String>,
    Query(query): Query<TokenQuery>,
    incoming_headers: HeaderMap,
) -> Response {
    if query.t.as_deref() != Some(state.auth_token.as_str()) {
        return unauthorized_response();
    }

    let entry = {
        let Ok(reg) = state.streams.read() else {
            return (StatusCode::INTERNAL_SERVER_ERROR, "Registry lock failed").into_response();
        };
        reg.get(&id).cloned()
    };

    let Some(entry) = entry else {
        return (StatusCode::NOT_FOUND, "Stream not found").into_response();
    };

    proxy_media_url(&state, &entry.url, &entry.headers, &id, &incoming_headers).await
}

async fn handle_proxy_asset(
    State(state): State<ProxyState>,
    Query(query): Query<AssetQuery>,
    incoming_headers: HeaderMap,
) -> Response {
    if query.t.as_deref() != Some(state.auth_token.as_str()) {
        return unauthorized_response();
    }

    let Some(encoded_url) = query.u else {
        return (StatusCode::BAD_REQUEST, "Missing u parameter").into_response();
    };

    let Some(target_url) = base64url_decode_str(&encoded_url) else {
        return (StatusCode::BAD_REQUEST, "Invalid base64url").into_response();
    };

    let ref_id = query.ref_id.unwrap_or_default();
    let custom_headers = if !ref_id.is_empty() {
        state
            .streams
            .read()
            .ok()
            .and_then(|reg| reg.get(&ref_id).map(|s| s.headers.clone()))
            .unwrap_or_default()
    } else {
        HashMap::new()
    };

    proxy_media_url(&state, &target_url, &custom_headers, &ref_id, &incoming_headers).await
}

async fn proxy_media_url(
    state: &ProxyState,
    target_url: &str,
    custom_headers: &HashMap<String, String>,
    ref_id: &str,
    incoming_headers: &HeaderMap,
) -> Response {
    let req_headers = build_upstream_headers(custom_headers, incoming_headers);
    let resp = match state.client.get(target_url).headers(req_headers).send().await {
        Ok(r) => r,
        Err(e) => return (StatusCode::BAD_GATEWAY, format!("Upstream error: {e}")).into_response(),
    };

    let status = StatusCode::from_u16(resp.status().as_u16()).unwrap_or(StatusCode::OK);
    let final_url = resp.url().to_string();
    let content_type = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());

    if status.is_success() && is_hls_response(&final_url, content_type.as_deref()) {
        let playlist_text = match resp.text().await {
            Ok(t) => t,
            Err(e) => {
                return (StatusCode::BAD_GATEWAY, format!("Failed to read M3U8: {e}"))
                    .into_response()
            }
        };

        let rewritten = rewrite_m3u8_playlist(
            &playlist_text,
            &final_url,
            state.port,
            ref_id,
            &state.auth_token,
        );

        let builder = cors_headers(Response::builder().status(StatusCode::OK))
            .header(header::CONTENT_TYPE, "application/vnd.apple.mpegurl; charset=utf-8")
            .header(header::CACHE_CONTROL, "no-store");
        return builder
            .body(Body::from(rewritten))
            .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response());
    }

    let mut builder = cors_headers(Response::builder().status(status));
    for header_name in [
        reqwest::header::CONTENT_TYPE,
        reqwest::header::CONTENT_RANGE,
        reqwest::header::ACCEPT_RANGES,
        reqwest::header::CONTENT_LENGTH,
    ] {
        if let Some(val) = resp.headers().get(&header_name) {
            if let (Ok(ax_name), Ok(ax_val)) = (
                HeaderName::from_bytes(header_name.as_str().as_bytes()),
                HeaderValue::from_bytes(val.as_bytes()),
            ) {
                builder = builder.header(ax_name, ax_val);
            }
        }
    }

    let body = Body::from_stream(resp.bytes_stream());
    builder
        .body(body)
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

async fn handle_subtitle(
    State(state): State<ProxyState>,
    Path(id): Path<String>,
    Query(query): Query<TokenQuery>,
    incoming_headers: HeaderMap,
) -> Response {
    if query.t.as_deref() != Some(state.auth_token.as_str()) {
        return unauthorized_response();
    }

    let entry = {
        let Ok(reg) = state.subtitles.read() else {
            return (StatusCode::INTERNAL_SERVER_ERROR, "Registry lock failed").into_response();
        };
        reg.get(&id).cloned()
    };

    let Some(entry) = entry else {
        return (StatusCode::NOT_FOUND, "Subtitle not found").into_response();
    };

    let req_headers = build_upstream_headers(&entry.headers, &incoming_headers);
    let resp = match state.client.get(&entry.url).headers(req_headers).send().await {
        Ok(r) => r,
        Err(e) => return (StatusCode::BAD_GATEWAY, format!("Upstream error: {e}")).into_response(),
    };

    if !resp.status().is_success() {
        return (
            StatusCode::from_u16(resp.status().as_u16()).unwrap_or(StatusCode::BAD_GATEWAY),
            "Subtitle fetch failed",
        )
            .into_response();
    }

    let raw = match resp.text().await {
        Ok(t) => t,
        Err(e) => {
            return (
                StatusCode::BAD_GATEWAY,
                format!("Failed to read subtitle: {e}"),
            )
                .into_response()
        }
    };

    let vtt = match entry.format {
        SubtitleFormat::VTT => {
            let trimmed = raw.trim_start_matches('\u{feff}');
            if trimmed.starts_with("WEBVTT") {
                trimmed.to_string()
            } else {
                format!("WEBVTT\n\n{trimmed}")
            }
        }
        SubtitleFormat::SRT => srt_to_vtt(&raw),
        SubtitleFormat::ASS => ass_to_vtt(&raw),
    };

    cors_headers(Response::builder().status(StatusCode::OK))
        .header(header::CONTENT_TYPE, "text/vtt; charset=utf-8")
        .body(Body::from(vtt))
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

pub fn rewrite_m3u8_playlist(
    playlist: &str,
    base_url: &str,
    proxy_port: u16,
    ref_id: &str,
    auth_token: &str,
) -> String {
    let base = Url::parse(base_url).ok();
    let mut out = String::with_capacity(playlist.len() + 256);

    for raw_line in playlist.lines() {
        let line = raw_line.trim_end_matches('\r');
        if line.is_empty() {
            out.push('\n');
            continue;
        }

        if line.starts_with('#') {
            let rewritten_tag = rewrite_m3u8_uri_attributes(line, base.as_ref(), proxy_port, ref_id, auth_token);
            out.push_str(&rewritten_tag);
            out.push('\n');
        } else {
            let resolved = resolve_url(base.as_ref(), line.trim());
            let proxied = make_proxy_asset_url(&resolved, proxy_port, ref_id, auth_token);
            out.push_str(&proxied);
            out.push('\n');
        }
    }

    out
}

fn rewrite_m3u8_uri_attributes(
    line: &str,
    base: Option<&Url>,
    proxy_port: u16,
    ref_id: &str,
    auth_token: &str,
) -> String {
    let mut result = String::with_capacity(line.len() + 64);
    let mut rest = line;

    while let Some(idx) = rest.find("URI=\"") {
        let prefix_end = idx + 5;
        result.push_str(&rest[..prefix_end]);
        let after_quote = &rest[prefix_end..];
        if let Some(end_quote) = after_quote.find('"') {
            let uri_val = &after_quote[..end_quote];
            let resolved = resolve_url(base, uri_val);
            let proxied = make_proxy_asset_url(&resolved, proxy_port, ref_id, auth_token);
            result.push_str(&proxied);
            result.push('"');
            rest = &after_quote[end_quote + 1..];
        } else {
            result.push_str(after_quote);
            rest = "";
            break;
        }
    }
    result.push_str(rest);
    result
}

fn resolve_url(base: Option<&Url>, target: &str) -> String {
    if let Some(b) = base {
        if let Ok(joined) = b.join(target) {
            return joined.to_string();
        }
    }
    target.to_string()
}

fn make_proxy_asset_url(target_url: &str, port: u16, ref_id: &str, token: &str) -> String {
    let encoded = base64url_encode(target_url.as_bytes());
    format!(
        "http://127.0.0.1:{port}/proxy-asset?u={encoded}&ref={ref_id}&t={token}"
    )
}

pub fn srt_to_vtt(srt: &str) -> String {
    let normalized = srt.trim_start_matches('\u{feff}').replace("\r\n", "\n").replace('\r', "\n");
    let mut out = String::from("WEBVTT\n\n");

    for line in normalized.lines() {
        if line.contains("-->") {
            let converted = convert_srt_timestamp_line(line);
            out.push_str(&converted);
        } else {
            out.push_str(line);
        }
        out.push('\n');
    }

    out
}

fn convert_srt_timestamp_line(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let chars: Vec<char> = line.chars().collect();
    for i in 0..chars.len() {
        if chars[i] == ','
            && i > 0
            && chars[i - 1].is_ascii_digit()
            && i + 1 < chars.len()
            && chars[i + 1].is_ascii_digit()
        {
            out.push('.');
        } else {
            out.push(chars[i]);
        }
    }
    out
}

pub fn ass_to_vtt(ass: &str) -> String {
    let normalized = ass.trim_start_matches('\u{feff}').replace("\r\n", "\n").replace('\r', "\n");
    let mut out = String::from("WEBVTT\n\n");

    let mut start_idx = 1usize;
    let mut end_idx = 2usize;
    let mut text_idx = 9usize;
    let mut total_fields = 10usize;
    let mut in_events = false;

    for raw_line in normalized.lines() {
        let line = raw_line.trim();
        if line.starts_with('[') && line.ends_with(']') {
            in_events = line.eq_ignore_ascii_case("[Events]");
            continue;
        }
        if !in_events && !line.starts_with("Dialogue:") {
            continue;
        }
        if let Some(fmt) = line.strip_prefix("Format:") {
            let cols: Vec<&str> = fmt.split(',').map(|s| s.trim()).collect();
            if !cols.is_empty() {
                total_fields = cols.len();
                for (idx, col) in cols.iter().enumerate() {
                    if col.eq_ignore_ascii_case("Start") {
                        start_idx = idx;
                    } else if col.eq_ignore_ascii_case("End") {
                        end_idx = idx;
                    } else if col.eq_ignore_ascii_case("Text") {
                        text_idx = idx;
                    }
                }
            }
            continue;
        }

        if let Some(payload) = line.strip_prefix("Dialogue:") {
            let fields: Vec<&str> = payload.trim_start().splitn(total_fields, ',').collect();
            if fields.len() <= text_idx {
                continue;
            }
            let start = format_ass_timestamp(fields[start_idx].trim());
            let end = format_ass_timestamp(fields[end_idx].trim());
            let text = strip_ass_tags(fields[text_idx].trim())
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ");
            let text = text.trim();
            if text.is_empty() {
                continue;
            }
            out.push_str(&format!("{start} --> {end}\n{text}\n\n"));
        }
    }

    out
}

fn format_ass_timestamp(ts: &str) -> String {
    // ASS format: H:MM:SS.cc -> VTT: HH:MM:SS.cc0
    let parts: Vec<&str> = ts.split(':').collect();
    if parts.len() == 3 {
        let h: u32 = parts[0].parse().unwrap_or(0);
        let m: u32 = parts[1].parse().unwrap_or(0);
        let sec_parts: Vec<&str> = parts[2].split('.').collect();
        let s: u32 = sec_parts.first().and_then(|v| v.parse().ok()).unwrap_or(0);
        let cs = sec_parts.get(1).copied().unwrap_or("00");
        let ms = if cs.len() == 2 {
            format!("{cs}0")
        } else if cs.len() == 1 {
            format!("{cs}00")
        } else {
            cs.chars().take(3).collect()
        };
        return format!("{h:02}:{m:02}:{s:02}.{ms}");
    }
    ts.to_string()
}

fn strip_ass_tags(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut in_braces = false;
    for ch in input.chars() {
        match ch {
            '{' => in_braces = true,
            '}' => in_braces = false,
            _ if !in_braces => out.push(ch),
            _ => {}
        }
    }
    out
}

const B64URL_ALPHABET: &[u8; 64] =
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

pub fn base64url_encode(input: &[u8]) -> String {
    let mut out = String::with_capacity((input.len() * 4 + 2) / 3);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let triple = (b0 << 16) | (b1 << 8) | b2;

        out.push(B64URL_ALPHABET[((triple >> 18) & 0x3F) as usize] as char);
        out.push(B64URL_ALPHABET[((triple >> 12) & 0x3F) as usize] as char);
        if chunk.len() > 1 {
            out.push(B64URL_ALPHABET[((triple >> 6) & 0x3F) as usize] as char);
        }
        if chunk.len() > 2 {
            out.push(B64URL_ALPHABET[(triple & 0x3F) as usize] as char);
        }
    }
    out
}

pub fn base64url_decode_str(input: &str) -> Option<String> {
    let bytes = base64url_decode(input)?;
    String::from_utf8(bytes).ok()
}

pub fn base64url_decode(input: &str) -> Option<Vec<u8>> {
    let clean = input.trim_end_matches('=');
    let mut out = Vec::with_capacity(clean.len() * 3 / 4);
    let mut buf: u32 = 0;
    let mut bits: u8 = 0;

    for b in clean.bytes() {
        let val = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'-' | b'+' => 62,
            b'_' | b'/' => 63,
            _ => return None,
        } as u32;

        buf = (buf << 6) | val;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(((buf >> bits) & 0xFF) as u8);
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_srt_to_vtt_conversion() {
        let srt = "\u{feff}1\r\n00:00:01,500 --> 00:00:04,200\r\nHello, world!\r\n\r\n2\r\n00:01:02,050 --> 00:01:05,999\r\nSecond line\r\n";
        let vtt = srt_to_vtt(srt);
        assert!(vtt.starts_with("WEBVTT\n\n"));
        assert!(vtt.contains("00:00:01.500 --> 00:00:04.200"));
        assert!(vtt.contains("Hello, world!"));
        assert!(vtt.contains("00:01:02.050 --> 00:01:05.999"));
    }

    #[test]
    fn test_ass_to_vtt_conversion() {
        let ass = "[Script Info]\nTitle: Test\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:10.50,0:00:13.25,Default,,0,0,0,,{\\an8\\pos(640,50)}Hello\\NWorld!\n";
        let vtt = ass_to_vtt(ass);
        assert!(vtt.starts_with("WEBVTT\n\n"));
        assert!(vtt.contains("00:00:10.500 --> 00:00:13.250\nHello\nWorld!"));
        assert!(!vtt.contains("\\an8"));
    }

    #[test]
    fn test_m3u8_url_rewriting() {
        let m3u8 = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"keys/enc.key\"\n#EXTINF:6.0,\nseg_001.ts\nhttps://cdn.example.com/hls/seg_002.ts?q=1\n";
        let rewritten = rewrite_m3u8_playlist(
            m3u8,
            "https://origin.example.com/videos/123/master.m3u8",
            45678,
            "stream-1",
            "tok-abc",
        );

        assert!(rewritten.contains("http://127.0.0.1:45678/proxy-asset?u="));
        assert!(rewritten.contains("&ref=stream-1&t=tok-abc"));

        let lines: Vec<&str> = rewritten.lines().collect();
        let key_line = lines[1];
        assert!(key_line.starts_with("#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1:45678/proxy-asset?u="));
        let u_start = key_line.find("u=").unwrap() + 2;
        let u_end = key_line[u_start..].find('&').unwrap() + u_start;
        let decoded_key = base64url_decode_str(&key_line[u_start..u_end]).unwrap();
        assert_eq!(decoded_key, "https://origin.example.com/videos/123/keys/enc.key");

        let seg1_line = lines[3];
        let u_start1 = seg1_line.find("u=").unwrap() + 2;
        let u_end1 = seg1_line[u_start1..].find('&').unwrap() + u_start1;
        let decoded_seg1 = base64url_decode_str(&seg1_line[u_start1..u_end1]).unwrap();
        assert_eq!(decoded_seg1, "https://origin.example.com/videos/123/seg_001.ts");

        let seg2_line = lines[4];
        let u_start2 = seg2_line.find("u=").unwrap() + 2;
        let u_end2 = seg2_line[u_start2..].find('&').unwrap() + u_start2;
        let decoded_seg2 = base64url_decode_str(&seg2_line[u_start2..u_end2]).unwrap();
        assert_eq!(decoded_seg2, "https://cdn.example.com/hls/seg_002.ts?q=1");
    }

    #[test]
    fn test_base64url_roundtrip() {
        let s = "https://example.com/path?a=1&b=hello%20world";
        let enc = base64url_encode(s.as_bytes());
        assert_eq!(base64url_decode_str(&enc).as_deref(), Some(s));
    }
}
