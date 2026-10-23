#!/usr/bin/env python3
#
# Checks the docs site's Markdown sources for mistakes that build without an
# error but publish something wrong. Run by .github/workflows/docs-site.yml.
#
# Checks every site/content/**/*.md file:
#   - a front-matter value containing " #" is quoted. YAML reads " #" as the
#     start of a comment, so `title: Finding by doc #` publishes without its #;
#   - a heading that ends in "#" ends in "\#". Markdown reads a trailing # as
#     closing the heading, so `## Legacy Doc #` publishes as "Legacy Doc".
#     Headings inside fenced code blocks aren't checked;
#   - links to LogicalDOC's docs (docs, wiki, help or forums.logicaldoc.com)
#     appear only in the `interim` shortcode, which marks a page Healdan hasn't
#     written yet.
#
# Usage: check-site-sources.py [site dir]
# Exit status: 0 when nothing is wrong, 1 otherwise.

import pathlib
import re
import sys

LOGICALDOC_DOCS = re.compile(r"https?://(docs|wiki|help|forums)\.logicaldoc\.com")
FRONT_MATTER_VALUE = re.compile(r"^\s*[A-Za-z_][\w-]*:\s+(?P<value>\S.*)$")
HEADING = re.compile(r"^#{1,6}\s")
FENCE = re.compile(r"^\s*(```|~~~)")


def check_file(path, relative):
    problems = []
    lines = path.read_text(encoding="utf-8").splitlines()
    in_front_matter = bool(lines) and lines[0].strip() == "---"
    in_fence = False
    for number, line in enumerate(lines, start=1):
        where = f"{relative}:{number}"
        if in_front_matter:
            if number > 1 and line.strip() == "---":
                in_front_matter = False
                continue
            match = FRONT_MATTER_VALUE.match(line)
            if match and " #" in match.group("value") and match.group("value")[0] not in "\"'":
                problems.append(f"{where}: YAML drops everything after ' #'. Put the value in quotes")
        elif FENCE.match(line):
            in_fence = not in_fence
        elif not in_fence and HEADING.match(line) and line.rstrip().endswith("#") \
                and not line.rstrip().endswith("\\#"):
            problems.append(f"{where}: Markdown drops a heading's closing '#'. End it with '\\#'")
        if LOGICALDOC_DOCS.search(line) and "{{< interim" not in line:
            problems.append(f"{where}: links to LogicalDOC's docs. Use the interim shortcode, or link to Healdan's own page")
    return problems


def find_problems(site):
    site = pathlib.Path(site)
    problems = []
    for path in sorted((site / "content").rglob("*.md")):
        problems.extend(check_file(path, path.relative_to(site).as_posix()))
    return problems


def main(argv):
    site = pathlib.Path(argv[0]) if argv else pathlib.Path(__file__).resolve().parents[2] / "site"
    problems = find_problems(site)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
