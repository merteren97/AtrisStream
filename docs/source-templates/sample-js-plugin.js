// AtrisStream QuickJS Sandbox Plugin Template
// Global host bridge available: httpGet(url, headersJson), httpPost(url, body, headersJson)

var manifest = {
  id: "example-js-source",
  name: "Example JS Provider",
  version: "1.0.0",
  baseUrl: "https://example.com",
  supportedTypes: ["MOVIE", "TV", "ANIME"],
  languages: ["tr", "en"]
};

function getHome() {
  return JSON.stringify([
    {
      title: "Trending",
      items: [
        {
          id: "sample-movie-1",
          sourceId: manifest.id,
          title: "Sample Movie",
          posterUrl: "https://example.com/poster.jpg",
          type: "MOVIE",
          year: 2025,
          rating: 8.2
        }
      ]
    }
  ]);
}

function browse(type, page) {
  return JSON.stringify({
    items: [],
    page: page,
    hasNextPage: false
  });
}

function search(query, page) {
  return JSON.stringify({
    items: [],
    page: page,
    hasNextPage: false
  });
}

function getDetails(id, type) {
  return JSON.stringify({
    id: id,
    sourceId: manifest.id,
    title: "Sample Movie",
    type: type,
    description: "Sample synopsis.",
    posterUrl: "https://example.com/poster.jpg",
    backdropUrl: null,
    year: 2025,
    rating: 8.2,
    genres: ["Action", "Sci-Fi"],
    seasons: []
  });
}

function getEpisodes(id, season) {
  return JSON.stringify([]);
}

function getStreams(mediaId, type, season, episode) {
  return JSON.stringify({
    streams: [
      {
        url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        quality: "Auto",
        format: "HLS",
        serverName: "Primary HLS",
        audioLang: "en",
        headers: {
          "Referer": "https://example.com/"
        }
      }
    ],
    subtitles: []
  });
}
