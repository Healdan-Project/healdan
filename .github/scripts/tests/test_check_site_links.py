import contextlib
import io
import pathlib
import tempfile
import unittest

import _scripts

checker = _scripts.load("check-site-links")

BASE = "https://docs.healdan.com/"


class CheckSiteLinksTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.public = pathlib.Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def page(self, name, html):
        path = self.public / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(html, encoding="utf-8")

    def broken(self):
        return checker.find_broken_links(self.public, BASE)

    def test_missing_page_reported(self):
        self.page("index.html", '<a href="/missing/">x</a>')
        broken = self.broken()
        self.assertEqual(1, len(broken), broken)
        self.assertTrue(broken[0].startswith("index.html: /missing/: "), broken[0])

    def test_directory_link_resolves_to_index(self):
        self.page("index.html", '<a href="/install/">Install</a>')
        self.page("install/index.html", "<h1>Install</h1>")
        self.assertEqual([], self.broken())

    def test_relative_link_resolved(self):
        self.page("install/quick-start/index.html", '<a href="../../admin/">Admin</a> <a href="../">Up</a>')
        self.page("install/index.html", "")
        self.page("admin/index.html", "")
        self.assertEqual([], self.broken())

    def test_relative_link_to_missing_page_reported(self):
        self.page("install/quick-start/index.html", '<a href="../nope/">x</a>')
        self.assertEqual(["install/quick-start/index.html: ../nope/: no such page"], self.broken())

    def test_missing_anchor_reported(self):
        self.page("index.html", "<a href=/user/#nope>x</a> <a href=/user/#go-straight>y</a>")
        self.page("user/index.html", "<h2 id=go-straight>Go Straight</h2>")
        broken = self.broken()
        self.assertEqual(1, len(broken), broken)
        self.assertIn("/user/#nope", broken[0])

    def test_same_page_anchor(self):
        self.page("index.html", '<a href="#local">x</a><div id="local"></div><a href="#gone">y</a>')
        broken = self.broken()
        self.assertEqual(1, len(broken), broken)
        self.assertIn("#gone", broken[0])

    def test_assets_checked(self):
        self.page("index.html", "<img src=/images/x.png><script src=/js/a.js></script>"
                                "<link rel=stylesheet href=/css/b.css><img src=/images/ok.svg>")
        self.page("images/ok.svg", "<svg/>")
        broken = self.broken()
        self.assertEqual(3, len(broken), broken)
        for missing in ("/images/x.png", "/js/a.js", "/css/b.css"):
            self.assertTrue(any(missing in b for b in broken), missing)

    def test_query_string_ignored(self):
        self.page("index.html", '<a href="/user/?lang=de">x</a>')
        self.page("user/index.html", "")
        self.assertEqual([], self.broken())

    def test_base_url_links_are_internal(self):
        self.page("index.html", '<a href="https://docs.healdan.com/admin/">a</a>'
                                '<a href="https://docs.healdan.com/gone/">b</a>')
        self.page("admin/index.html", "")
        broken = self.broken()
        self.assertEqual(1, len(broken), broken)
        self.assertIn("https://docs.healdan.com/gone/", broken[0])

    def test_external_and_special_links_ignored(self):
        self.page("index.html", '<a href="https://example.org/x">a</a><a href="http://example.org">b</a>'
                                '<a href="mailto:a@example.org">c</a><a href="tel:123">d</a>'
                                '<a href="javascript:void(0)">e</a><img src="data:image/png;base64,AAAA">'
                                '<a href="//cdn.example.org/x.js">f</a>')
        self.assertEqual([], self.broken())

    def test_main_exit_codes(self):
        self.page("index.html", '<a href="/install/">x</a>')
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(1, checker.main([str(self.public)]))
        self.assertIn("/install/", out.getvalue())
        self.page("install/index.html", "")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, checker.main([str(self.public), "--base-url", BASE]))


if __name__ == "__main__":
    unittest.main()
