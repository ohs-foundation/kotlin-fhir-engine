#!/usr/bin/env python3
"""Writes docs/ignored-tests.md: every @Ignore'd test in the KMP engine and why.

The reason is the comment above @Ignore, or the string or constant passed to it.

Usage: scripts/ignored_tests.py
"""

import glob
import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "engine", "src")
OUT = os.path.join(REPO, "docs", "ignored-tests.md")

# Test folder to where its tests run.
RUNS_ON = {
    "commonTest": "every platform",
    "desktopTest": "desktop",
    "androidDeviceTest": "Android",
    "iosTest": "iOS",
    "webTest": "JS and wasm",
}


def strings(text):
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', text))


def reason(lines, at, text, source):
    """The reason for the @Ignore that starts at lines[at], whose full text is [text]."""
    args = text[text.index("(") + 1 : text.rindex(")")].strip() if "(" in text else ""
    if args.startswith('"'):
        return strings(args)
    if args:
        constant = re.search(rf"const val {args}\s*=\s*((?:\s*\"(?:[^\"\\]|\\.)*\"\s*\+?)+)", source)
        return strings(constant.group(1)) if constant else args
    comment = []
    i = at - 1
    while i >= 0 and lines[i].strip().startswith("//"):
        comment.insert(0, lines[i].strip()[2:].strip())
        i -= 1
    return " ".join(comment) or "No reason given."


def ignored_tests():
    found = []
    for path in sorted(glob.glob(os.path.join(SRC, "*", "kotlin", "**", "*.kt"), recursive=True)):
        folder = os.path.relpath(path, SRC).split(os.sep)[0]
        if folder not in RUNS_ON:
            continue
        source = open(path, encoding="utf-8").read()
        lines = source.split("\n")
        for at, line in enumerate(lines):
            if not re.match(r"\s*@Ignore\b", line):
                continue
            # The annotation may span lines, as in @Ignore(\n "reason",\n).
            end, text = at, line
            while text.count("(") > text.count(")"):
                end += 1
                text += lines[end]
            name = next(
                m.group(1)
                for l in lines[end + 1 :]
                if (m := re.search(r"fun\s+(`[^`]+`|\w+)\s*\(", l))
            )
            found.append(
                (
                    os.path.splitext(os.path.basename(path))[0],
                    name.strip("`"),
                    RUNS_ON[folder],
                    reason(lines, at, text, source),
                )
            )
    return found


def main():
    tests = ignored_tests()
    out = [
        "# Ignored tests",
        "",
        "Every test the KMP engine skips with `@Ignore`, and why. Each one shows a known bug in the",
        "KMP engine or a library it uses. Fixing the bug and removing `@Ignore` makes the test run",
        "again.",
        "",
        "This page is generated from the test code. After adding or removing an `@Ignore`, run",
        "`python3 scripts/ignored_tests.py`. CI fails when the page is out of date.",
        "",
        f"{len(tests)} ignored tests.",
        "",
        "| Test | Runs on | Why |",
        "|---|---|---|",
    ]
    for cls, name, runs_on, why in tests:
        why = (why if why.endswith(".") else why + ".").replace("|", "\\|")
        out.append(f"| `{cls}.{name}` | {runs_on} | {why} |")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print(f"{len(tests)} ignored tests written to {os.path.relpath(OUT, REPO)}")


if __name__ == "__main__":
    main()
