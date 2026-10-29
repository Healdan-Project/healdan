#!/usr/bin/env python3
#
# Checks that no build can get artifacts from LogicalDOC's Maven repositories. Builds get the artifacts only
# LogicalDOC's repository has from Healdan's copies at https://maven.healdan.com/, through a mirror of the logicaldoc
# repository in docker/maven-settings.xml, and an upstream sync can bring another LogicalDOC repository in, as it can
# Font Awesome Pro icons. Run by .github/workflows/build-logicaldoc.yml. The design is
# docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md.
#
# Fails when:
#   - docker/maven-settings.xml has no mirror of logicaldoc to https://maven.healdan.com/;
#   - a <repository> or <pluginRepository> in a tracked pom.xml, or in the settings file, has a URL on a host whose
#     name contains "logicaldoc", under an id that mirror doesn't cover.
# Repositories inside <distributionManagement>, which only a deploy reads, are ignored. An artifact only LogicalDOC's
# repository has isn't this check's job: the build fails on it with "not found in healdan".
#
# Usage: check-maven-repositories.py [repo root]
# Exit status: 0 when no build can reach LogicalDOC's repositories, 1 otherwise.

import pathlib
import subprocess
import sys
import urllib.parse
import xml.parsers.expat

SETTINGS = "docker/maven-settings.xml"
HEALDAN = "https://maven.healdan.com/"
REPOSITORY_TAGS = ("repository", "pluginRepository")

NO_MIRROR = (f"needs a mirror of logicaldoc to {HEALDAN}, so builds get the artifacts only LogicalDOC's repository "
             "has from Healdan's copies.")
FIX = ("is on LogicalDOC's host {host}. Builds get those artifacts from " + HEALDAN + ": add {id} to the healdan "
       "mirror's mirrorOf in " + SETTINGS + ", or drop the repository.")


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


def mirrored_ids(settings):
    """The repository ids the settings file mirrors to Healdan's repository."""
    ids = set()
    for mirror in elements(settings, ("mirror",), ("mirrorOf", "url")):
        if (mirror["url"] or "").rstrip("/") == HEALDAN.rstrip("/"):
            ids.update(part.strip() for part in (mirror["mirrorOf"] or "").split(","))
    return ids


def logicaldoc_host(url):
    """The URL's host when it's one of LogicalDOC's, else None."""
    try:
        host = urllib.parse.urlsplit(url or "").hostname or ""
    except ValueError:
        return None
    return host if "logicaldoc" in host else None


def find_problems(root):
    root = pathlib.Path(root)
    tracked = tracked_files(root)
    problems = []
    mirrored = set()
    if SETTINGS in tracked and (root / SETTINGS).is_file():
        try:
            mirrored = mirrored_ids(root / SETTINGS)
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
            host = logicaldoc_host(repository["url"])
            if host and repository["id"] not in mirrored:
                problems.append(f"{name}:{repository['line']}: repository {repository['id']} "
                                + FIX.format(host=host, id=repository["id"]))
    return problems


def main(argv):
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(__file__).resolve().parents[2]
    problems = find_problems(root)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
