use crate::provider::SourceError;
use reqwest::header::{HeaderMap, HeaderName, HeaderValue, CONTENT_TYPE, USER_AGENT};
use std::collections::HashMap;
use std::time::Duration;

pub const DEFAULT_USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

#[derive(Clone)]
pub struct HttpClient {
    client: reqwest::Client,
}

impl HttpClient {
    pub fn new() -> Self {
        let client = reqwest::Client::builder()
            .cookie_store(true)
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(30))
            .user_agent(DEFAULT_USER_AGENT)
            .build()
            .expect("failed to build reqwest client");
        Self { client }
    }

    pub async fn get_text(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> Result<String, SourceError> {
        let req = self.client.get(url).headers(build_header_map(headers));
        let resp = req.send().await?;
        let status = resp.status();
        if !status.is_success() {
            return Err(SourceError::InvalidArgument(format!(
                "HTTP {} for {}",
                status.as_u16(),
                url
            )));
        }
        Ok(resp.text().await?)
    }

    pub async fn post_text(
        &self,
        url: &str,
        body: &str,
        content_type: &str,
        headers: &HashMap<String, String>,
    ) -> Result<String, SourceError> {
        let mut hdrs = build_header_map(headers);
        if let Ok(ct) = HeaderValue::from_str(content_type) {
            hdrs.insert(CONTENT_TYPE, ct);
        }
        let resp = self
            .client
            .post(url)
            .headers(hdrs)
            .body(body.to_string())
            .send()
            .await?;
        let status = resp.status();
        if !status.is_success() {
            return Err(SourceError::InvalidArgument(format!(
                "HTTP {} for {}",
                status.as_u16(),
                url
            )));
        }
        Ok(resp.text().await?)
    }
}

impl Default for HttpClient {
    fn default() -> Self {
        Self::new()
    }
}

fn build_header_map(headers: &HashMap<String, String>) -> HeaderMap {
    let mut map = HeaderMap::new();
    for (k, v) in headers {
        if let (Ok(name), Ok(val)) = (
            HeaderName::from_bytes(k.as_bytes()),
            HeaderValue::from_str(v),
        ) {
            map.insert(name, val);
        }
    }
    if !map.contains_key(USER_AGENT) {
        if let Ok(ua) = HeaderValue::from_str(DEFAULT_USER_AGENT) {
            map.insert(USER_AGENT, ua);
        }
    }
    map
}
