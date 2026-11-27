use atris_core::{DiscoveredDevice, PairedDevice, SyncPayload, SyncSummary};
use atris_storage::AtrisStorage;
use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashSet,
    net::IpAddr,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use uuid::Uuid;

const SERVICE_TYPE: &str = "_atrisstream._tcp.local.";

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn build_http_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| format!("Failed to create HTTP client: {e}"))
}

pub async fn discover_tv_devices(timeout_ms: u64) -> Vec<DiscoveredDevice> {
    let clamped_ms = timeout_ms.clamp(300, 10_000);
    tokio::task::spawn_blocking(move || discover_tv_devices_blocking(clamped_ms))
        .await
        .unwrap_or_default()
}

fn discover_tv_devices_blocking(timeout_ms: u64) -> Vec<DiscoveredDevice> {
    let Ok(daemon) = ServiceDaemon::new() else {
        return Vec::new();
    };
    let Ok(rx) = daemon.browse(SERVICE_TYPE) else {
        let _ = daemon.shutdown();
        return Vec::new();
    };

    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut seen: HashSet<(String, u16)> = HashSet::new();
    let mut devices = Vec::new();

    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        let wait = remaining.min(Duration::from_millis(150));
        match rx.recv_timeout(wait) {
            Ok(ServiceEvent::ServiceResolved(info)) => {
                let port = info.get_port();
                let host = info
                    .get_addresses()
                    .iter()
                    .find_map(|addr| match addr {
                        IpAddr::V4(v4) if !v4.is_loopback() => Some(v4.to_string()),
                        _ => None,
                    })
                    .or_else(|| {
                        info.get_addresses()
                            .iter()
                            .find_map(|addr| match addr {
                                IpAddr::V4(v4) => Some(v4.to_string()),
                                _ => None,
                            })
                    })
                    .unwrap_or_else(|| info.get_hostname().trim_end_matches('.').to_string());

                if host.is_empty() || port == 0 {
                    continue;
                }

                if seen.insert((host.clone(), port)) {
                    let raw_name = info
                        .get_fullname()
                        .split('.')
                        .next()
                        .unwrap_or("AtrisStream TV")
                        .trim();
                    let name = info
                        .get_property_val_str("deviceName")
                        .filter(|s| !s.is_empty())
                        .unwrap_or(raw_name)
                        .to_string();
                    let version = info
                        .get_property_val_str("version")
                        .unwrap_or("0.1.0")
                        .to_string();

                    devices.push(DiscoveredDevice {
                        name,
                        host,
                        port,
                        version,
                    });
                }
            }
            Ok(_) => {}
            Err(flume_err) => {
                let _ = flume_err;
                // recv_timeout elapsed; continue until deadline
            }
        }
    }

    let _ = daemon.stop_browse(SERVICE_TYPE);
    let _ = daemon.shutdown();
    devices
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PairRequest<'a> {
    pin: &'a str,
    device_name: &'a str,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PairResponse {
    token: String,
    #[serde(default)]
    device_name: Option<String>,
}

#[derive(Deserialize)]
struct ApiErrorResponse {
    error: Option<String>,
}

pub async fn pair_tv_device(
    host: &str,
    port: u16,
    pin: &str,
    desktop_name: &str,
) -> Result<PairedDevice, String> {
    let client = build_http_client()?;
    let url = format!("http://{host}:{port}/api/v1/pair");
    let body = PairRequest {
        pin: pin.trim(),
        device_name: desktop_name,
    };

    let resp = client
        .post(&url)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("Failed to reach TV at {host}:{port}: {e}"))?;

    let status = resp.status();
    if !status.is_success() {
        let msg = resp
            .json::<ApiErrorResponse>()
            .await
            .ok()
            .and_then(|e| e.error)
            .unwrap_or_else(|| format!("Pairing failed with HTTP {status}"));
        return Err(msg);
    }

    let parsed: PairResponse = resp
        .json()
        .await
        .map_err(|e| format!("Invalid pairing response from TV: {e}"))?;

    Ok(PairedDevice {
        id: Uuid::new_v4().to_string(),
        name: parsed
            .device_name
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| format!("Android TV ({host})")),
        host: host.to_string(),
        port,
        token: parsed.token,
        last_synced_at: None,
    })
}

pub async fn sync_with_device(
    storage: &AtrisStorage,
    device: &PairedDevice,
) -> Result<SyncSummary, String> {
    let local_payload = storage
        .export_sync_payload()
        .map_err(|e| format!("Failed to export local sync payload: {e}"))?;

    let client = build_http_client()?;
    let url = format!("http://{}:{}/api/v1/sync", device.host, device.port);

    let resp = client
        .post(&url)
        .header("X-Atris-Token", &device.token)
        .json(&local_payload)
        .send()
        .await
        .map_err(|e| format!("Failed to sync with TV: {e}"))?;

    let status = resp.status();
    if !status.is_success() {
        let msg = resp
            .json::<ApiErrorResponse>()
            .await
            .ok()
            .and_then(|e| e.error)
            .unwrap_or_else(|| format!("Sync failed with HTTP {status}"));
        return Err(msg);
    }

    let remote_payload: SyncPayload = resp
        .json()
        .await
        .map_err(|e| format!("Invalid sync payload from TV: {e}"))?;

    let summary = storage
        .merge_sync_payload(&remote_payload)
        .map_err(|e| format!("Failed to merge TV sync payload: {e}"))?;

    let mut updated_device = device.clone();
    updated_device.last_synced_at = Some(now_millis());
    storage
        .upsert_paired_device(&updated_device)
        .map_err(|e| format!("Failed to update paired device timestamp: {e}"))?;

    Ok(summary)
}

#[derive(Serialize)]
struct AddUrlRequest<'a> {
    url: &'a str,
}

pub async fn send_source_url_to_tv(host: &str, port: u16, url: &str) -> Result<String, String> {
    let client = build_http_client()?;
    let endpoint = format!("http://{host}:{port}/add");
    let resp = client
        .post(&endpoint)
        .json(&AddUrlRequest { url: url.trim() })
        .send()
        .await
        .map_err(|e| format!("Failed to send URL to TV: {e}"))?;

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(if text.trim().is_empty() {
            format!("TV returned HTTP {status}")
        } else {
            text
        });
    }

    Ok(if text.trim().is_empty() {
        "OK".to_string()
    } else {
        text
    })
}
