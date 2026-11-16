#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only
#
# Checks that no build can get artifacts from LogicalDOC's Maven repositories, or contact upstream's retired ones.
# Builds get the artifacts only LogicalDOC's repository has from Healdan's copies at https://maven.healdan.com/,
# through a mirror of the logicaldoc repository in docker/maven-settings.xml, and send upstream's retired repositories
# (java.net's and Sonatype's OSSRH) to Maven Central through another. An upstream sync can bring another such
# repository in, as it can Font Awesome Pro icons. Run by .github/workflows/build-logicaldoc.yml. The design is
# docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md.
#
# Fails when:
#   - docker/maven-settings.xml has no mirror of logicaldoc to https://maven.healdan.com/;
#   - a <repository> or <pluginRepository> in a tracked pom.xml, or in the settings file, has a URL on a host whose
#     name contains "logicaldoc", under an id that mirror doesn't cover;
#   - one has a URL on a retired host (RETIRED), under an id that no mirror to Maven Central or to Healdan's copies
#     covers.
# Repositories inside <distributionManagement>, which only a deploy reads, are ignored. An artifact only LogicalDOC's
# repository has isn't this check's job: the build fails on it with "not found in healdan".
#
# Usage: check-maven-repositories.py [repo root]
# Exit status: 0 when no build can reach LogicalDOC's repositories or the retired ones, 1 otherwise.

import pathlib
import subprocess
import sys
import urllib.parse
import xml.parsers.expat

SETTINGS = "docker/maven-settings.xml"
HEALDAN = "https://maven.healdan.com/"
CENTRAL = "https://repo1.maven.org/maven2"
REPOSITORY_TAGS = ("repository", "pluginRepository")
# Hosts of upstream's repositories that have gone (java.net's, and Sonatype's OSSRH), and how. Nothing comes from
# them, but Maven asks every repository for each version range's metadata, so a build would still contact them.
RETIRED = {
    "maven.java.net": "its certificate chain no longer validates",
    "oss.sonatype.org": "Sonatype retired it, and it answers 404",
}

NO_MIRROR = (f"needs a mirror of logicaldoc to {HEALDAN}, so builds get the artifacts only LogicalDOC's repository "
             "has from Healdan's copies.")
FIX = ("is on LogicalDOC's host {host}. Builds get those artifacts from " + HEALDAN + ": add {id} to the healdan "
       "mirror's mirrorOf in " + SETTINGS + ", or drop the repository.")
RETIRED_FIX = ("is on {host}, which has gone ({why}). Builds send it to Maven Central: add {id} to the "
               "retired-repositories mirror's mirrorOf in " + SETTINGS + " (or, for what only Healdan's copies "
               "have, the healdan mirror's), or drop the repository.")


def tracked_files(root):
    listing = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return sorted(name.decode("utf-8", errors="replace") for name in listing.split(b"\0") if name)


def elements(path, tags, fields):
    """A dict per element named in tags, outside <distributionManagement>: its line, and the text of each direct
    child named in fields (None when it has none). Raises xml.parsers.expat.ExpatError on a malformed file."""
    parser = xml.parsers.expat.ParserCreate()
    stack, found, text = [], [], []
    current = None

    def start(name, _attributes):
        nonlocal current
        if name in tags and "distributionManagement" not in stack:
            current = {"line": parser.CurrentLineNumber, **dict.fromkeys(fields)}
        stack.append(name)
        text.clear()

    def end(name):
        nonlocal current
        stack.pop()
        if current is not None:
            if name in fields and stack and stack[-1] in tags:
                current[name] = "".join(text).strip()
            elif name in tags:
                found.append(current)
                current = None
        text.clear()

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = text.append
    parser.Parse(path.read_bytes(), True)
    return found


def mirrored_ids(settings, url):
    """The repository ids the settings file mirrors to url."""
    ids = set()
    for mirror in elements(settings, ("mirror",), ("mirrorOf", "url")):
        if (mirror["url"] or "").rstrip("/") == url.rstrip("/"):
            ids.update(part.strip() for part in (mirror["mirrorOf"] or "").split(","))
    return ids


def host_of(url):
    """The URL's host, lower case, or "" when it has none."""
    try:
        return urllib.parse.urlsplit(url or "").hostname or ""
    except ValueError:
        return ""


def logicaldoc_host(url):
    """The URL's host when it's one of LogicalDOC's, else None."""
    host = host_of(url)
    return host if "logicaldoc" in host else None


def find_problems(root):
    root = pathlib.Path(root)
    tracked = tracked_files(root)
    problems = []
    mirrored, to_central = set(), set()
    if SETTINGS in tracked and (root / SETTINGS).is_file():
        try:
            mirrored = mirrored_ids(root / SETTINGS, HEALDAN)
            to_central = mirrored_ids(root / SETTINGS, CENTRAL)
        except xml.parsers.expat.ExpatError as error:
            return [f"{SETTINGS}: can't be parsed: {error}"]
    if "logicaldoc" not in mirrored:
        problems.append(f"{SETTINGS}: {NO_MIRROR}")
    names = [name for name in tracked if name == "pom.xml" or name.endswith("/pom.xml")]
    if SETTINGS in tracked:
        names.append(SETTINGS)
    for name in names:
        path = root / name
        if not path.is_file():
            continue
        try:
            repositories = elements(path, REPOSITORY_TAGS, ("id", "url"))
        except xml.parsers.expat.ExpatError as error:
            problems.append(f"{name}: can't be parsed: {error}")
            continue
        for repository in repositories:
            where = f"{name}:{repository['line']}: repository {repository['id']} "
            host = logicaldoc_host(repository["url"])
            if host and repository["id"] not in mirrored:
                problems.append(where + FIX.format(host=host, id=repository["id"]))
            host = host_of(repository["url"])
            if host in RETIRED and repository["id"] not in mirrored | to_central:
                problems.append(where + RETIRED_FIX.format(host=host, why=RETIRED[host], id=repository["id"]))
    return problems


def main(argv):
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(__file__).resolve().parents[2]
    problems = find_problems(root)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
