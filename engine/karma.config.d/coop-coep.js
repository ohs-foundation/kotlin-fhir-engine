// SQLite-WASM with OPFS requires cross-origin isolation (SharedArrayBuffer); without it
// `sqlite3.oo1.OpfsDb` is not available and every database test fails. Set the COOP/COEP headers on
// the Karma test server, mirroring engine-app/webpack.config.d/coop-coep.js used by the app.
config.set({
  beforeMiddleware: (config.beforeMiddleware || []).concat(["coopCoep"]),
  plugins: (config.plugins || []).concat([
    {
      "middleware:coopCoep": [
        "factory",
        function () {
          return function (request, response, next) {
            response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
            response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
            next();
          };
        },
      ],
    },
  ]),
});
