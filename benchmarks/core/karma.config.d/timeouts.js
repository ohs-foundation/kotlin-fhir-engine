// A benchmark is one test that runs for minutes; Karma and Mocha defaults would end it early.
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
