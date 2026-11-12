# Benchmarks

See [docs/benchmarking.md](../docs/benchmarking.md) for how to run these.

- `core/`: the workload catalogue and the in-process runner. Every workload is defined here and nowhere else.
- `app/`: a driver app started by intents, so Android measures the engine inside a real app rather than a library test.
- `macro/`: the macrobenchmark that drives the app and reads its trace sections.

Nothing here runs in CI, and nothing here is published.
