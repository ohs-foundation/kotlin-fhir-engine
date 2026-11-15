#!/usr/bin/env python3
"""Writes one Markdown table of test results per platform from JUnit XML reports.

Usage: test_summary.py <directory with the downloaded test-reports-* artifacts>

Each artifact is named test-reports-<job>. Inside, every Gradle test task writes its XML files to a
directory named after the task, so a job that runs two tasks gets two rows.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

# Gradle test task (or result directory) to the name shown in the table.
PLATFORMS = {
    "desktopTest": "Desktop",
    "managedDevice": "Android",
    "connected": "Android",
    "iosSimulatorArm64Test": "iOS",
    "iosSimulatorArm64SystemSqliteTest": "iOS (system SQLite)",
    "jsBrowserTest": "JS",
    "wasmJsBrowserTest": "Wasm",
}


def platform_of(path):
    for part in path.split(os.sep):
        if part in PLATFORMS:
            return PLATFORMS[part]
    return None


def main():
    root = sys.argv[1]
    counts = {}
    for path in glob.glob(os.path.join(root, "**", "*.xml"), recursive=True):
        platform = platform_of(os.path.relpath(path, root))
        if platform is None:
            continue
        try:
            suites = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for suite in suites.iter("testsuite"):
            row = counts.setdefault(platform, [0, 0, 0, 0])
            tests = int(suite.get("tests", 0))
            skipped = int(suite.get("skipped", 0))
            failed = int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            row[0] += tests
            row[1] += tests - skipped - failed
            row[2] += skipped
            row[3] += failed

    print("## Test results\n")
    print("| Platform | Run | Passed | Ignored | Failed |")
    print("|---|---:|---:|---:|---:|")
    for platform in dict.fromkeys(PLATFORMS.values()):
        if platform in counts:
            run, passed, ignored, failed = counts[platform]
            mark = " ❌" if failed else ""
            print(f"| {platform}{mark} | {run} | {passed} | {ignored} | {failed} |")
        else:
            print(f"| {platform} | | | | no results |")
    print(
        "\nShared tests (`commonTest`) run on every platform, plus each platform's own tests. "
        "Every ignored test has its reason in a comment above its `@Ignore`."
    )


if __name__ == "__main__":
    main()
