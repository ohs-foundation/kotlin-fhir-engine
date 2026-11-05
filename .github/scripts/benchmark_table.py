#!/usr/bin/env python3
"""Render kotlinx-benchmark JSON reports as Markdown for a pull request comment.

With --base, the comment shows the change from base to head. See docs/benchmarking.md.

Usage: benchmark_table.py <head-dir-or-file> [--base <dir-or-file>] [--marker <html-comment>]
           [--base-sha <sha>] [--head-sha <sha>] [--repository <owner/name>]
           [--changed-files <file>] [--machine <file>]
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import re
import statistics
import sys

# Lets the commenting workflow find and update its previous comment.
DEFAULT_MARKER = "<!-- micro-benchmarks -->"

# A smaller change is not flagged, even when it is statistically distinct.
MIN_RELATIVE_CHANGE = 0.075

# Paths compiled into, or used to build, the desktop benchmark runner. Keyed by the label shown.
BENCHMARKED_PATHS = {
    "commonMain": ("engine/src/commonMain/",),
    "desktopMain": ("engine/src/desktopMain/",),
    "desktopBenchmark": ("engine/src/desktopBenchmark/",),
    "build": ("engine/build.gradle.kts", "gradle/libs.versions.toml", "buildSrc/"),
}

TIME_UNITS = ["ns", "us", "ms", "s"]

# Two-sided 95% t-quantiles by degrees of freedom. A missing df rounds down to the next key.
T_95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306,
        9: 2.262, 10: 2.228, 12: 2.179, 15: 2.131, 20: 2.086, 30: 2.042}

SHA = re.compile(r"^[0-9a-f]{40}$")
# A CI shard's reports, as the merge job lays them out.
SHARD = re.compile(r"^benchmark-shard-\d+$")

Key = tuple[str, tuple[tuple[str, str], ...]]


def find_reports(target: pathlib.Path) -> list[pathlib.Path]:
    """The newest run of each benchmark configuration.

    kotlinx-benchmark writes each run to `<configuration>/<timestamp>/` and never prunes old runs.
    """
    if target.is_file():
        return [target]
    reports = sorted(target.rglob("*.json"))
    newest_by_configuration: dict[pathlib.Path, pathlib.Path] = {}
    for report in reports:
        run, configuration = report.parent, report.parent.parent
        if run > newest_by_configuration.get(configuration, pathlib.Path()):
            newest_by_configuration[configuration] = run
    newest = set(newest_by_configuration.values())
    return [report for report in reports if report.parent in newest]


def load_entries(paths: list[pathlib.Path]) -> list[dict]:
    entries: list[dict] = []
    for path in paths:
        try:
            content = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError) as error:
            print(f"skipping {path}: {error}", file=sys.stderr)
            continue
        if isinstance(content, list):
            entries.extend(content)
    return entries


def load_by_shard(root: pathlib.Path) -> dict[str, list[dict]]:
    """Entries by the CI shard that ran them; one group when the run was not sharded."""
    children = root.iterdir() if root.is_dir() else []
    shards = [child for child in children if child.is_dir() and SHARD.match(child.name)]
    if not shards:
        return {"": load_entries(find_reports(root))}
    return {shard.name: load_entries(find_reports(shard)) for shard in shards}


def same_shard(base: dict[str, list[dict]], head: dict[str, list[dict]]) -> list[dict]:
    """Base entries from the shard that ran each head row, so no row spans two runners."""
    head_keys = {shard: {key_of(entry) for entry in entries} for shard, entries in head.items()}
    every = set().union(*head_keys.values())
    return [
        entry
        for shard, entries in base.items()
        for entry in entries
        if key_of(entry) in head_keys.get(shard, set()) or key_of(entry) not in every
    ]


def key_of(entry: dict) -> Key:
    params = entry.get("params", {}) or {}
    return entry.get("benchmark", "?"), tuple(sorted((k, str(v)) for k, v in params.items()))


def short_name(fully_qualified: str) -> tuple[str, str]:
    """Splits `a.b.ClassBenchmark.method` into its class and method."""
    parts = fully_qualified.split(".")
    if len(parts) < 2:
        return "", fully_qualified
    return parts[-2], parts[-1]


def constant_params(keys: list[Key]) -> dict[str, str]:
    """Parameters that take one value wherever they appear, so the rows need not repeat them."""
    values: dict[str, set[str]] = {}
    for _, params in keys:
        for name, value in params:
            values.setdefault(name, set()).add(value)
    return {name: next(iter(seen)) for name, seen in values.items() if len(seen) == 1}


def format_params(params, omit: dict[str, str]) -> str:
    return ", ".join(f"{name}={value}" for name, value in sorted(params) if name not in omit)


def decimals_for(error: float, fallback: float) -> int:
    """Decimal places that show `error` to two significant figures, or `fallback` to three."""
    reference, figures = (error, 2) if error > 0 else (abs(fallback), 3)
    if reference <= 0:
        return 0
    return max(0, figures - 1 - math.floor(math.log10(reference)))


def display_unit(unit: str, magnitude: float) -> tuple[str, float]:
    """A time unit in which `magnitude` falls in [1, 1000), and the factor to convert to it."""
    name, _, per = unit.partition("/")
    if name not in TIME_UNITS or per != "op":
        return unit, 1.0
    index, factor = TIME_UNITS.index(name), 1.0
    while index + 1 < len(TIME_UNITS) and magnitude * factor >= 1000:
        index, factor = index + 1, factor / 1000
    while index > 0 and 0 < magnitude * factor < 1:
        index, factor = index - 1, factor * 1000
    return TIME_UNITS[index].replace("us", "µs"), factor


def t_95(df: float) -> float:
    eligible = [key for key in T_95 if key <= df]
    return T_95[max(eligible)] if eligible else T_95[1]


def fork_means(entry: dict) -> list[float]:
    raw = entry.get("primaryMetric", {}).get("rawData") or []
    return [statistics.fmean(fork) for fork in raw if fork]


def across_forks(entries: list[dict]) -> bool:
    """Whether every entry has several forks, so intervals can be taken across them."""
    return bool(entries) and all(len(fork_means(entry)) >= 2 for entry in entries)


def measurement(entry: dict, forks: bool) -> tuple[float, float]:
    """The score and its interval: 95% across forks, or JMH's 99.9% over one fork's iterations."""
    means = fork_means(entry)
    if forks:
        n = len(means)
        return statistics.fmean(means), t_95(n - 1) * statistics.stdev(means) / math.sqrt(n)
    metric = entry.get("primaryMetric", {})
    return metric.get("score", 0.0), metric.get("scoreError", 0.0)


def format_measurements(entries: list[dict], unit: str, factor: float, forks: bool) -> list[str]:
    """Each entry's score and error, all to the decimal places the widest error needs."""
    pairs = [measurement(entry, forks) for entry in entries]
    scores = [score * factor for score, _ in pairs]
    errors = [error * factor for _, error in pairs]
    places = decimals_for(max(errors), min(scores))
    return [f"{score:,.{places}f} ± {error:,.{places}f} {unit}" for score, error in zip(scores, errors)]


def format_change(relative: float, interval: float) -> str:
    places = min(1, decimals_for(interval * 100, relative * 100))
    change = f"{relative * 100:+.{places}f}"
    if float(change) == 0:
        change = change[1:]
    return f"{change}% ± {interval * 100:.{places}f}%"


def change_interval(base: dict, head: dict, forks: bool) -> float:
    """The half-width of the interval of head minus base.

    With several forks a side, a pooled 95% t-interval over the fork means, because forks differ
    more than iterations within a fork. Pooled, because both sides run the same benchmark on the same
    machine. Otherwise the two 99.9% errors combined in quadrature; both sides run the same
    iteration count, so they share a t-quantile and this is exact.
    """
    if forks:
        base_means, head_means = fork_means(base), fork_means(head)
        nb, nh = len(base_means), len(head_means)
        df = nb + nh - 2
        pooled = (
            (nb - 1) * statistics.variance(base_means) + (nh - 1) * statistics.variance(head_means)
        ) / df
        return t_95(df) * math.sqrt(pooled * (1 / nb + 1 / nh))
    return math.hypot(
        base["primaryMetric"].get("scoreError", 0.0), head["primaryMetric"].get("scoreError", 0.0)
    )


def interval_note(entries: list[dict], forks: bool) -> str:
    if forks:
        counts = sorted({len(fork_means(entry)) for entry in entries})
        count = f"{counts[0]}" if len(counts) == 1 else f"{counts[0]}–{counts[-1]}"
        return f"`±` is a 95% interval across {count} forks."
    return "`±` is the 99.9% confidence interval of one fork."


def classify(base: dict, head: dict, forks: bool) -> tuple[str, float, float]:
    """A verdict, the relative change of head against base, and the interval of that change."""
    base_score, _ = measurement(base, forks)
    head_score, _ = measurement(head, forks)
    if base_score <= 0:
        return "unchanged", 0.0, 0.0
    relative = (head_score - base_score) / base_score
    combined_error = change_interval(base, head, forks)
    interval = combined_error / base_score
    separated = abs(head_score - base_score) > combined_error
    if separated and abs(relative) >= MIN_RELATIVE_CHANGE:
        return ("slower" if relative > 0 else "faster"), relative, interval
    if interval > MIN_RELATIVE_CHANGE:
        return "unclear", relative, interval
    return "unchanged", relative, interval


def table(rows: list[tuple[str, str, list[str]]], columns: list[str]) -> list[str]:
    """A Markdown table with a heading row per class. Each row is (class, label, cells)."""
    lines = [
        "| Benchmark | " + " | ".join(columns) + " |",
        "| --- | " + " | ".join("---:" for _ in columns) + " |",
    ]
    current_class = None
    for class_name, label, cells in rows:
        if class_name != current_class:
            lines.append(f"| **{class_name}** |" + " |" * len(columns))
            current_class = class_name
        lines.append(f"| {label} | " + " | ".join(cells) + " |")
    return lines


def collapsed(summary: str, body: list[str]) -> list[str]:
    return ["<details>", f"<summary>{summary}</summary>", "", *body, "", "</details>", ""]


def describe_commits(base_sha: str | None, head_sha: str | None, repository: str | None) -> str:
    def link(name: str, sha: str | None) -> str | None:
        if sha is None or not SHA.match(sha):
            return None
        if repository:
            return f"{name} [`{sha[:7]}`](https://github.com/{repository}/commit/{sha})"
        return f"{name} `{sha[:7]}`"

    parts = [part for part in (link("Base", base_sha), link("head", head_sha)) if part]
    return ", ".join(parts) + "." if parts else ""


def count_changes(changed_files: list[str]) -> dict[str, int]:
    return {
        label: sum(1 for path in changed_files if path.startswith(prefixes))
        for label, prefixes in BENCHMARKED_PATHS.items()
    }


def describe_changes(changed_files: list[str] | None) -> str:
    """Says how many changed files the benchmarks run. Never echoes a path, which is untrusted."""
    if changed_files is None:
        return ""
    counts = count_changes(changed_files)
    total = sum(counts.values())
    if total == 0:
        return "No benchmarked code changed; any flag is noise."
    parts = ", ".join(f"{label} {count}" for label, count in counts.items() if count)
    return f"Benchmarked files changed: {parts}."


def machine_block(machine: str | None) -> list[str]:
    if not machine:
        return []
    # The text comes from an artifact; without backticks it cannot close the code block.
    return collapsed("Machine", ["```", machine.replace("`", "").strip(), "```"])


def header(marker: str, title_suffix: str = "") -> list[str]:
    return [marker, f"### Micro benchmarks{title_suffix}", ""]


def render_single(entries: list[dict], marker: str, machine: str | None) -> str:
    if not entries:
        return "\n".join(
            header(marker)
            + [
                "The run produced no benchmark entries. This usually means every benchmark failed "
                "its `@Setup` assertions; check the job log."
            ]
        )

    entries = sorted(entries, key=key_of)
    forks = across_forks(entries)
    omit = constant_params([key_of(entry) for entry in entries])
    rows = []
    for entry in entries:
        name, params = key_of(entry)
        class_name, method = short_name(name)
        label = f"`{method}` {format_params(params, omit)}".rstrip()
        metric = entry.get("primaryMetric", {})
        unit, factor = display_unit(metric.get("scoreUnit", ""), metric.get("score", 0.0))
        rows.append((class_name, label, format_measurements([entry], unit, factor, forks)))

    lines = header(marker) + [
        f"{len(rows)} benchmarks. Lower is better; {interval_note(entries, forks)} "
        "No base to compare against, so these numbers only describe this runner.",
        "",
        *table(rows, ["Score"]),
        "",
    ]
    if omit:
        lines += [f"Every run uses {format_params(omit.items(), {})}.", ""]
    lines += machine_block(machine)
    return "\n".join(lines).rstrip()


def render_diff(
    base_entries: list[dict],
    head_entries: list[dict],
    marker: str,
    commits: str,
    changes: str,
    machine: str | None,
) -> str:
    base_by_key = {key_of(entry): entry for entry in base_entries}
    head_by_key = {key_of(entry): entry for entry in head_entries}
    omit = constant_params(list(base_by_key) + list(head_by_key))
    # One fork on either side, as when the base predates the forks setting, falls back throughout.
    forks = across_forks(base_entries + head_entries)

    groups: dict[str, list] = {"flagged": [], "unclear": [], "unchanged": [], "new": []}
    counts = {"slower": 0, "faster": 0, "unclear": 0, "unchanged": 0, "new": 0}
    for key, head in sorted(head_by_key.items()):
        class_name, method = short_name(key[0])
        label = f"`{method}` {format_params(key[1], omit)}".rstrip()
        base = base_by_key.get(key)
        reference = (base or head).get("primaryMetric", {})
        unit, factor = display_unit(reference.get("scoreUnit", ""), reference.get("score", 0.0))
        if base is None:
            counts["new"] += 1
            groups["new"].append((class_name, label, format_measurements([head], unit, factor, forks)))
            continue
        verdict, relative, interval = classify(base, head, forks)
        counts[verdict] += 1
        change = format_change(relative, interval)
        if verdict in ("slower", "faster"):
            change = ("⚠️ " if verdict == "slower" else "✅ ") + change
        cells = [*format_measurements([base, head], unit, factor, forks), change]
        group = "flagged" if verdict in ("slower", "faster") else verdict
        groups[group].append((class_name, label, cells))
    removed = sorted(set(base_by_key) - set(head_by_key))

    summary = (
        f"**{counts['slower']} slower, {counts['faster']} faster**, "
        f"{counts['unchanged']} unchanged, {counts['unclear']} too noisy to tell"
        + (f", {counts['new']} new" if counts["new"] else "")
        + (f", {len(removed)} removed" if removed else "")
        + "."
    )
    columns = ["Base", "Head", "Change"]
    lines = header(marker, " — base vs. head") + [summary, ""]
    context = " ".join(part for part in (commits, changes) if part)
    if context:
        lines += [context, ""]

    if groups["flagged"]:
        lines += table(groups["flagged"], columns) + [""]
    else:
        lines += ["Nothing flagged.", ""]

    if groups["unchanged"]:
        lines += collapsed(
            f"{counts['unchanged']} unchanged: no change of {MIN_RELATIVE_CHANGE:.1%} or more",
            table(groups["unchanged"], columns),
        )
    if groups["unclear"]:
        lines += collapsed(
            f"{counts['unclear']} too noisy to tell: interval wider than {MIN_RELATIVE_CHANGE:.1%}",
            table(groups["unclear"], columns),
        )
    if groups["new"]:
        lines += collapsed(f"{counts['new']} new", table(groups["new"], ["Head"]))
    if removed:
        lines += ["Present in base but not in head:"]
        for name, params in removed:
            class_name, method = short_name(name)
            lines.append(f"- `{class_name}.{method}` {format_params(params, omit)}".rstrip())
        lines.append("")

    notes = [
        "- Base and head ran in the same job. Lower is better.",
        f"- {interval_note(base_entries + head_entries, forks)}",
        f"- Flagged: the change is outside its interval and at least {MIN_RELATIVE_CHANGE:.1%}.",
        "- Expect the odd false flag.",
    ]
    if omit:
        notes += [f"- Every run uses {format_params(omit.items(), {})}."]
    lines += collapsed("How to read this", notes)
    lines += machine_block(machine)
    return "\n".join(lines).rstrip()


def read_optional(path: pathlib.Path | None) -> str | None:
    if path is None:
        return None
    try:
        return path.read_text()
    except OSError as error:
        print(f"skipping {path}: {error}", file=sys.stderr)
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("head", type=pathlib.Path, nargs="?")
    parser.add_argument("--base", type=pathlib.Path)
    parser.add_argument("--marker", default=DEFAULT_MARKER)
    parser.add_argument("--base-sha")
    parser.add_argument("--head-sha")
    parser.add_argument("--repository", help="owner/name, to link the commits")
    parser.add_argument("--changed-files", type=pathlib.Path, help="one changed path per line")
    parser.add_argument("--machine", type=pathlib.Path, help="a description of the runner")
    parser.add_argument(
        "--count-changes",
        action="store_true",
        help="print how many --changed-files the benchmarks compile, and exit",
    )
    arguments = parser.parse_args()

    changed = read_optional(arguments.changed_files)
    changed_files = changed.splitlines() if changed is not None else None
    benchmarked = sum(count_changes(changed_files).values()) if changed_files is not None else None
    if arguments.count_changes:
        if benchmarked is None:
            parser.error("--count-changes needs --changed-files")
        print(benchmarked)
        return 0

    commits = describe_commits(arguments.base_sha, arguments.head_sha, arguments.repository)
    found = arguments.head is not None and arguments.head.exists()
    head = load_by_shard(arguments.head) if found else {}
    head_entries = [entry for entries in head.values() for entry in entries]
    # CI skips the benchmarks, and reports nothing, when no benchmarked file changed.
    if not head_entries and benchmarked == 0:
        skipped = f"{commits} No benchmarked code changed; skipped.".lstrip()
        print("\n".join(header(arguments.marker) + [skipped]))
        return 0
    if not found:
        print(f"no report at {arguments.head}", file=sys.stderr)
        return 1
    base_entries = (
        same_shard(load_by_shard(arguments.base), head)
        if arguments.base is not None and arguments.base.exists()
        else []
    )
    machine = read_optional(arguments.machine)

    # With no base entries, every row would show as new.
    if base_entries and head_entries:
        print(
            render_diff(
                base_entries,
                head_entries,
                arguments.marker,
                commits,
                describe_changes(changed_files),
                machine,
            )
        )
    else:
        print(render_single(head_entries, arguments.marker, machine))
    return 0


if __name__ == "__main__":
    sys.exit(main())
