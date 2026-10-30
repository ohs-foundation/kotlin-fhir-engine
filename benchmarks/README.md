# Benchmarks

See [docs/benchmarking.md](../docs/benchmarking.md) for how to run these.

- `core/` — the workload catalogue and in-process runner. Workloads live here and nowhere else.
- `app/` — intent-driven driver app, so Android is measured as a real app, not a library test.
- `macro/` — macrobenchmark driving the app and reading its trace sections.

Nothing here runs in CI, and nothing here is published.
