#!/usr/bin/env python3
"""Measure the binary size of each published module and render it as Markdown.

`measure` reads the manifests that binary-size.init.gradle writes and outputs JSON. `render`
compares a head report with an optional base report for a pull request comment.

Usage: binary_size.py measure --root <dir> --output <file> [--skip-android]
       binary_size.py render <head.json> [--base <base.json>] [--marker <html-comment>]
           [--base-sha <sha>] [--head-sha <sha>] [--repository <owner/name>]
"""

from __future__ import annotations

import argparse
import glob
import gzip
import json
import os
import pathlib
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

# Lets the commenting workflow find and update its previous comment.
DEFAULT_MARKER = "<!-- binary-size -->"

ANDROID_MIN_API = "26"
DEX_FIELD_IDS_OFFSET = 0x50
DEX_METHOD_IDS_OFFSET = 0x58
DEX_CLASS_DEFS_OFFSET = 0x60

PLATFORM_DIR = re.compile(r"^(linux|osx|macos|darwin|windows|win)[_-](x64|x86_64|amd64|arm64|aarch64|x86)$")
NATIVE_SUFFIXES = (".so", ".dylib", ".dll", ".jnilib")
DESKTOP_OS = (("linux", "Linux"), ("mac", "macOS"), ("osx", "macOS"), ("darwin", "macOS"),
              ("windows", "Windows"), ("win", "Windows"))

# Files the browser loads for the SQLite Wasm worker, relative to the root build's node_modules.
SQLITE_WORKER_FILES = (
    "sqlite-wasm-worker/worker.js",
    "@sqlite.org/sqlite-wasm/index.mjs",
    "@sqlite.org/sqlite-wasm/sqlite-wasm/jswasm/sqlite3-bundler-friendly.mjs",
    "@sqlite.org/sqlite-wasm/sqlite-wasm/jswasm/sqlite3-worker1-promiser.mjs",
    "@sqlite.org/sqlite-wasm/sqlite-wasm/jswasm/sqlite3-opfs-async-proxy.js",
    "@sqlite.org/sqlite-wasm/sqlite-wasm/jswasm/sqlite3.wasm",
)

# A smaller change is shown as unchanged. Builds are not byte-for-byte reproducible.
NOISE_BYTES = 1024
NOISE_RELATIVE = 0.001

TOP_DEPENDENCIES = 15
SHA = re.compile(r"^[0-9a-f]{40}$")
# The reports come from the pull request, so text from them is shown only if it matches.
SAFE_TEXT = re.compile(r"^[A-Za-z0-9._:+\-]{1,200}$")


def gzip_size(data: bytes) -> int:
    return len(gzip.compress(data, compresslevel=6))


def desktop_os(path: str) -> str:
    """The platform directory, such as linux_x64, or the OS when the path has none."""
    parts = path.lower().split("/")
    if len(parts) >= 2 and PLATFORM_DIR.match(parts[-2]):
        return parts[-2]
    lowered = path.lower()
    for needle, name in DESKTOP_OS:
        if re.search(rf"(^|[/_\-.]){needle}", lowered):
            return name
    return "Other"


def native_libs_in_zip(path: str, prefix: str = "") -> dict[str, int]:
    """Native library bytes by Android ABI (AARs) or desktop OS (JARs)."""
    sizes: dict[str, int] = {}
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            name = info.filename
            if info.is_dir() or not name.endswith(NATIVE_SUFFIXES) or not name.startswith(prefix):
                continue
            key = name.split("/")[1] if prefix == "jni/" else desktop_os(name)
            sizes[key] = sizes.get(key, 0) + info.file_size
    return sizes


def add_sizes(total: dict[str, int], more: dict[str, int]) -> None:
    for key, value in more.items():
        total[key] = total.get(key, 0) + value


def measure_jar(path: str, dependencies: list[dict]) -> dict:
    classes = class_bytes = 0
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.filename.endswith(".class"):
                classes += 1
                class_bytes += info.file_size
    native: dict[str, int] = {}
    for dependency in dependencies:
        if dependency["file"].endswith(".jar") and os.path.isfile(dependency["file"]):
            add_sizes(native, native_libs_in_zip(dependency["file"]))
    return {
        "jar_bytes": os.path.getsize(path),
        "classes": classes,
        "class_bytes": class_bytes,
        "native_libs": dict(sorted(native.items())),
    }


def find_android_sdk() -> tuple[str | None, str | None]:
    """d8.jar (which contains D8 and R8) and the newest android.jar."""
    candidates = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        os.path.expanduser("~/Library/Android/sdk"),
        os.path.expanduser("~/Android/Sdk"),
        "/usr/local/lib/android/sdk",
    ]
    for sdk in filter(None, candidates):
        d8 = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "lib", "d8.jar")))
        platforms = sorted(
            glob.glob(os.path.join(sdk, "platforms", "android-*", "android.jar")),
            key=lambda p: [int(n) for n in re.findall(r"\d+", pathlib.Path(p).parent.name)],
        )
        if d8 and platforms:
            return d8[-1], platforms[-1]
    return None, None


def parse_dex_zip(path: str) -> dict:
    classes = methods = dex_bytes = 0
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if not info.filename.endswith(".dex"):
                continue
            data = archive.read(info.filename)
            dex_bytes += len(data)
            methods += struct.unpack_from("<I", data, DEX_METHOD_IDS_OFFSET)[0]
            classes += struct.unpack_from("<I", data, DEX_CLASS_DEFS_OFFSET)[0]
    return {"dex_bytes": dex_bytes, "classes": classes, "methods": methods}


def unpack_aar(path: str, directory: str) -> tuple[str | None, str | None]:
    """classes.jar and the consumer ProGuard rules of an AAR."""
    os.makedirs(directory, exist_ok=True)
    found: dict[str, str] = {}
    with zipfile.ZipFile(path) as archive:
        for name in ("classes.jar", "proguard.txt"):
            if name in archive.namelist():
                target = os.path.join(directory, name)
                pathlib.Path(target).write_bytes(archive.read(name))
                found[name] = target
    return found.get("classes.jar"), found.get("proguard.txt")


def run_d8(jar: str, d8: str, android_jar: str, out: str) -> dict:
    subprocess.run(
        ["java", "-Xmx3g", "-cp", d8, "com.android.tools.r8.D8", "--release",
         "--min-api", ANDROID_MIN_API, "--lib", android_jar, "--output", out, jar],
        check=True, capture_output=True,
    )
    return parse_dex_zip(out)


def run_r8(jars: list[str], rules: list[str], namespace: str, d8: str, android_jar: str,
           directory: str) -> dict:
    # Keeping the whole public API makes this an upper bound: an app that uses all of it.
    keep = os.path.join(directory, "keep.pro")
    pathlib.Path(keep).write_text(
        "-dontwarn **\n-ignorewarnings\n-dontobfuscate\n"
        f"-keep public class {namespace}.** {{ public protected *; }}\n",
        encoding="utf-8",
    )
    out = os.path.join(directory, "r8.zip")
    command = ["java", "-Xmx4g", "-cp", d8, "com.android.tools.r8.R8", "--release",
               "--min-api", ANDROID_MIN_API, "--output", out, "--lib", android_jar,
               "--pg-conf", keep]
    for rule in rules:
        command += ["--pg-conf", rule]
    subprocess.run(command + jars, check=True, capture_output=True)
    return parse_dex_zip(out)


def measure_android(aar: str, namespace: str | None, dependencies: list[dict],
                    skip_dex: bool) -> dict:
    aars = [aar] + [d["file"] for d in dependencies
                    if d["file"].endswith(".aar") and os.path.isfile(d["file"])]
    native: dict[str, int] = {}
    assets = 0
    for path in aars:
        add_sizes(native, native_libs_in_zip(path, prefix="jni/"))
        with zipfile.ZipFile(path) as archive:
            assets += sum(i.file_size for i in archive.infolist()
                          if i.filename.startswith("assets/") and not i.is_dir())
    result = {
        "aar_bytes": os.path.getsize(aar),
        "native_libs": dict(sorted(native.items())),
        "assets_bytes": assets,
    }

    d8, android_jar = (None, None) if skip_dex else find_android_sdk()
    if not (d8 and android_jar):
        if not skip_dex:
            print("Android SDK build-tools not found; skipping DEX metrics.", file=sys.stderr)
        return result

    with tempfile.TemporaryDirectory() as directory:
        classes, module_rules = unpack_aar(aar, os.path.join(directory, "module"))
        if not classes:
            return result
        try:
            result["d8"] = run_d8(classes, d8, android_jar, os.path.join(directory, "d8.zip"))
            jars, rules = [classes], [module_rules] if module_rules else []
            for index, dependency in enumerate(dependencies):
                path = dependency["file"]
                if path.endswith(".aar") and os.path.isfile(path):
                    jar, rule = unpack_aar(path, os.path.join(directory, f"dep{index}"))
                    jars += [jar] if jar else []
                    rules += [rule] if rule else []
                elif path.endswith(".jar") and os.path.isfile(path):
                    jars.append(path)
            if namespace:
                result["r8"] = run_r8(jars, rules, namespace, d8, android_jar, directory)
        except subprocess.CalledProcessError as error:
            print(f"D8/R8 failed: {error.stderr.decode()[-2000:]}", file=sys.stderr)
    return result


def measure_files(paths: list[str], module_prefix: str | None = None) -> dict:
    total = total_gzip = own_gzip = 0
    for path in paths:
        data = pathlib.Path(path).read_bytes()
        compressed = gzip_size(data)
        total += len(data)
        total_gzip += compressed
        if module_prefix and os.path.basename(path).startswith(module_prefix):
            own_gzip += compressed
    result = {"bytes": total, "gzip_bytes": total_gzip}
    if module_prefix:
        result["module_gzip_bytes"] = own_gzip
    return result


def measure_web(directories: dict[str, str], root_build_dir: str, root_name: str,
                name: str) -> dict:
    # The output holds the module and every Kotlin dependency it links. Wasm links them into one
    # binary, so only JS has a module-only share.
    web: dict = {}
    extensions = {"js": (".js", ".mjs"), "wasmJs": (".wasm", ".mjs")}
    for target, directory in directories.items():
        files = sorted(p for p in glob.glob(os.path.join(directory, "**", "*"), recursive=True)
                       if os.path.isfile(p) and p.endswith(extensions.get(target, ())))
        if files:
            prefix = f"{root_name}-{name}." if target == "js" else None
            web[target] = measure_files(files, prefix)
    worker = [os.path.join(root_build_dir, "js", "node_modules", f) for f in SQLITE_WORKER_FILES]
    if all(os.path.isfile(f) for f in worker):
        web["sqlite_worker"] = measure_files(worker)
    return web


def summarize_dependencies(entries: list[dict]) -> dict:
    modules: dict[str, dict] = {}
    for entry in entries:
        module = modules.setdefault(entry["module"], {"version": entry.get("version"), "bytes": 0})
        module["bytes"] += entry["bytes"]
    return {
        "count": len(modules),
        "bytes": sum(m["bytes"] for m in modules.values()),
        "modules": dict(sorted(modules.items())),
    }


def measure(root: str, skip_android: bool) -> dict:
    root_name = pathlib.Path(root).resolve().name
    settings = pathlib.Path(root, "settings.gradle.kts")
    if settings.is_file():
        if match := re.search(r'rootProject\.name\s*=\s*"([^"]+)"', settings.read_text()):
            root_name = match.group(1)

    report: dict = {"modules": {}}
    for manifest_path in sorted(glob.glob(os.path.join(root, "**", "build", "binary-size",
                                                       "manifest.json"), recursive=True)):
        manifest = json.loads(pathlib.Path(manifest_path).read_text(encoding="utf-8"))
        dependencies = manifest.get("dependencies", {})
        module: dict = {
            "dependencies": {t: summarize_dependencies(e) for t, e in dependencies.items()},
        }
        for target, jar in manifest.get("jvm", {}).items():
            if os.path.isfile(jar):
                module.setdefault("jvm", {})[target] = measure_jar(jar, dependencies.get(target, []))
        if (aar := manifest.get("android")) and os.path.isfile(aar):
            module["android"] = measure_android(aar, manifest.get("namespace"),
                                                dependencies.get("android", []), skip_android)
        if web := measure_web(manifest.get("web", {}), manifest["rootBuildDir"], root_name,
                              manifest["name"]):
            module["web"] = web
        report["modules"][manifest["project"]] = module
    return report


def fmt_bytes(value: int) -> str:
    if abs(value) >= 1_048_576:
        return f"{value / 1_048_576:.2f} MB"
    if abs(value) >= 1024:
        return f"{value / 1024:.1f} KB"
    return f"{value} B"


def fmt_value(value: int | None, unit: str) -> str:
    if value is None:
        return "—"
    return fmt_bytes(value) if unit == "bytes" else f"{value:,}"


def fmt_change(head: int | None, base: int | None, unit: str) -> str:
    if head is None:
        return "removed"
    if base is None:
        return "new"
    delta = head - base
    if delta == 0:
        return "—"
    text = (("+" if delta > 0 else "-") + fmt_bytes(abs(delta))) if unit == "bytes" else f"{delta:+,}"
    return f"{text}, {delta / base * 100:+.1f}%" if base else text


def is_changed(head: int | None, base: int | None, unit: str) -> bool:
    if head is None or base is None:
        return head != base
    delta = abs(head - base)
    if unit != "bytes":
        return delta != 0
    return delta >= NOISE_BYTES or (base != 0 and delta / base >= NOISE_RELATIVE)


def marker(head: int | None, base: int | None) -> str:
    if head is None:
        return "➖"
    if base is None:
        return "🆕"
    return "🔺" if head > base else "🟢"


def safe(text: object) -> str:
    return str(text) if SAFE_TEXT.match(str(text or "")) else "?"


def metric_rows(module: dict) -> list[tuple[str, int | None, str]]:
    """(label, value, unit) for every metric of a module, in display order."""
    rows: list[tuple[str, int | None, str]] = []
    android = module.get("android", {})
    if android:
        rows.append(("Android: R8 release DEX incl. dependencies", android.get("r8", {}).get("dex_bytes"), "bytes"))
        for abi, size in android.get("native_libs", {}).items():
            rows.append((f"Android: native libraries ({safe(abi)})", size, "bytes"))
        rows.append(("Android: assets incl. dependencies", android.get("assets_bytes"), "bytes"))
        rows.append(("Android: AAR", android.get("aar_bytes"), "bytes"))
        rows.append(("Android: D8 DEX (module only)", android.get("d8", {}).get("dex_bytes"), "bytes"))
        rows.append(("Android: D8 method count (module only)", android.get("d8", {}).get("methods"), "count"))
    for target, jar in module.get("jvm", {}).items():
        label = safe(target)
        rows.append((f"JVM ({label}): JAR", jar.get("jar_bytes"), "bytes"))
        for system, size in jar.get("native_libs", {}).items():
            rows.append((f"JVM ({label}): native libraries ({safe(system)})", size, "bytes"))
    web = module.get("web", {})
    if "js" in web:
        rows.append(("JS: total (gzip)", web["js"].get("gzip_bytes"), "bytes"))
        rows.append(("JS: module only (gzip)", web["js"].get("module_gzip_bytes"), "bytes"))
    if "wasmJs" in web:
        rows.append(("Wasm: total (gzip)", web["wasmJs"].get("gzip_bytes"), "bytes"))
    if "sqlite_worker" in web:
        rows.append(("Web: SQLite Wasm worker (gzip)", web["sqlite_worker"].get("gzip_bytes"), "bytes"))
    for target, summary in module.get("dependencies", {}).items():
        rows.append((f"Runtime dependencies ({safe(target)})", summary.get("bytes"), "bytes"))
    return rows


def compare_rows(head: dict, base: dict | None) -> list[dict]:
    head_rows = {label: (value, unit) for label, value, unit in metric_rows(head)}
    base_rows = {label: (value, unit) for label, value, unit in metric_rows(base or {})}
    labels = list(head_rows) + [label for label in base_rows if label not in head_rows]
    rows = []
    for label in labels:
        value, unit = head_rows.get(label, (None, base_rows.get(label, (None, "bytes"))[1]))
        before = base_rows.get(label, (None, unit))[0]
        if value is None and before is None:
            continue
        rows.append({"label": label, "unit": unit, "head": value, "base": before,
                     "changed": base is not None and is_changed(value, before, unit)})
    return rows


def table(rows: list[dict], with_base: bool, bold: bool = False) -> list[str]:
    if not with_base:
        return ["| Metric | Size |", "|---|---:|"] + [
            f"| {r['label']} | {fmt_value(r['head'], r['unit'])} |" for r in rows
        ]
    lines = ["| Metric | Size | Base | Change |", "|---|---:|---:|---:|"]
    for r in rows:
        size, change = fmt_value(r["head"], r["unit"]), fmt_change(r["head"], r["base"], r["unit"])
        if bold:
            size, change = f"**{size}**", f"{marker(r['head'], r['base'])} **{change}**"
        lines.append(f"| {r['label']} | {size} | {fmt_value(r['base'], r['unit'])} | {change} |")
    return lines


def details(summary: str, lines: list[str]) -> list[str]:
    return ["<details>", f"<summary>{summary}</summary>", "", *lines, "", "</details>", ""]


def dependency_changes(head: dict, base: dict) -> list[str]:
    lines: list[str] = []
    targets = sorted(set(head.get("dependencies", {})) | set(base.get("dependencies", {})))
    for target in targets:
        now = head.get("dependencies", {}).get(target, {}).get("modules", {})
        before = base.get("dependencies", {}).get(target, {}).get("modules", {})
        rows = []
        for name in sorted(set(now) | set(before)):
            a, b = now.get(name), before.get(name)
            if a and not b:
                rows.append(f"| ➕ `{safe(name)}` | {safe(a['version'])} | +{fmt_bytes(a['bytes'])} |")
            elif b and not a:
                rows.append(f"| ➖ `{safe(name)}` | {safe(b['version'])} | -{fmt_bytes(b['bytes'])} |")
            elif a["version"] != b["version"] or a["bytes"] != b["bytes"]:
                version = (safe(a["version"]) if a["version"] == b["version"]
                           else f"{safe(b['version'])} → {safe(a['version'])}")
                rows.append(f"| 🔄 `{safe(name)}` | {version} | {fmt_change(a['bytes'], b['bytes'], 'bytes')} |")
        if rows:
            lines += details(f"Dependency changes ({safe(target)}, {len(rows)})",
                             ["| Dependency | Version | Size change |", "|---|---|---:|", *rows])
    return lines


def largest_dependencies(module: dict) -> list[str]:
    modules = module.get("dependencies", {}).get("android", {}).get("modules", {})
    if not modules:
        return []
    largest = sorted(modules.items(), key=lambda kv: kv[1]["bytes"], reverse=True)[:TOP_DEPENDENCIES]
    rows = [f"| `{safe(m)}` | {safe(i['version'])} | {fmt_bytes(i['bytes'])} |" for m, i in largest]
    return details(f"Largest Android runtime dependencies ({len(largest)} of {len(modules)})",
                   ["| Dependency | Version | Size |", "|---|---|---:|", *rows])


def overview(rows: list[dict]) -> str:
    changed = [r for r in rows if r["changed"]]
    if not changed:
        return "**No size changes.**"
    text = f"**{len(changed)} of {len(rows)} metrics changed.**"
    sized = [r for r in changed if r["unit"] == "bytes" and r["head"] is not None and r["base"] is not None]
    if sized:
        top = max(sized, key=lambda r: abs(r["head"] - r["base"]))
        text += (f" Largest change: {top['label']} "
                 f"({marker(top['head'], top['base'])} {fmt_change(top['head'], top['base'], 'bytes')}).")
    return text


def render(head: dict, base: dict | None, marker_comment: str, base_sha: str | None,
           head_sha: str | None, repository: str | None) -> str:
    modules = head.get("modules", {})
    compared = {name: compare_rows(module, base.get("modules", {}).get(name, {}) if base else None)
                for name, module in modules.items()}
    lines = [marker_comment, "## Binary size", ""]
    if base is None:
        lines += ["_No base report; showing absolute sizes only._", ""]
    else:
        source = "the base branch"
        if base_sha and SHA.match(base_sha) and repository and SAFE_TEXT.match(repository.replace("/", "-")):
            source = f"[`{base_sha[:10]}`](https://github.com/{repository}/commit/{base_sha})"
        all_rows = [r for rows in compared.values() for r in rows]
        lines += [f"Compared with {source}. {overview(all_rows)}", ""]

    for name, module in modules.items():
        rows = compared[name]
        lines += [f"### `{safe(name)}`", ""]
        if base is None:
            lines += table(rows, with_base=False) + [""]
        else:
            changed = [r for r in rows if r["changed"]]
            unchanged = [r for r in rows if not r["changed"]]
            if changed:
                lines += table(changed, with_base=True, bold=True) + [""]
            if unchanged:
                summary = "All metrics" if not changed else "No significant change"
                lines += details(f"{summary} ({len(unchanged)})", table(unchanged, with_base=True))
            lines += dependency_changes(module, base.get("modules", {}).get(name, {}))
        lines += largest_dependencies(module)

    lines.append("<sub>The R8 estimate keeps the whole public API, so it is an upper bound. "
                 "Sizes are uncompressed unless marked gzip.</sub>")
    if head_sha and SHA.match(head_sha):
        lines += ["", f"<sub>Head {head_sha[:10]}</sub>"]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    measure_parser = commands.add_parser("measure")
    measure_parser.add_argument("--root", default=".")
    measure_parser.add_argument("--output", required=True)
    measure_parser.add_argument("--skip-android", action="store_true",
                                help="Skip the D8 and R8 metrics")

    render_parser = commands.add_parser("render")
    render_parser.add_argument("head")
    render_parser.add_argument("--base")
    render_parser.add_argument("--marker", default=DEFAULT_MARKER)
    render_parser.add_argument("--base-sha")
    render_parser.add_argument("--head-sha")
    render_parser.add_argument("--repository")

    args = parser.parse_args()
    if args.command == "measure":
        report = measure(args.root, args.skip_android)
        if not report["modules"]:
            print(f"No binary-size manifests under {args.root}.", file=sys.stderr)
            return 1
        pathlib.Path(args.output).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        return 0

    head = json.loads(pathlib.Path(args.head).read_text(encoding="utf-8"))
    base = None
    if args.base and os.path.isfile(args.base):
        base = json.loads(pathlib.Path(args.base).read_text(encoding="utf-8"))
    sys.stdout.write(render(head, base, args.marker, args.base_sha, args.head_sha, args.repository))
    return 0


if __name__ == "__main__":
    sys.exit(main())
