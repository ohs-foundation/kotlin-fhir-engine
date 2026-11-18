"""Unit tests for the comparison and rendering in binary_size."""

import copy
import unittest

import binary_size as size

HEAD_SHA = "a" * 40
BASE_SHA = "b" * 40


def report(aar=1_000_000, r8=5_000_000, arm64=2_000_000, dependencies=None):
    return {
        "modules": {
            ":engine": {
                "android": {
                    "aar_bytes": aar,
                    "assets_bytes": 0,
                    "native_libs": {"arm64-v8a": arm64},
                    "r8": {"dex_bytes": r8},
                    "d8": {"dex_bytes": 900_000, "methods": 4000},
                },
                "dependencies": {
                    "android": {
                        "bytes": 10_000_000,
                        "modules": dependencies
                        or {"androidx.room3:room3-runtime": {"version": "3.0.0", "bytes": 500_000}},
                    },
                },
            },
        },
    }


def render(head, base=None):
    return size.render(head, base, size.DEFAULT_MARKER, BASE_SHA, HEAD_SHA, "owner/repo")


class ChangeTest(unittest.TestCase):
    def test_noise_is_not_a_change(self):
        self.assertFalse(size.is_changed(1_000_300, 1_000_000, "bytes"))

    def test_change_above_the_byte_threshold(self):
        self.assertTrue(size.is_changed(1_002_000, 1_000_000, "bytes"))

    def test_small_relative_change_on_a_small_value(self):
        self.assertTrue(size.is_changed(600, 100, "bytes"))

    def test_any_count_change(self):
        self.assertTrue(size.is_changed(4001, 4000, "count"))

    def test_new_and_removed_metrics(self):
        self.assertTrue(size.is_changed(1, None, "bytes"))
        self.assertTrue(size.is_changed(None, 1, "bytes"))


class RenderTest(unittest.TestCase):
    def test_no_base_shows_absolute_sizes(self):
        text = render(report())
        self.assertIn("No base report", text)
        self.assertIn("| Metric | Size |", text)

    def test_identical_reports(self):
        text = render(report(), report())
        self.assertIn("**No size changes.**", text)
        self.assertIn("<summary>All metrics", text)

    def test_changed_rows_come_first(self):
        text = render(report(arm64=7_000_000), report())
        self.assertIn("**1 of", text)
        self.assertIn("Largest change: Android: native libraries (arm64-v8a)", text)
        self.assertIn("| Android: native libraries (arm64-v8a) | **6.68 MB** | 1.91 MB | 🔺", text)
        self.assertLess(text.index("arm64-v8a) | **"), text.index("No significant change"))

    def test_reduction_marker(self):
        self.assertIn("🟢 **-976.6 KB", render(report(r8=4_000_000), report()))

    def test_dependency_changes(self):
        added = {"androidx.room3:room3-runtime": {"version": "3.0.0", "bytes": 500_000},
                 "com.example:big": {"version": "1.0", "bytes": 3_000_000}}
        text = render(report(dependencies=added), report())
        self.assertIn("| ➕ `com.example:big` | 1.0 | +2.86 MB |", text)

    def test_untrusted_text_is_replaced(self):
        unsafe = {"x](https://example.com)<img>": {"version": "1.0 @someone", "bytes": 1}}
        text = render(report(dependencies=unsafe))
        self.assertNotIn("example.com", text)
        self.assertNotIn("@someone", text)
        self.assertIn("`?`", text)

    def test_invalid_sha_is_not_linked(self):
        text = size.render(report(), report(), size.DEFAULT_MARKER, "not-a-sha", HEAD_SHA, "o/r")
        self.assertIn("Compared with the base branch.", text)

    def test_marker_is_first(self):
        self.assertTrue(render(report()).startswith(size.DEFAULT_MARKER))

    def test_module_missing_from_base(self):
        base = copy.deepcopy(report())
        base["modules"] = {}
        self.assertIn("🆕 **new**", render(report(), base))


class DesktopOsTest(unittest.TestCase):
    def test_paths(self):
        self.assertEqual(size.desktop_os("natives/linux_x64/libsqliteJni.so"), "linux_x64")
        self.assertEqual(size.desktop_os("natives/osx_arm64/libsqliteJni.dylib"), "osx_arm64")
        self.assertEqual(size.desktop_os("natives/windows_x64/sqliteJni.dll"), "windows_x64")
        self.assertEqual(size.desktop_os("META-INF/native/libnetty_linux.so"), "Linux")
        self.assertEqual(size.desktop_os("darwin/libfoo.dylib"), "macOS")
        self.assertEqual(size.desktop_os("lib/unknown/libx.so"), "Other")


if __name__ == "__main__":
    unittest.main()
