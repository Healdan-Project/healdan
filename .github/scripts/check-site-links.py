#!/usr/bin/env python3
#
# Checks the built docs site's internal links. Run by
# .github/workflows/docs-site.yml after Hugo builds site/public.
#
# For every page in the built site, every href and src that points inside the
# site must reach a file, and every #fragment must match an id on the page it
# points to. A path ending in / reaches its index.html, and query strings are
# ignored. Links to the site's own base URL count as internal. Links to other
# sites, mailto:, tel:, javascript: and data: URIs aren't checked.
#
# Usage: check-site-links.py <public dir> [--base-url URL]
# Exit status: 0 when every internal link resolves, 1 otherwise.

import argparse
import html.parser
import pathlib
import posixpath
import sys
import urllib.parse

DEFAULT_BASE_URL = "https://docs.healdan.com/"
SKIPPED_SCHEMES = {"mailto", "tel", "javascript", "data"}


class Page(html.parser.HTMLParser):
    """The links and ids of one built page."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links = []
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        for name, value in attrs:
            if value is None:
                continue
            if name == "id":
                self.ids.add(value)
            elif name in ("href", "src"):
                self.links.append(value)


def parse(path):
    page = Page()
    page.feed(path.read_text(encoding="utf-8", errors="replace"))
    return page


def target_file(public, page_dir, path):
    """The file a link's path reaches, or None. page_dir is the page's folder, relative to public."""
    if path.startswith("/"):
        resolved = posixpath.normpath(path.lstrip("/") or ".")
    else:
        resolved = posixpath.normpath(posixpath.join(page_dir, path))
    if resolved == "." or path.endswith("/"):
        resolved = posixpath.join(resolved, "index.html")
    if resolved.startswith(".."):
        return None
    candidate = public / resolved
    if candidate.is_dir():
        candidate = candidate / "index.html"
    return candidate if candidate.is_file() else None


def find_broken_links(public, base_url=DEFAULT_BASE_URL):
    public = pathlib.Path(public)
    base = urllib.parse.urlsplit(base_url)
    pages = {path: parse(path) for path in sorted(public.rglob("*.html"))}
    broken = []
    for path, page in pages.items():
        relative = path.relative_to(public).as_posix()
        page_dir = posixpath.dirname(relative)
        for href in page.links:
            url = urllib.parse.urlsplit(href)
            if url.scheme in SKIPPED_SCHEMES:
                continue
            link_path = url.path
            if url.scheme or url.netloc:
                if url.netloc != base.netloc:
                    continue
                link_path = url.path or "/"
            if not link_path:
                target = path
            else:
                target = target_file(public, page_dir, link_path)
                if target is None:
                    broken.append(f"{relative}: {href}: no such page")
                    continue
            if url.fragment:
                target_page = pages.get(target) if target.suffix == ".html" else None
                if target_page is None or url.fragment not in target_page.ids:
                    broken.append(f"{relative}: {href}: no id '{url.fragment}' on that page")
    return broken


def main(argv):
    parser = argparse.ArgumentParser(description="Checks the built docs site's internal links.")
    parser.add_argument("public")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    args = parser.parse_args(argv)
    broken = find_broken_links(pathlib.Path(args.public), args.base_url)
    for problem in broken:
        print(problem)
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
