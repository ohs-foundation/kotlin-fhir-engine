// SQLite-WASM with OPFS requires cross-origin isolation for `sqlite3.oo1.OpfsDb`.
// Mirrors engine-app/webpack.config.d/coop-coep.js.
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
