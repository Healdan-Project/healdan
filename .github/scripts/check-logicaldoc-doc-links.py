#!/usr/bin/env python3
#
# Checks that nothing in the repository links to LogicalDOC's docs (docs, wiki, help or forums.logicaldoc.com).
# Healdan's docs are https://docs.healdan.com, and an upstream sync can bring LogicalDOC's links back, as it can Font
# Awesome Pro icons. Run by .github/workflows/build-logicaldoc.yml.
#
# Checks every file git tracks, except:
#   - docs/, whose specs and plans discuss these links;
#   - site/content/, which check-site-sources.py checks (links only inside the interim shortcode), and the vendored
#     theme in site/_vendor/. The rest of site/ is checked here;
#   - .github/scripts/tests/, whose tests hold example links;
#   - upstream's release history, two test fixtures, and check-logicaldoc-hosts.py, whose allow-list quotes the
#     lines below (SKIPPED_FILES);
#   - lines in ALLOWED, each with its reason. A line that changes no longer matches, and is flagged again.
# Binary files (with a NUL byte) are skipped. LogicalDOC's other hosts, such as www (its credit) and ws (the SOAP
# API's XML namespace), aren't matched.
#
# Usage: check-logicaldoc-doc-links.py [repo root]
# Exit status: 0 when nothing links to LogicalDOC's docs, 1 otherwise.

import pathlib
import re
import subprocess
import sys

# The optional backslash matches a properties file's escaped colon (https\://), as the English message bundles write it
LOGICALDOC_DOCS = re.compile(r"https?\\?://(docs|wiki|help|forums)\.logicaldoc\.com")

SKIPPED_PREFIXES = ("docs/", "site/content/", "site/_vendor/", ".github/scripts/tests/")

SKIPPED_FILES = {
    "ReleaseNotes.md",  # upstream's release history
    "ReleaseNotes.txt",
    "CHANGELOG.md",
    "logicaldoc-core/src/test/resources/README.md",  # FormatConverterTest reads it
    "logicaldoc-dropbox/src/test/resources/context.properties",  # a test fixture
    ".github/scripts/check-logicaldoc-doc-links.py",
    ".github/scripts/check-logicaldoc-hosts.py",
}

REPLACED = "replaced by HealdanSystemInfo, whose own values are what users see"

ALLOWED = (
    ("logicaldoc-core/src/main/java/com/logicaldoc/core/SystemInfo.java",
     'protected String help = "https://docs.logicaldoc.com";', REPLACED),
    ("logicaldoc-webservice/src/main/java/com/logicaldoc/webservice/model/WSSystemInfo.java",
     'private String help = "https://docs.logicaldoc.com";', REPLACED),
    ("logicaldoc-webservice/src/main/java/com/logicaldoc/webservice/model/WSSystemInfo.java",
     'private String forum = "https://forums.logicaldoc.com";', REPLACED),
)

FIX = "links to LogicalDOC's docs. Link to https://docs.healdan.com instead, or add the line to ALLOWED with a reason."


def tracked_files(root):
    listing = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return sorted(name.decode("utf-8", errors="replace") for name in listing.split(b"\0") if name)


def find_links(root):
    root = pathlib.Path(root)
    allowed = {(path, line) for path, line, _ in ALLOWED}
    problems = []
    for name in tracked_files(root):
        if name.startswith(SKIPPED_PREFIXES) or name in SKIPPED_FILES:
            continue
        path = root / name
        if not path.is_file():
            continue
        data = path.read_bytes()
        if b"\0" in data or not LOGICALDOC_DOCS.search(data.decode("iso-8859-1")):
            continue
        for number, line in enumerate(data.decode("iso-8859-1").splitlines(), start=1):
            if LOGICALDOC_DOCS.search(line) and (name, line.strip()) not in allowed:
                problems.append(f"{name}:{number}: {FIX}")
    return problems


def main(argv):
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(__file__).resolve().parents[2]
    problems = find_links(root)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
