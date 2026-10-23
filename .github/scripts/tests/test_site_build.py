"""Builds site/ with Hugo and checks what readers get: the landing page, the nav, the footer and the branding.

Needs hugo on the PATH. Without it the tests skip, unless CI is set, when they fail.
"""

import collections
import html.parser
import json
import os
import pathlib
import posixpath
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import urllib.parse

import _scripts

SITE = _scripts.REPO / "site"
BASE_URL = "https://docs.healdan.com/"
VENDORED_STATIC = SITE / "_vendor/github.com/imfing/hextra/static"
FAVICONS = ("favicon.svg", "favicon.ico", "favicon-16x16.png", "favicon-32x32.png", "apple-touch-icon.png",
            "android-chrome-192x192.png", "android-chrome-512x512.png", "site.webmanifest")
MANUALS = {"Install": "/install/", "User": "/user/", "Admin": "/admin/", "Developer": "/developer/"}

COPYRIGHT_1 = ("© 2026 The Healdan Project. This documentation is licensed under CC BY 4.0; "
               "Healdan's code is licensed under the LGPL-3.0.")
COPYRIGHT_2 = ("Healdan is a modified version of LogicalDOC Community Edition, © 2006-2027 LOGICALDOC Srl. "
               "LogicalDOC is a trademark of LOGICALDOC Srl, which doesn't endorse Healdan.")

Link = collections.namedtuple("Link", "href text classes")

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}


class _Links(html.parser.HTMLParser):
    """The <a> elements of a page, each with its text and the class tokens of it and its ancestors."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []  # (tag, class tokens)
        self.links = []  # [href, text parts, own classes, ancestor and own classes]
        self.open = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = frozenset((attrs.get("class") or "").split())
        if tag == "a":
            inherited = frozenset().union(classes, *(c for _, c in self.stack))
            self.open.append([attrs.get("href") or "", [], classes, inherited])
        if tag not in VOID:
            self.stack.append((tag, classes))

    def handle_endtag(self, tag):
        if tag == "a" and self.open:
            self.links.append(self.open.pop())
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break

    def handle_data(self, data):
        for link in self.open:
            link[1].append(data)


class _Text(html.parser.HTMLParser):
    """The text inside the first element with a given tag."""

    def __init__(self, tag):
        super().__init__(convert_charrefs=True)
        self.tag, self.depth, self.done, self.parts = tag, 0, False, []

    def handle_starttag(self, tag, attrs):
        if self.done:
            return
        if tag == self.tag:
            self.depth += 1
        if tag == "br" and self.depth:
            self.parts.append(" ")

    def handle_endtag(self, tag):
        if tag == self.tag and self.depth:
            self.depth -= 1
            self.done = self.depth == 0

    def handle_data(self, data):
        if self.depth and not self.done:
            self.parts.append(data)


def _normalise(text):
    return re.sub(r"\s+", " ", text.replace("’", "'")).strip()


class SiteBuild(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        hugo = shutil.which("hugo")
        if hugo is None:
            if os.environ.get("CI"):
                raise AssertionError("hugo isn't on the PATH")
            raise unittest.SkipTest("hugo isn't on the PATH")
        cls._tmp = tempfile.TemporaryDirectory()
        cls.public = pathlib.Path(cls._tmp.name) / "public"
        build = subprocess.run(
            [hugo, "--source", str(SITE), "--gc", "--minify", "--panicOnWarning", "--baseURL", BASE_URL,
             "-d", str(cls.public)], capture_output=True, text=True)
        if build.returncode != 0:
            cls._tmp.cleanup()
            raise AssertionError(f"hugo failed:\n{build.stdout}\n{build.stderr}")

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    # Helpers for this and later tasks' tests

    def html(self, path):
        """The built page at a site path such as /admin/, or a file such as /404.html."""
        relative = path.lstrip("/")
        if relative == "" or relative.endswith("/"):
            relative += "index.html"
        return (self.public / relative).read_text(encoding="utf-8")

    def links(self, html, within_class=None, page="/"):
        """The page's links. within_class is space-separated class tokens that must all be on the link or
        its ancestors. Internal hrefs come back as site paths, resolved against page."""
        parser = _Links()
        parser.feed(html)
        wanted = set((within_class or "").split())
        found = []
        for href, parts, own, inherited in parser.links:
            if not wanted <= inherited:
                continue
            found.append(Link(self._site_path(href, page), _normalise("".join(parts)), own))
        return found

    def text(self, html, tag="body"):
        """The unescaped text inside the first element with this tag, whitespace collapsed."""
        parser = _Text(tag)
        parser.feed(html)
        return _normalise("".join(parser.parts))

    @staticmethod
    def _site_path(href, page):
        url = urllib.parse.urlsplit(href)
        base = urllib.parse.urlsplit(BASE_URL)
        if url.scheme or url.netloc:
            if url.netloc != base.netloc:
                return href
            path = url.path or "/"
        elif not url.path:
            return href
        elif url.path.startswith("/"):
            path = url.path
        else:
            path = posixpath.normpath(posixpath.join(page, url.path)) + ("/" if url.path.endswith("/") else "")
        return path + (f"#{url.fragment}" if url.fragment else "")

    # Task 2

    def test_title_is_healdan_documentation(self):
        self.assertEqual("Healdan Documentation", self.text(self.html("/"), "title"))

    def test_landing_has_three_guide_cards_and_developer_link(self):
        home = self.html("/")
        cards = [link for link in self.links(home) if "hextra-feature-card" in link.classes]
        self.assertEqual(["/install/", "/user/", "/admin/"], [card.href for card in cards])
        for card, title in zip(cards, ("Install", "User Guide", "Admin Guide")):
            self.assertTrue(card.text.startswith(title), card.text)
        self.assertEqual([Link("/developer/", "Developer Guide →", frozenset())],
                         self.links(home, "healdan-devlink"))

    def test_current_manual_tab_is_highlighted(self):
        nav = {link.text: link for link in self.links(self.html("/admin/"), "hextra-nav-container")}
        for name, path in MANUALS.items():
            self.assertEqual(path, nav[name].href)
            self.assertEqual(name == "Admin", "hx:font-medium" in nav[name].classes, name)

    def test_desktop_nav_shows_only_current_manual(self):
        sidebar = self.links(self.html("/admin/"), "hextra-sidebar-container hx:max-md:hidden")
        others = [link.href for link in sidebar if link.href.startswith(("/install/", "/user/", "/developer/"))]
        self.assertEqual([], others)

    def test_footer_copyright_and_links(self):
        home = self.html("/")
        footer = self.text(home, "footer")
        self.assertIn(COPYRIGHT_1, footer)
        self.assertIn(COPYRIGHT_2, footer)
        hrefs = {link.href for link in self.links(home, "hextra-footer")}
        for href in ("https://creativecommons.org/licenses/by/4.0/",
                     "https://github.com/Healdan-Project/healdan/blob/master/LICENSE",
                     "https://github.com/Healdan-Project/healdan/issues"):
            self.assertIn(href, hrefs)

    def test_no_hextra_branding(self):
        for page in self.public.rglob("*.html"):
            self.assertNotIn("Powered by Hextra", page.read_text(encoding="utf-8"), page)
        for name in FAVICONS:
            built = (self.public / name).read_bytes()
            self.assertEqual((SITE / "static" / name).read_bytes(), built, name)
            self.assertNotEqual((VENDORED_STATIC / name).read_bytes(), built, name)
        manifest = json.loads((self.public / "site.webmanifest").read_text(encoding="utf-8"))
        self.assertEqual("Healdan Documentation", manifest["name"])

    def test_404_page_is_healdan(self):
        self.assertIn("/images/logo.svg", self.html("/404.html"))

    def test_sources_and_links_pass(self):
        for script, arg in (("check-site-sources.py", SITE), ("check-site-links.py", self.public)):
            result = subprocess.run([sys.executable, str(_scripts.SCRIPTS / script), str(arg)],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, f"{script}:\n{result.stdout}{result.stderr}")


if __name__ == "__main__":
    unittest.main()
