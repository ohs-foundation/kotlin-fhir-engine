// A benchmark is one test that runs for minutes, so every Karma default written for short tests has
// to be raised. Mocha's 2s per-test timeout fails the run outright; the activity and ping timeouts
// kill the session from the server side while a workload is still mid-flight.
config.set({
  client: {
    ...(config.client || {}),
    mocha: { ...((config.client || {}).mocha || {}), timeout: 0 },
  },
  browserNoActivityTimeout: 60 * 60 * 1000,
  browserDisconnectTimeout: 60 * 1000,
  browserDisconnectTolerance: 2,
  pingTimeout: 60 * 60 * 1000,
  captureTimeout: 5 * 60 * 1000,
});
