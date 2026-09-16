#!/usr/bin/env python3
"""Render kotlinx-benchmark JSON reports as a Markdown table for a pull request comment.

The report is JMH's own schema: one entry per benchmark-and-parameter combination, each carrying a
score, an error at 99.9% confidence, and the parameter values that produced it.

Two modes.

With only a head report, a table of score and error per benchmark. Every row prints its error
alongside its score, because these runs happen on shared GitHub runners whose machine class varies
between jobs, and a score without its error invites a comparison the numbers cannot support.

With a base report as well, a table of the *difference*. CI produces the two on the same runner in
the same job, minutes apart, so unlike two scores from two jobs they can be compared. A row is
flagged when the difference exceeds its own confidence interval and is at least MIN_RELATIVE_CHANGE
— both conditions, so a tight-but-tiny shift and a large-but-noisy one are each left unflagged.

An unflagged row is only "unchanged" if a change of MIN_RELATIVE_CHANGE *would* have been flagged.
When the two errors together are wider than that, the row is marked as too noisy to tell, with the
smallest change it could have caught, rather than left blank to read as a clean bill of health. See
"Reading these plans honestly" in docs/benchmarking.md.

Usage: benchmark_table.py <head-dir-or-file> [--base <dir-or-file>] [--marker <html-comment>]
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import sys

# Lets the commenting workflow find its own previous comment and update it in place rather than
# adding another one to every push.
DEFAULT_MARKER = "<!-- micro-benchmarks -->"

# A change smaller than this is not flagged even when statistically distinct. Half a percent on a
# tight benchmark is real and uninteresting; the flag is for changes someone should look at.
MIN_RELATIVE_CHANGE = 0.05

Key = tuple[str, tuple[tuple[str, str], ...]]


def find_reports(target: pathlib.Path) -> list[pathlib.Path]:
    """The newest run of each benchmark configuration, not every run of every one.

    kotlinx-benchmark writes each run to `<configuration>/<timestamp>/` and never prunes, so a
    directory that has been run against more than once holds several. Merging them would print the
    same benchmark many times with different scores, which looks like flakiness rather than like
    history. The newest is chosen *per configuration*, because the pull-request tier is split across
    two of them and keeping only the single newest directory would silently drop one half.
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


def key_of(entry: dict) -> Key:
    params = entry.get("params", {}) or {}
    return entry.get("benchmark", "?"), tuple(sorted((k, str(v)) for k, v in params.items()))


def short_name(fully_qualified: str) -> tuple[str, str]:
    """Splits `a.b.ClassBenchmark.method` into its class and method."""
    parts = fully_qualified.split(".")
    if len(parts) < 2:
        return "", fully_qualified
    return parts[-2], parts[-1]


def format_params(params) -> str:
    items = params.items() if isinstance(params, dict) else params
    return ", ".join(f"`{key}={value}`" for key, value in sorted(items))


def format_number(value: float) -> str:
    if value >= 1000:
        return f"{value:,.0f}"
    if value >= 10:
        return f"{value:.1f}"
    return f"{value:.3f}"


def format_measurement(entry: dict) -> str:
    metric = entry.get("primaryMetric", {})
    score, error = metric.get("score", 0.0), metric.get("scoreError", 0.0)
    return f"{format_number(score)} ± {format_number(error)}"


def header(marker: str, title_suffix: str = "") -> list[str]:
    return [marker, f"### Micro benchmarks{title_suffix}", ""]


def render_single(entries: list[dict], marker: str) -> str:
    if not entries:
        return "\n".join(
            header(marker)
            + [
                "The run produced no benchmark entries. That usually means every benchmark failed "
                "its `@Setup` assertions rather than that none exist — check the job log."
            ]
        )

    rows = []
    for entry in entries:
        metric = entry.get("primaryMetric", {})
        class_name, method = short_name(entry.get("benchmark", "?"))
        rows.append(
            (
                class_name,
                method,
                format_params(entry.get("params", {})),
                format_number(metric.get("score", 0.0)),
                format_number(metric.get("scoreError", 0.0)),
                metric.get("scoreUnit", ""),
            )
        )
    rows.sort()

    lines = header(marker) + [
        f"{len(rows)} benchmarks, lower is better. `±` is the 99.9% confidence interval.",
        "",
        "> Indicative only. These run on shared GitHub runners whose hardware varies between jobs,",
        "> so differences between runs are not evidence of a regression unless they are much larger",
        "> than the error column. This job exists to run the benchmarks' own assertions; the numbers",
        "> are a by-product.",
        "",
        "| Class | Benchmark | Params | Score | ± Error | Units |",
        "| --- | --- | --- | ---: | ---: | --- |",
    ]
    for class_name, method, params, score, error, unit in rows:
        lines.append(f"| {class_name} | `{method}` | {params} | {score} | {error} | {unit} |")
    return "\n".join(lines)


def classify(base: dict, head: dict) -> tuple[str, float, float]:
    """A verdict, the relative change of head against base, and the smallest detectable change.

    The question is whether the *difference* between the two scores is distinguishable from zero,
    not whether their two intervals happen to overlap. For independent measurements the variance of
    a difference is the sum of the variances, so its interval is the two errors combined in
    quadrature. Both sides run the same iteration count and so share a t-quantile, which makes that
    exact rather than approximate. Requiring the intervals not to overlap — adding the errors
    linearly — is the common shortcut, and it is conservative by up to a factor of the square root
    of two: on this suite it left six more benchmarks unable to see a 5% change.
    """
    base_metric, head_metric = base["primaryMetric"], head["primaryMetric"]
    base_score, head_score = base_metric["score"], head_metric["score"]
    if base_score <= 0:
        return "unchanged", 0.0, 0.0
    relative = (head_score - base_score) / base_score
    combined_error = math.hypot(base_metric.get("scoreError", 0.0), head_metric.get("scoreError", 0.0))
    detectable = combined_error / base_score
    separated = abs(head_score - base_score) > combined_error
    if separated and abs(relative) >= MIN_RELATIVE_CHANGE:
        return ("slower" if relative > 0 else "faster"), relative, detectable
    if detectable > MIN_RELATIVE_CHANGE:
        return "unclear", relative, detectable
    return "unchanged", relative, detectable


def render_diff(base_entries: list[dict], head_entries: list[dict], marker: str) -> str:
    base_by_key = {key_of(entry): entry for entry in base_entries}
    head_by_key = {key_of(entry): entry for entry in head_entries}

    rows = []
    counts = {"slower": 0, "faster": 0, "unclear": 0, "unchanged": 0, "new": 0}
    for key, head in sorted(head_by_key.items()):
        class_name, method = short_name(key[0])
        params = format_params(key[1])
        unit = head.get("primaryMetric", {}).get("scoreUnit", "")
        base = base_by_key.get(key)
        if base is None:
            counts["new"] += 1
            rows.append((class_name, method, params, "—", format_measurement(head), "new", "", unit))
            continue
        verdict, relative, detectable = classify(base, head)
        counts[verdict] += 1
        flag = {
            "slower": "⚠️ slower",
            "faster": "✅ faster",
            "unclear": f"❔ too noisy (needs >{detectable:.0%})",
            "unchanged": "",
        }[verdict]
        rows.append(
            (
                class_name,
                method,
                params,
                format_measurement(base),
                format_measurement(head),
                f"{relative:+.1%}",
                flag,
                unit,
            )
        )
    removed = sorted(set(base_by_key) - set(head_by_key))

    summary = (
        f"**{counts['slower']} slower**, {counts['faster']} faster, "
        f"{counts['unclear']} too noisy to tell, "
        f"{counts['unchanged']} unchanged, {counts['new']} new"
        + (f", {len(removed)} removed" if removed else "")
        + "."
    )
    lines = header(marker, " — base vs. head") + [
        summary,
        "",
        "> Both sides ran on the same runner in the same job, so they are comparable in a way two",
        "> separate runs are not. Each is still a single run: a flag means the difference is",
        f"> outside its 99.9% confidence interval and at least {MIN_RELATIVE_CHANGE:.0%} — a prompt",
        "> to look, not a verdict. \"Too noisy\" means a change that size could not have been",
        "> told apart from noise, so the row says nothing either way. `±` is the 99.9% confidence",
        "> interval; lower is better.",
        "",
        "| Class | Benchmark | Params | Base | Head | Δ | | Units |",
        "| --- | --- | --- | ---: | ---: | ---: | --- | --- |",
    ]
    for class_name, method, params, base, head, delta, flag, unit in rows:
        lines.append(
            f"| {class_name} | `{method}` | {params} | {base} | {head} | {delta} | {flag} | {unit} |"
        )
    if removed:
        lines += ["", "Present in base but not in head:"]
        for key in removed:
            class_name, method = short_name(key[0])
            lines.append(f"- `{class_name}.{method}` {format_params(key[1])}")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("head", type=pathlib.Path)
    parser.add_argument("--base", type=pathlib.Path)
    parser.add_argument("--marker", default=DEFAULT_MARKER)
    arguments = parser.parse_args()

    if not arguments.head.exists():
        print(f"no report at {arguments.head}", file=sys.stderr)
        return 1

    head_entries = load_entries(find_reports(arguments.head))
    base_entries = (
        load_entries(find_reports(arguments.base))
        if arguments.base is not None and arguments.base.exists()
        else []
    )

    # A base that produced nothing — a branch predating the benchmarks, or a failed run — is
    # reported as a plain table rather than as every benchmark having appeared at once.
    if base_entries and head_entries:
        print(render_diff(base_entries, head_entries, arguments.marker))
    else:
        print(render_single(head_entries, arguments.marker))
    return 0


if __name__ == "__main__":
    sys.exit(main())
