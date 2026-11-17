// SQLite-WASM with OPFS requires cross-origin isolation (SharedArrayBuffer). Set the COOP/COEP
// headers on the webpack dev server so the SQLite Web Worker can use the Origin Private File System.
(function (config) {
  config.devServer = config.devServer || {};
  config.devServer.headers = [
    { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
    { key: "Cross-Origin-Embedder-Policy", value: "require-corp" },
  ];
})(config);
