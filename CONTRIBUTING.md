# Contributing to AtrisStream

Thank you for your interest in contributing to **AtrisStream**!

## Code Contributions

1. Fork the repository and create a feature branch from `main`.
2. Follow Kotlin official coding conventions (`kotlin.code.style=official`).
3. Keep modules decoupled:
   - `:core:model` must remain pure Kotlin/Android-minimal with shared data contracts.
   - `:core:network` handles HTTP calls and headers.
   - `:core:database` handles Room entities/DAOs and DataStore preferences.
   - `:core:source-engine` implements `MediaProvider` drivers (`BUILTIN`, `JSON_MANIFEST`, `JS_PLUGIN`).
   - `:feature:player` handles Media3 ExoPlayer and playback UI.
   - `:app` wires everything via `AppContainer` and hosts TV screens.
4. Ensure `./gradlew test` and `./gradlew assembleDebug` pass before opening a pull request.

## Creating Source Plugins (Resources / Kaynaklar)

AtrisStream supports two external source formats:

1. **Declarative JSON Manifest** (`JSON_MANIFEST`):
   - Best for standard HTML SSR sites or simple JSON APIs.
   - See [`docs/source-templates/sample-json-source.json`](docs/source-templates/sample-json-source.json).
2. **QuickJS Sandbox Plugin** (`JS_PLUGIN`):
   - Best for sites requiring custom request signing, multi-step embed extraction, or dynamic decoding.
   - Must export `manifest`, `getHome()`, `browse(type, page)`, `search(query, page)`, `getDetails(id, type)`, `getEpisodes(id, season)`, and `getStreams(mediaId, type, season, episode)`.
   - See [`docs/source-templates/sample-js-plugin.js`](docs/source-templates/sample-js-plugin.js).
3. **Source Repository Index**:
   - Group multiple sources into a single `repo.json` file for one-click import.
   - See [`docs/source-templates/sample-repo.json`](docs/source-templates/sample-repo.json).
