// Serves the run config and packaged Synthea data to the page, and writes posted reports to disk.
// basePath is <repo>/build/js/packages/<module>-test.
const fs = require("fs");
const path = require("path");

const moduleBuild = path.resolve(config.basePath, "../../../..", "benchmarks/core/build");
const configFile = path.join(moduleBuild, "benchmark-web-config.json");
const dataDirectory = path.join(moduleBuild, "benchmark-data/synthea");
const reportDirectory = path.join(moduleBuild, "reports/benchmarks");

function serve(response, file, contentType) {
  if (!fs.existsSync(file)) {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, { "Content-Type": contentType });
  // Streamed: a packaged Synthea file can be hundreds of megabytes.
  fs.createReadStream(file).pipe(response);
}

function receive(request, response, directory) {
  const name = path.basename(decodeURIComponent(request.url.split("/").pop()));
  let body = "";
  request.on("data", (chunk) => {
    body += chunk;
  });
  request.on("end", () => {
    fs.mkdirSync(directory, { recursive: true });
    const file = path.join(directory, name);
    fs.writeFileSync(file, body);
    console.log("Benchmark report written to " + file);
    response.writeHead(204);
    response.end();
  });
}

config.set({
  beforeMiddleware: (config.beforeMiddleware || []).concat(["benchmarkServer"]),
  plugins: (config.plugins || []).concat([
    {
      "middleware:benchmarkServer": [
        "factory",
        function () {
          return function (request, response, next) {
            const url = request.url.split("?")[0];
            if (url === "/benchmark-config") {
              serve(response, configFile, "application/json");
            } else if (url.startsWith("/benchmark-data/")) {
              const name = path.basename(decodeURIComponent(url));
              serve(response, path.join(dataDirectory, name), "application/x-ndjson");
            } else if (request.method === "POST" && url.startsWith("/benchmark-report/")) {
              receive(request, response, reportDirectory);
            } else {
              next();
            }
          };
        },
      ],
    },
  ]),
});
