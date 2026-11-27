use async_trait::async_trait;
use atris_core::{
    Episode, HomeSection, MediaCard, MediaDetails, MediaType, PagedResult, ProviderManifest,
    StreamResult,
};
use atris_storage::StorageError;

#[derive(Debug, thiserror::Error)]
pub enum SourceError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("storage error: {0}")]
    Storage(#[from] StorageError),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("cipher error: {0}")]
    Cipher(String),
    #[error("plugin error: {0}")]
    Plugin(String),
    #[error("invalid argument: {0}")]
    InvalidArgument(String),
    #[error("source not found: {0}")]
    NotFound(String),
}

#[async_trait]
pub trait MediaProvider: Send + Sync {
    fn manifest(&self) -> &ProviderManifest;

    async fn get_home(&self) -> Result<Vec<HomeSection>, SourceError>;

    async fn browse(
        &self,
        media_type: MediaType,
        page: u32,
    ) -> Result<PagedResult<MediaCard>, SourceError>;

    async fn search(
        &self,
        query: &str,
        page: u32,
    ) -> Result<PagedResult<MediaCard>, SourceError>;

    async fn get_details(
        &self,
        id: &str,
        media_type: MediaType,
    ) -> Result<MediaDetails, SourceError>;

    async fn get_episodes(&self, id: &str, season: i32) -> Result<Vec<Episode>, SourceError>;

    async fn get_streams(
        &self,
        media_id: &str,
        media_type: MediaType,
        season: Option<i32>,
        episode: Option<i32>,
    ) -> Result<StreamResult, SourceError>;
}
