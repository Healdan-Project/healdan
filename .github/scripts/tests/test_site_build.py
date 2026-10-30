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
# Healdan's files at the paths where Hextra ships its own, so a missing one would quietly publish Hextra's
BRANDING = ("favicon.svg", "favicon.ico", "favicon-16x16.png", "favicon-32x32.png", "apple-touch-icon.png",
            "android-chrome-192x192.png", "android-chrome-512x512.png", "site.webmanifest",
            "images/logo.svg", "images/logo-dark.svg")
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
    """The text inside the first element with a given tag and, optionally, class token."""

    def __init__(self, tag, cls=None):
        super().__init__(convert_charrefs=True)
        self.tag, self.cls, self.depth, self.done, self.parts = tag, cls, 0, False, []

    def handle_starttag(self, tag, attrs):
        if self.done:
            return
        if tag == self.tag and (self.depth or self.cls is None
                                or self.cls in (dict(attrs).get("class") or "").split()):
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


SidebarItem = collections.namedtuple("SidebarItem", "href has_toggle li_classes")


class _SidebarItems(html.parser.HTMLParser):
    """Hextra's sidebar entries inside elements carrying all the wanted class tokens: each entry's link, whether
    it has an open/close button, and the classes of the <li> around it."""

    def __init__(self, wanted):
        super().__init__(convert_charrefs=True)
        self.wanted = wanted
        self.stack = []  # (tag, classes)
        self.items = []
        self.item = None  # [href, has_toggle, li_classes, depth]

    def _inside_wanted(self):
        return self.wanted <= frozenset().union(*(c for _, c in self.stack))

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = frozenset((attrs.get("class") or "").split())
        if tag == "div" and "hextra-sidebar-item" in classes and self._inside_wanted():
            li = next((c for t, c in reversed(self.stack) if t == "li"), frozenset())
            self.item = [None, False, li, len(self.stack)]
        elif self.item is not None:
            if tag == "a" and self.item[0] is None:
                self.item[0] = attrs.get("href") or ""
            elif tag == "button" and "hextra-sidebar-collapsible-button" in classes:
                self.item[1] = True
        if tag not in VOID:
            self.stack.append((tag, classes))

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break
        if self.item is not None and len(self.stack) <= self.item[3]:
            self.items.append(SidebarItem(*self.item[:3]))
            self.item = None


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

    def sidebar_items(self, html, within_class, page="/"):
        """Hextra's sidebar entries inside within_class (space-separated class tokens), with hrefs as site paths."""
        parser = _SidebarItems(set(within_class.split()))
        parser.feed(html)
        return [item._replace(href=self._site_path(item.href or "", page)) for item in parser.items]

    def text(self, html, tag="body", within_class=None):
        """The unescaped text inside the first element with this tag (and class token), whitespace collapsed."""
        parser = _Text(tag, within_class)
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
        for page, current in (("/admin/", "Admin"), ("/user/finding-documents/finding-by-doc-number/", "User")):
            nav = {link.text: link for link in self.links(self.html(page), "hextra-nav-container")}
            for name, path in MANUALS.items():
                self.assertEqual(path, nav[name].href)
                self.assertEqual(name == current, "hx:font-medium" in nav[name].classes, f"{page}: {name}")

    def test_desktop_nav_shows_only_current_manual(self):
        for page, own in (("/admin/", "/admin/stores/"), ("/user/finding-documents/finding-by-doc-number/",
                                                          "/user/documents/versions/")):
            sidebar = [link.href for link in self.links(self.html(page), "hextra-sidebar-container hx:max-md:hidden")]
            self.assertIn(own, sidebar, page)
            manual = own.split("/")[1]
            self.assertEqual([], [href for href in sidebar if href.startswith("/") and
                                  not href.startswith(f"/{manual}/")], page)

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
        for name in BRANDING:
            built = (self.public / name).read_bytes()
            self.assertEqual((SITE / "static" / name).read_bytes(), built, name)
            self.assertNotEqual((VENDORED_STATIC / name).read_bytes(), built, name)
        manifest = json.loads((self.public / "site.webmanifest").read_text(encoding="utf-8"))
        self.assertEqual("Healdan Documentation", manifest["name"])

    def test_404_page_is_healdan(self):
        self.assertIn("/images/logo.svg", self.html("/404.html"))

    def test_404_page_has_the_phone_menu(self):
        # Hextra's menu script expects the sidebar; without it the phone menu button does nothing
        self.assertIn("hextra-sidebar-container", self.html("/404.html"))

    def test_hextra_licence_is_shipped(self):
        # site/_vendor has no LICENSE (hugo mod vendor copies only mounted folders), so Healdan keeps the MIT notice
        licence = (self.public / "licenses/hextra.txt").read_text(encoding="utf-8")
        self.assertIn("MIT License", licence)
        self.assertIn("Copyright (c) 2023 Xin", licence)
        self.assertIn("licenses/hextra.txt", (SITE / "LICENSE").read_text(encoding="utf-8"))

    # Task 3

    def test_interim_box_renders(self):
        # A page that isn't written yet: the User Guide's Tags page stays interim until the demo docs' PR 3 writes it
        page = self.html("/user/documents/tags/")
        box = self.text(page, "div", "healdan-interim")
        self.assertIn("This page isn't written yet. Until it is, LogicalDOC's Tags covers similar ground.", box)
        self.assertIn("LogicalDOC's docs describe its Enterprise edition, so some features there aren't in Healdan, "
                      "and their workspace isn't a Healdan Workspace.", box)
        self.assertIn("https://docs.logicaldoc.com/en/document-metadata/tags",
                      [link.href for link in self.links(page, "healdan-interim")])

    def test_api_page_written(self):
        # M2 replaced the API page's interim box with Healdan's own API; RestAuthService's key hint links here
        page = self.html("/developer/api/")
        self.assertNotIn("healdan-interim", page)
        self.assertEqual("API – Healdan Documentation", self.text(page, "title"))
        self.assertIn("/api/v1/openapi.yaml", page)

    # Task 4

    def test_phone_menu_lists_manuals_and_opens_only_current(self):
        page = "/user/finding-documents/finding-by-doc-number/"
        items = self.sidebar_items(self.html(page), "hextra-sidebar-container hx:md:hidden", page)
        by_href = {item.href: item for item in items}
        for other in ("/install/", "/admin/", "/developer/"):
            self.assertIn(other, by_href)
            self.assertFalse(by_href[other].has_toggle, other)
        strays = [item.href for item in items if item.href not in ("/install/", "/admin/", "/developer/")
                  and not item.href.startswith(("/user/", "#"))]
        self.assertEqual([], strays)
        self.assertIn(page, by_href)
        for ancestor in ("/user/", "/user/finding-documents/"):
            self.assertIn("open", by_href[ancestor].li_classes, ancestor)
        self.assertNotIn("open", by_href["/user/documents/"].li_classes)

    def test_phone_menu_on_a_manuals_landing_page(self):
        items = self.sidebar_items(self.html("/admin/"), "hextra-sidebar-container hx:md:hidden", "/admin/")
        by_href = {item.href: item for item in items}
        self.assertIn("open", by_href["/admin/"].li_classes)
        self.assertIn("/admin/stores/", by_href)
        for other in ("/install/", "/user/", "/developer/"):
            self.assertFalse(by_href[other].has_toggle, other)
            self.assertNotIn("open", by_href[other].li_classes, other)
        self.assertEqual([], [item.href for item in items if item.href.startswith(("/user/", "/developer/"))
                              and item.href not in ("/user/", "/developer/")])

    # Task 5

    def test_install_pages_exist(self):
        pages = {"quick-start": "Quick Start", "what-runs": "What Runs", "configuration": "Configuration",
                 "reverse-proxy": "Reverse Proxy", "backup-and-restore": "Backup and Restore",
                 "upgrading": "Upgrading", "host-folders": "Host Folders Instead of Volumes",
                 "troubleshooting": "Troubleshooting", "known-limitations": "Known Limitations",
                 "office-integration": "Office Integration"}
        for slug, title in pages.items():
            self.assertEqual(f"{title} – Healdan Documentation", self.text(self.html(f"/install/{slug}/"), "title"))
        for page in (self.public / "install").rglob("*.html"):
            self.assertNotIn("ghcr.io/healdan-project/healdan", page.read_text(encoding="utf-8"), page)

    # Task 6

    def test_developer_pages_exist(self):
        pages = {"building-from-source": "Building from Source", "development-stack": "Running a Development Stack",
                 "tests": "Tests", "continuous-integration": "Continuous Integration",
                 "how-healdan-extends-logicaldoc": "How Healdan Extends LogicalDOC",
                 "upstream-syncs": "Upstream Syncs", "writing-docs": "Writing Docs",
                 "desktop-clients": "Desktop Clients"}
        for slug, title in pages.items():
            self.assertEqual(f"{title} – Healdan Documentation",
                             self.text(self.html(f"/developer/{slug}/"), "title"))

    # Task 10

    USER_LAUNCH_PAGES = (
        "getting-started/signing-in", "getting-started/getting-around", "getting-started/clearing-your-browsers-cache",
        "working-with-matters/stores-and-matter-workspaces", "working-with-matters/filing-a-document",
        "working-with-matters/properties", "working-with-matters/emails", "working-with-matters/document-permissions",
        "finding-documents/searching", "finding-documents/finding-by-doc-number", "finding-documents/search-folders",
        "finding-documents/my-matters-and-recent-matters", "documents/versions", "documents/previews")

    def test_user_launch_pages_written(self):
        unwritten = [page for page in self.USER_LAUNCH_PAGES
                     if "healdan-interim" in self.html(f"/user/{page}/")]
        self.assertEqual([], unwritten)

    # Task 11

    ADMIN_LAUNCH_PAGES = ("stores", "clients-and-matters", "workspace-templates", "top-level-folders",
                          "users-and-groups", "permissions", "healdan-settings", "office-previews")

    def test_admin_launch_pages_written(self):
        unwritten = [page for page in self.ADMIN_LAUNCH_PAGES if "healdan-interim" in self.html(f"/admin/{page}/")]
        self.assertEqual([], unwritten)

    # The demo stack and docs gaps, PR 4

    ADMIN_SYSTEM_PAGES = {"property-templates": "Property Templates", "interface-settings": "Interface Settings",
                          "search-and-indexing": "Search and Indexing", "outgoing-email": "Outgoing Email",
                          "webdav-cmis-and-web-services": "WebDAV, CMIS and Web Services",
                          "scheduled-tasks": "Scheduled Tasks", "system-information": "System Information",
                          "reports": "Reports"}

    def test_admin_system_pages_written(self):
        for slug, title in self.ADMIN_SYSTEM_PAGES.items():
            page = self.html(f"/admin/system/{slug}/")
            self.assertEqual(f"{title} – Healdan Documentation", self.text(page, "title"), slug)
            self.assertNotIn("healdan-interim", page, slug)

    # Task 17

    def test_ci_page_names_every_check_the_workflows_run(self):
        workflows = (_scripts.REPO / ".github/workflows").glob("*.yml")
        checks = {name for workflow in workflows
                  for name in re.findall(r"\.github/scripts/(check-[\w-]+\.py)", workflow.read_text(encoding="utf-8"))}
        page = self.html("/developer/continuous-integration/")
        self.assertIn("check-fontawesome-icons.py", checks)
        self.assertEqual([], sorted(name for name in checks if name not in page))

    # M3

    OFFICE_PAGES = {"": "Working in LibreOffice", "installing-the-helper/": "Installing the Healdan Helper",
                    "saving-to-healdan/": "Saving to Healdan", "opening-documents/": "Opening Documents",
                    "editing-and-versions/": "Editing and Versions", "doc-id-footer/": "The Doc ID Footer",
                    "when-things-go-wrong/": "When Things Go Wrong"}

    def test_office_pages_written(self):
        for slug, title in self.OFFICE_PAGES.items():
            page = self.html(f"/user/office/{slug}")
            self.assertEqual(f"{title} – Healdan Documentation", self.text(page, "title"), slug)
            self.assertNotIn("healdan-interim", page, slug)
        for page in ("documents/checking-out-and-checking-in", "documents/bookmarks"):
            self.assertNotIn("healdan-interim", self.html(f"/user/{page}/"), page)

    def test_open_in_apps_notice_links_to_the_install_page(self):
        # The web page's Open in App notice links here, so the page must keep its address
        opener = _scripts.REPO / ("logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/"
                                  "HealdanDocumentOpener.java")
        url = re.search(r'INSTALL_URL = "([^"]+)"', opener.read_text(encoding="utf-8")).group(1)
        self.assertEqual(BASE_URL + "user/office/installing-the-helper/", url)
        self.assertIn("Installing the Healdan Helper", self.text(self.html(url[len(BASE_URL) - 1:]), "title"))

    def test_sources_and_links_pass(self):
        for script, arg in (("check-site-sources.py", SITE), ("check-site-links.py", self.public)):
            result = subprocess.run([sys.executable, str(_scripts.SCRIPTS / script), str(arg)],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, f"{script}:\n{result.stdout}{result.stderr}")


if __name__ == "__main__":
    unittest.main()
