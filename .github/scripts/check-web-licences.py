#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only
#
# Checks the licences of the packages the User app's lockfile (healdan-web/web/package-lock.json) installs. What the app
# ships is bundled into a page served with Healdan and must be under a licence Healdan can pass on with its notices
# (apps/user's notices.txt, written by scripts/notices.mjs). Run by .github/workflows/build-logicaldoc.yml. The design is
# docs/superpowers/specs/2026-11-10-m6-user-app-design.md (§7.4).
#
# Fails when a package (workspace packages, which are Healdan's own, are skipped):
#   - ships in the app (no "dev": true in the lockfile) and has a licence outside STRICT, or none;
#   - is development only and has a licence outside STRICT and DEV_ALSO;
#   - is neither listed in ALLOWED: reviewed packages, each with its reason. An ALLOWED entry the lockfile no longer
#     has fails too, so the list stays honest.
# An SPDX expression passes when any "OR" alternative does, and an "AND" when all its parts do.
#
# Usage: check-web-licences.py [lockfile]
# Exit status: 0 when every package is acceptable, or when there is no lockfile yet, 1 otherwise.

import json
import pathlib
import sys

STRICT = {"MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD"}
DEV_ALSO = {"BlueOak-1.0.0", "CC0-1.0", "CC-BY-4.0", "Python-2.0", "Unlicense"}
ALLOWED = {
    # "node_modules/<name>": "why it's acceptable",
    "node_modules/axe-core": "MPL-2.0, development only: runs in the accessibility tests and is never bundled or shipped",
    "node_modules/isbot": "Unlicense, a public-domain dedication with no conditions; @tanstack/react-router depends on it, and its notice is listed in notices.txt",
    "node_modules/lightningcss": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-android-arm64": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-darwin-arm64": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-darwin-x64": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-freebsd-x64": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-linux-arm-gnueabihf": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-linux-arm64-gnu": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-linux-arm64-musl": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-linux-x64-gnu": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-linux-x64-musl": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-win32-arm64-msvc": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
    "node_modules/lightningcss-win32-x64-msvc": "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped",
}

DEFAULT_LOCKFILE = pathlib.Path(__file__).resolve().parents[2] / "healdan-web" / "web" / "package-lock.json"


def acceptable(expression, allowed):
    expression = expression.strip().strip("()")
    if " OR " in expression:
        return any(acceptable(part, allowed) for part in expression.split(" OR "))
    if " AND " in expression:
        return all(acceptable(part, allowed) for part in expression.split(" AND "))
    return expression in allowed


def problems(lock):
    found = []
    for key, entry in lock.get("packages", {}).items():
        if "node_modules/" not in key or entry.get("link") or key in ALLOWED:
            continue
        licence = entry.get("license")
        allowed = STRICT | DEV_ALSO if entry.get("dev") else STRICT
        if not licence or not isinstance(licence, str) or not acceptable(licence, allowed):
            found.append(f"{key}@{entry.get('version')}: {licence or 'no licence'}")
    stale = [key for key in ALLOWED if key not in lock.get("packages", {})]
    return found + [f"{key}: in ALLOWED but not in the lockfile" for key in stale]


def main(argv):
    path = pathlib.Path(argv[0]) if argv else DEFAULT_LOCKFILE
    if not path.exists():
        print(f"No lockfile at {path}, so nothing to check.")
        return 0
    found = problems(json.loads(path.read_text(encoding="utf-8")))
    for problem in found:
        print(f"{path}: {problem}")
    if found:
        print("Each package must have a licence in STRICT (or DEV_ALSO for a development-only one), or an ALLOWED "
              "entry with its reason. See .github/scripts/check-web-licences.py.")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
