#!/usr/bin/env python3
#
# Checks that Healdan neither ships nor offers Oracle or SQL Server. Healdan supports only open-source databases
# (Healdan-Project/healdan#110): the WAR ships neither driver and the setup wizard doesn't offer them, and an upstream
# sync can bring either back, as it can Font Awesome Pro icons. Run by .github/workflows/build-logicaldoc.yml. The
# design is docs/superpowers/specs/2026-11-01-open-source-databases-design.md.
#
# Fails when:
#   - a tracked pom.xml declares a dependency on one of their JDBC drivers (DRIVER_GROUPS), managed or not, in any
#     scope. Excluding one (<exclusion>) is fine;
#   - a tracked file names one of their driver classes (DRIVER_CLASSES), as the setup wizard's database list did.
# CE's isOracle() and isSQLServer() code paths and upstream's .oracle and .mssql SQL variants stay, and name neither.
#
# Checks every file git tracks, except docs/ and Markdown files, which are prose; site/, the docs site; tests and their
# fixtures (src/test/ in any module, and .github/scripts/tests/), which never ship; and this checker. Binary files (with
# a NUL byte) are skipped.
#
# Usage: check-database-drivers.py [repo root]
# Exit status: 0 when nothing ships or offers Oracle or SQL Server, 1 otherwise.

import pathlib
import re
import subprocess
import sys
import xml.parsers.expat

DRIVER_GROUPS = {
    "com.microsoft.sqlserver": "SQL Server",
    "net.sourceforge.jtds": "SQL Server",
    "com.oracle.jdbc": "Oracle",
    "com.oracle.database.jdbc": "Oracle",
}

DRIVER_CLASSES = re.compile(r"\b(com\.microsoft\.sqlserver\.jdbc\.SQLServerDriver|net\.sourceforge\.jtds\.jdbc\.Driver"
                            r"|oracle\.jdbc\.(?:driver\.)?OracleDriver)\b")

SKIPPED_PREFIXES = ("docs/", "site/", ".github/scripts/tests/")
SKIPPED_FILES = {".github/scripts/check-database-drivers.py"}

DEPENDENCY_FIX = ("is {database}'s JDBC driver, and Healdan supports only open-source databases. Drop the "
                  "dependency.")
CLASS_FIX = ("names {database}'s JDBC driver, and Healdan supports only open-source databases. Remove it, as the "
             "setup wizard's database list did.")


def tracked_files(root):
    listing = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return sorted(name.decode("utf-8", errors="replace") for name in listing.split(b"\0") if name)


def skipped(name):
    return (name.startswith(SKIPPED_PREFIXES) or name in SKIPPED_FILES or name.endswith(".md")
            or "/src/test/" in f"/{name}")


def dependencies(path):
    """(line, groupId, artifactId) for each <dependency> in the POM. Raises xml.parsers.expat.ExpatError on a malformed
    file."""
    parser = xml.parsers.expat.ParserCreate()
    stack, found, text = [], [], []
    current = None

    def start(name, _attributes):
        nonlocal current
        if name == "dependency":
            current = {"line": parser.CurrentLineNumber, "groupId": None, "artifactId": None}
        stack.append(name)
        text.clear()

    def end(name):
        nonlocal current
        stack.pop()
        if current is not None:
            if name in ("groupId", "artifactId") and stack and stack[-1] == "dependency":
                current[name] = "".join(text).strip()
            elif name == "dependency":
                found.append((current["line"], current["groupId"], current["artifactId"]))
                current = None
        text.clear()

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = text.append
    parser.Parse(path.read_bytes(), True)
    return found


def database_of(driver_class):
    return "SQL Server" if "sqlserver" in driver_class or "jtds" in driver_class else "Oracle"


def find_problems(root):
    root = pathlib.Path(root)
    problems = []
    for name in tracked_files(root):
        if skipped(name):
            continue
        path = root / name
        if not path.is_file():
            continue
        if name == "pom.xml" or name.endswith("/pom.xml"):
            try:
                for line, group, artifact in dependencies(path):
                    if group in DRIVER_GROUPS:
                        problems.append(f"{name}:{line}: dependency {group}:{artifact} "
                                        + DEPENDENCY_FIX.format(database=DRIVER_GROUPS[group]))
            except xml.parsers.expat.ExpatError as error:
                problems.append(f"{name}: can't be parsed: {error}")
                continue
        data = path.read_bytes()
        text = data.decode("iso-8859-1")
        if b"\0" in data or not DRIVER_CLASSES.search(text):
            continue
        for number, line in enumerate(text.splitlines(), start=1):
            match = DRIVER_CLASSES.search(line)
            if match:
                problems.append(f"{name}:{number}: " + CLASS_FIX.format(database=database_of(match.group(1))))
    return problems


def main(argv):
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(__file__).resolve().parents[2]
    problems = find_problems(root)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
