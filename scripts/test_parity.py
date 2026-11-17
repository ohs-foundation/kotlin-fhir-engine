#!/usr/bin/env python3
"""Checks that every android-fhir engine test is mapped to a KMP engine test, or has a reason.

Reads docs/test-parity.csv, adds rows for android-fhir tests that have a KMP test with the same
name, and fails when an android-fhir test has no row, or a row points at a KMP test that no longer
exists.

Usage: scripts/test_parity.py [path to the android-fhir checkout]
"""

import csv
import glob
import os
import re
import sys
from collections import Counter

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(REPO, "docs", "test-parity.csv")
FIELDS = ["android_file", "android_test", "status", "kmp_file", "kmp_test", "note"]
STATUSES = ["ported", "ignored", "partial", "not-applicable", "missing"]
FUN = re.compile(r"\bfun\s+(`[^`]+`|\w+)\s*\(")
ANNOTATION = re.compile(r"^\s*@(\w+)")


def open_parens(line):
    """Parentheses opened minus closed on [line], ignoring any inside string literals."""
    depth, in_string, i = 0, False, 0
    while i < len(line):
        c = line[i]
        if in_string:
            if c == "\\":
                i += 1
            elif c == '"':
                in_string = False
        elif c == '"':
            in_string = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    return depth


def tests(root):
    """Returns (path relative to root, test name, is ignored) for every @Test under root.

    Reads the annotations above each function, which may span several lines and hold parentheses in
    their arguments, and treats the function as a test when one of them is @Test.
    """
    found = []
    for path in sorted(glob.glob(os.path.join(root, "**", "*.kt"), recursive=True)):
        annotations, depth = set(), 0
        for line in open(path, encoding="utf-8"):
            if depth > 0:
                depth += open_parens(line)
                continue
            if ANNOTATION.match(line):
                annotations.update(re.findall(r"@(\w+)", line.split("fun ")[0]))
                depth = open_parens(line)
                if depth > 0 or not FUN.search(line):
                    continue
            if match := FUN.search(line):
                if "Test" in annotations:
                    name = match.group(1).strip("`")
                    found.append((os.path.relpath(path, root), name, "Ignore" in annotations))
                annotations = set()
                continue
            stripped = line.strip()
            # Comments may sit between annotations, but any other line ends them.
            if stripped and not stripped.startswith(("//", "/*", "*")):
                annotations = set()
    return found


def norm(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


def main():
    android_root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(REPO), "android-fhir"
    )
    android = []
    for source_set in ["test", "androidTest"]:
        root = os.path.join(android_root, "engine", "src", source_set)
        android += [(os.path.join(source_set, f), n) for f, n, _ in tests(root)]
    if not android:
        sys.exit(f"No android-fhir tests found under {android_root}")

    kmp_root = os.path.join(REPO, "engine", "src")
    kmp = tests(kmp_root)
    kmp_by_name = {}
    for f, n, ignored in kmp:
        kmp_by_name.setdefault(norm(n), []).append((f, n, ignored))

    def find(name, kmp_file):
        """The KMP tests a row's name refers to: the one in the row's own file, or else the only
        test anywhere with that name. Several tests can share a name in different files."""
        matches = kmp_by_name.get(norm(name), [])
        in_file = [m for m in matches if m[0] == kmp_file]
        return in_file or matches

    rows = {}
    if os.path.exists(CSV_PATH):
        for row in csv.DictReader(open(CSV_PATH, encoding="utf-8")):
            rows[(row["android_file"], row["android_test"])] = row

    problems = []
    for f, n in android:
        row = rows.get((f, n))
        if row is None:
            # Only a test of the same name in a file of the same name is taken as its port.
            matches = kmp_by_name.get(norm(n), [])
            same_file = [m for m in matches if os.path.basename(m[0]) == os.path.basename(f)]
            if len(same_file) != 1:
                problems.append(f"no row: {f} :: {n}")
                continue
            kf, kn, _ = same_file[0]
            row = rows[(f, n)] = dict(
                android_file=f, android_test=n, status="ported", kmp_file=kf, kmp_test=kn, note=""
            )
        if row["status"] not in STATUSES:
            problems.append(f"unknown status {row['status']!r}: {f} :: {n}")
        names = [x.strip() for x in row["kmp_test"].split(";") if x.strip()]
        found = []
        for name in names:
            matches = find(name, row["kmp_file"])
            if not matches:
                problems.append(f"KMP test not found {name!r}: {f} :: {n}")
            elif len(matches) > 1:
                problems.append(f"KMP test {name!r} is in several files, set kmp_file: {f} :: {n}")
            else:
                found.append(matches[0])
        # Keep ported and ignored in step with the code, so un-ignoring a test updates the table.
        if row["status"] in ("ported", "ignored") and found:
            row["status"] = "ignored" if any(i for _, _, i in found) else "ported"

    android_keys = {(f, n) for f, n in android}
    for key in list(rows):
        if key not in android_keys:
            problems.append(f"row for a test android-fhir no longer has: {key[0]} :: {key[1]}")
            del rows[key]

    with open(CSV_PATH, "w", encoding="utf-8", newline="") as out:
        writer = csv.DictWriter(out, FIELDS, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        for f, n in android:
            if (f, n) in rows:
                writer.writerow(rows[(f, n)])

    counts = Counter(rows[k]["status"] for k in rows if k in android_keys)
    print(f"{len(android)} android-fhir tests")
    for status in STATUSES:
        print(f"  {counts[status]:4} {status}")
    for problem in problems:
        print(problem)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
