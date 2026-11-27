use crate::mpv::MpvController;
use atris_proxy::StreamProxyServer;
use atris_sources::SourceManager;
use atris_storage::AtrisStorage;
use std::sync::Arc;

pub struct AppState {
    pub storage: Arc<AtrisStorage>,
    pub sources: Arc<SourceManager>,
    pub proxy: StreamProxyServer,
    pub mpv: Arc<MpvController>,
}
