#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only
#
# Checks that download repositories in tracked POMs and docker/maven-settings.xml are approved directly or have an
# explicit mirror to Maven Central or Healdan. Repositories inside <distributionManagement> are for publishing.
#
# Usage: check-maven-repositories.py [repo root]
# Exit status: 0 when all declarations pass, 1 otherwise.

import pathlib
import subprocess
import sys
import urllib.parse
import xml.parsers.expat

SETTINGS = "docker/maven-settings.xml"
HEALDAN = "https://maven.healdan.com"
CENTRAL = {"https://repo1.maven.org/maven2", "https://repo.maven.apache.org/maven2"}
DIRECT_REPOSITORIES = {
    "central": CENTRAL,
    "terracotta": {"https://repo.terracotta.org/maven2"},
}

NO_MIRROR = (f"needs a mirror of logicaldoc to {HEALDAN}, so builds get the artifacts only LogicalDOC's repository "
             "has from Healdan's copies.")


def tracked_files(root):
    listing = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return sorted(name.decode("utf-8", errors="replace") for name in listing.split(b"\0") if name)


def elements(path, parents, fields):
    """A dict per element named in parents, under its list: its line, and the text of each direct
    child named in fields (None when it has none). Raises xml.parsers.expat.ExpatError on a malformed file."""
    parser = xml.parsers.expat.ParserCreate()
    stack, found, text = [], [], []
    current = None

    def start(name, _attributes):
        nonlocal current
        if stack and stack[-1] == parents.get(name) and "configuration" not in stack:
            current = {"line": parser.CurrentLineNumber, **dict.fromkeys(fields)}
        stack.append(name)
        text.clear()

    def end(name):
        nonlocal current
        stack.pop()
        if current is not None:
            if name in fields and stack and stack[-1] in parents:
                current[name] = "".join(text).strip()
            elif name in parents:
                found.append(current)
                current = None
        text.clear()

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = text.append
    parser.Parse(path.read_bytes(), True)
    return found


def read_mirrors(settings, problems):
    mirrors = {}
    for mirror in elements(settings, {"mirror": "mirrors"}, ("mirrorOf", "url", "mirrorOfLayouts", "blocked")):
        where = f"{SETTINGS}:{mirror['line']}: mirror of {mirror['mirrorOf']}"
        url = (mirror["url"] or "").rstrip("/")
        ids = (mirror["mirrorOf"] or "").split(",")
        if url not in CENTRAL | {HEALDAN}:
            problems.append(f"{where} must point to Maven Central or {HEALDAN}.")
        elif (mirror["mirrorOfLayouts"] not in (None, "*") or mirror["blocked"] not in (None, "false")
              or any(not id_ or id_ != id_.strip() or id_.startswith("!") or "*" in id_ for id_ in ids)):
            problems.append(f"{where} needs explicit repository ids, separated by commas without extra spaces, "
                            "with all layouts and downloads enabled.")
        else:
            for id_ in ids:
                if id_ in mirrors:
                    problems.append(f"{where} declares a second mirror for {id_}.")
                mirrors[id_] = url
    return mirrors


def host_of(url):
    """The URL's host, lower case, or "" when it has none."""
    try:
        return urllib.parse.urlsplit(url or "").hostname or ""
    except ValueError:
        return ""


def find_problems(root):
    root = pathlib.Path(root)
    tracked = tracked_files(root)
    problems = []
    mirrors = {}
    if SETTINGS in tracked and (root / SETTINGS).is_file():
        try:
            mirrors = read_mirrors(root / SETTINGS, problems)
        except xml.parsers.expat.ExpatError as error:
            return [f"{SETTINGS}: can't be parsed: {error}"]
    if mirrors.get("logicaldoc") != HEALDAN:
        problems.append(f"{SETTINGS}: {NO_MIRROR}")
    names = [name for name in tracked if name == "pom.xml" or name.endswith("/pom.xml")]
    if SETTINGS in tracked:
        names.append(SETTINGS)
    for name in names:
        path = root / name
        if not path.is_file():
            continue
        try:
            repositories = elements(path, {"repository": "repositories", "pluginRepository": "pluginRepositories"},
                                    ("id", "url"))
        except xml.parsers.expat.ExpatError as error:
            problems.append(f"{name}: can't be parsed: {error}")
            continue
        for repository in repositories:
            id_, url = repository["id"], (repository["url"] or "").rstrip("/")
            where = f"{name}:{repository['line']}: repository {id_} ({url})"
            if id_ == "Alfresco Maven Repository" or "logicaldoc" in host_of(url):
                if mirrors.get(id_) != HEALDAN:
                    problems.append(f"{where} needs a mirror to {HEALDAN} in {SETTINGS}.")
            elif id_ not in mirrors and url not in DIRECT_REPOSITORIES.get(id_, ()):
                problems.append(f"{where} is unapproved. Review what the build resolves there, then drop it or "
                                f"add its id to a mirror in {SETTINGS}.")
    return problems


def main(argv):
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(__file__).resolve().parents[2]
    problems = find_problems(root)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
