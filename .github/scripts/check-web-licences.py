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
#   - is neither listed in ALLOWED (reviewed packages, each with the licence reviewed and its reason) nor in ALLOWED_DEV
#     (the same, for packages that must stay development-only: one the lockfile no longer marks dev fails). An entry
#     whose package the lockfile now gives another licence fails, as does one the lockfile no longer has, so the lists
#     stay honest.
# An SPDX expression passes when any "OR" alternative does, and an "AND" when all its parts do; parentheses group, and
# "X WITH Y" passes only when that whole text is in the list of licences.
#
# Usage: check-web-licences.py [lockfile]
# Exit status: 0 when every package is acceptable, or when there is no lockfile yet, 1 otherwise.

import json
import pathlib
import re
import sys

STRICT = {"MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD"}
DEV_ALSO = {"BlueOak-1.0.0", "CC0-1.0", "CC-BY-4.0", "Python-2.0", "Unlicense"}
ALLOWED = {
    # "node_modules/<name>": ("the licence reviewed, as the lockfile gives it", "why it's acceptable"),
    "node_modules/isbot": ("Unlicense", "Unlicense, a public-domain dedication with no conditions; @tanstack/react-router depends on it, and its notice is listed in notices.txt"),
}
# Exceptions for packages that must stay development-only: one that loses "dev": true in the lockfile (it entered the
# app's runtime tree) fails.
ALLOWED_DEV = {
    "node_modules/axe-core": ("MPL-2.0", "MPL-2.0, development only: runs in the accessibility tests and is never bundled or shipped"),
    "node_modules/lightningcss": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-android-arm64": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-darwin-arm64": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-darwin-x64": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-freebsd-x64": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-linux-arm-gnueabihf": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-linux-arm64-gnu": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-linux-arm64-musl": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-linux-x64-gnu": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-linux-x64-musl": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-win32-arm64-msvc": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
    "node_modules/lightningcss-win32-x64-msvc": ("MPL-2.0", "MPL-2.0, development only: Vite's CSS minifier (and its platform builds), used at build time and never shipped"),
}

DEFAULT_LOCKFILE = pathlib.Path(__file__).resolve().parents[2] / "healdan-web" / "web" / "package-lock.json"


def acceptable(expression, allowed):
    """Whether an SPDX expression is acceptable. OR binds looser than AND, parentheses group, and a term "X WITH Y" (a
    licence with an exception) counts only when that whole text is in allowed. Anything malformed is refused."""
    tokens = re.findall(r"\(|\)|[^\s()]+", expression)
    position = 0

    def peek():
        return tokens[position] if position < len(tokens) else None

    def take():
        nonlocal position
        position += 1
        return tokens[position - 1]

    def parse_or():
        result = parse_and()
        while peek() == "OR":
            take()
            right = parse_and()  # no short circuit: the whole expression must parse
            result = result or right
        return result

    def parse_and():
        result = parse_term()
        while peek() == "AND":
            take()
            right = parse_term()
            result = result and right
        return result

    def parse_term():
        token = peek()
        if token is None or token in (")", "OR", "AND", "WITH"):
            raise ValueError(expression)
        take()
        if token == "(":
            result = parse_or()
            if peek() != ")":
                raise ValueError(expression)
            take()
            return result
        if peek() == "WITH":
            take()
            exception = peek()
            if exception is None or exception in ("(", ")", "OR", "AND", "WITH"):
                raise ValueError(expression)
            take()
            token = f"{token} WITH {exception}"
        return token in allowed

    try:
        result = parse_or()
    except ValueError:
        return False
    return result and position == len(tokens)


def problems(lock):
    found = []
    packages = lock.get("packages", {})
    for key, entry in packages.items():
        if "node_modules/" not in key or entry.get("link"):
            continue
        licence = entry.get("license")
        if key in ALLOWED or key in ALLOWED_DEV:
            reviewed = (ALLOWED.get(key) or ALLOWED_DEV[key])[0]
            if licence != reviewed:
                found.append(f"{key}@{entry.get('version')}: allowed as {reviewed}, but the lockfile now says "
                             f"{licence or 'no licence'}")
            elif key in ALLOWED_DEV and not entry.get("dev"):
                found.append(f"{key}@{entry.get('version')}: allowed as development only, but the lockfile no longer "
                             f"marks it dev")
            continue
        allowed = STRICT | DEV_ALSO if entry.get("dev") else STRICT
        if not licence or not isinstance(licence, str) or not acceptable(licence, allowed):
            found.append(f"{key}@{entry.get('version')}: {licence or 'no licence'}")
    stale = [key for key in list(ALLOWED) + list(ALLOWED_DEV) if key not in packages]
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
              "entry with the licence reviewed and its reason. See .github/scripts/check-web-licences.py.")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
