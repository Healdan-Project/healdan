# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

import contextlib
import io
import json
import pathlib
import tempfile
import unittest
from unittest import mock

import _scripts

checker = _scripts.load("check-web-licences")


def package(licence, dev=False, version="1.0.0"):
    entry = {"version": version}
    if licence is not None:
        entry["license"] = licence
    if dev:
        entry["dev"] = True
    return entry


class CheckWebLicencesTest(unittest.TestCase):

    def setUp(self):
        # The real ALLOWED names packages these small lockfiles don't have.
        patcher = mock.patch.object(checker, "ALLOWED", {})
        patcher.start()
        self.addCleanup(patcher.stop)

    def lock(self, **packages):
        """A lockfile whose package keys are the names given, under node_modules/ (a double underscore is a slash)."""
        entries = {"": {"name": "web"}, "packages/ui": {"name": "@healdan/ui"}, "apps/user": {"name": "@healdan/user"},
                   "node_modules/@healdan/ui": {"resolved": "packages/ui", "link": True}}
        for name, entry in packages.items():
            entries[f"node_modules/{name.replace('__', '/')}"] = entry
        return {"lockfileVersion": 3, "packages": entries}

    def test_runtime_licences_pass(self):
        for licence in ["MIT", "Apache-2.0", "BSD-3-Clause", "ISC", "0BSD", "(MIT OR Apache-2.0)"]:
            with self.subTest(licence=licence):
                self.assertEqual([], checker.problems(self.lock(a=package(licence))))

    def test_runtime_licences_fail(self):
        for licence in ["MPL-2.0", "GPL-3.0", None]:
            with self.subTest(licence=licence):
                found = checker.problems(self.lock(a=package(licence, version="2.3.4")))
                self.assertEqual(1, len(found))
                self.assertIn("node_modules/a@2.3.4", found[0])
                self.assertIn(licence or "no licence", found[0])

    def test_dev_licences(self):
        for licence in ["BlueOak-1.0.0", "CC0-1.0", "CC-BY-4.0", "Python-2.0", "Unlicense"]:
            with self.subTest(licence=licence):
                self.assertEqual([], checker.problems(self.lock(a=package(licence, dev=True))))
                self.assertEqual(1, len(checker.problems(self.lock(a=package(licence)))))
        self.assertEqual(1, len(checker.problems(self.lock(a=package("GPL-3.0", dev=True)))))

    def test_expressions(self):
        self.assertEqual(1, len(checker.problems(self.lock(a=package("MIT AND GPL-3.0")))))
        self.assertEqual([], checker.problems(self.lock(a=package("GPL-3.0 OR MIT"))))
        self.assertEqual([], checker.problems(self.lock(a=package("MIT AND ISC"))))

    def test_allowed_passes_and_stale_fails(self):
        with mock.patch.object(checker, "ALLOWED", {"node_modules/a": "reviewed"}):
            self.assertEqual([], checker.problems(self.lock(a=package("GPL-3.0"))))
            found = checker.problems(self.lock())
            self.assertEqual(["node_modules/a: in ALLOWED but not in the lockfile"], found)

    def test_workspace_packages_are_skipped(self):
        lock = self.lock()
        lock["packages"]["apps/user"]["license"] = "GPL-3.0"
        lock["packages"]["packages/ui"]["license"] = "GPL-3.0"
        self.assertEqual([], checker.problems(lock))

    def run_main(self, text=None):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "package-lock.json"
            if text is not None:
                path.write_text(text, encoding="utf-8")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                status = checker.main([str(path)])
            return status, out.getvalue()

    def test_main(self):
        status, out = self.run_main(json.dumps(self.lock(a=package("GPL-3.0"))))
        self.assertEqual(1, status)
        self.assertIn("node_modules/a@1.0.0: GPL-3.0", out)
        self.assertEqual(0, self.run_main(json.dumps(self.lock(a=package("MIT"))))[0])

    def test_no_lockfile_passes(self):
        self.assertEqual(0, self.run_main()[0])


if __name__ == "__main__":
    unittest.main()
